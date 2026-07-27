import Foundation
#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
#endif
import Metal
import QuartzCore
import simd

// On iOS, AppKit types (NSView/NSWindow) are unavailable. We expose platform-
// neutral type aliases so the rest of the file can reference the same names
// without littering every signature with #if branches.
#if os(macOS)
public typealias MetallumView = NSView
public typealias MetallumWindow = NSWindow
#elseif os(iOS)
public typealias MetallumView = UIView
public typealias MetallumWindow = UIWindow
#endif

private struct DepthStencilKey: Hashable {
    let deviceAddress: UInt
    let compareOp: MTLCompareFunction
    let writeDepth: Bool
}

private struct PipelineVariantKey: Hashable {
    let deviceAddress: UInt
    let colorFormat: MTLPixelFormat
    let depthFormat: MTLPixelFormat
    let writeColor: Bool
}

private enum NativeState {
    static var debugLabelsEnabled = false
    static var depthStencilStates: [DepthStencilKey: MTLDepthStencilState] = [:]
    static var clearPipelines: [PipelineVariantKey: MTLRenderPipelineState] = [:]
    static var presentPipeline: MTLRenderPipelineState!
    static var presentNearestSampler: MTLSamplerState!
    static var presentLinearSampler: MTLSamplerState!
}

@inline(__always)
private func retainedPointer(_ object: AnyObject?) -> UnsafeMutableRawPointer? {
    guard let object else {
        return nil
    }
    return UnsafeMutableRawPointer(Unmanaged.passRetained(object).toOpaque())
}

@inline(__always)
private func unretainedPointer(_ object: AnyObject?) -> UnsafeMutableRawPointer? {
    guard let object else {
        return nil
    }
    return UnsafeMutableRawPointer(Unmanaged.passUnretained(object).toOpaque())
}

@inline(__always)
private func objectAddress(_ object: AnyObject) -> UInt {
    UInt(bitPattern: Unmanaged.passUnretained(object).toOpaque())
}

private func textureSliceCount(_ texture: MTLTexture) -> Int {
    switch texture.textureType {
    case .type2DArray:
        return max(texture.arrayLength, 1)
    case .typeCube:
        return 6
    case .typeCubeArray:
        return max(texture.arrayLength, 1) * 6
    default:
        return 1
    }
}

private func stencilPixelFormat(for depthFormat: MTLPixelFormat) -> MTLPixelFormat {
    let isStencil: Bool = {
        #if os(macOS)
        return depthFormat == .depth24Unorm_stencil8 || depthFormat == .depth32Float_stencil8
        #else
        return depthFormat == .depth32Float_stencil8
        #endif
    }()
    return isStencil ? depthFormat : .invalid
}

private func makeClearColor(red: Float, green: Float, blue: Float, alpha: Float) -> MTLClearColor {
    MTLClearColor(red: Double(red), green: Double(green), blue: Double(blue), alpha: Double(alpha))
}

private func stringFromOptionalCString(_ pointer: UnsafePointer<CChar>?) -> String? {
    guard let pointer else {
        return nil
    }
    let value = String(cString: pointer)
    return value.isEmpty ? nil : value
}

private func presentMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct PresentVertexOut {
      float4 position [[position]];
      float2 uv;
    };

    vertex PresentVertexOut metallum_present_vs(uint vertexId [[vertex_id]]) {
      const float2 positions[3] = {
        float2(-1.0,  1.0),
        float2( 3.0,  1.0),
        float2(-1.0, -3.0)
      };

      // Y-flip version:
      // old equivalent was uvMin=(0,1), uvMax=(1,0)
      const float2 uvs[3] = {
        float2(0.0,  1.0),
        float2(2.0,  1.0),
        float2(0.0, -1.0)
      };

      PresentVertexOut out;
      out.position = float4(positions[vertexId], 0.0, 1.0);
      out.uv = uvs[vertexId];
      return out;
    }

    fragment float4 metallum_present_fs(
      PresentVertexOut in [[stage_in]],
      texture2d<float> tex [[texture(0)]],
      sampler smp [[sampler(0)]]
    ) {
      return tex.sample(smp, in.uv);
    }
    """
}

private struct MetallumClearUniforms {
    var z: Float
    var _padding0: SIMD3<Float>
    var color: SIMD4<Float>
}

private func clearMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct ClearUniforms {
      float z;
      float3 _padding0;
      float4 color;
    };

    struct ClearVertexOut {
      float4 position [[position]];
      float4 color;
    };

    vertex ClearVertexOut metallum_clear_vs(
      uint vertexId [[vertex_id]],
      constant ClearUniforms& u [[buffer(1)]]
    ) {
      const float2 positions[3] = {
        float2(-1.0,  1.0),
        float2( 3.0,  1.0),
        float2(-1.0, -3.0)
      };

      ClearVertexOut out;
      out.position = float4(positions[vertexId], u.z, 1.0);
      out.color = u.color;
      return out;
    }

    fragment float4 metallum_clear_fs(ClearVertexOut in [[stage_in]]) {
      return in.color;
    }
    """
}

private func encodeClearDraw(
    encoder: MTLRenderCommandEncoder,
    pipeline: MTLRenderPipelineState,
    textureWidth: Int,
    textureHeight: Int,
    clearColor: SIMD4<Float>,
    scissorRect: MTLScissorRect,
    depthState: MTLDepthStencilState? = nil,
    clearDepth: Double = 0.0
) {
    encoder.setViewport(MTLViewport(
        originX: 0.0,
        originY: 0.0,
        width: Double(textureWidth),
        height: Double(textureHeight),
        znear: 0.0,
        zfar: 1.0
    ))

    encoder.setScissorRect(scissorRect)
    encoder.setRenderPipelineState(pipeline)

    if let depthState {
        encoder.setDepthStencilState(depthState)
    }

    var uniforms = MetallumClearUniforms(
        z: depthState == nil ? 0.0 : Float(max(0.0, min(clearDepth, 1.0))),
        _padding0: SIMD3<Float>(0.0, 0.0, 0.0),
        color: clearColor
    )

    withUnsafeBytes(of: &uniforms) { bytes in
        encoder.setVertexBytes(bytes.baseAddress!, length: bytes.count, index: 1)
    }

    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
}

private func buildClearPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat,
    depthFormat: MTLPixelFormat = .invalid,
    writeColor: Bool = true
) -> MTLRenderPipelineState? {
    do {
        let library = try device.makeLibrary(source: clearMslSource(), options: nil)

        guard
            let vertexFunction = library.makeFunction(name: "metallum_clear_vs"),
            let fragmentFunction = library.makeFunction(name: "metallum_clear_fs")
        else {
            NSLog("[metallum] Failed to create clear shader functions")
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.depthAttachmentPixelFormat = depthFormat
        descriptor.colorAttachments[0].isBlendingEnabled = false
        descriptor.colorAttachments[0].writeMask = writeColor ? .all : []

        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        NSLog("[metallum] Failed to create clear pipeline: %@", String(describing: error))
        return nil
    }
}

private func buildPresentPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    do {
        let library = try device.makeLibrary(source: presentMslSource(), options: nil)

        guard
            let vertexFunction = library.makeFunction(name: "metallum_present_vs"),
            let fragmentFunction = library.makeFunction(name: "metallum_present_fs")
        else {
            NSLog("[metallum] Failed to create present shader functions")
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.colorAttachments[0].isBlendingEnabled = false

        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        NSLog("[metallum] Failed to create present render pipeline: %@", String(describing: error))
        return nil
    }
}

private func buildPresentSampler(device: MTLDevice, filter: MTLSamplerMinMagFilter) -> MTLSamplerState? {
    let descriptor = MTLSamplerDescriptor()
    descriptor.minFilter = filter
    descriptor.magFilter = filter
    descriptor.mipFilter = .notMipmapped
    descriptor.sAddressMode = .clampToEdge
    descriptor.tAddressMode = .clampToEdge
    return device.makeSamplerState(descriptor: descriptor)
}

private func ensureClearColorDepthPipeline(_ device: MTLDevice, _ colorFormat: MTLPixelFormat, _ depthFormat: MTLPixelFormat, _ writeColor: Bool = true) -> MTLRenderPipelineState? {
    let key = PipelineVariantKey(deviceAddress: objectAddress(device), colorFormat: colorFormat, depthFormat: depthFormat, writeColor: writeColor)
    if let cached = NativeState.clearPipelines[key] {
        return cached
    }
    let pipeline = buildClearPipeline(device: device, colorFormat: colorFormat, depthFormat: depthFormat, writeColor: writeColor)
    if let pipeline {
        NativeState.clearPipelines[key] = pipeline
    }
    return pipeline
}

@_cdecl("metallum_init_pipelines")
public func metallum_init_pipelines(_ device: MTLDevice) {
    autoreleasepool {
        NativeState.presentPipeline = buildPresentPipeline(device: device, colorFormat: .bgra8Unorm)
        NativeState.presentLinearSampler = buildPresentSampler(device: device, filter: .linear)
        NativeState.presentNearestSampler = buildPresentSampler(device: device, filter: .nearest)
        _ = ensureClearColorDepthPipeline(device, .bgra8Unorm, .depth32Float)
        _ = ensureClearColorDepthPipeline(device, .rgba8Unorm, .depth32Float)
        _ = ensureClearColorDepthPipeline(device, .bgra8Unorm, .invalid)
    }
}

private func ensureDepthStencilState(device: MTLDevice, compareOp: MTLCompareFunction, writeDepth: Bool) -> MTLDepthStencilState? {
    let key = DepthStencilKey(deviceAddress: objectAddress(device), compareOp: compareOp, writeDepth: writeDepth)
    if let cached = NativeState.depthStencilStates[key] {
        return cached
    }
    let descriptor = MTLDepthStencilDescriptor()
    descriptor.depthCompareFunction = compareOp
    descriptor.isDepthWriteEnabled = writeDepth
    let state = device.makeDepthStencilState(descriptor: descriptor)
    if let state {
        NativeState.depthStencilStates[key] = state
    }
    return state
}

private func triangleFanOutputIndexCount(sourceCount: Int, buffer: MTLBuffer, offset: Int) -> Int? {
    let triangleCount = sourceCount - 2
    guard triangleCount <= Int.max / 3 else {
        return nil
    }

    let indexCount = triangleCount * 3
    let bufferIndexCapacity = UInt64((buffer.length - offset) / MemoryLayout<UInt32>.stride)
    guard indexCount <= UInt64(Int.max), indexCount <= bufferIndexCapacity else {
        return nil
    }
    return Int(indexCount)
}

private func readIndex(_ indexBuffer: MTLBuffer, byteOffset: Int, index: Int, indexType: Int) -> UInt32 {
    let base = indexBuffer.contents().advanced(by: Int(byteOffset))
    if indexType == 0 {
        return UInt32(base.assumingMemoryBound(to: UInt16.self)[Int(index)])
    }
    return base.assumingMemoryBound(to: UInt32.self)[Int(index)]
}

private func writeIndexedTriangleFanIndices(
    sourceIndexBuffer: MTLBuffer,
    destinationIndexBuffer: MTLBuffer,
    destinationOffset: Int,
    indexType: Int,
    indexOffsetBytes: Int,
    indexCount: Int
) -> Int? {
    guard indexCount >= 3, let generatedIndexCount = triangleFanOutputIndexCount(sourceCount: indexCount, buffer: destinationIndexBuffer, offset: destinationOffset) else {
        return nil
    }
    let triangleCount = indexCount - 2
    let center = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: 0, indexType: indexType)
    let indices = (destinationIndexBuffer.contents() + destinationOffset).assumingMemoryBound(to: UInt32.self)
    var writeIndex = 0
    for triangle in 0..<triangleCount {
        indices[writeIndex] = center
        indices[writeIndex + 1] = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: triangle + 1, indexType: indexType)
        indices[writeIndex + 2] = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: triangle + 2, indexType: indexType)
        writeIndex += 3
    }
    return generatedIndexCount
}

@_cdecl("metallum_create_system_default_device")
public func metallum_create_system_default_device() -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(MTLCreateSystemDefaultDevice())
    }
}

#if os(iOS)
/// Locates the host launcher's game surface UIView on iOS without requiring
/// the host to publish a pointer via a system property.
///
/// Strategy 1: call `+[SurfaceViewController surface]` directly. This class
/// method just returns a static variable (`pojavWindow`) — it does NOT touch
/// UIKit, so it's safe to call from any thread (including the JVM render
/// thread) without dispatching to main. This is the preferred path because
/// the main thread may be blocked inside `launchJVM`, making
/// `DispatchQueue.main.sync` deadlock.
///
/// Strategy 2 (fallback): dispatch to the main thread with a timeout and
/// walk the view hierarchy for a `GameSurfaceView`. This handles launchers
/// that don't expose `+surface` but requires the main thread to be runnable.
@_cdecl("metallum_ios_find_surface_view")
public func metallum_ios_find_surface_view() -> UnsafeMutableRawPointer? {
    // Strategy 1: +[SurfaceViewController surface] — no UIKit, any thread.
    if let view = callSurfaceViewControllerSurface() {
        return view
    }

    // Strategy 2: dispatch to main with a timeout and walk the view hierarchy.
    // If the main thread is blocked (e.g. inside launchJVM), the timeout
    // fires and we return nil rather than deadlocking forever.
    if Thread.isMainThread {
        return findViewInHierarchy()
    }
    let semaphore = DispatchSemaphore(value: 0)
    var hierarchyResult: UnsafeMutableRawPointer? = nil
    DispatchQueue.main.async {
        hierarchyResult = findViewInHierarchy()
        semaphore.signal()
    }
    let timeout: DispatchTime = .now() + .seconds(3)
    if semaphore.wait(timeout: timeout) == .timedOut {
        NSLog("[Metallum] WARNING: main thread did not respond within 3s; view-hierarchy lookup skipped")
        return nil
    }
    return hierarchyResult
}

/// Calls `+[SurfaceViewController surface]` via the ObjC runtime. This method
/// only returns a static variable, so it's thread-safe without main-thread
/// dispatch.
private func callSurfaceViewControllerSurface() -> UnsafeMutableRawPointer? {
    guard let cls = NSClassFromString("SurfaceViewController") as? NSObject.Type else {
        NSLog("[Metallum] SurfaceViewController class not found")
        return nil
    }
    let sel = NSSelectorFromString("surface")
    if !cls.responds(to: sel) {
        NSLog("[Metallum] SurfaceViewController does not respond to 'surface'")
        return nil
    }
    guard let result = cls.perform(sel) else {
        NSLog("[Metallum] +[SurfaceViewController surface] returned nil")
        return nil
    }
    let view = result.takeUnretainedValue()
    NSLog("[Metallum] +[SurfaceViewController surface] returned \(view)")
    return Unmanaged.passUnretained(view as AnyObject).toOpaque()
}

private func findViewInHierarchy() -> UnsafeMutableRawPointer? {
    let gameSurfaceClass = objc_getClass("GameSurfaceView") as? NSObject.Type
    let windows = UIApplication.shared.connectedScenes
        .compactMap({ $0 as? UIWindowScene })
        .flatMap({ $0.windows })
    NSLog("[Metallum] view hierarchy walk: \(windows.count) window(s); GameSurfaceView class found: \(gameSurfaceClass != nil)")
    for window in windows {
        if let found = findViewInView(window, targetClass: gameSurfaceClass) {
            return found
        }
    }
    let keyWindow = windows.first(where: { $0.isKeyWindow }) ?? windows.first
    if let root = keyWindow?.rootViewController?.view {
        return findLargestSubview(root)
    }
    return nil
}

/// Recursively searches a view hierarchy for a view of the given class.
private func findViewInView(_ view: UIView, targetClass: NSObject.Type?) -> UnsafeMutableRawPointer? {
    if let targetClass = targetClass, view.isKind(of: targetClass) {
        return Unmanaged.passUnretained(view).toOpaque()
    }
    for sub in view.subviews {
        if let found = findViewInView(sub, targetClass: targetClass) {
            return found
        }
    }
    return nil
}

private func findLargestSubview(_ view: UIView) -> UnsafeMutableRawPointer {
    var largest = view
    var largestArea = view.bounds.width * view.bounds.height
    for sub in view.subviews {
        let area = sub.bounds.width * sub.bounds.height
        if area > largestArea {
            largestArea = area
            largest = sub
        }
    }
    if largest !== view && !largest.subviews.isEmpty {
        return findLargestSubview(largest)
    }
    return Unmanaged.passUnretained(largest).toOpaque()
}
#endif

@_cdecl("metallum_copy_device_name")
public func metallum_copy_device_name(
    _ device: MTLDevice,
    _ output: UnsafeMutablePointer<CChar>?,
    _ capacity: Int64
) -> Int32 {
    return autoreleasepool {
        guard let output, capacity > 0 else {
            return 1
        }
        let maxLength = Int(capacity - 1)
        let bytes = Array(device.name.utf8.prefix(maxLength))
        for i in 0..<bytes.count {
            output[i] = CChar(bitPattern: bytes[i])
        }
        output[bytes.count] = 0
        return 0
    }
}

@_cdecl("metallum_NSWindow_backingScaleFactor")
public func metallum_NSWindow_backingScaleFactor(_ window: MetallumWindow) -> Double {
    #if os(macOS)
    return Double(window.backingScaleFactor)
    #elseif os(iOS)
    // UIWindow on iOS does not expose backingScaleFactor directly; the
    // on-screen scale is determined by the window's UIScreen.
    return Double(window.screen.scale)
    #endif
}

@_cdecl("metallum_create_metal_layer")
public func metallum_create_metal_layer(
    _ device: MTLDevice,
    _ contentsScale: Double
) -> UnsafeMutableRawPointer? {
    let layer = CAMetalLayer()
    layer.device = device
    layer.framebufferOnly = true
    layer.isOpaque = true
    layer.contentsScale = CGFloat(contentsScale)
    return retainedPointer(layer)
}

#if os(iOS)
/// Returns the host launcher's existing CAMetalLayer for the given UIView.
///
/// On Amethyst / PojavLauncher_iOS, `GameSurfaceView` overrides `+layerClass`
/// to return `CAMetalLayer.class`, so `view.layer` IS already a CAMetalLayer.
/// Amethyst's own Vulkan path (`pojavCreateContext` in `egl_bridge.m`) returns
/// `SurfaceViewController.surface.layer` directly to MoltenVK — it does NOT
/// create a new CAMetalLayer or attach a sublayer. We must follow the same
/// pattern: use `view.layer` itself as the render target.
///
/// Previously we created a new CAMetalLayer and added it as a sublayer of
/// `view.layer`. That does NOT work reliably: CAMetalLayer has special
/// compositing semantics, and a CAMetalLayer sublayer hosted inside another
/// CAMetalLayer (the view's backing layer) is not guaranteed to be displayed.
/// The result was a black screen with audio playing normally.
///
/// This function configures the existing layer's device (and a few other
/// render-target properties) and returns an *unretained* pointer — the view
/// owns the layer, so we must not retain it (would leak).
@_cdecl("metallum_ios_get_view_metal_layer")
public func metallum_ios_get_view_metal_layer(
    _ view: UIView,
    _ device: MTLDevice,
    _ contentsScale: Double
) -> UnsafeMutableRawPointer? {
    guard let layer = view.layer as? CAMetalLayer else {
        NSLog("[Metallum] view.layer is not a CAMetalLayer (got %@); falling back to sublayer attachment", String(describing: type(of: view.layer)))
        // Fallback for launchers that do not override +layerClass. Create a
        // new CAMetalLayer and add it as a sublayer, matching the macOS path.
        let newLayer = CAMetalLayer()
        newLayer.device = device
        newLayer.framebufferOnly = true
        newLayer.isOpaque = true
        newLayer.contentsScale = CGFloat(contentsScale)
        newLayer.frame = view.bounds
        view.layer.sublayers = [newLayer]
        return retainedPointer(newLayer)
    }
    NSLog("[Metallum] Using existing view.layer as CAMetalLayer (frame=\(layer.frame), contentsScale=\(layer.contentsScale), drawsAsynchronously=\(layer.drawsAsynchronously ? "YES" : "NO"))")
    layer.device = device
    layer.framebufferOnly = true
    layer.isOpaque = true
    // Do NOT override contentsScale: Amethyst sets it to
    // screenScale * resolutionScale and re-syncs it on rotation; let the
    // launcher own that property. The renderable size is governed by
    // `drawableSize`, which we set in metallum_configure_layer.
    return unretainedPointer(layer)
}
#endif

@_cdecl("metallum_NSView_setMetalLayer")
public func metallum_NSView_setMetalLayer(
    _ view: MetallumView,
    _ layer: CAMetalLayer
) {
    #if os(macOS)
    view.wantsLayer = true
    view.layer = layer
    #elseif os(iOS)
    // On iOS the Java side uses metallum_ios_get_view_metal_layer, which
    // returns view.layer directly (GameSurfaceView already overrides
    // +layerClass to CAMetalLayer.class). This function is therefore a no-op
    // on iOS — the layer is already attached to the view. We keep the symbol
    // so the macOS/Java code path that calls it unconditionally does not
    // need an #if guard.
    _ = view
    _ = layer
    #endif
}

@_cdecl("metallum_NSView_clearLayer")
public func metallum_NSView_clearLayer(_ view: MetallumView) {
    #if os(macOS)
    view.layer = nil
    view.wantsLayer = false
    #endif
}

@_cdecl("metallum_set_debug_labels_enabled")
public func metallum_set_debug_labels_enabled(_ enabled: Int32) {
    NativeState.debugLabelsEnabled = enabled != 0
}

@_cdecl("metallum_MTLDevice_maxMemoryAllocationSize")
public func metallum_MTLDevice_maxMemoryAllocationSize(_ device: MTLDevice) -> UInt64 {
    let maxBuffer = UInt64(device.maxBufferLength)
    #if os(iOS)
    if #available(iOS 16.0, *) {
        return min(maxBuffer, device.recommendedMaxWorkingSetSize)
    }
    return maxBuffer
    #else
    return min(maxBuffer, device.recommendedMaxWorkingSetSize)
    #endif
}

@_cdecl("metallum_MTLDevice_makeCommandQueue")
public func metallum_MTLDevice_makeCommandQueue(_ device: MTLDevice) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(device.makeCommandQueue())
    }
}

@_cdecl("metallum_MTLCommandQueue_makeCommandBuffer")
public func metallum_MTLCommandQueue_makeCommandBuffer(
    _ queue: MTLCommandQueue,
    _ labelPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard let commandBuffer = queue.makeCommandBuffer() else {
            return nil
        }
        if NativeState.debugLabelsEnabled {
            commandBuffer.label = stringFromOptionalCString(labelPtr)
        }
        return retainedPointer(commandBuffer)
    }
}

@_cdecl("metallum_MTLCommandBuffer_commit")
public func metallum_MTLCommandBuffer_commit(_ commandBuffer: MTLCommandBuffer) {
    commandBuffer.commit()
}

@_cdecl("metallum_create_semaphore")
public func metallum_create_semaphore() -> UnsafeMutableRawPointer? {
    retainedPointer(DispatchSemaphore(value: 0))
}

@_cdecl("metallum_MTLCommandBuffer_commitWithSignal")
public func metallum_MTLCommandBuffer_commitWithSignal(_ commandBuffer: MTLCommandBuffer, _ semaphore: DispatchSemaphore) {
    while semaphore.wait(timeout: .now()) == .success {}
    commandBuffer.addCompletedHandler { _ in
        semaphore.signal()
    }
    commandBuffer.commit()
}

@_cdecl("metallum_semaphore_wait")
public func metallum_semaphore_wait(_ semaphore: DispatchSemaphore, _ timeoutMs: UInt64) -> Int32 {
    let result: DispatchTimeoutResult
    if timeoutMs >= UInt64(Int.max) {
        result = semaphore.wait(timeout: .distantFuture)
    } else {
        result = semaphore.wait(timeout: .now() + .milliseconds(Int(timeoutMs)))
    }
    guard result == .success else {
        return 1
    }
    semaphore.signal()
    return 0
}

@_cdecl("metallum_MTLCommandBuffer_isCompleted")
public func metallum_MTLCommandBuffer_isCompleted(_ commandBuffer: MTLCommandBuffer) -> Int32 {
    commandBuffer.status == .completed || commandBuffer.status == .error ? 1 : 0
}

@_cdecl("metallum_MTLCommandBuffer_waitUntilCompleted")
public func metallum_MTLCommandBuffer_waitUntilCompleted(_ commandBuffer: MTLCommandBuffer, _ timeoutMs: UInt64) -> Int32 {
    if commandBuffer.status == .completed || commandBuffer.status == .error {
        return 0
    }
    if timeoutMs == 0 {
        return 1
    }
    commandBuffer.waitUntilCompleted()
    return commandBuffer.status == .completed || commandBuffer.status == .error ? 0 : 1
}

@_cdecl("metallum_MTLCommandBuffer_pushDebugGroup")
public func metallum_MTLCommandBuffer_pushDebugGroup(
    _ commandBuffer: MTLCommandBuffer,
    _ labelPtr: UnsafePointer<CChar>?
) {
    autoreleasepool {
        commandBuffer.pushDebugGroup(stringFromOptionalCString(labelPtr) ?? "")
    }
}

@_cdecl("metallum_MTLCommandBuffer_popDebugGroup")
public func metallum_MTLCommandBuffer_popDebugGroup(_ commandBuffer: MTLCommandBuffer) {
    commandBuffer.popDebugGroup()
}

@_cdecl("metallum_MTLCommandBuffer_makeBlitCommandEncoder")
public func metallum_MTLCommandBuffer_makeBlitCommandEncoder(
    _ commandBuffer: MTLCommandBuffer
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(commandBuffer.makeBlitCommandEncoder())
    }
}

@_cdecl("metallum_MTLCommandEncoder_endEncoding")
public func metallum_MTLCommandEncoder_endEncoding(_ encoder: MTLCommandEncoder) {
    encoder.endEncoding()
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer")
public func metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer(
    _ blit: MTLBlitCommandEncoder,
    _ sourceBuffer: MTLBuffer,
    _ sourceOffset: UInt64,
    _ destinationBuffer: MTLBuffer,
    _ destinationOffset: UInt64,
    _ length: UInt64
) {
    blit.copy(from: sourceBuffer, sourceOffset: Int(sourceOffset), to: destinationBuffer, destinationOffset: Int(destinationOffset), size: Int(length))
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromBufferToTexture")
public func metallum_MTLBlitCommandEncoder_copyFromBufferToTexture(
    _ blit: MTLBlitCommandEncoder,
    _ sourceBuffer: MTLBuffer,
    _ sourceOffset: UInt64,
    _ texture: MTLTexture,
    _ mipLevel: UInt64,
    _ slice: UInt64,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64,
    _ bytesPerImage: UInt64
) {
    blit.copy(
        from: sourceBuffer,
        sourceOffset: Int(sourceOffset),
        sourceBytesPerRow: Int(bytesPerRow),
        sourceBytesPerImage: Int(bytesPerImage),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: texture,
        destinationSlice: Int(slice),
        destinationLevel: Int(mipLevel),
        destinationOrigin: MTLOrigin(x: Int(x), y: Int(y), z: 0)
    )
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromTextureToTexture")
public func metallum_MTLBlitCommandEncoder_copyFromTextureToTexture(
    _ blit: MTLBlitCommandEncoder,
    _ sourceTexture: MTLTexture,
    _ destinationTexture: MTLTexture,
    _ mipLevel: UInt64,
    _ sourceX: UInt64,
    _ sourceY: UInt64,
    _ destX: UInt64,
    _ destY: UInt64,
    _ width: UInt64,
    _ height: UInt64
) {
    blit.copy(
        from: sourceTexture,
        sourceSlice: 0,
        sourceLevel: Int(mipLevel),
        sourceOrigin: MTLOrigin(x: Int(sourceX), y: Int(sourceY), z: 0),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: destinationTexture,
        destinationSlice: 0,
        destinationLevel: Int(mipLevel),
        destinationOrigin: MTLOrigin(x: Int(destX), y: Int(destY), z: 0)
    )
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer")
public func metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer(
    _ blit: MTLBlitCommandEncoder,
    _ sourceTexture: MTLTexture,
    _ destinationBuffer: MTLBuffer,
    _ destinationOffset: UInt64,
    _ mipLevel: UInt64,
    _ slice: UInt64,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64,
    _ bytesPerImage: UInt64
) {
    blit.copy(
        from: sourceTexture,
        sourceSlice: Int(slice),
        sourceLevel: Int(mipLevel),
        sourceOrigin: MTLOrigin(x: Int(x), y: Int(y), z: 0),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: destinationBuffer,
        destinationOffset: Int(destinationOffset),
        destinationBytesPerRow: Int(bytesPerRow),
        destinationBytesPerImage: Int(bytesPerImage)
    )
}

@_cdecl("metallum_create_buffer")
public func metallum_create_buffer(
    _ device: MTLDevice,
    _ length: Int,
    _ options: MTLResourceOptions
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(device.makeBuffer(length: length, options: options))
    }
}

@_cdecl("metallum_create_texture_2d")
public func metallum_create_texture_2d(
    _ device: MTLDevice,
    _ pixelFormat: MTLPixelFormat,
    _ width: UInt64,
    _ height: UInt64,
    _ depthOrLayers: UInt64,
    _ mipLevels: UInt64,
    _ cubeCompatible: UInt64,
    _ usage: MTLTextureUsage,
    _ storageMode: MTLStorageMode,
    _ labelPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: pixelFormat,
            width: Int(width),
            height: Int(height),
            mipmapped: mipLevels > 1
        )

        if cubeCompatible != 0 {
            if depthOrLayers > 6 {
                descriptor.textureType = MTLTextureType.typeCubeArray
                descriptor.arrayLength = Int(depthOrLayers) / 6
            } else {
                descriptor.textureType = MTLTextureType.typeCube
                descriptor.arrayLength = 1
            }
        } else if depthOrLayers > 1 {
            descriptor.textureType = MTLTextureType.type2DArray
            descriptor.arrayLength = Int(depthOrLayers)
        }

        descriptor.mipmapLevelCount = max(Int(mipLevels), 1)
        descriptor.usage = usage
        descriptor.storageMode = storageMode
        descriptor.hazardTrackingMode = .untracked
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            return nil
        }
        texture.label = stringFromOptionalCString(labelPtr)
        return retainedPointer(texture)
    }
}

@_cdecl("metallum_create_texture_view")
public func metallum_create_texture_view(_ texture: MTLTexture, _ baseMipLevel: UInt64, _ mipLevelCount: UInt64) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard mipLevelCount > 0 else {
            return nil
        }

        let baseLevel = Int(baseMipLevel)
        let levelCount = Int(mipLevelCount)
        guard baseLevel < texture.mipmapLevelCount, baseLevel + levelCount <= texture.mipmapLevelCount else {
            return nil
        }

        let view = texture.__newTextureView(
            with: texture.pixelFormat,
            textureType: texture.textureType,
            levels: NSRange(location: baseLevel, length: levelCount),
            slices: NSRange(location: 0, length: textureSliceCount(texture))
        )

        return retainedPointer(view)
    }
}

@_cdecl("metallum_create_buffer_texture_view")
public func metallum_create_buffer_texture_view(
    _ buffer: MTLBuffer,
    _ pixelFormat: MTLPixelFormat,
    _ offset: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard
            pixelFormat != .invalid,
            width > 0,
            bytesPerRow > 0
        else {
            return nil
        }

        let nativeOffset = Int(offset)
        let nativeWidth = Int(width)
        let nativeBytesPerRow = Int(bytesPerRow)
        guard nativeOffset >= 0, nativeWidth > 0, nativeBytesPerRow > 0, nativeOffset <= buffer.length, nativeBytesPerRow <= buffer.length - nativeOffset else {
            return nil
        }

        let alignment = buffer.device.minimumLinearTextureAlignment(for: pixelFormat)
        guard alignment > 0, nativeOffset % alignment == 0 else {
            return nil
        }

        let alignedBytesPerRow = roundUp(nativeBytesPerRow, alignment: alignment)
        let descriptor = MTLTextureDescriptor.textureBufferDescriptor(
            with: pixelFormat,
            width: nativeWidth,
            resourceOptions: [],
            usage: MTLTextureUsage.shaderRead
        )
        descriptor.storageMode = buffer.storageMode
        descriptor.hazardTrackingMode = .untracked

        return retainedPointer(buffer.makeTexture(descriptor: descriptor, offset: nativeOffset, bytesPerRow: alignedBytesPerRow))
    }
}

private func roundUp(_ value: Int, alignment: Int) -> Int {
    let remainder = value % alignment
    return remainder == 0 ? value : value + alignment - remainder
}

@_cdecl("metallum_create_sampler")
public func metallum_create_sampler(
    _ device: MTLDevice,
    _ addressModeU: MTLSamplerAddressMode,
    _ addressModeV: MTLSamplerAddressMode,
    _ minFilter: MTLSamplerMinMagFilter,
    _ magFilter: MTLSamplerMinMagFilter,
    _ mipFilter: MTLSamplerMipFilter,
    _ maxAnisotropy: Int32,
    _ lodMaxClamp: Double
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = minFilter
        descriptor.magFilter = magFilter
        descriptor.mipFilter = mipFilter
        descriptor.sAddressMode = addressModeU
        descriptor.tAddressMode = addressModeV
        descriptor.maxAnisotropy = max(Int(maxAnisotropy), 1)
        descriptor.lodMinClamp = 0.0
        descriptor.lodMaxClamp = lodMaxClamp >= 0.0 && lodMaxClamp.isFinite ? Float(lodMaxClamp) : Float.greatestFiniteMagnitude
        return retainedPointer(device.makeSamplerState(descriptor: descriptor))
    }
}

@_cdecl("metallum_MTLDevice_makeDepthStencilState")
public func metallum_MTLDevice_makeDepthStencilState(
    _ device: MTLDevice,
    _ depthCompareOp: MTLCompareFunction,
    _ writeDepth: Int32
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        unretainedPointer(ensureDepthStencilState(device: device, compareOp: depthCompareOp, writeDepth: writeDepth != 0))
    }
}

@_cdecl("metallum_MTLCommandBuffer_makeRenderCommandEncoder")
public func metallum_MTLCommandBuffer_makeRenderCommandEncoder(
    _ commandBuffer: MTLCommandBuffer,
    _ colorTexture: MTLTexture?,
    _ depthTexture: MTLTexture?,
    _ viewportWidth: Double,
    _ viewportHeight: Double,
    _ clearColorEnabled: Int32,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ clearDepthEnabled: Int32,
    _ clearDepth: Double
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard colorTexture != nil || depthTexture != nil else {
            return nil
        }
        let depthFormat = depthTexture?.pixelFormat ?? .invalid
        let stencilFormat = stencilPixelFormat(for: depthFormat)

        let renderPass = MTLRenderPassDescriptor()
        if let colorTexture {
            renderPass.colorAttachments[0].texture = colorTexture
            if clearColorEnabled != 0 {
                renderPass.colorAttachments[0].loadAction = .clear
                renderPass.colorAttachments[0].clearColor = makeClearColor(red: clearColorRed, green: clearColorGreen, blue: clearColorBlue, alpha: clearColorAlpha)
            } else {
                renderPass.colorAttachments[0].loadAction = .load
            }
            renderPass.colorAttachments[0].storeAction = .store
        }

        if let depthTexture {
            renderPass.depthAttachment.texture = depthTexture
            renderPass.depthAttachment.loadAction = clearDepthEnabled != 0 ? .clear : .load
            renderPass.depthAttachment.clearDepth = clearDepth
            renderPass.depthAttachment.storeAction = .store
            if stencilFormat != .invalid {
                renderPass.stencilAttachment.texture = depthTexture
                renderPass.stencilAttachment.loadAction = .dontCare
                renderPass.stencilAttachment.storeAction = .dontCare
            }
        }

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
            return nil
        }
        encoder.setViewport(MTLViewport(originX: 0.0, originY: 0.0, width: viewportWidth, height: viewportHeight, znear: 0.0, zfar: 1.0))
        return retainedPointer(encoder)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setRenderPipelineState")
public func metallum_MTLRenderCommandEncoder_setRenderPipelineState(_ encoder: MTLRenderCommandEncoder, _ pipeline: MTLRenderPipelineState) {
    encoder.setRenderPipelineState(pipeline)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setDepthStencilState")
public func metallum_MTLRenderCommandEncoder_setDepthStencilState(_ encoder: MTLRenderCommandEncoder, _ state: MTLDepthStencilState?) {
    encoder.setDepthStencilState(state)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setDepthBias")
public func metallum_MTLRenderCommandEncoder_setDepthBias(
    _ encoder: MTLRenderCommandEncoder,
    _ depthBias: Float,
    _ slopeScale: Float,
    _ clamp: Float
) {
    encoder.setDepthBias(depthBias, slopeScale: slopeScale, clamp: clamp)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setFrontFacingWinding")
public func metallum_MTLRenderCommandEncoder_setFrontFacingWinding(_ encoder: MTLRenderCommandEncoder, _ winding: MTLWinding) {
    encoder.setFrontFacing(winding)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setCullMode")
public func metallum_MTLRenderCommandEncoder_setCullMode(_ encoder: MTLRenderCommandEncoder, _ cullMode: MTLCullMode) {
    encoder.setCullMode(cullMode)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setTriangleFillMode")
public func metallum_MTLRenderCommandEncoder_setTriangleFillMode(_ encoder: MTLRenderCommandEncoder, _ fillMode: MTLTriangleFillMode) {
    encoder.setTriangleFillMode(fillMode)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setBuffer")
public func metallum_MTLRenderCommandEncoder_setBuffer(_ encoder: MTLRenderCommandEncoder, _ buffer: MTLBuffer?, _ offset: UInt64, _ index: UInt64, _ stageMask: Int32) {
    if (stageMask & 1) != 0 {
        encoder.setVertexBuffer(buffer, offset: Int(offset), index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentBuffer(buffer, offset: Int(offset), index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setBufferOffset")
public func metallum_MTLRenderCommandEncoder_setBufferOffset(_ encoder: MTLRenderCommandEncoder, _ offset: UInt64, _ index: UInt64, _ stageMask: Int32) {
    if (stageMask & 1) != 0 {
        encoder.setVertexBufferOffset(Int(offset), index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentBufferOffset(Int(offset), index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setTexture")
public func metallum_MTLRenderCommandEncoder_setTexture(_ encoder: MTLRenderCommandEncoder, _ texture: MTLTexture?, _ index: UInt64, _ stageMask: Int32) {
    if (stageMask & 1) != 0 {
        encoder.setVertexTexture(texture, index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentTexture(texture, index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setTextureAndSampler")
public func metallum_MTLRenderCommandEncoder_setTextureAndSampler(_ encoder: MTLRenderCommandEncoder, _ texture: MTLTexture?, _ sampler: MTLSamplerState?, _ index: UInt64, _ stageMask: Int32) {
    if (stageMask & 1) != 0 {
        encoder.setVertexTexture(texture, index: Int(index))
        encoder.setVertexSamplerState(sampler, index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentTexture(texture, index: Int(index))
        encoder.setFragmentSamplerState(sampler, index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setScissorRect")
public func metallum_MTLRenderCommandEncoder_setScissorRect(
    _ encoder: MTLRenderCommandEncoder,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64
) {
    encoder.setScissorRect(MTLScissorRect(x: Int(x), y: Int(y), width: Int(width), height: Int(height)))
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawPrimitives")
public func metallum_MTLRenderCommandEncoder_drawPrimitives(
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ firstVertex: Int,
    _ vertexCount: Int,
    _ instanceCount: Int,
    _ baseInstance: Int
) {
    encoder.drawPrimitives(
        type: primitiveType,
        vertexStart: firstVertex,
        vertexCount: vertexCount,
        instanceCount: instanceCount,
        baseInstance: baseInstance
    )
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawIndexedPrimitives")
public func metallum_MTLRenderCommandEncoder_drawIndexedPrimitives(
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ indexCount: Int,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ indexBufferOffset: Int,
    _ instanceCount: Int,
    _ baseVertex: Int,
    _ baseInstance: Int
) {
    encoder.drawIndexedPrimitives(
        type: primitiveType,
        indexCount: indexCount,
        indexType: indexType,
        indexBuffer: indexBuffer,
        indexBufferOffset: indexBufferOffset,
        instanceCount: instanceCount,
        baseVertex: baseVertex,
        baseInstance: baseInstance
    )
}

@_cdecl("metallum_MTLRenderCommandEncoder_multiDrawIndexed")
public func metallum_MTLRenderCommandEncoder_multiDrawIndexed(
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ firstIndexOffsets: UnsafePointer<Int>,
    _ indexCounts: UnsafePointer<Int32>,
    _ vertexOffsets: UnsafePointer<Int32>,
    _ drawCount: Int,
    _ instanceCount: Int,
    _ baseInstance: Int
) {
    for i in 0..<drawCount {
        let indexCount = Int(indexCounts[i])
        if indexCount > 0 {
            encoder.drawIndexedPrimitives(
                type: primitiveType,
                indexCount: indexCount,
                indexType: indexType,
                indexBuffer: indexBuffer,
                indexBufferOffset: firstIndexOffsets[i],
                instanceCount: instanceCount,
                baseVertex: Int(vertexOffsets[i]),
                baseInstance: baseInstance
            )
        }
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect")
public func metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ indirectBuffer: MTLBuffer,
    _ indirectBufferOffset: UInt64,
    _ drawCount: Int,
    _ stride: UInt64
) {
    var offset = Int(indirectBufferOffset)
    for _ in 0..<drawCount {
        encoder.drawIndexedPrimitives(
            type: primitiveType,
            indexType: indexType,
            indexBuffer: indexBuffer,
            indexBufferOffset: 0,
            indirectBuffer: indirectBuffer,
            indirectBufferOffset: offset
        )
        offset += Int(stride)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect")
public func metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect(
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ indirectBuffer: MTLBuffer,
    _ indirectBufferOffset: UInt64,
    _ drawCount: Int,
    _ stride: UInt64
) {
    var offset = Int(indirectBufferOffset)
    for _ in 0..<drawCount {
        encoder.drawPrimitives(
            type: primitiveType,
            indirectBuffer: indirectBuffer,
            indirectBufferOffset: offset
        )
        offset += Int(stride)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan")
public func metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(
    _ encoder: MTLRenderCommandEncoder,
    _ indexBuffer: MTLBuffer,
    _ fanIndexBuffer: MTLBuffer,
    _ fanIndexBufferOffset: Int,
    _ indexType: Int,
    _ indexOffsetBytes: Int,
    _ indexCount: Int,
    _ baseVertex: Int,
    _ instanceCount: Int,
    _ baseInstance: Int
) {
    guard let generatedIndexCount = writeIndexedTriangleFanIndices(
        sourceIndexBuffer: indexBuffer,
        destinationIndexBuffer: fanIndexBuffer,
        destinationOffset: fanIndexBufferOffset,
        indexType: indexType,
        indexOffsetBytes: indexOffsetBytes,
        indexCount: indexCount
    ) else {
        return
    }
    encoder.drawIndexedPrimitives(
        type: .triangle,
        indexCount: generatedIndexCount,
        indexType: .uint32,
        indexBuffer: fanIndexBuffer,
        indexBufferOffset: fanIndexBufferOffset,
        instanceCount: instanceCount,
        baseVertex: baseVertex,
        baseInstance: baseInstance
    )
}

@_cdecl("metallum_MTLCommandBuffer_clearColorDepthTexturesRegion")
public func metallum_MTLCommandBuffer_clearColorDepthTexturesRegion(
    _ commandBuffer: MTLCommandBuffer,
    _ colorTexture: MTLTexture,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ depthTexture: MTLTexture,
    _ clearDepth: Double,
    _ x: Int32,
    _ y: Int32,
    _ width: Int32,
    _ height: Int32,
    _ globalFence: MTLFence?
) {
    return autoreleasepool {
        guard width > 0, height > 0 else {
            return
        }

        let textureWidth = min(colorTexture.width, depthTexture.width)
        let textureHeight = min(colorTexture.height, depthTexture.height)
        let clampedX = max(Int(x), 0)
        let clampedY = max(Int(y), 0)
        let clampedMaxX = min(Int(x) + Int(width), textureWidth)
        let clampedMaxY = min(Int(y) + Int(height), textureHeight)
        if clampedX >= clampedMaxX || clampedY >= clampedMaxY {
            return
        }
        let scissorRect = MTLScissorRect(x: clampedX, y: clampedY, width: clampedMaxX - clampedX, height: clampedMaxY - clampedY)
        let fullRegion = clampedX == 0 && clampedY == 0 && clampedMaxX == textureWidth && clampedMaxY == textureHeight

        let renderPass = MTLRenderPassDescriptor()
        renderPass.colorAttachments[0].texture = colorTexture
        renderPass.colorAttachments[0].loadAction = fullRegion ? .clear : .load
        renderPass.colorAttachments[0].clearColor = makeClearColor(red: clearColorRed, green: clearColorGreen, blue: clearColorBlue, alpha: clearColorAlpha)
        renderPass.colorAttachments[0].storeAction = .store

        renderPass.depthAttachment.texture = depthTexture
        renderPass.depthAttachment.loadAction = fullRegion ? .clear : .load
        renderPass.depthAttachment.clearDepth = clearDepth
        renderPass.depthAttachment.storeAction = .store

        let depthFormat = depthTexture.pixelFormat
        let isStencilFormat: Bool = {
            #if os(macOS)
            return depthFormat == .depth24Unorm_stencil8 || depthFormat == .depth32Float_stencil8
            #else
            return depthFormat == .depth32Float_stencil8
            #endif
        }()
        if isStencilFormat {
            renderPass.stencilAttachment.texture = depthTexture
            renderPass.stencilAttachment.loadAction = .dontCare
            renderPass.stencilAttachment.storeAction = .dontCare
        }

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
            return
        }

        if let globalFence {
            encoder.waitForFence(globalFence, before: .fragment)
        }

        if !fullRegion {
            guard
                let pipeline = ensureClearColorDepthPipeline(commandBuffer.device, colorTexture.pixelFormat, depthTexture.pixelFormat),
                let depthState = ensureDepthStencilState(device: commandBuffer.device, compareOp: MTLCompareFunction.always, writeDepth: true)
            else {
                encoder.endEncoding()
                return
            }
            encodeClearDraw(
                encoder: encoder,
                pipeline: pipeline,
                textureWidth: textureWidth,
                textureHeight: textureHeight,
                clearColor: SIMD4<Float>(clearColorRed, clearColorGreen, clearColorBlue, clearColorAlpha),
                scissorRect: scissorRect,
                depthState: depthState,
                clearDepth: clearDepth
            )
        }

        if let globalFence {
            encoder.updateFence(globalFence, after: .fragment)
        }

        encoder.endEncoding()
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_clearDraw")
public func metallum_MTLRenderCommandEncoder_clearDraw(
    _ encoder: MTLRenderCommandEncoder,
    _ colorTexture: MTLTexture?,
    _ depthTexture: MTLTexture?,
    _ viewportWidth: Double,
    _ viewportHeight: Double,
    _ clearColorEnabled: Int32,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ clearDepthEnabled: Int32,
    _ clearDepth: Double
) {
    autoreleasepool {
        guard let device = colorTexture?.device ?? depthTexture?.device else {
            return
        }
        let colorFormat = colorTexture?.pixelFormat ?? .invalid
        let depthFormat = depthTexture?.pixelFormat ?? .invalid
        let writeColor = clearColorEnabled != 0

        guard let pipeline = ensureClearColorDepthPipeline(device, colorFormat, depthFormat, writeColor) else {
            return
        }

        let depthState: MTLDepthStencilState?
        if depthFormat != .invalid {
            depthState = ensureDepthStencilState(device: device, compareOp: .always, writeDepth: clearDepthEnabled != 0)
        } else {
            depthState = nil
        }

        let width = colorTexture?.width ?? depthTexture?.width ?? 0
        let height = colorTexture?.height ?? depthTexture?.height ?? 0
        guard width > 0, height > 0 else {
            return
        }

        encodeClearDraw(
            encoder: encoder,
            pipeline: pipeline,
            textureWidth: Int(viewportWidth),
            textureHeight: Int(viewportHeight),
            clearColor: SIMD4<Float>(clearColorRed, clearColorGreen, clearColorBlue, clearColorAlpha),
            scissorRect: MTLScissorRect(x: 0, y: 0, width: width, height: height),
            depthState: depthState,
            clearDepth: clearDepth
        )
    }
}

@_cdecl("metallum_configure_layer")
public func metallum_configure_layer(_ layer: CAMetalLayer, _ width: Double, _ height: Double, _ immediatePresentMode: Int32) {
    layer.pixelFormat = .bgra8Unorm
    layer.drawableSize = CGSize(width: width, height: height)
    #if os(macOS)
    layer.allowsNextDrawableTimeout = false
    layer.displaySyncEnabled = immediatePresentMode == 0
    #elseif os(iOS)
    // iOS: use allowsNextDrawableTimeout = true to prevent silent frame
    // drops when all drawables are in-flight. The host UIView owns the
    // CAMetalLayer (it IS view.layer), and the drawable pool is small
    // (3 drawables). With MAX_SUBMITS_IN_FLIGHT=3, racing between
    // command-buffer completions and drawable recycling can exhaust the
    // pool, causing nextDrawable() to return nil (black frame).
    layer.allowsNextDrawableTimeout = true
    // The CAMetalLayer IS view.layer (see metallum_ios_get_view_metal_layer):
    // the host UIView owns the layer's frame and updates it on layout /
    // rotation. We must NOT touch layer.frame here — doing so would fight
    // the view's layout pass and could leave the layer with the wrong frame.
    // The renderable size is governed by `drawableSize` above, which is what
    // Metal actually cares about.
    //
    // (The legacy sublayer fallback in metallum_ios_get_view_metal_layer sets
    // newLayer.frame = view.bounds at attach time; we accept that it will not
    // auto-resize if the view is later laid out larger.)
    #endif
}

@_cdecl("metallum_MTLCommandBuffer_encodePresentTextureToDrawable")
public func metallum_MTLCommandBuffer_encodePresentTextureToDrawable(
    _ commandBuffer: MTLCommandBuffer,
    _ layer: CAMetalLayer,
    _ sourceTexture: MTLTexture,
    _ globalFence: MTLFence?
) {
    return autoreleasepool {
        guard let drawable: CAMetalDrawable = layer.nextDrawable() else {
            NSLog("[Metallum] WARNING: nextDrawable() returned nil (drawableSize=\(layer.drawableSize), frame=\(layer.frame), isOpaque=\(layer.isOpaque), device=\(layer.device != nil ? "set" : "nil"))")
            return
        }

        let renderPass = MTLRenderPassDescriptor()
        renderPass.colorAttachments[0].texture = drawable.texture
        renderPass.colorAttachments[0].loadAction = .dontCare
        renderPass.colorAttachments[0].storeAction = .store

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
            return
        }

        if let globalFence {
            encoder.waitForFence(globalFence, before: .fragment)
        }

        encoder.setViewport(MTLViewport(
            originX: 0.0,
            originY: 0.0,
            width: Double(drawable.texture.width),
            height: Double(drawable.texture.height),
            znear: 0.0,
            zfar: 1.0
        ))

        encoder.setRenderPipelineState(NativeState.presentPipeline)
        encoder.setFragmentTexture(sourceTexture, index: 0)

        let requiresScaling = sourceTexture.width != drawable.texture.width ||
                              sourceTexture.height != drawable.texture.height

        let sampler = requiresScaling ? NativeState.presentLinearSampler : NativeState.presentNearestSampler
        encoder.setFragmentSamplerState(sampler, index: 0)

        encoder.drawPrimitives(
            type: .triangle,
            vertexStart: 0,
            vertexCount: 3
        )

        encoder.endEncoding()
        commandBuffer.present(drawable)
        #if os(iOS)
        CATransaction.flush()
        #endif
    }
}

@_cdecl("metallum_create_fence")
public func metallum_create_fence(_ device: MTLDevice) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(device.makeFence())
    }
}

@_cdecl("MTLRenderCommandEncoder_updateFence")
public func MTLRenderCommandEncoder_updateFence(
    _ encoder: MTLRenderCommandEncoder,
    _ fence: MTLFence,
    _ stages: MTLRenderStages
) {
    encoder.updateFence(fence, after: stages)
}

@_cdecl("MTLRenderCommandEncoder_waitForFence")
public func MTLRenderCommandEncoder_waitForFence(
    _ encoder: MTLRenderCommandEncoder,
    _ fence: MTLFence,
    _ stages: MTLRenderStages
) {
    encoder.waitForFence(fence, before: stages)
}

@_cdecl("MTLBlitCommandEncoder_updateFence")
public func MTLBlitCommandEncoder_updateFence(
    _ encoder: MTLBlitCommandEncoder,
    _ fence: MTLFence
) {
    encoder.updateFence(fence)
}

@_cdecl("MTLBlitCommandEncoder_waitForFence")
public func MTLBlitCommandEncoder_waitForFence(
    _ encoder: MTLBlitCommandEncoder,
    _ fence: MTLFence
) {
    encoder.waitForFence(fence)
}

@_cdecl("metallum_release_object")
public func metallum_release_object(_ obj: UnsafeMutableRawPointer?) {
    autoreleasepool {
        guard let obj else { return }
        Unmanaged<AnyObject>.fromOpaque(obj).release()
    }
}

@_cdecl("metallum_get_buffer_contents")
public func metallum_get_buffer_contents(_ buffer: MTLBuffer) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        buffer.contents()
    }
}

@_cdecl("metallum_MTLVertexDescriptor_create")
public func metallum_MTLVertexDescriptor_create() -> UnsafeMutableRawPointer? {
    retainedPointer(MTLVertexDescriptor())
}

@_cdecl("metallum_MTLVertexDescriptor_setAttribute")
public func metallum_MTLVertexDescriptor_setAttribute(
    _ desc: MTLVertexDescriptor,
    _ index: Int,
    _ format: MTLVertexFormat,
    _ offset: Int,
    _ bufferIndex: Int
) {
    autoreleasepool {
        desc.attributes[index].format = format
        desc.attributes[index].offset = offset
        desc.attributes[index].bufferIndex = bufferIndex
    }
}

@_cdecl("metallum_MTLVertexDescriptor_setLayout")
public func metallum_MTLVertexDescriptor_setLayout(
    _ desc: MTLVertexDescriptor,
    _ bufferIndex: Int,
    _ stride: Int,
    _ stepFunction: MTLVertexStepFunction,
    _ stepRate: Int
) {
    autoreleasepool {
        desc.layouts[bufferIndex].stride = stride
        desc.layouts[bufferIndex].stepFunction = stepFunction
        desc.layouts[bufferIndex].stepRate = stepRate
    }
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_create")
public func metallum_MTLRenderPipelineDescriptor_create() -> UnsafeMutableRawPointer? {
    retainedPointer(MTLRenderPipelineDescriptor())
}

@_cdecl("metallum_create_shader_function")
public func metallum_create_shader_function(
    _ device: MTLDevice,
    _ sourcePtr: UnsafePointer<CChar>?,
    _ entryPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard let sourcePtr, let entryPtr else {
            return nil
        }
        do {
            let library = try device.makeLibrary(source: String(cString: sourcePtr), options: nil)
            guard let function = library.makeFunction(name: String(cString: entryPtr)) else {
                NSLog("[metallum] Failed to resolve MSL entry point '%s'", entryPtr)
                return nil
            }
            return retainedPointer(function)
        } catch {
            NSLog("[metallum] Failed to compile MSL: %@", String(describing: error))
            return nil
        }
    }
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setCompiledFunctions")
public func metallum_MTLRenderPipelineDescriptor_setCompiledFunctions(
    _ desc: MTLRenderPipelineDescriptor,
    _ vertexFunction: MTLFunction,
    _ fragmentFunction: MTLFunction
) {
    desc.vertexFunction = vertexFunction
    desc.fragmentFunction = fragmentFunction
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setVertexDescriptor")
public func metallum_MTLRenderPipelineDescriptor_setVertexDescriptor(
    _ desc: MTLRenderPipelineDescriptor,
    _ vertexDesc: MTLVertexDescriptor
) {
    desc.vertexDescriptor = vertexDesc
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setAttachmentFormats")
public func metallum_MTLRenderPipelineDescriptor_setAttachmentFormats(
    _ desc: MTLRenderPipelineDescriptor,
    _ colorFormat: MTLPixelFormat,
    _ depthFormat: MTLPixelFormat,
    _ stencilFormat: MTLPixelFormat
) {
    autoreleasepool {
        desc.colorAttachments[0].pixelFormat = colorFormat
        desc.depthAttachmentPixelFormat = depthFormat
        desc.stencilAttachmentPixelFormat = stencilFormat
    }
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setBlendState")
public func metallum_MTLRenderPipelineDescriptor_setBlendState(
    _ desc: MTLRenderPipelineDescriptor,
    _ enabled: Int32,
    _ srcRgb: MTLBlendFactor,
    _ dstRgb: MTLBlendFactor,
    _ opRgb: MTLBlendOperation,
    _ srcAlpha: MTLBlendFactor,
    _ dstAlpha: MTLBlendFactor,
    _ opAlpha: MTLBlendOperation,
    _ writeMask: MTLColorWriteMask
) {
    autoreleasepool {
        desc.colorAttachments[0].writeMask = writeMask
        if enabled != 0 {
            desc.colorAttachments[0].isBlendingEnabled = true
            desc.colorAttachments[0].sourceRGBBlendFactor = srcRgb
            desc.colorAttachments[0].destinationRGBBlendFactor = dstRgb
            desc.colorAttachments[0].rgbBlendOperation = opRgb
            desc.colorAttachments[0].sourceAlphaBlendFactor = srcAlpha
            desc.colorAttachments[0].destinationAlphaBlendFactor = dstAlpha
            desc.colorAttachments[0].alphaBlendOperation = opAlpha
        } else {
            desc.colorAttachments[0].isBlendingEnabled = false
        }
    }
}

@_cdecl("metallum_MTLDevice_makeRenderPipelineState")
public func metallum_MTLDevice_makeRenderPipelineState(
    _ device: MTLDevice,
    _ descriptor: MTLRenderPipelineDescriptor
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        do {
            return retainedPointer(try device.makeRenderPipelineState(descriptor: descriptor))
        } catch {
            NSLog("[metallum] Failed to create render pipeline state: %@", String(describing: error))
            return nil
        }
    }
}

// ============================================================================
// MetalFX — Spatial Upscaling & Frame Interpolation
// ----------------------------------------------------------------------------
// MetalFX is Apple's GPU-accelerated upscaling (MTLFXSpatialScaler) and frame
// interpolation (MTLFXFrameInterpolator) framework. Availability:
//   * MTLFXSpatialScaler     — macOS 13.0+ (Apple Silicon M1+), iOS 16.0+
//   * MTLFXTemporalScaler    — macOS 13.0+ (Apple Silicon M1+), iOS 16.0+
//   * MTLFXFrameInterpolator — macOS 14.0+ (Apple Silicon M3+), iOS 17.0+
//
// On platforms/SDKs without MetalFX, all entry points are no-ops returning
// null/0 so the JVM-side bridge can gracefully fall back to the normal
// present path. The `canImport(MetalFX)` guard ensures the file still
// compiles on Linux toolchains (used by some CI sanity checks) and on older
// Xcode versions that predate the framework.
// ============================================================================

#if canImport(MetalFX)
import MetalFX

/// Cache of MetalFX support flags per device, keyed by device address. The
/// underlying `supports` family of methods is cheap but does an ObjC runtime
/// lookup each call, so memoizing avoids re-querying every frame.
private enum MetalFxSupportCache {
    static var spatial: [UInt: Bool] = [:]
    static var temporal: [UInt: Bool] = [:]
    static var interpolation: [UInt: Bool] = [:]
}

private struct MetalFxScalerKey: Hashable {
    let deviceAddress: UInt
    let inputWidth: Int
    let inputHeight: Int
    let outputWidth: Int
    let outputHeight: Int
    let colorFormat: MTLPixelFormat
    let outputFormat: MTLPixelFormat
    let variant: Int // 0 = spatial, 1 = temporal
}

private enum MetalFxState {
    static var spatialScalers: [MetalFxScalerKey: AnyObject] = [:]
    static var temporalScalers: [MetalFxScalerKey: AnyObject] = [:]
    static var interpolators: [MetalFxScalerKey: AnyObject] = [:]
    static var frameBlendPipeline: MTLRenderPipelineState?
}

@_cdecl("metallum_fx_supports_spatial_scaler")
public func metallum_fx_supports_spatial_scaler(_ device: MTLDevice) -> Int {
    return autoreleasepool {
        if #available(macOS 13.0, iOS 16.0, *) {
            let key = objectAddress(device)
            if let cached = MetalFxSupportCache.spatial[key] {
                return cached ? 1 : 0
            }
            let supported = MTLFXSpatialScalerDescriptor.supportsDevice(device)
            MetalFxSupportCache.spatial[key] = supported
            return supported ? 1 : 0
        }
        return 0
    }
}

@_cdecl("metallum_fx_supports_temporal_scaler")
public func metallum_fx_supports_temporal_scaler(_ device: MTLDevice) -> Int {
    return autoreleasepool {
        if #available(macOS 13.0, iOS 16.0, *) {
            let key = objectAddress(device)
            if let cached = MetalFxSupportCache.temporal[key] {
                return cached ? 1 : 0
            }
            let supported = MTLFXTemporalScalerDescriptor.supportsDevice(device)
            MetalFxSupportCache.temporal[key] = supported
            return supported ? 1 : 0
        }
        return 0
    }
}

@_cdecl("metallum_fx_supports_frame_interpolation")
public func metallum_fx_supports_frame_interpolation(_ device: MTLDevice) -> Int {
    return autoreleasepool {
        // MTLFXFrameInterpolator shipped in macOS 14.0 / iOS 17.0, NOT 26.0.
        // The previous guard (macOS 26.0) wrongly reported interpolation as
        // unsupported on every macOS 14/15 device, silently disabling the
        // feature even on M3/M4 machines where it works. Apple Silicon chip
        // gating (M3+ / A17 Pro+) is already encoded inside
        // `supportsDevice`, so we only need the OS-version floor here.
        if #available(macOS 14.0, iOS 17.0, *) {
            let key = objectAddress(device)
            if let cached = MetalFxSupportCache.interpolation[key] {
                return cached ? 1 : 0
            }
            // MTLFXFrameInterpolator requires both `supportsDevice` and the
            // appropriate OS / chip. On Apple Silicon, frame interpolation is
            // gated to M3+ on macOS and A17 Pro+ on iOS; `supportsDevice`
            // already encodes that gate.
            let supported = MTLFXFrameInterpolatorDescriptor.supportsDevice(device)
            MetalFxSupportCache.interpolation[key] = supported
            return supported ? 1 : 0
        }
        return 0
    }
}

@_cdecl("metallum_fx_create_spatial_scaler")
public func metallum_fx_create_spatial_scaler(
    _ device: MTLDevice,
    _ inputWidth: Int,
    _ inputHeight: Int,
    _ outputWidth: Int,
    _ outputHeight: Int,
    _ colorFormat: MTLPixelFormat,
    _ outputFormat: MTLPixelFormat
) -> UnsafeMutableRawPointer? {
    return autoreleasepool { () -> UnsafeMutableRawPointer? in
        if #available(macOS 13.0, iOS 16.0, *) {
            let key = MetalFxScalerKey(
                deviceAddress: objectAddress(device),
                inputWidth: inputWidth,
                inputHeight: inputHeight,
                outputWidth: outputWidth,
                outputHeight: outputHeight,
                colorFormat: colorFormat,
                outputFormat: outputFormat,
                variant: 0
            )
            if let cached = MetalFxState.spatialScalers[key] {
                return retainedPointer(cached)
            }
            let descriptor = MTLFXSpatialScalerDescriptor()
            descriptor.inputWidth = inputWidth
            descriptor.inputHeight = inputHeight
            descriptor.outputWidth = outputWidth
            descriptor.outputHeight = outputHeight
            descriptor.colorTextureFormat = colorFormat
            descriptor.outputTextureFormat = outputFormat
            // MTLFXSpatialScalerColorProcessingMode (macOS 26 SDK):
            //   .perceptual — input/output use a perceptual color space (sRGB)
            //   .linear     — input/output use a linear color space in [0,1]
            //   .hdr        — input/output use an HDR color space beyond [0,1]
            // We default to .perceptual so the scaler works for any color-only
            // present path; the depth-aware variant is exposed separately.
            descriptor.colorProcessingMode = .perceptual
            guard let scaler = try? descriptor.makeSpatialScaler(device: device) else {
                NSLog("[metallum-fx] Failed to create MTLFXSpatialScaler (%dx%d -> %dx%d)",
                      inputWidth, inputHeight, outputWidth, outputHeight)
                return nil
            }
            MetalFxState.spatialScalers[key] = scaler
            return retainedPointer(scaler)
        }
        return nil
    }
}

@_cdecl("metallum_fx_spatial_scaler_encode")
public func metallum_fx_spatial_scaler_encode(
    _ scaler: AnyObject,
    _ commandBuffer: MTLCommandBuffer,
    _ sourceTexture: MTLTexture,
    _ destinationTexture: MTLTexture,
    _ jitterOffsetX: Float,
    _ jitterOffsetY: Float,
    _ mipScale: Float
) {
    return autoreleasepool {
        if #available(macOS 13.0, iOS 16.0, *), let scaler = scaler as? MTLFXSpatialScaler {
            scaler.colorTexture = sourceTexture
            scaler.outputTexture = destinationTexture
            // jitterOffsetX / jitterOffsetY / mipLevel were removed from the
            // MTLFXSpatialScaler protocol in the macOS 26 SDK; they are now
            // configured at descriptor time. We leave them at their default
            // (0) which is correct for non-TAA renderers like Minecraft.
            _ = jitterOffsetX
            _ = jitterOffsetY
            _ = mipScale
            scaler.encode(commandBuffer: commandBuffer)
        }
    }
}

@_cdecl("metallum_fx_create_temporal_scaler")
public func metallum_fx_create_temporal_scaler(
    _ device: MTLDevice,
    _ inputWidth: Int,
    _ inputHeight: Int,
    _ outputWidth: Int,
    _ outputHeight: Int,
    _ colorFormat: MTLPixelFormat,
    _ outputFormat: MTLPixelFormat,
    _ motionVectorFormat: MTLPixelFormat,
    _ depthFormat: MTLPixelFormat
) -> UnsafeMutableRawPointer? {
    return autoreleasepool { () -> UnsafeMutableRawPointer? in
        if #available(macOS 13.0, iOS 16.0, *) {
            let key = MetalFxScalerKey(
                deviceAddress: objectAddress(device),
                inputWidth: inputWidth,
                inputHeight: inputHeight,
                outputWidth: outputWidth,
                outputHeight: outputHeight,
                colorFormat: colorFormat,
                outputFormat: outputFormat,
                variant: 1
            )
            if let cached = MetalFxState.temporalScalers[key] {
                return retainedPointer(cached)
            }
            let descriptor = MTLFXTemporalScalerDescriptor()
            descriptor.inputWidth = inputWidth
            descriptor.inputHeight = inputHeight
            descriptor.outputWidth = outputWidth
            descriptor.outputHeight = outputHeight
            descriptor.colorTextureFormat = colorFormat
            descriptor.outputTextureFormat = outputFormat
            descriptor.motionTextureFormat = motionVectorFormat
            descriptor.depthTextureFormat = depthFormat
            // temporalAAEnabled was removed from MTLFXTemporalScalerDescriptor
            // in the macOS 26 SDK; temporal AA is now always implicit.
            guard let scaler = try? descriptor.makeTemporalScaler(device: device) else {
                NSLog("[metallum-fx] Failed to create MTLFXTemporalScaler")
                return nil
            }
            MetalFxState.temporalScalers[key] = scaler
            return retainedPointer(scaler)
        }
        return nil
    }
}

@_cdecl("metallum_fx_temporal_scaler_encode")
public func metallum_fx_temporal_scaler_encode(
    _ scaler: AnyObject,
    _ commandBuffer: MTLCommandBuffer,
    _ sourceColor: MTLTexture,
    _ prevColor: MTLTexture?,
    _ motionVectors: MTLTexture?,
    _ depth: MTLTexture?,
    _ destination: MTLTexture,
    _ jitterOffsetX: Float,
    _ jitterOffsetY: Float,
    _ motionVectorScaleX: Float,
    _ motionVectorScaleY: Float,
    _ reset: Int
) {
    return autoreleasepool {
        if #available(macOS 13.0, iOS 16.0, *), let scaler = scaler as? MTLFXTemporalScaler {
            scaler.colorTexture = sourceColor
            // MTLFXTemporalScaler manages its own history internally, so there
            // is no previousColorTexture property to set. jitterOffsetX/Y and
            // reset are still present in the macOS 26 SDK.
            if let motionVectors { scaler.motionTexture = motionVectors }
            if let depth { scaler.depthTexture = depth }
            scaler.outputTexture = destination
            scaler.jitterOffsetX = jitterOffsetX
            scaler.jitterOffsetY = jitterOffsetY
            scaler.motionVectorScaleX = motionVectorScaleX
            scaler.motionVectorScaleY = motionVectorScaleY
            scaler.reset = reset != 0
            _ = prevColor
            scaler.encode(commandBuffer: commandBuffer)
        }
    }
}

@_cdecl("metallum_fx_create_frame_interpolator")
public func metallum_fx_create_frame_interpolator(
    _ device: MTLDevice,
    _ outputWidth: Int,
    _ outputHeight: Int,
    _ colorFormat: MTLPixelFormat
) -> UnsafeMutableRawPointer? {
    return autoreleasepool { () -> UnsafeMutableRawPointer? in
        if #available(macOS 26.0, iOS 26.0, *) {
            let key = MetalFxScalerKey(
                deviceAddress: objectAddress(device),
                inputWidth: outputWidth,
                inputHeight: outputHeight,
                outputWidth: outputWidth,
                outputHeight: outputHeight,
                colorFormat: colorFormat,
                outputFormat: colorFormat,
                variant: 2
            )
            if let cached = MetalFxState.interpolators[key] {
                return retainedPointer(cached)
            }
            let descriptor = MTLFXFrameInterpolatorDescriptor()
            descriptor.outputWidth = outputWidth
            descriptor.outputHeight = outputHeight
            descriptor.colorTextureFormat = colorFormat
            // Must match the MTLPixelFormat.RG32Float motion texture created
            // by MetalFxPipeline.ensureMotionVectorTexture(). If left at the
            // default (.invalid), assigning a RG32Float motionTexture at encode
            // time is a format mismatch that newer Metal validation (e.g. on
            // M5 Max) rejects — crashing the encoder.
            descriptor.motionTextureFormat = .rg32Float
            guard let interp = try? descriptor.makeFrameInterpolator(device: device) else {
                NSLog("[metallum-fx] Failed to create MTLFXFrameInterpolator (%dx%d)",
                      outputWidth, outputHeight)
                return nil
            }
            MetalFxState.interpolators[key] = interp
            return retainedPointer(interp)
        }
        return nil
    }
}

@_cdecl("metallum_fx_frame_interpolator_encode")
public func metallum_fx_frame_interpolator_encode(
    _ interpolator: AnyObject,
    _ commandBuffer: MTLCommandBuffer,
    _ sourceColor: MTLTexture,
    _ previousColor: MTLTexture,
    _ motionVectors: MTLTexture,
    _ destination: MTLTexture,
    _ motionVectorScaleX: Float,
    _ motionVectorScaleY: Float,
    _ reset: Int
) {
    return autoreleasepool {
        if #available(macOS 26.0, iOS 26.0, *),
           let interpolator = interpolator as? MTLFXFrameInterpolator {
            interpolator.colorTexture = sourceColor
            // MTLFXFrameInterpolator uses prevColorTexture (renamed from
            // previousColorTexture) and shouldResetHistory (renamed from reset).
            interpolator.prevColorTexture = previousColor
            interpolator.motionTexture = motionVectors
            interpolator.outputTexture = destination
            interpolator.motionVectorScaleX = motionVectorScaleX
            interpolator.motionVectorScaleY = motionVectorScaleY
            interpolator.shouldResetHistory = reset != 0
            interpolator.encode(commandBuffer: commandBuffer)
        }
    }
}

// ---------------------------------------------------------------------------
// Frame-blend fallback interpolator. When MTLFXFrameInterpolator is not
// available (older chips, e.g. M1/M2 on macOS, A14–A16 on iOS) we provide a
// simple 50/50 lerp between the previous and current frame as a perceptual
// "smooth motion" fallback. This is NOT true motion-interpolation but gives a
// usable intermediate frame between two real frames at half the cost.
// ---------------------------------------------------------------------------

private func frameBlendMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct FrameBlendVertexOut {
      float4 position [[position]];
      float2 uv;
    };

    vertex FrameBlendVertexOut metallum_frame_blend_vs(uint vertexId [[vertex_id]]) {
      const float2 positions[3] = {
        float2(-1.0,  1.0),
        float2( 3.0,  1.0),
        float2(-1.0, -3.0)
      };
      const float2 uvs[3] = {
        float2(0.0, 1.0),
        float2(2.0, 1.0),
        float2(0.0, -1.0)
      };
      FrameBlendVertexOut out;
      out.position = float4(positions[vertexId], 0.0, 1.0);
      out.uv = uvs[vertexId];
      return out;
    }

    fragment float4 metallum_frame_blend_fs(
      FrameBlendVertexOut in [[stage_in]],
      texture2d<float> currentTex [[texture(0)]],
      texture2d<float> previousTex [[texture(1)]],
      sampler smp [[sampler(0)]]
    ) {
      float4 current = currentTex.sample(smp, in.uv);
      float4 previous = previousTex.sample(smp, in.uv);
      return mix(previous, current, 0.5);
    }
    """
}

private func ensureFrameBlendPipeline(device: MTLDevice, colorFormat: MTLPixelFormat) -> MTLRenderPipelineState? {
    if MetalFxState.frameBlendPipeline != nil {
        return MetalFxState.frameBlendPipeline
    }
    do {
        let library = try device.makeLibrary(source: frameBlendMslSource(), options: nil)
        guard let vertexFunction = library.makeFunction(name: "metallum_frame_blend_vs"),
              let fragmentFunction = library.makeFunction(name: "metallum_frame_blend_fs") else {
            NSLog("[metallum-fx] Failed to load frame-blend shader functions")
            return nil
        }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.colorAttachments[0].isBlendingEnabled = false
        let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
        MetalFxState.frameBlendPipeline = pipeline
        return pipeline
    } catch {
        NSLog("[metallum-fx] Failed to build frame-blend pipeline: %@", String(describing: error))
        return nil
    }
}

@_cdecl("metallum_fx_encode_frame_blend")
public func metallum_fx_encode_frame_blend(
    _ device: MTLDevice,
    _ commandBuffer: MTLCommandBuffer,
    _ sourceTexture: MTLTexture,
    _ previousTexture: MTLTexture,
    _ destinationTexture: MTLTexture
) -> Int {
    return autoreleasepool {
        guard let pipeline = ensureFrameBlendPipeline(device: device, colorFormat: destinationTexture.pixelFormat) else {
            return 0
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = destinationTexture
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            return 0
        }
        encoder.setViewport(MTLViewport(
            originX: 0, originY: 0,
            width: Double(destinationTexture.width),
            height: Double(destinationTexture.height),
            znear: 0, zfar: 1
        ))
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(sourceTexture, index: 0)
        encoder.setFragmentTexture(previousTexture, index: 1)
        encoder.setFragmentSamplerState(NativeState.presentLinearSampler, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        return 1
    }
}

// ---------------------------------------------------------------------------
// Clear a texture to all zeros via a render pass with .clear loadAction.
// Used to initialise the MetalFX motion-vector texture: a Private-storage
// texture has undefined initial contents, and feeding garbage RG32Float
// motion vectors into MTLFXFrameInterpolator crashes the GPU encoder on
// stricter drivers (e.g. M5 Max). A one-shot clear at creation time is
// sufficient — the interpolator only reads it; nothing writes new motion
// vectors, so it stays zero for the lifetime of the texture.
// ---------------------------------------------------------------------------

@_cdecl("metallum_fx_clear_texture")
public func metallum_fx_clear_texture(
    _ commandBuffer: MTLCommandBuffer,
    _ texture: MTLTexture
) -> Int {
    return autoreleasepool {
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(
            red: 0.0, green: 0.0, blue: 0.0, alpha: 0.0
        )
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            return 0
        }
        encoder.endEncoding()
        return 1
    }
}

#else

// Stubs for toolchains/SDKs without MetalFX (older Xcode that predates the
// framework). Metal is always available because the rest of this file already
// imports it unconditionally. All stubs return 0/null so the JVM falls back
// to the legacy present path.

@_cdecl("metallum_fx_supports_spatial_scaler")
public func metallum_fx_supports_spatial_scaler(_ device: MTLDevice) -> Int { return 0 }

@_cdecl("metallum_fx_supports_temporal_scaler")
public func metallum_fx_supports_temporal_scaler(_ device: MTLDevice) -> Int { return 0 }

@_cdecl("metallum_fx_supports_frame_interpolation")
public func metallum_fx_supports_frame_interpolation(_ device: MTLDevice) -> Int { return 0 }

@_cdecl("metallum_fx_create_spatial_scaler")
public func metallum_fx_create_spatial_scaler(
    _ device: MTLDevice, _ inputWidth: Int, _ inputHeight: Int,
    _ outputWidth: Int, _ outputHeight: Int,
    _ colorFormat: MTLPixelFormat, _ outputFormat: MTLPixelFormat
) -> UnsafeMutableRawPointer? { return nil }

@_cdecl("metallum_fx_spatial_scaler_encode")
public func metallum_fx_spatial_scaler_encode(
    _ scaler: AnyObject, _ commandBuffer: AnyObject,
    _ sourceTexture: AnyObject, _ destinationTexture: AnyObject,
    _ jitterOffsetX: Float, _ jitterOffsetY: Float, _ mipScale: Float
) {}

@_cdecl("metallum_fx_create_temporal_scaler")
public func metallum_fx_create_temporal_scaler(
    _ device: MTLDevice, _ inputWidth: Int, _ inputHeight: Int,
    _ outputWidth: Int, _ outputHeight: Int,
    _ colorFormat: MTLPixelFormat, _ outputFormat: MTLPixelFormat,
    _ motionVectorFormat: MTLPixelFormat, _ depthFormat: MTLPixelFormat
) -> UnsafeMutableRawPointer? { return nil }

@_cdecl("metallum_fx_temporal_scaler_encode")
public func metallum_fx_temporal_scaler_encode(
    _ scaler: AnyObject, _ commandBuffer: AnyObject,
    _ sourceColor: AnyObject, _ prevColor: AnyObject?,
    _ motionVectors: AnyObject?, _ depth: AnyObject?, _ destination: AnyObject,
    _ jitterOffsetX: Float, _ jitterOffsetY: Float,
    _ motionVectorScaleX: Float, _ motionVectorScaleY: Float, _ reset: Int
) {}

@_cdecl("metallum_fx_create_frame_interpolator")
public func metallum_fx_create_frame_interpolator(
    _ device: MTLDevice, _ outputWidth: Int, _ outputHeight: Int,
    _ colorFormat: MTLPixelFormat
) -> UnsafeMutableRawPointer? { return nil }

@_cdecl("metallum_fx_frame_interpolator_encode")
public func metallum_fx_frame_interpolator_encode(
    _ interpolator: AnyObject, _ commandBuffer: AnyObject,
    _ sourceColor: AnyObject, _ previousColor: AnyObject,
    _ motionVectors: AnyObject, _ destination: AnyObject,
    _ motionVectorScaleX: Float, _ motionVectorScaleY: Float, _ reset: Int
) {}

@_cdecl("metallum_fx_encode_frame_blend")
public func metallum_fx_encode_frame_blend(
    _ device: MTLDevice, _ commandBuffer: AnyObject,
    _ sourceTexture: AnyObject, _ previousTexture: AnyObject, _ destinationTexture: AnyObject
) -> Int { return 0 }

@_cdecl("metallum_fx_clear_texture")
public func metallum_fx_clear_texture(
    _ commandBuffer: AnyObject, _ texture: AnyObject
) -> Int { return 0 }

#endif // canImport(MetalFX)
