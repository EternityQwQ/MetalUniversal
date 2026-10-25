package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.minecraft.SharedConstants;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.lwjgl.vulkan.VkDrawIndirectCommand;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
final class MetalRenderPass implements RenderPassBackend {
    static final boolean VALIDATION = SharedConstants.IS_RUNNING_IN_IDE;
    static final int MAX_VERTEX_BUFFERS = RenderPass.MAX_VERTEX_BUFFERS;
    /**
     * Prefix Iris' vanilla-core transformer adds to the engine's built-in UBO
     * names in patched programs (eg {@code Fog} becomes {@code iris_Fog}).
     * The engine still binds the values under the plain names, so descriptor
     * lookups fall back to them.
     */
    private static final String IRIS_UNIFORM_PREFIX = "iris_";
    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    @Nullable
    private final String label;
    private final GpuTextureView[] colorTextures;
    @Nullable
    private final GpuTextureView depthTexture;
    private final RenderPass.RenderArea renderArea;
    @Nullable
    private Vector4fc[] clearColors;
    private boolean clearDepthEnabled;
    private final double clearDepthValue;
    private final ScissorState scissorState = new ScissorState();
    private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    private final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
    private final HashMap<Integer, GpuBufferSlice> storageBuffers = new HashMap<>();
    private final HashMap<String, TextureViewAndSampler> samplers = new HashMap<>();
    private final HashMap<String, GpuTextureView> storageImages = new HashMap<>();
    private long dirtyDescriptorMask;
    @Nullable
    private MetalCompiledRenderPipeline compiledPipeline;
    /**
     * Per-draw replacement for the shaderpack uniform block, materialized by
     * {@link IrisMetalWorldBridge#materializePerDrawUniforms} from the engine's
     * {@code DynamicTransforms}/{@code Projection}; {@code null} keeps the
     * shared per-frame slice.
     */
    @Nullable
    private GpuBufferSlice perDrawUniformOverride;
    @Nullable
    private GpuBuffer indexBuffer;
    private MTLIndexType indexType = MTLIndexType.UInt16;
    private int pushedDebugGroups = 0;
    private boolean scissorDirty = true;
    private boolean vertexBuffersDirty = true;
    private boolean pipelineDirty = true;

    MetalRenderPass(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView[] colorTextures,
            @Nullable final GpuTextureView depthTexture,
            final RenderPass.RenderArea renderArea,
            @Nullable final Vector4fc[] clearColors,
            final boolean clearDepthEnabled,
            final double clearDepthValue
    ) {
        this.device = device;
        this.commandEncoder = encoder;
        this.label = device.useLabels() ? label.get() : null;
        this.colorTextures = colorTextures.clone();
        this.depthTexture = depthTexture;
        this.renderArea = renderArea;
        this.clearColors = clearColors == null ? null : clearColors.clone();
        this.clearDepthEnabled = clearDepthEnabled;
        this.clearDepthValue = clearDepthValue;
    }

    @Override
    public void pushDebugGroup(final @NonNull Supplier<String> label) {
        pushedDebugGroups++;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().pushDebugGroup(label.get());
        }
    }

    @Override
    public void popDebugGroup() {
        if (pushedDebugGroups == 0) {
            throw new IllegalStateException("Can't pop more debug groups than was pushed!");
        }
        pushedDebugGroups--;
        if (device.useLabels()) {
            commandEncoder.commandBuffer().popDebugGroup();
        }
    }

    @Override
    public void setPipeline(final @NonNull RenderPipeline pipeline) {
        MetalCompiledRenderPipeline compiled = device.getOrCompilePipeline(pipeline);
        validateAttachmentSignature(compiled, pipeline.getLocation().toString());
        if (this.compiledPipeline != compiled) {
            this.compiledPipeline = compiled;
            vertexBuffersDirty = true;
            pipelineDirty = true;
        }
    }

    /**
     * Swap in a pre-compiled shaderpack Metal render pipeline (bypassing the
     * {@link RenderPipeline}&rarr;{@code getOrCompilePipeline} path) and mark
     * all dependent state dirty so the next {@link #bindDrawState} re-pushes
     * the pipeline state, vertex buffers, and every descriptor against the new
     * pipeline's resource table.
     *
     * <p>Used by {@link MetalIrisProgram#iris$setupState} to install an Iris
     * shaderpack program's Metal pipeline state object (previously compiled
     * and cached by {@code MetalCrossShaderCompiler.compileShaderpackPipeline})
     * onto the active render pass. Unlike {@link #setPipeline}, this accepts a
     * {@link MetalCompiledRenderPipeline} directly because shaderpack
     * pipelines are constructed outside the vanilla {@code RenderPipeline}
     * code path and have no blaze3d {@code RenderPipeline} to key on.
     *
     * <p><b>Always marks dirty.</b> Unlike {@code setPipeline}, the comparison
     * guard is intentionally skipped: when a shaderpack program sets up state
     * the dispatch contract is "this pass now draws with my pipeline", even if
     * the same pipeline object was already installed (e.g. a prior
     * iris$clearState may have left stale descriptor bindings that must be
     * re-pushed against the shaderpack resource table).
     *
     * @param pipeline the shaderpack Metal render pipeline to install.
     */
    void setCompiledPipeline(final MetalCompiledRenderPipeline pipeline) {
        validateAttachmentSignature(pipeline, "Iris shaderpack pipeline");
        this.compiledPipeline = pipeline;
        this.pipelineDirty = true;
        this.vertexBuffersDirty = true;
    }

    private void validateAttachmentSignature(
            final MetalCompiledRenderPipeline pipeline,
            final String pipelineName
    ) {
        MTLPixelFormat[] expected = pipeline.colorAttachmentFormats();
        MTLPixelFormat[] actual = colorAttachmentFormats();
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalArgumentException(
                    "Metal pipeline/render-pass color attachment signature mismatch for " + pipelineName
                            + ": pipeline=" + Arrays.toString(expected)
                            + ", renderPass=" + Arrays.toString(actual)
            );
        }
    }

    @Override
    public void bindTexture(final @NonNull String name, @Nullable final GpuTextureView textureView, @Nullable final GpuSampler sampler) {
        if (textureView != null && sampler != null) {
            samplers.put(name, new TextureViewAndSampler(textureView, sampler));
            commandEncoder.flushPendingClear((MetalGpuTexture) textureView.texture());
            markDescriptorDirty(name);
        } else if (textureView == null && sampler == null) {
            samplers.remove(name);
        } else {
            throw new IllegalArgumentException();
        }
    }

    void bindStorageImage(final String name, final GpuTextureView textureView) {
        if (!(textureView instanceof MetalGpuTextureView metalView)
                || !(metalView.texture() instanceof MetalGpuTexture texture)) {
            throw new IllegalArgumentException("Storage image " + name + " is not backed by Metal");
        }
        storageImages.put(name, textureView);
        commandEncoder.flushPendingClear(texture);
        texture.markContentsDirty();
        markDescriptorDirty(name);
    }

    void bindStorageBuffer(final int binding, final GpuBufferSlice slice) {
        if (binding < 0 || !(slice.buffer() instanceof MetalGpuBuffer)) {
            throw new IllegalArgumentException("Invalid Metal storage buffer binding " + binding);
        }
        storageBuffers.put(binding, slice);
        if (compiledPipeline != null) {
            for (MetalCompiledRenderPipeline.ResourceBinding resource : compiledPipeline.resources()) {
                if (resource.kind() == MetalCompiledRenderPipeline.ResourceKind.STORAGE_BUFFER
                        && MetalCrossShaderCompiler.storageBufferLogicalBinding(resource.name()) == binding) {
                    dirtyDescriptorMask |= 1L << resource.descriptorMaskIndex();
                }
            }
        }
    }

    @Override
    public void setUniform(final @NonNull String name, final GpuBuffer value) {
        setUniform(name, value.slice());
    }

    @Override
    public void setUniform(final @NonNull String name, final @NonNull GpuBufferSlice value) {
        uniforms.put(name, value);
        markDescriptorDirty(name);
    }

    @Override
    public void enableScissor(final int x, final int y, final int width, final int height) {
        if (scissorState.enabled()
                && scissorState.x() == x
                && scissorState.y() == y
                && scissorState.width() == width
                && scissorState.height() == height) {
            return;
        }
        scissorState.enable(x, y, width, height);
        scissorDirty = true;
    }

    @Override
    public void disableScissor() {
        if (!scissorState.enabled()) {
            return;
        }
        scissorState.disable();
        scissorDirty = true;
    }

    @Override
    public void setVertexBuffer(final int slot, @Nullable final GpuBufferSlice vertexBuffer) {
        if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
            throw new IllegalArgumentException("Unsupported Metal vertex buffer slot: " + slot);
        }

        if (!sameSlice(vertexBuffers[slot], vertexBuffer)) {
            vertexBuffers[slot] = vertexBuffer;
            vertexBuffersDirty = true;
        }
    }

    @Override
    public void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final @NonNull IndexType indexType) {
        setIndexBuffer(indexBuffer, MTLIndexType.from(indexType));
    }

    private void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final MTLIndexType indexType) {
        if (this.indexBuffer != indexBuffer || this.indexType != indexType) {
            this.indexBuffer = indexBuffer;
            this.indexType = indexType;
        }
    }

    @Override
    public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        MetalPerformanceProbe.recordDraw(1);
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);
        drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, vertexOffset, instanceCount, indexType, firstInstance);
    }

    @Override
    public void multiDrawIndexed(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        for (int i = 0; i < drawCount; i++) {
            int firstIndex = drawParameters.get(i * 3);
            int indexCount = drawParameters.get(i * 3 + 1);
            int baseVertex = drawParameters.get(i * 3 + 2);
            if (indexCount > 0) {
                drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, baseVertex, instanceCount, indexType, firstInstance);
            }
        }
    }

    @Override
    public void multiDrawIndexed(@NonNull PointerBuffer firstIndexOffsets, @NonNull IntBuffer indexCounts, @NonNull IntBuffer vertexOffsets, int drawCount) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan multiDrawIndexed");
        }

        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        MetalNativeBridge.MTLRenderCommandEncoder_multiDrawIndexed(
                enc.handle(),
                primitiveType.value,
                indexType.value,
                nativeIndexBuffer.nativeHandle(),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(firstIndexOffsets)),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(indexCounts)),
                MemorySegment.ofAddress(org.lwjgl.system.MemoryUtil.memAddress(vertexOffsets)),
                drawCount,
                1L,
                0L
        );
    }

    @Override
    public void drawIndexedIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        MetalPerformanceProbe.recordDraw(drawCount);
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }

        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        enc.drawIndexedPrimitivesIndirect(
                primitiveType,
                indexType,
                nativeIndexBuffer.nativeHandle(),
                ((MetalGpuBuffer) commands.buffer()).nativeHandle(),
                commands.offset(),
                drawCount,
                VkDrawIndexedIndirectCommand.SIZEOF
        );
    }

    @Override
    public <T> void drawMultipleIndexed(
            final Collection<RenderPass.Draw<T>> draws,
            @Nullable final GpuBuffer defaultIndexBuffer,
            @Nullable final IndexType defaultIndexType,
            final @NonNull Collection<String> dynamicUniforms,
            final @NonNull T uniformArgument
    ) {
        MetalPerformanceProbe.recordDraw(draws.size());
        IndexType fallbackIndexType = defaultIndexType == null ? IndexType.SHORT : defaultIndexType;
        MTLRenderCommandEncoder enc = renderEncoder();

        for (RenderPass.Draw<T> draw : draws) {
            MTLIndexType drawIndexType = MTLIndexType.from(draw.indexType() == null ? fallbackIndexType : draw.indexType());
            GpuBuffer currentIndexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();

            setIndexBuffer(currentIndexBuffer, drawIndexType);
            setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());

            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(uniformArgument, this::setUniform);
            }

            if (scissorDirty || vertexBuffersDirty || dirtyDescriptorMask != 0L || pipelineDirty) {
                bindDrawState(enc);
            }
            MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
            drawIndexedNative(enc, nativeIndexBuffer, draw.firstIndex(), draw.indexCount(), draw.baseVertex(), 1, drawIndexType, 0);
        }
    }

    @Override
    public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);

        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            drawTriangleFan(enc, firstVertex, vertexCount, instanceCount, firstInstance);
        } else {
            enc.drawPrimitives(primitiveType, firstVertex, vertexCount, Math.max(1, instanceCount), firstInstance);
        }
    }

    @Override
    public void multiDraw(@NonNull IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void multiDraw(@NonNull IntBuffer firstVertices, @NonNull IntBuffer vertexCounts, int drawCount) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void drawIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }

        MTLRenderCommandEncoder enc = renderEncoder();
        bindDrawState(enc);

        enc.drawPrimitivesIndirect(
                primitiveType,
                ((MetalGpuBuffer) commands.buffer()).nativeHandle(),
                commands.offset(),
                drawCount,
                VkDrawIndirectCommand.SIZEOF
        );
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            metalPool.setValue(index, device.getTimestampNow());
        }
    }

    MTLPixelFormat[] colorAttachmentFormats() {
        MTLPixelFormat[] formats = new MTLPixelFormat[colorTextures.length];
        for (int index = 0; index < colorTextures.length; index++) {
            formats[index] = colorTextures[index] == null
                    ? MTLPixelFormat.Invalid
                    : ((MetalGpuTexture) colorTextures[index].texture()).mtlPixelFormat();
        }
        return formats;
    }

    MTLPixelFormat depthAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlDepthPixelFormat();
    }

    MTLPixelFormat stencilAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlStencilPixelFormat();
    }

    void materializePendingClear() {
        if (clearColors != null || clearDepthEnabled) {
            renderEncoder();
        }
    }

    private GpuTextureView extentTexture() {
        for (GpuTextureView colorTexture : colorTextures) {
            if (colorTexture != null) {
                return colorTexture;
            }
        }
        if (depthTexture != null) {
            return depthTexture;
        }
        throw new IllegalStateException("Metal render pass has no color or depth attachment");
    }

    private MTLRenderCommandEncoder renderEncoder() {
        MetalGpuTextureView[] colorTextureViews = new MetalGpuTextureView[colorTextures.length];
        int[] clearColorEnabled = new int[colorTextures.length];
        float[] clearColorValues = new float[colorTextures.length * 4];
        for (int index = 0; index < colorTextures.length; index++) {
            GpuTextureView colorTexture = colorTextures[index];
            if (colorTexture == null) {
                continue;
            }
            colorTextureViews[index] = (MetalGpuTextureView) colorTexture;
            Vector4fc clearColor = clearColors == null ? null : clearColors[index];
            if (clearColor != null) {
                clearColorEnabled[index] = 1;
                int base = index * 4;
                clearColorValues[base] = clearColor.x();
                clearColorValues[base + 1] = clearColor.y();
                clearColorValues[base + 2] = clearColor.z();
                clearColorValues[base + 3] = clearColor.w();
            }
        }
        MetalGpuTextureView depthTextureView = depthTexture == null ? null : (MetalGpuTextureView) depthTexture;
        boolean clearDepthNow = clearDepthEnabled;
        GpuTextureView extent = extentTexture();
        MTLRenderCommandEncoder encoder = commandEncoder.renderCommandEncoder(
                colorTextureViews,
                depthTextureView,
                extent.getWidth(0),
                extent.getHeight(0),
                clearColorEnabled,
                clearColorValues,
                clearDepthNow,
                clearDepthValue
        );
        clearColors = null;
        clearDepthEnabled = false;
        return encoder;
    }

    GpuBufferSlice.MappedView allocateTransient(final long size, final long alignment, @GpuBuffer.Usage final int usage) {
        return commandEncoder.transientMemory().allocateGpuMapped(size, alignment, usage);
    }

    /** Whether the engine bound a vertex buffer at the given logical slot for this pass. */
    boolean isVertexBufferBound(final int slot) {
        return slot >= 0 && slot < MAX_VERTEX_BUFFERS && vertexBuffers[slot] != null;
    }

    /**
     * CPU bytes of a bound uniform resource, or {@code null} when it is unbound
     * or its buffer is not host-visible. Only used for the engine's shared
     * {@code DynamicTransforms}/{@code Projection} buffers.
     */
    @Nullable
    ByteBuffer uniformBytes(final String name) {
        GpuBufferSlice slice = uniforms.get(name);
        if (slice == null || !(slice.buffer() instanceof MetalGpuBuffer metalBuffer)) {
            return null;
        }
        return metalBuffer.sliceStorageOrNull(slice.offset(), slice.length());
    }

    /** Metal's minimum uniform buffer offset alignment for per-draw transient slices. */
    int minUniformOffsetAlignment() {
        return this.device.getDeviceInfo().limits().minUniformOffsetAlignment();
    }

    private void pushVertexBuffers(final MTLRenderCommandEncoder enc) {
        int firstSlot = compiledPipeline.firstAvailableVertexBufferSlot();
        int count = compiledPipeline.vertexBufferCount();
        for (int slot = 0; slot < count; slot++) {
            GpuBufferSlice vertexBuffer = vertexBuffers[slot];
            if (vertexBuffer == null) {
                continue;
            }
            if (VALIDATION && vertexBuffer.buffer().isClosed()) {
                throw new IllegalStateException("Vertex buffer at slot " + slot + " has been closed");
            }

            MetalGpuBuffer nativeVertexBuffer = (MetalGpuBuffer) vertexBuffer.buffer();
            int metalSlot = firstSlot + slot;
            enc.setBuffer(nativeVertexBuffer.nativeHandle(), vertexBuffer.offset(), metalSlot, MetalCompiledRenderPipeline.STAGE_VERTEX);
        }

        int genericSlot = compiledPipeline.genericVertexBufferSlot();
        if (genericSlot >= 0) {
            MetalGpuBuffer defaults = device.genericVertexAttributeBuffer();
            enc.setBuffer(defaults.nativeHandle(), 0L, genericSlot, MetalCompiledRenderPipeline.STAGE_VERTEX);
        }
    }

    private void drawTriangleFan(MTLRenderCommandEncoder encoder, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        int triangleCount = vertexCount - 2;
        int indexCount = triangleCount * 3;
        MTLIndexType fanIndexType = vertexCount - 1 <= 0xFFFF ? MTLIndexType.UInt16 : MTLIndexType.UInt32;

        try (GpuBufferSlice.MappedView mapped = commandEncoder.transientMemory().allocateGpuMapped((long) indexCount * fanIndexType.bytes, fanIndexType.bytes, GpuBuffer.USAGE_INDEX)) {
            if (fanIndexType == MTLIndexType.UInt16) {
                java.nio.ShortBuffer indices = mapped.data().asShortBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put((short) 0);
                    indices.put((short) (i + 1));
                    indices.put((short) (i + 2));
                }
            } else {
                java.nio.IntBuffer indices = mapped.data().asIntBuffer();
                for (int i = 0; i < triangleCount; i++) {
                    indices.put(0);
                    indices.put(i + 1);
                    indices.put(i + 2);
                }
            }
            GpuBufferSlice slice = mapped.slice();
            encoder.drawIndexedPrimitives(MTLPrimitiveType.Triangle, indexCount, fanIndexType, ((MetalGpuBuffer) slice.buffer()).nativeHandle(), slice.offset(), Math.max(1, instanceCount), firstVertex, baseInstance);
        }
    }

    private void drawIndexedNative(
            final MTLRenderCommandEncoder enc,
            final MetalGpuBuffer nativeIndexBuffer,
            final int firstIndex,
            final int indexCount,
            final int baseVertex,
            final int instanceCount,
            final MTLIndexType indexType,
            final int baseInstance
    ) {
        MTLPrimitiveType primitiveType = primitiveTopology();

        long indexOffsetBytes = (long) firstIndex * indexType.bytes;
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            long fanSize = Math.multiplyExact(Math.multiplyExact((long) indexCount - 2L, 3L), Integer.BYTES);
            try (GpuBufferSlice.MappedView mapped = commandEncoder.transientMemory().allocateGpuMapped(fanSize, Integer.BYTES, GpuBuffer.USAGE_INDEX)) {
                GpuBufferSlice slice = mapped.slice();
                enc.drawIndexedPrimitivesTriangleFan(
                        nativeIndexBuffer.nativeHandle(),
                        ((MetalGpuBuffer) slice.buffer()).nativeHandle(),
                        slice.offset(),
                        indexType.value,
                        indexOffsetBytes,
                        indexCount,
                        baseVertex,
                        instanceCount,
                        baseInstance
                );
            }
        } else {
            enc.drawIndexedPrimitives(primitiveType, indexCount, indexType, nativeIndexBuffer.nativeHandle(), indexOffsetBytes, instanceCount, baseVertex, baseInstance);
        }
    }

    private void bindDrawState(final MTLRenderCommandEncoder enc) {
        IrisMetalWorldBridge.recordDrawVertexBuffers(this);
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }

        if (pipelineDirty) {
            MTLPixelFormat depthFormat = depthAttachmentFormat();
            MTLPixelFormat stencilFormat = stencilAttachmentFormat();
            boolean hasAttachment = depthFormat != MTLPixelFormat.Invalid
                    || stencilFormat != MTLPixelFormat.Invalid;
            MemorySegment pipelineHandle = compiledPipeline.getNativePipeline(
                    hasAttachment ? depthFormat : MTLPixelFormat.Invalid,
                    hasAttachment ? stencilFormat : MTLPixelFormat.Invalid
            );
            if (MetalNativeBridge.isNullHandle(pipelineHandle)) {
                throw new IllegalStateException("Native pipeline is unavailable");
            }
            enc.setRenderPipelineState(pipelineHandle);
            pipelineDirty = false;

            MemorySegment depthState = compiledPipeline.getDepthStencilState();
            if (MetalNativeBridge.isNullHandle(depthState)) {
                throw new IllegalStateException("Native depth state is unavailable");
            }
            enc.setDepthStencilState(depthState);
            if (hasAttachment && compiledPipeline.hasDepthStencilState()) {
                float biasConstant = compiledPipeline.depthBiasConstant();
                float biasScale = compiledPipeline.depthBiasScaleFactor();
                if (Iris.isPackInUseQuick()) {
                    // Mirror Iris's UndoReverseZFive polygon-offset negation for
                    // shaderpack rendering (the GL backend negates factor/units).
                    biasConstant = -biasConstant;
                    biasScale = -biasScale;
                }
                enc.setDepthBias(biasConstant, biasScale, 0.0f);
            } else {
                enc.setDepthBias(0.0f, 0.0f, 0.0f);
            }

            enc.setFrontFacingWinding(MTLWinding.Clockwise);
            enc.setCullMode(compiledPipeline.cullMode());
            enc.setTriangleFillMode(compiledPipeline.fillMode());

            dirtyDescriptorMask |= compiledPipeline.allResourceMask();
        }

        if (scissorDirty) {
            pushEffectiveScissor(enc);
            scissorDirty = false;
        }

        if (vertexBuffersDirty) {
            pushVertexBuffers(enc);
            vertexBuffersDirty = false;
        }

        // Per-draw core transforms must be materialized after the engine wrote
        // DynamicTransforms/Projection (setPipeline) and before descriptors are
        // pushed; null keeps the shared per-frame block. Mark the block dirty
        // so a second draw in the same pass rebinds its own slice.
        this.perDrawUniformOverride = IrisMetalWorldBridge.materializePerDrawUniforms(this);
        if (this.perDrawUniformOverride != null) {
            markDescriptorDirty(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME);
        }

        if (dirtyDescriptorMask != 0) {
            for (MetalCompiledRenderPipeline.ResourceBinding binding : compiledPipeline.resources()) {
                if ((dirtyDescriptorMask & (1L << binding.descriptorMaskIndex())) != 0L) {
                    pushDescriptor(enc, binding);
                }
            }
        }

        dirtyDescriptorMask = 0L;
    }

    private MTLPrimitiveType primitiveTopology() {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }
        return compiledPipeline.topology();
    }

    private void pushEffectiveScissor(final MTLRenderCommandEncoder enc) {
        int areaLeft = renderArea.x();
        int areaTop = renderArea.y();
        GpuTextureView extent = extentTexture();
        if (!scissorState.enabled()) {
            if (renderArea.fillsTexture(extent)) {
                enc.setScissorRect(0L, 0L, extent.getWidth(0), extent.getHeight(0));
                return;
            }
            enc.setScissorRect(areaLeft, areaTop, renderArea.width(), renderArea.height());
            return;
        }

        int areaRight = areaLeft + renderArea.width();
        int areaBottom = areaTop + renderArea.height();
        int left = Math.max(areaLeft, scissorState.x());
        int top = Math.max(areaTop, scissorState.y());
        int right = Math.min(areaRight, scissorState.x() + scissorState.width());
        int bottom = Math.min(areaBottom, scissorState.y() + scissorState.height());
        if (right <= left || bottom <= top) {
            enc.setScissorRect(0, 0, 0, 0);
        } else {
            enc.setScissorRect(left, top, right - left, bottom - top);
        }
    }

    /**
     * Marks the descriptor(s) backed by {@code name} dirty. Besides the exact
     * resource, an {@code iris_}-renamed built-in UBO is marked too: the engine
     * updates {@code Fog}/{@code DynamicTransforms}/... under the plain name
     * while an Iris-patched program binds them as {@code iris_Fog} etc. Without
     * this the renamed binding would keep the first pushed slice forever.
     */
    private void markDescriptorDirty(final String name) {
        if (compiledPipeline == null) {
            return;
        }
        markDescriptorDirty(compiledPipeline.resource(name));
        if (!name.startsWith(IRIS_UNIFORM_PREFIX)) {
            MetalCompiledRenderPipeline.ResourceBinding renamed =
                    compiledPipeline.resource(IRIS_UNIFORM_PREFIX + name);
            // Only uniform buffers can be renamed built-ins; never dirty a
            // sampler/image an engine texture bind happens to alias.
            if (renamed != null
                    && renamed.kind() == MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER) {
                markDescriptorDirty(renamed);
            }
        }
    }

    private void markDescriptorDirty(final MetalCompiledRenderPipeline.@Nullable ResourceBinding binding) {
        if (binding != null) {
            dirtyDescriptorMask |= 1L << binding.descriptorMaskIndex();
        }
    }

    /**
     * Resolves a uniform buffer slice under the exact resource name, falling
     * back to the plain engine name for Iris' renamed built-in blocks (eg
     * {@code iris_Fog} &rarr; the engine-bound {@code Fog} slice). Returns
     * {@code null} when neither name is bound, so callers keep their original
     * missing-uniform error.
     */
    private @Nullable GpuBufferSlice uniformSlice(final String name) {
        GpuBufferSlice slice = uniforms.get(name);
        if (slice != null) {
            return slice;
        }
        if (name.startsWith(IRIS_UNIFORM_PREFIX)) {
            return uniforms.get(name.substring(IRIS_UNIFORM_PREFIX.length()));
        }
        return null;
    }

    private void pushDescriptor(
            final MTLRenderCommandEncoder enc,
            final MetalCompiledRenderPipeline.ResourceBinding binding
    ) {
        if (binding.stageMask() == 0) {
            // Resource is not active in either stage's MSL (shaderpack programs
            // get a per-stage active set); there is no declared argument to bind.
            return;
        }
        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
            TextureViewAndSampler textureBinding = samplers.get(binding.name());
            if (textureBinding == null) {
                textureBinding = IrisMetalWorldBridge.fallbackSampler(binding.name(), samplers);
            }
            if (textureBinding == null) {
                textureBinding = IrisMetalTerrainBridge.fallbackSampler(binding.name(), samplers);
            }
            if (textureBinding == null) {
                throw new IllegalStateException("Missing sampler " + binding.name());
            }

            if (VALIDATION && textureBinding.textureView().isClosed()) {
                throw new IllegalStateException("Sampler " + binding.name() + " texture view has been closed");
            }

            MetalGpuTextureView textureView = (MetalGpuTextureView) textureBinding.textureView();
            MetalGpuSampler sampler = (MetalGpuSampler) textureBinding.sampler();
            setTextureForStages(enc, sampler.nativeHandle(), textureView.nativeHandle(), binding);
            return;
        }

        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER) {
            pushTexelBufferDescriptor(enc, binding);
            return;
        }

        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.STORAGE_IMAGE) {
            GpuTextureView view = storageImages.get(binding.name());
            if (!(view instanceof MetalGpuTextureView metalView)
                    || !(metalView.texture() instanceof MetalGpuTexture texture)
                    || view.isClosed() || texture.isClosed()) {
                throw new IllegalStateException("Missing or invalid storage image " + binding.name());
            }
            commandEncoder.flushPendingClear(texture);
            texture.markContentsDirty();
            setTextureForStages(enc, null, metalView.nativeHandle(), binding);
            return;
        }

        GpuBufferSlice uniformBinding = binding.name().equals(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME)
                && perDrawUniformOverride != null
                ? perDrawUniformOverride
                : uniformSlice(binding.name());
        if (uniformBinding == null
                && binding.kind() == MetalCompiledRenderPipeline.ResourceKind.STORAGE_BUFFER) {
            int logicalBinding = MetalCrossShaderCompiler.storageBufferLogicalBinding(binding.name());
            uniformBinding = storageBuffers.get(logicalBinding);
        }
        if (uniformBinding == null) {
            throw new IllegalStateException(
                    "Missing "
                            + (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.STORAGE_BUFFER
                            ? "storage buffer " : "uniform ")
                            + binding.name()
            );
        }
        if (VALIDATION && uniformBinding.buffer().isClosed()) {
            throw new IllegalStateException("Uniform " + binding.name() + " buffer has been closed");
        }

        MetalGpuBuffer uniformBuffer = (MetalGpuBuffer) uniformBinding.buffer();
        enc.setBuffer(uniformBuffer.nativeHandle(), uniformBinding.offset(), binding.bindingIndex(), binding.stageMask());
    }

    private void pushTexelBufferDescriptor(final MTLRenderCommandEncoder enc, final MetalCompiledRenderPipeline.ResourceBinding binding) {
        GpuBufferSlice texelSlice = uniformSlice(binding.name());
        if (texelSlice == null) {
            throw new IllegalStateException("Missing texel buffer " + binding.name());
        }
        if (VALIDATION && texelSlice.buffer().isClosed()) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " has been closed");
        }

        GpuFormat texelFormat = binding.texelBufferFormat();
        if (texelFormat == null) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " is missing a format");
        }

        MetalGpuBuffer texelBuffer = (MetalGpuBuffer) texelSlice.buffer();
        long pixelFormat = MTLPixelFormat.from(texelFormat).value;
        int pixelSize = texelFormat.blockSize();
        long texelByteLength = texelSlice.length();
        if (texelByteLength <= 0L || texelByteLength % pixelSize != 0L) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " length " + texelByteLength + " is not a valid " + texelFormat + " range");
        }
        long texelCount = texelByteLength / pixelSize;
        MemorySegment texelTexture = MetalNativeBridge.metallum_create_buffer_texture_view(
                texelBuffer.nativeHandle(),
                pixelFormat,
                texelSlice.offset(),
                texelCount,
                1L,
                texelByteLength
        );
        if (MetalNativeBridge.isNullHandle(texelTexture)) {
            throw new IllegalStateException("Failed to create Metal texel buffer texture for " + binding.name());
        }

        setTextureForStages(enc, null, texelTexture, binding);
        commandEncoder.queueForDestroy(() -> MetalNativeBridge.metallum_release_object(texelTexture));
    }

    /**
     * Binds a texture, and optionally its sampler, at the per-stage Metal
     * indices carried by {@code binding}.
     *
     * <p>Vanilla bindings use one shared index, so this is a single native
     * call (byte-for-byte the previous behavior). Shaderpack sampled resources
     * may compact their vertex and fragment tables independently, so when the
     * two indices differ each active stage is bound separately with its own
     * index.
     *
     * @param sampler sampler state to bind alongside the texture, or
     *                {@code null} for a plain texture binding (texel/storage
     *                images).
     */
    private void setTextureForStages(
            final MTLRenderCommandEncoder enc,
            final @Nullable MemorySegment sampler,
            final MemorySegment texture,
            final MetalCompiledRenderPipeline.ResourceBinding binding
    ) {
        final int vertexIndex = binding.bindingIndex();
        final int fragmentIndex = binding.fragmentBindingIndex();
        if (vertexIndex == fragmentIndex) {
            if (sampler == null) {
                enc.setTexture(texture, vertexIndex, binding.stageMask());
            } else {
                enc.setTextureAndSampler(texture, sampler, vertexIndex, binding.stageMask());
            }
            return;
        }
        if ((binding.stageMask() & MetalCompiledRenderPipeline.STAGE_VERTEX) != 0) {
            int index = binding.bindingIndexForStage(MetalCompiledRenderPipeline.STAGE_VERTEX);
            if (sampler == null) {
                enc.setTexture(texture, index, MetalCompiledRenderPipeline.STAGE_VERTEX);
            } else {
                enc.setTextureAndSampler(texture, sampler, index, MetalCompiledRenderPipeline.STAGE_VERTEX);
            }
        }
        if ((binding.stageMask() & MetalCompiledRenderPipeline.STAGE_FRAGMENT) != 0) {
            int index = binding.bindingIndexForStage(MetalCompiledRenderPipeline.STAGE_FRAGMENT);
            if (sampler == null) {
                enc.setTexture(texture, index, MetalCompiledRenderPipeline.STAGE_FRAGMENT);
            } else {
                enc.setTextureAndSampler(texture, sampler, index, MetalCompiledRenderPipeline.STAGE_FRAGMENT);
            }
        }
    }

    record TextureViewAndSampler(GpuTextureView textureView, GpuSampler sampler) {
    }

    private static boolean sameSlice(@Nullable final GpuBufferSlice left, @Nullable final GpuBufferSlice right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.buffer() == right.buffer()
                && left.offset() == right.offset()
                && left.length() == right.length();
    }
}
