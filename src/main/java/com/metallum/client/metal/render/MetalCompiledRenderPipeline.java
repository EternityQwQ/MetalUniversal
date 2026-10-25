package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import net.irisshaders.iris.Iris;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline, AutoCloseable {
    static final int MAX_METAL_VERTEX_SLOTS = 31;

    enum ResourceKind {
        UNIFORM_BUFFER,
        STORAGE_BUFFER,
        SAMPLED_IMAGE,
        STORAGE_IMAGE,
        TEXEL_BUFFER
    }

    static final int STAGE_VERTEX = 1;
    static final int STAGE_FRAGMENT = 2;
    static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;

    /**
     * A pipeline resource and the Metal argument-table indices it occupies.
     *
     * <p>Vanilla pipelines use one shared {@code bindingIndex} for both stages.
     * Shaderpack pipelines may use different per-stage indices for the same
     * sampled resource (each stage compacts its active sampled images onto
     * {@code 0..n-1} independently), so the fragment index is tracked
     * separately. {@link #descriptorMaskIndex()} returns the highest of the two
     * for dirty-mask bookkeeping; {@link #bindingIndexForStage(int)} selects the
     * index to pass to the native setter for one stage.
     */
    record ResourceBinding(ResourceKind kind, String name, int bindingIndex, int stageMask,
                           @Nullable GpuFormat texelBufferFormat, int fragmentBindingIndex) {
        ResourceBinding(
                ResourceKind kind,
                String name,
                int bindingIndex,
                int stageMask,
                @Nullable GpuFormat texelBufferFormat
        ) {
            this(kind, name, bindingIndex, stageMask, texelBufferFormat, bindingIndex);
        }

        int bindingIndexForStage(int stageBit) {
            return stageBit == STAGE_FRAGMENT ? fragmentBindingIndex : bindingIndex;
        }

        int descriptorMaskIndex() {
            return Math.max(bindingIndex, fragmentBindingIndex);
        }
    }

    private final List<ResourceBinding> resources;
    private final Map<String, ResourceBinding> resourcesByName;
    private final long allResourceMask;
    private final int firstAvailableVertexBufferSlot;
    private final List<MetalCrossShaderCompiler.GenericVertexInput> genericVertexInputs;
    private final int genericVertexBufferSlot;
    private final MTLCullMode cullMode;
    private final MTLTriangleFillMode fillMode;
    private final float depthBiasScaleFactor;
    private final float depthBiasConstant;
    private final MTLPrimitiveType topology;
    private final int vertexBufferCount;

    private final MemorySegment depthStencilState;
    private final @Nullable MemorySegment reverseDepthStencilState;
    private final boolean hasDepthStencilState;
    private final MTLPixelFormat[] colorFormats;
    private final Map<PipelineSignature, MemorySegment> pipelineStates;
    private final MemorySegment withoutDepthPipeline;

    private record PipelineSignature(
            List<MTLPixelFormat> colorFormats,
            MTLPixelFormat depthFormat,
            MTLPixelFormat stencilFormat,
            int sampleCount
    ) {
    }

    private record DepthStencilFormats(MTLPixelFormat depthFormat, MTLPixelFormat stencilFormat) {
    }

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources,
            final List<MetalCrossShaderCompiler.GenericVertexInput> genericVertexInputs
    ) {
        this.resources = resources;
        this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));
        this.genericVertexInputs = List.copyOf(genericVertexInputs);

        int maxBindingIndex = -1;
        long resourceMask = 0L;
        for (ResourceBinding binding : resources) {
            maxBindingIndex = Math.max(maxBindingIndex, binding.descriptorMaskIndex());
            resourceMask |= 1L << binding.descriptorMaskIndex();
        }
        if (maxBindingIndex >= Long.SIZE) {
            throw new IllegalStateException("Pipeline " + info.getLocation() + " has binding index " + maxBindingIndex + ", limit is " + (Long.SIZE - 1));
        }
        this.allResourceMask = resourceMask;

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(resources);
        this.cullMode = info.isCull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.getPolygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(info.getPrimitiveTopology());
        this.vertexBufferCount = info.getVertexFormatBindings().length;
        this.genericVertexBufferSlot = resolveGenericVertexBufferSlot(
                this.firstAvailableVertexBufferSlot,
                this.vertexBufferCount,
                !this.genericVertexInputs.isEmpty()
        );
        boolean[] genericLocations = new boolean[MAX_METAL_VERTEX_SLOTS];
        for (MetalCrossShaderCompiler.GenericVertexInput input : this.genericVertexInputs) {
            if (input.location() >= MAX_METAL_VERTEX_SLOTS) {
                throw new IllegalStateException(
                        "Pipeline " + info.getLocation() + " needs generic vertex attribute location "
                                + input.location() + ", limit is " + (MAX_METAL_VERTEX_SLOTS - 1)
                );
            }
            if (genericLocations[input.location()]) {
                throw new IllegalStateException(
                        "Pipeline " + info.getLocation() + " has duplicate generic vertex attribute location "
                                + input.location()
                );
            }
            genericLocations[input.location()] = true;
        }

        MTLCompareFunction depthCompareOp;
        int depthWrite;
        var depthStencilState = info.getDepthStencilState();
        this.hasDepthStencilState = depthStencilState != null;
        if (depthStencilState == null) {
            depthCompareOp = MTLCompareFunction.Always;
            depthWrite = 0;
            this.depthBiasScaleFactor = 0.0f;
            this.depthBiasConstant = 0.0f;
        } else {
            depthCompareOp = MTLCompareFunction.from(depthStencilState.depthTest());
            depthWrite = depthStencilState.writeDepth() ? 1 : 0;
            this.depthBiasScaleFactor = depthStencilState.depthBiasScaleFactor();
            this.depthBiasConstant = depthStencilState.depthBiasConstant();
        }

        this.depthStencilState = MetalNativeBridge.MTLDevice_makeDepthStencilState(
                device.metalDeviceHandle(),
                depthCompareOp,
                depthWrite
        );
        // Iris "undoes" Minecraft's reverse-z convention for shaderpacks in the
        // GL backend (UndoReverseZThree) by flipping the compare op per draw.
        // Metal bakes the compare op into a depth-stencil state, so keep a
        // second, flipped state and select it while an Iris pack is in use.
        this.reverseDepthStencilState = this.hasDepthStencilState
                ? MetalNativeBridge.MTLDevice_makeDepthStencilState(
                        device.metalDeviceHandle(),
                        flipDepthCompare(depthCompareOp),
                        depthWrite
                )
                : null;

        ColorTargetState[] colorTargets = info.getColorTargetStates();
        validateColorTargets(info.getLocation().toString(), colorTargets);
        this.colorFormats = colorFormats(colorTargets);

        MemorySegment vertexFunction = device.getOrCompileFunction(vertexMsl, vertexEntryPoint);
        MemorySegment fragmentFunction = device.getOrCompileFunction(fragmentMsl, fragmentEntryPoint);

        Map<PipelineSignature, MemorySegment> states = new HashMap<>();
        try (MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(
                info,
                this.firstAvailableVertexBufferSlot,
                this.genericVertexInputs,
                this.genericVertexBufferSlot
        )) {
            createPipelineVariants(
                    states, device, colorTargets, vertexFunction, fragmentFunction, vertexDescriptor, this.colorFormats
            );
        }
        this.pipelineStates = Map.copyOf(states);
        this.withoutDepthPipeline = pipelineFor(
                this.pipelineStates, this.colorFormats, MTLPixelFormat.Invalid, MTLPixelFormat.Invalid
        );
    }

    /**
     * Constructor overload for shaderpack (Iris light-shader) pipelines compiled
     * outside the vanilla {@link RenderPipeline} code path. Instead of deriving
     * pipeline state from a {@code RenderPipeline}, the caller supplies each
     * scalar directly. This mirrors {@link #MetalCompiledRenderPipeline(MetalDevice, RenderPipeline, String, String, String, String, List)}
     * field-for-field; only the source of each value differs.
     *
     * @param location              logical name used in error messages (replaces {@code info.getLocation()})
     * @param cull                  back-face cull enabled (replaces {@code info.isCull()})
     * @param polygonMode           fill / wireframe (replaces {@code info.getPolygonMode()})
     * @param primitiveTopology      primitive topology (replaces {@code info.getPrimitiveTopology()})
     * @param vertexFormatBindings  vertex format bindings (replaces {@code info.getVertexFormatBindings()})
     * @param depthStencilState     depth/stencil state, nullable (replaces {@code info.getDepthStencilState()})
     * @param colorTarget           color target state, nullable (replaces {@code info.getColorTargetState()})
     */
    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final String location,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources,
            final boolean cull,
            final PolygonMode polygonMode,
            final PrimitiveTopology primitiveTopology,
            final VertexFormat[] vertexFormatBindings,
            final DepthStencilState depthStencilState,
            final ColorTargetState colorTarget
    ) {
        this(
                device, location, vertexMsl, fragmentMsl, vertexEntryPoint, fragmentEntryPoint,
                resources, cull, polygonMode, primitiveTopology, vertexFormatBindings,
                depthStencilState, new ColorTargetState[]{colorTarget}, List.of()
        );
    }

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final String location,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources,
            final boolean cull,
            final PolygonMode polygonMode,
            final PrimitiveTopology primitiveTopology,
            final VertexFormat[] vertexFormatBindings,
            final DepthStencilState depthStencilState,
            final ColorTargetState[] colorTargets
    ) {
        this(
                device, location, vertexMsl, fragmentMsl, vertexEntryPoint, fragmentEntryPoint,
                resources, cull, polygonMode, primitiveTopology, vertexFormatBindings,
                depthStencilState, colorTargets, List.of()
        );
    }

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final String location,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources,
            final boolean cull,
            final PolygonMode polygonMode,
            final PrimitiveTopology primitiveTopology,
            final VertexFormat[] vertexFormatBindings,
            final DepthStencilState depthStencilState,
            final ColorTargetState[] colorTargets,
            final List<MetalCrossShaderCompiler.GenericVertexInput> genericVertexInputs
    ) {
        this.resources = resources;
        this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));

        int maxBindingIndex = -1;
        long resourceMask = 0L;
        for (ResourceBinding binding : resources) {
            maxBindingIndex = Math.max(maxBindingIndex, binding.descriptorMaskIndex());
            resourceMask |= 1L << binding.descriptorMaskIndex();
        }
        if (maxBindingIndex >= Long.SIZE) {
            throw new IllegalStateException("Pipeline " + location + " has binding index " + maxBindingIndex + ", limit is " + (Long.SIZE - 1));
        }
        this.allResourceMask = resourceMask;

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(resources);
        this.vertexBufferCount = vertexFormatBindings.length;
        this.genericVertexInputs = List.copyOf(genericVertexInputs);
        this.genericVertexBufferSlot = resolveGenericVertexBufferSlot(
                this.firstAvailableVertexBufferSlot,
                this.vertexBufferCount,
                !this.genericVertexInputs.isEmpty()
        );
        this.cullMode = cull ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = polygonMode == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(primitiveTopology);
        boolean[] genericLocations = new boolean[MAX_METAL_VERTEX_SLOTS];
        for (MetalCrossShaderCompiler.GenericVertexInput input : this.genericVertexInputs) {
            if (input.location() >= MAX_METAL_VERTEX_SLOTS) {
                throw new IllegalStateException(
                        "Pipeline " + location + " needs generic vertex attribute location "
                                + input.location() + ", limit is " + (MAX_METAL_VERTEX_SLOTS - 1)
                );
            }
            if (genericLocations[input.location()]) {
                throw new IllegalStateException(
                        "Pipeline " + location + " has duplicate generic vertex attribute location "
                                + input.location()
                );
            }
            genericLocations[input.location()] = true;
        }

        MTLCompareFunction depthCompareOp;
        int depthWrite;
        this.hasDepthStencilState = depthStencilState != null;
        if (depthStencilState == null) {
            depthCompareOp = MTLCompareFunction.Always;
            depthWrite = 0;
            this.depthBiasScaleFactor = 0.0f;
            this.depthBiasConstant = 0.0f;
        } else {
            depthCompareOp = MTLCompareFunction.from(depthStencilState.depthTest());
            depthWrite = depthStencilState.writeDepth() ? 1 : 0;
            this.depthBiasScaleFactor = depthStencilState.depthBiasScaleFactor();
            this.depthBiasConstant = depthStencilState.depthBiasConstant();
        }

        this.depthStencilState = MetalNativeBridge.MTLDevice_makeDepthStencilState(
                device.metalDeviceHandle(),
                depthCompareOp,
                depthWrite
        );
        // Iris "undoes" Minecraft's reverse-z convention for shaderpacks in the
        // GL backend (UndoReverseZThree) by flipping the compare op per draw.
        // Metal bakes the compare op into a depth-stencil state, so keep a
        // second, flipped state and select it while an Iris pack is in use.
        this.reverseDepthStencilState = this.hasDepthStencilState
                ? MetalNativeBridge.MTLDevice_makeDepthStencilState(
                        device.metalDeviceHandle(),
                        flipDepthCompare(depthCompareOp),
                        depthWrite
                )
                : null;

        validateColorTargets(location, colorTargets);
        this.colorFormats = colorFormats(colorTargets);

        MemorySegment vertexFunction = device.getOrCompileFunction(vertexMsl, vertexEntryPoint);
        MemorySegment fragmentFunction = device.getOrCompileFunction(fragmentMsl, fragmentEntryPoint);

        Map<PipelineSignature, MemorySegment> states = new HashMap<>();
        try (MTLVertexDescriptor vertexDescriptor = buildVertexDescriptor(
                vertexFormatBindings,
                this.firstAvailableVertexBufferSlot,
                this.genericVertexInputs,
                this.genericVertexBufferSlot
        )) {
            createPipelineVariants(
                    states, device, colorTargets, vertexFunction, fragmentFunction, vertexDescriptor, this.colorFormats
            );
        }
        this.pipelineStates = Map.copyOf(states);
        this.withoutDepthPipeline = pipelineFor(
                this.pipelineStates, this.colorFormats, MTLPixelFormat.Invalid, MTLPixelFormat.Invalid
        );
    }

    private static void validateColorTargets(final String location, final ColorTargetState[] colorTargets) {
        if (colorTargets.length > ColorTargetState.MAX_COLOR_TARGETS) {
            throw new IllegalArgumentException(
                    "Pipeline " + location + " has " + colorTargets.length
                            + " color targets; supported range is 0.." + ColorTargetState.MAX_COLOR_TARGETS
            );
        }
    }

    private static MTLPixelFormat[] colorFormats(final ColorTargetState[] colorTargets) {
        MTLPixelFormat[] formats = new MTLPixelFormat[colorTargets.length];
        for (int index = 0; index < colorTargets.length; index++) {
            ColorTargetState target = colorTargets[index];
            formats[index] = target == null ? MTLPixelFormat.Invalid : MTLPixelFormat.from(target.format());
        }
        return formats;
    }

    private static List<DepthStencilFormats> supportedDepthStencilFormats() {
        return List.of(
                new DepthStencilFormats(MTLPixelFormat.Invalid, MTLPixelFormat.Invalid),
                new DepthStencilFormats(MTLPixelFormat.Depth16Unorm, MTLPixelFormat.Invalid),
                new DepthStencilFormats(MTLPixelFormat.Depth32Float, MTLPixelFormat.Invalid),
                new DepthStencilFormats(MTLPixelFormat.Depth32Float_Stencil8, MTLPixelFormat.Depth32Float_Stencil8),
                new DepthStencilFormats(MTLPixelFormat.Invalid, MTLPixelFormat.Stencil8)
        );
    }

    private static void createPipelineVariants(
            final Map<PipelineSignature, MemorySegment> states,
            final MetalDevice device,
            final ColorTargetState[] colorTargets,
            final MemorySegment vertexFunction,
            final MemorySegment fragmentFunction,
            final MTLVertexDescriptor vertexDescriptor,
            final MTLPixelFormat[] colorFormats
    ) {
        for (DepthStencilFormats formats : supportedDepthStencilFormats()) {
            MemorySegment pipeline = createPipeline(
                    device, colorTargets, vertexFunction, fragmentFunction, vertexDescriptor,
                    colorFormats, formats.depthFormat(), formats.stencilFormat()
            );
            if (!MetalNativeBridge.isNullHandle(pipeline)) {
                states.put(
                        signature(colorFormats, formats.depthFormat(), formats.stencilFormat()),
                        pipeline
                );
            }
        }
    }

    private static MemorySegment createPipeline(
            final MetalDevice device,
            final ColorTargetState[] colorTargets,
            final MemorySegment vertexFunction,
            final MemorySegment fragmentFunction,
            final MTLVertexDescriptor vertexDescriptor,
            final MTLPixelFormat[] colorFormats,
            final MTLPixelFormat depthFormat,
            final MTLPixelFormat stencilFormat
    ) {
        if (MetalNativeBridge.isNullHandle(vertexFunction) || MetalNativeBridge.isNullHandle(fragmentFunction)) {
            return MemorySegment.NULL;
        }

        try (MTLRenderPipelineDescriptor pipelineDesc = new MTLRenderPipelineDescriptor()) {
            pipelineDesc.setCompiledFunctions(vertexFunction, fragmentFunction);
            pipelineDesc.setVertexDescriptor(vertexDescriptor);
            for (int index = 0; index < colorFormats.length; index++) {
                ColorTargetState colorTarget = colorTargets[index];
                pipelineDesc.setColorAttachmentFormat(index, colorFormats[index]);
                if (colorTarget == null) {
                    pipelineDesc.disableBlending(index, MTLColorWriteMask.None.value);
                    continue;
                }

                Optional<BlendFunction> blendFunction = colorTarget.blendFunction();
                long writeMask = MTLColorWriteMask.from(colorTarget.writeMask());
                if (blendFunction.isPresent()) {
                    var function = blendFunction.get();
                    pipelineDesc.setColorAttachmentBlendState(
                            index,
                            true,
                            MTLBlendFactor.from(function.color().sourceFactor()),
                            MTLBlendFactor.from(function.color().destFactor()),
                            MTLBlendOperation.from(function.color().op()),
                            MTLBlendFactor.from(function.alpha().sourceFactor()),
                            MTLBlendFactor.from(function.alpha().destFactor()),
                            MTLBlendOperation.from(function.alpha().op()),
                            writeMask
                    );
                } else {
                    pipelineDesc.disableBlending(index, writeMask);
                }
            }
            if (depthFormat != MTLPixelFormat.Invalid || stencilFormat != MTLPixelFormat.Invalid) {
                pipelineDesc.setDepthStencilFormats(depthFormat, stencilFormat);
            }
            return MetalNativeBridge.metallum_MTLDevice_makeRenderPipelineState(
                    device.metalDeviceHandle(),
                    pipelineDesc.handle()
            );
        }
    }

    private static PipelineSignature signature(
            final MTLPixelFormat[] colorFormats,
            final MTLPixelFormat depthFormat,
            final MTLPixelFormat stencilFormat
    ) {
        return new PipelineSignature(
                List.copyOf(Arrays.asList(colorFormats)),
                depthFormat,
                stencilFormat,
                1
        );
    }

    private static MemorySegment pipelineFor(
            final Map<PipelineSignature, MemorySegment> states,
            final MTLPixelFormat[] colorFormats,
            final MTLPixelFormat depthFormat,
            final MTLPixelFormat stencilFormat
    ) {
        return states.getOrDefault(
                signature(colorFormats, depthFormat, stencilFormat),
                MemorySegment.NULL
        );
    }

    @Override
    public boolean isValid() {
        return this.pipelineStates.values().stream().anyMatch(state -> !MetalNativeBridge.isNullHandle(state));
    }

    List<ResourceBinding> resources() {
        return this.resources;
    }

    long allResourceMask() {
        return this.allResourceMask;
    }

    @Nullable
    ResourceBinding resource(final String name) {
        return this.resourcesByName.get(name);
    }

    int firstAvailableVertexBufferSlot() {
        return this.firstAvailableVertexBufferSlot;
    }

    float depthBiasScaleFactor() {
        return this.depthBiasScaleFactor;
    }

    float depthBiasConstant() {
        return this.depthBiasConstant;
    }

    MemorySegment getDepthStencilState() {
        if (!this.hasDepthStencilState
                || this.reverseDepthStencilState == null
                || !Iris.isPackInUseQuick()) {
            return this.depthStencilState;
        }
        return this.reverseDepthStencilState;
    }

    /**
     * Mirrors Iris's {@code UndoReverseZThree}: while a shaderpack is in use the
     * engine's reverse-z compare ops must be flipped back to standard z
     * (LESS_THAN &harr; GREATER_THAN, *_OR_EQUAL likewise; the rest unchanged).
     */
    private static MTLCompareFunction flipDepthCompare(final MTLCompareFunction op) {
        return switch (op) {
            case Less -> MTLCompareFunction.Greater;
            case LessEqual -> MTLCompareFunction.GreaterEqual;
            case Greater -> MTLCompareFunction.Less;
            case GreaterEqual -> MTLCompareFunction.LessEqual;
            case Never, Equal, NotEqual, Always -> op;
        };
    }

    MemorySegment getNativePipeline(final MTLPixelFormat depthFormat, final MTLPixelFormat stencilFormat) {
        MemorySegment pipeline = pipelineFor(this.pipelineStates, this.colorFormats, depthFormat, stencilFormat);
        if (MetalNativeBridge.isNullHandle(pipeline)) {
            throw new IllegalStateException(
                    "No cached Metal pipeline for attachment signature "
                            + signature(this.colorFormats, depthFormat, stencilFormat)
            );
        }
        return pipeline;
    }

    boolean hasDepthStencilState() {
        return this.hasDepthStencilState;
    }

    MTLPixelFormat[] colorAttachmentFormats() {
        return this.colorFormats.clone();
    }

    MTLCullMode cullMode() {
        return this.cullMode;
    }

    MTLTriangleFillMode fillMode() {
        return this.fillMode;
    }

    MTLPrimitiveType topology() {
        return this.topology;
    }

    int vertexBufferCount() {
        return this.vertexBufferCount;
    }

    int genericVertexBufferSlot() {
        return this.genericVertexBufferSlot;
    }

    List<MetalCrossShaderCompiler.GenericVertexInput> genericVertexInputs() {
        return this.genericVertexInputs;
    }

    static int resolveGenericVertexBufferSlot(
            final int firstAvailableSlot,
            final int physicalBindingCount,
            final boolean required
    ) {
        if (!required) {
            return -1;
        }
        long slot = (long) firstAvailableSlot + physicalBindingCount;
        if (firstAvailableSlot < 0 || physicalBindingCount < 0 || slot >= MAX_METAL_VERTEX_SLOTS) {
            throw new IllegalStateException(
                    "Generic vertex buffer slot " + slot + " is outside Metal's 0.."
                            + (MAX_METAL_VERTEX_SLOTS - 1) + " range"
            );
        }
        return (int) slot;
    }

    private static MTLVertexDescriptor buildVertexDescriptor(
            final RenderPipeline pipeline,
            final int firstMetalVertexBufferSlot,
            final List<MetalCrossShaderCompiler.GenericVertexInput> genericVertexInputs,
            final int genericVertexBufferSlot
    ) {
        MTLVertexDescriptor vertexDesc = buildVertexDescriptor(
                pipeline.getVertexFormatBindings(), firstMetalVertexBufferSlot
        );
        return addGenericVertexInputs(
                vertexDesc,
                pipeline.getLocation().toString(),
                pipeline.getVertexFormatBindings(),
                genericVertexInputs,
                genericVertexBufferSlot
        );
    }

    private static MTLVertexDescriptor buildVertexDescriptor(
            final VertexFormat[] bindings,
            final int firstMetalVertexBufferSlot,
            final List<MetalCrossShaderCompiler.GenericVertexInput> genericVertexInputs,
            final int genericVertexBufferSlot
    ) {
        MTLVertexDescriptor vertexDesc = buildVertexDescriptor(bindings, firstMetalVertexBufferSlot);
        return addGenericVertexInputs(
                vertexDesc,
                "shaderpack",
                bindings,
                genericVertexInputs,
                genericVertexBufferSlot
        );
    }

    private static MTLVertexDescriptor addGenericVertexInputs(
            final MTLVertexDescriptor vertexDesc,
            final String pipelineName,
            final VertexFormat[] bindings,
            final List<MetalCrossShaderCompiler.GenericVertexInput> genericVertexInputs,
            final int genericVertexBufferSlot
    ) {
        int physicalAttributeCount = 0;
        for (VertexFormat binding : bindings) {
            if (binding != null) {
                physicalAttributeCount += binding.getElements().size();
            }
        }
        if (!genericVertexInputs.isEmpty()) {
            vertexDesc.setLayout(
                    genericVertexBufferSlot,
                    MetalCrossShaderCompiler.GENERIC_VERTEX_DEFAULT_VALUES_SIZE,
                    MTLVertexStepFunction.Constant,
                    0
            );
            for (MetalCrossShaderCompiler.GenericVertexInput input : genericVertexInputs) {
                if (input.location() < physicalAttributeCount) {
                    throw new IllegalStateException(
                        "Generic vertex attribute location " + input.location()
                                    + " overlaps the physical vertex layout of " + pipelineName
                    );
                }
                vertexDesc.setAttribute(
                        input.location(),
                        input.metalFormat().value,
                        input.defaultValueOffset(),
                        genericVertexBufferSlot
                );
            }
        }
        return vertexDesc;
    }

    private static MTLVertexDescriptor buildVertexDescriptor(
            final VertexFormat[] bindings,
            final int firstMetalVertexBufferSlot
    ) {
        MTLVertexDescriptor vertexDesc = new MTLVertexDescriptor();
        long attrIndex = 0;

        for (int i = 0; i < bindings.length; i++) {
            VertexFormat binding = bindings[i];
            if (binding == null || binding.getElements().isEmpty()) {
                continue;
            }

            int metalSlot = firstMetalVertexBufferSlot + i;

            long stride = binding.getVertexSize();
            long stepRate = binding.getStepRate();
            MTLVertexStepFunction stepFunction = stepRate > 0 ? MTLVertexStepFunction.PerInstance : MTLVertexStepFunction.PerVertex;
            vertexDesc.setLayout(metalSlot, stride, stepFunction, stepRate > 0 ? stepRate : 1);

            for (VertexFormatElement element : binding.getElements()) {
                MTLVertexFormat format = MTLVertexFormat.from(element.format());
                if (format == MTLVertexFormat.Invalid) {
                    throw new IllegalStateException("Unsupported vertex attribute format: " + element.format());
                }
                vertexDesc.setAttribute(attrIndex, format.value, element.offset(), metalSlot);
                attrIndex++;
            }
        }

        return vertexDesc;
    }

    private static int firstAvailableVertexBufferSlot(final List<ResourceBinding> resources) {
        int maxVertexBufferBinding = -1;
        for (ResourceBinding resource : resources) {
            if ((resource.kind() == ResourceKind.UNIFORM_BUFFER
                    || resource.kind() == ResourceKind.STORAGE_BUFFER)
                    && (resource.stageMask() & STAGE_VERTEX) != 0) {
                maxVertexBufferBinding = Math.max(maxVertexBufferBinding, resource.bindingIndex());
            }
        }
        return maxVertexBufferBinding + 1;
    }

    @Override
    public void close() {
        Set<MemorySegment> uniqueStates = new HashSet<>(this.pipelineStates.values());
        for (MemorySegment state : uniqueStates) {
            if (!MetalNativeBridge.isNullHandle(state)) {
                MetalNativeBridge.metallum_release_object(state);
            }
        }
    }
}
