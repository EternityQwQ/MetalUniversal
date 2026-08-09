package com.metallum.client.metal.render.bridge;

import com.metallum.Metallum;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Java 8 JNI bridge to {@code libmetallum.dylib}.
 *
 * <p>This is the 1.12.2-forge port of the original
 * {@code MetalNativeBridge}, which used the Java 25 Foreign Function &amp; Memory
 * API (panama) with per-symbol {@code MethodHandle} downcalls. Java 8 has no
 * FFM API, so each native function is declared as a {@code native} method and
 * linked against the Swift dylib's {@code @_cdecl} symbols via JNI.</p>
 *
 * <p>Handles are plain {@code long}s ({@link NativePointer}); pointer
 * parameters that were {@code MemorySegment} in the original become
 * {@code long} here. The Swift sources ({@code src/main/native/*.swift}) are
 * reused verbatim — they speak the Apple Metal API, which is independent of
 * Minecraft's version.</p>
 *
 * <p>The dylib is shipped inside the jar under {@code /natives/macos/} (and
 * {@code /natives/ios/}) and extracted to a writable temp directory at load
 * time, mirroring the original extraction strategy. On iOS, code-signing
 * forbids loading unsigned dylibs from writable directories, so the host
 * launcher is expected to bundle a signed framework; this class detects iOS
 * and defers to the host-provided library.</p>
 */
public final class MetalNativeBridge {
    private static final String MACOS_RESOURCE_PATH = "/natives/macos/libmetallum.dylib";
    private static final String IOS_RESOURCE_PATH = "/natives/ios/libmetallum.dylib";

    private static volatile boolean loaded = false;
    private static volatile boolean loadFailed = false;

    private MetalNativeBridge() {
    }

    // ----- device / surface ------------------------------------------------

    public static native long metallum_create_system_default_device();

    public static native int metallum_copy_device_name(long device, byte[] out, int outLen);

    public static native String metallum_copy_device_name(long device);

    public static native double metallum_NSWindow_backingScaleFactor(long window);

    public static native long metallum_create_metal_layer(long device, double scale);

    public static native long metallum_configure_layer(long layer, long device, double scale);

    public static native void metallum_set_metal_hud(long device, int enabled);

    public static native int metallum_metal_hud_status(long device);

    public static native void metallum_NSView_setMetalLayer(long view, long layer);

    public static native void metallum_NSView_clearLayer(long view);

    public static native void metallum_set_debug_labels_enabled(int enabled);

    public static native void metallum_init_pipelines(long device);

    public static native long metallum_MTLDevice_maxMemoryAllocationSize(long device);

    public static native long metallum_MTLDevice_makeCommandQueue(long device);

    public static native long metallum_MTLDevice_makeRenderPipelineState(long device, long descriptor);

    public static native long metallum_MTLDevice_makeComputePipelineState(long device, long function);

    public static native long metallum_MTLDevice_makeDepthStencilState(long device, int compareFun, boolean depthWriteEnabled);

    // ----- command queue / buffer / encoder -------------------------------

    public static native long metallum_MTLCommandQueue_makeCommandBuffer(long queue);

    public static native void metallum_MTLCommandBuffer_commit(long buffer);

    public static native void metallum_MTLCommandBuffer_commitWithSignal(long buffer, long semaphore);

    public static native int metallum_MTLCommandBuffer_isCompleted(long buffer);

    public static native int metallum_MTLCommandBuffer_completedSuccessfully(long buffer);

    public static native double metallum_MTLCommandBuffer_gpuStartTime(long buffer);

    public static native double metallum_MTLCommandBuffer_gpuEndTime(long buffer);

    public static native int metallum_MTLCommandBuffer_waitUntilCompleted(long buffer, long timeoutMs);

    public static native void metallum_MTLCommandBuffer_pushDebugGroup(long buffer, String name);

    public static native void metallum_MTLCommandBuffer_popDebugGroup(long buffer);

    public static native long metallum_MTLCommandBuffer_makeBlitCommandEncoder(long buffer);

    public static native long metallum_MTLCommandBuffer_makeComputeCommandEncoder(long buffer);

    public static native long metallum_MTLCommandBuffer_makeRenderCommandEncoder(long buffer, long renderPassDescriptor);

    public static native long metallum_MTLCommandBuffer_makeRenderCommandEncoder_v2(long buffer, long renderPassDescriptor);

    public static native void metallum_MTLCommandBuffer_clearColorDepthTexturesRegion(long buffer, long color, long depth, int clearFlags,
            float r, float g, float b, float a, double clearDepth, int stencil,
            long originX, long originY, long originZ, long width, long height, long depthSize);

    public static native void metallum_MTLCommandBuffer_encodePresentTextureToDrawable(long buffer, long texture, long drawable);

    public static native void metallum_MTLCommandEncoder_endEncoding(long encoder);

    // ----- render command encoder -----------------------------------------

    public static native void metallum_MTLRenderCommandEncoder_setRenderPipelineState(long encoder, long pipelineState);

    public static native void metallum_MTLRenderCommandEncoder_setVertexBuffer(long encoder, long buffer, long offset, int index);

    public static native void metallum_MTLRenderCommandEncoder_setBuffer(long encoder, long buffer, long offset, int index);

    public static native void metallum_MTLRenderCommandEncoder_setBufferOffset(long encoder, long offset, int index);

    public static native void metallum_MTLRenderCommandEncoder_setFragmentBuffer(long encoder, long buffer, long offset, int index);

    public static native void metallum_MTLRenderCommandEncoder_setVertexTexture(long encoder, long texture, int index);

    public static native void metallum_MTLRenderCommandEncoder_setFragmentTexture(long encoder, long texture, int index);

    public static native void metallum_MTLRenderCommandEncoder_setTexture(long encoder, long texture, int index);

    public static native void metallum_MTLRenderCommandEncoder_setTextureAndSampler(long encoder, long texture, long sampler, int index);

    public static native void metallum_MTLRenderCommandEncoder_setCullMode(long encoder, long mode);

    public static native void metallum_MTLRenderCommandEncoder_setFrontFacingWinding(long encoder, long winding);

    public static native void metallum_MTLRenderCommandEncoder_setTriangleFillMode(long encoder, long mode);

    public static native void metallum_MTLRenderCommandEncoder_setDepthBias(long encoder, float bias, float slopeScale, float clamp);

    public static native void metallum_MTLRenderCommandEncoder_setDepthStencilState(long encoder, long state);

    public static native void metallum_MTLRenderCommandEncoder_setDepthStoreAction(long encoder, int storeAction);

    public static native void metallum_MTLRenderCommandEncoder_setScissorRect(long encoder, int x, int y, int width, int height);

    public static native void metallum_MTLRenderCommandEncoder_setViewport(long encoder, double originX, double originY, double width, double height, double znear, double zfar);

    public static native void metallum_MTLRenderCommandEncoder_drawPrimitives(long encoder, long primitiveType, long vertexStart, long vertexCount);

    public static native void metallum_MTLRenderCommandEncoder_drawIndexedPrimitives(long encoder, long primitiveType, long indexCount, long indexType, long indexBuffer, long indexBufferOffset);

    public static native void metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect(long encoder, long primitiveType, long indirectBuffer, long indirectBufferOffset);

    public static native void metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(long encoder, long primitiveType, long indexType, long indexBuffer, long indexBufferOffset, long indirectBuffer, long indirectBufferOffset);

    public static native void metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(long encoder, long indexCount, long indexType, long indexBuffer, long indexBufferOffset);

    public static native void metallum_MTLRenderCommandEncoder_multiDrawIndexed(long encoder, long primitiveType, long indexType, long indexBuffer, long indirectBuffer, long indirectBufferOffset, int drawCount);

    public static native void metallum_MTLRenderCommandEncoder_clearDraw(long encoder, int flags, float r, float g, float b, float a, double depth);

    // ----- blit command encoder -------------------------------------------

    public static native void metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer(long encoder, long src, long srcOffset, long dst, long dstOffset, long size);

    public static native void metallum_MTLBlitCommandEncoder_copyFromBufferToTexture(long encoder, long src, long srcOffset, long srcRowBytes, long srcImageBytes, long dst, long dstSlice, long dstLevel, long dstOriginX, long dstOriginY, long dstOriginZ, long width, long height, long depth);

    public static native void metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer(long encoder, long src, long srcSlice, long srcLevel, long srcOriginX, long srcOriginY, long srcOriginZ, long dst, long dstOffset, long dstRowBytes, long dstImageBytes, long width, long height, long depth);

    public static native void metallum_MTLBlitCommandEncoder_copyFromTextureToTexture(long encoder, long src, long srcSlice, long srcLevel, long srcOriginX, long srcOriginY, long srcOriginZ, long dst, long dstSlice, long dstLevel, long dstOriginX, long dstOriginY, long dstOriginZ, long width, long height, long depth);

    public static native void metallum_MTLBlitCommandEncoder_generateMipmaps(long encoder, long texture);

    // ----- compute command encoder ----------------------------------------

    public static native void metallum_MTLComputeCommandEncoder_setComputePipelineState(long encoder, long state);

    public static native void metallum_MTLComputeCommandEncoder_setBuffer(long encoder, long buffer, long offset, int index);

    public static native void metallum_MTLComputeCommandEncoder_setTexture(long encoder, long texture, int index);

    public static native void metallum_MTLComputeCommandEncoder_setSamplerState(long encoder, long sampler, int index);

    public static native void metallum_MTLComputeCommandEncoder_dispatchThreadgroups(long encoder, long groupsX, long groupsY, long groupsZ);

    public static native void metallum_MTLComputeCommandEncoder_dispatchThreadgroupsIndirect(long encoder, long indirectBuffer, long indirectBufferOffset);

    public static native void metallum_MTLComputeCommandEncoder_updateFence(long encoder, long fence);

    public static native void metallum_MTLComputeCommandEncoder_waitForFence(long encoder, long fence);

    // ----- pipeline descriptor --------------------------------------------

    public static native long metallum_MTLRenderPipelineDescriptor_create();

    public static native void metallum_MTLRenderPipelineDescriptor_setCompiledFunctions(long descriptor, long vertexFunction, long fragmentFunction);

    public static native void metallum_MTLRenderPipelineDescriptor_setVertexDescriptor(long descriptor, long vertexDescriptor);

    public static native void metallum_MTLRenderPipelineDescriptor_setAttachmentFormats(long descriptor, long color0, long color1, long color2, long color3, long depth, int samples);

    public static native void metallum_MTLRenderPipelineDescriptor_setDepthStencilFormats(long descriptor, long depthFormat, long stencilFormat);

    public static native void metallum_MTLRenderPipelineDescriptor_setBlendState(long descriptor, int blendEnabled);

    public static native void metallum_MTLRenderPipelineDescriptor_setColorAttachmentBlendState(long descriptor, int index, int blendEnabled, long rgbBlendOp, long alphaBlendOp, long srcRgb, long dstRgb, long srcAlpha, long dstAlpha);

    public static native void metallum_MTLRenderPipelineDescriptor_setColorAttachmentFormat(long descriptor, int index, long pixelFormat);

    // ----- vertex descriptor ----------------------------------------------

    public static native long metallum_MTLVertexDescriptor_create();

    public static native void metallum_MTLVertexDescriptor_setLayout(long descriptor, int index, long stride, long stepRate, long stepFunction);

    public static native void metallum_MTLVertexDescriptor_setAttribute(long descriptor, int index, long format, long bufferIndex, long offset);

    // ----- buffers / textures / samplers / shaders ------------------------

    public static native long metallum_create_buffer(long device, long byteSize, int resourceOptions);

    public static native long metallum_get_buffer_contents(long buffer);

    public static native long metallum_create_texture(long device, int width, int height, int depth, int mipLevels, int sampleCount, int textureType, long pixelFormat, int usage, int resourceOptions);

    public static native long metallum_create_texture_2d(long device, int width, int height, int mipLevels, long pixelFormat, int usage);

    public static native long metallum_create_texture_view(long device, long sourceTexture, long pixelFormat, int baseLevel, int levelCount, int baseSlice, int sliceCount, int usage);

    public static native long metallum_create_texture_view_alpha_one(long device, long sourceTexture);

    public static native long metallum_create_buffer_texture_view(long device, long buffer, long pixelFormat, long width, long height, int resourceOptions);

    public static native long metallum_create_sampler(long device, long addressModeU, long addressModeV, long minFilter, long magFilter, long mipFilter, int maxAnisotropy, float lodMaxClamp, long compareFunction, int normalizedCoordinates);

    public static native long metallum_create_sampler_v2(long device, long addressModeU, long addressModeV, long minFilter, long magFilter, long mipFilter, int maxAnisotropy, float lodMaxClamp, long compareFunction);

    public static native long metallum_create_shader_function(long library, String name);

    public static native long metallum_create_fence(long device);

    public static native long metallum_create_semaphore(int initialValue);

    public static native void metallum_semaphore_wait(long semaphore, long timeoutMs);

    public static native void metallum_set_transfer_fence(long encoder, long fence);

    public static native void metallum_set_deferred_depth_store(int enabled);

    public static native void metallum_residency_set_enable(long device, int enabled);

    public static native void metallum_residency_set_stats(long device, int enabled);

    public static native void metallum_release_object(long handle);

    /** A {@code null}/zero Metal handle. Used uniformly by the render layer. */
    public static boolean isNull(long handle) {
        return handle == 0L;
    }

    public static native int metallum_encode_texture_copy(long device, long src, long dst, long commandBuffer, int sliceCount, long commandQueue);

    // ----- pso archive -----------------------------------------------------

    public static native long metallum_pso_archive_open(long device, String path);

    public static native void metallum_pso_archive_flush(long archive);

    // ----- timing ----------------------------------------------------------

    public static native void metallum_set_gpu_encoder_timing_enabled(long device, int enabled);

    public static native int metallum_gpu_encoder_timing_count(long buffer);

    public static native int metallum_gpu_encoder_timing_kind(long buffer, int index);

    public static native double metallum_gpu_encoder_timing_milliseconds(long buffer, int index);

    public static native String metallum_gpu_encoder_timing_copy_label(long buffer, int index);

    public static native void metallum_gpu_encoder_timing_reset(long buffer);

    // ----- MetalFX ---------------------------------------------------------

    public static native int metallum_metalfx_supports_spatial(long device);
    public static native int metallum_metalfx_supports_temporal(long device);
    public static native int metallum_metalfx_supports_frame_generation(long device);
    public static native int metallum_metalfx_supports_motion_v2(long device);
    public static native int metallum_metalfx_supports_cutout_reactive(long device);
    public static native int metallum_metalfx_supports_hand_overlay(long device);

    public static native void metallum_metalfx_shutdown();
    public static native void metallum_metalfx_release_scalers();
    public static native void metallum_metalfx_stop_frame_generation();

    public static native int metallum_metalfx_encode(long device, long commandBuffer, long color, long depth, long motion, long reactive, long depthReactive,
            long output, long historyColor, long historyDepth, long exposure, long motionScalars,
            float jitterX, float jitterY, int width, int height, int outWidth, int outHeight, int reset);

    public static native int metallum_metalfx_mark_transparency(long device, long commandBuffer, long color, long depth, long reactive, long transparentMask, long depthReactive, long motion, int width, int height);

    public static native void metallum_metalfx_set_reactive_tuning(float a, float b, float c, float d, float e, float f, float g);

    // ----- iOS surface discovery ------------------------------------------

    public static native long metallum_ios_find_surface_view();

    public static native long metallum_ios_get_view_metal_layer(long view, long device, double scale);

    // ----- loading ---------------------------------------------------------

    /** Loads the dylib exactly once. Safe to call repeatedly. */
    public static synchronized void load() {
        if (loaded || loadFailed) {
            return;
        }
        if (!isMacOS() && !isIOS()) {
            loadFailed = true;
            return;
        }
        try {
            String path = extractLibrary();
            if (path == null) {
                // On iOS a signed framework from the host launcher may already
                // be on java.library.path; try the plain library name.
                try {
                    System.loadLibrary("metallum");
                    loaded = true;
                    return;
                } catch (UnsatisfiedLinkError ignored) {
                    throw new IOException("No metallum native available");
                }
            }
            System.load(path);
            loaded = true;
            Metallum.LOGGER.info("Loaded libmetallum from {}", path);
        } catch (Throwable t) {
            loadFailed = true;
            Metallum.LOGGER.warn("Could not load libmetallum: {}", t.toString());
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static boolean isIOS() {
        String osName = System.getProperty("os.name", "");
        if (osName.toLowerCase().contains("ios")) {
            return true;
        }
        if (System.getProperty("pojav.launcher") != null || System.getProperty("org.pojavlauncher") != null) {
            return true;
        }
        String tmp = System.getProperty("java.io.tmpdir", "");
        String home = System.getProperty("user.home", "");
        if (tmp.contains("/var/mobile/") || tmp.contains("/var/containers/")
                || home.contains("/var/mobile/") || home.contains("/var/containers/")) {
            return true;
        }
        return osName.toLowerCase().contains("darwin")
                && System.getProperty("os.arch", "").toLowerCase().contains("aarch64")
                && !osName.toLowerCase().contains("mac");
    }

    public static boolean isMacOS() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("mac") || os.toLowerCase().contains("darwin");
    }

    private static String extractLibrary() throws IOException {
        String resourcePath = isIOS() ? IOS_RESOURCE_PATH : MACOS_RESOURCE_PATH;
        InputStream stream = MetalNativeBridge.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            return null;
        }
        Path out = null;
        IOException last = null;
        String[] dirProps = isIOS()
                ? new String[]{"pojav.launcher.home", "POJAV_HOME", "user.home", "java.io.tmpdir"}
                : new String[]{"java.io.tmpdir", "user.home"};
        try {
            for (String prop : dirProps) {
                String dir = System.getProperty(prop);
                if (dir == null || dir.trim().isEmpty()) {
                    continue;
                }
                Path dirPath = java.nio.file.Paths.get(dir);
                if (!Files.isDirectory(dirPath)) {
                    continue;
                }
                try {
                    out = dirPath.resolve("libmetallum_metallum.dylib");
                    Files.copy(stream, out, StandardCopyOption.REPLACE_EXISTING);
                    break;
                } catch (IOException e) {
                    last = e;
                    out = null;
                } finally {
                    // stream may need reopening for next attempt
                }
                // reopen stream for next loop iteration if copy failed
                if (out == null) {
                    try { stream.close(); } catch (IOException ignored) {}
                    stream = MetalNativeBridge.class.getResourceAsStream(resourcePath);
                    if (stream == null) {
                        break;
                    }
                }
            }
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) {}
            }
        }
        if (out == null) {
            if (last != null) {
                throw last;
            }
            throw new IOException("No writable directory for libmetallum extraction");
        }
        out.toFile().deleteOnExit();
        return out.toString();
    }
}
