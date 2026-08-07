package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.validation.contract.ProducerType;
import com.metallum.client.validation.contract.RenderContractRuntime;
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
import net.minecraft.SharedConstants;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
import org.lwjgl.vulkan.VkDrawIndirectCommand;

import java.lang.foreign.MemorySegment;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
final class MetalRenderPass implements RenderPassBackend {
    static final boolean VALIDATION = SharedConstants.IS_RUNNING_IN_IDE;
    static final int MAX_VERTEX_BUFFERS = RenderPass.MAX_VERTEX_BUFFERS;
    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    @Nullable
    private final String label;
    private final GpuTextureView[] colorTextures;
    private final MTLPixelFormat[] colorAttachmentFormats;
    @Nullable
    private final GpuTextureView depthTexture;
    private final RenderPass.RenderArea renderArea;
    @Nullable
    private Vector4fc[] clearColors;
    private boolean clearDepthEnabled;
    private final double clearDepthValue;
    private final long contractPassToken;
    private final ScissorState scissorState = new ScissorState();
    private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    private final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
    private final HashMap<Integer, GpuBufferSlice> storageBuffers = new HashMap<>();
    private final HashMap<String, TextureViewAndSampler> samplers = new HashMap<>();
    private final HashMap<String, GpuTextureView> storageImages = new HashMap<>();
    private long dirtyDescriptorMask;
    @Nullable
    private MetalCompiledRenderPipeline compiledPipeline;
    private String contractPipelineId = "unbound";
    @Nullable
    private GpuBuffer indexBuffer;
    private MTLIndexType indexType = MTLIndexType.UInt16;
    private int pushedDebugGroups = 0;
    private boolean scissorDirty = true;
    private boolean vertexBuffersDirty = true;
    private boolean pipelineDirty = true;
    private long boundEncoderGeneration = -1L;
    @Nullable
    private MTLRenderCommandEncoder nativeEncoder;
    private final long cpuTimingStartNanos = System.nanoTime();
    private boolean cpuTimingRecorded;

    MetalRenderPass(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView[] colorTextures,
            @Nullable final GpuTextureView depthTexture,
            final RenderPass.RenderArea renderArea,
            @Nullable final Vector4fc[] clearColors,
            final boolean clearDepthEnabled,
            final double clearDepthValue,
            final long contractPassToken
    ) {
        this.device = device;
        this.commandEncoder = encoder;
        this.label = device.useLabels() || MetalGpuTimingRecorder.passTimingEnabled()
                ? label.get()
                : null;
        this.colorTextures = colorTextures.clone();
        this.colorAttachmentFormats = new MTLPixelFormat[this.colorTextures.length];
        for (int index = 0; index < this.colorTextures.length; index++) {
            this.colorAttachmentFormats[index] = this.colorTextures[index] == null
                    ? MTLPixelFormat.Invalid
                    : ((MetalGpuTexture) this.colorTextures[index].texture()).mtlPixelFormat();
        }
        this.depthTexture = depthTexture;
        this.renderArea = renderArea;
        this.clearColors = clearColors == null ? null : clearColors.clone();
        this.clearDepthEnabled = clearDepthEnabled;
        this.clearDepthValue = clearDepthValue;
        this.contractPassToken = contractPassToken;
        if (contractPassToken >= 0L && (this.clearColors != null || this.clearDepthEnabled)) {
            RenderContractRuntime.recordProducer(
                    contractPassToken,
                    ProducerType.CLEAR,
                    "unbound",
                    Map.of(
                            "colorClear", Boolean.toString(this.clearColors != null),
                            "depthClear", Boolean.toString(this.clearDepthEnabled),
                            "depthValue", Double.toString(this.clearDepthValue)
                    ),
                    Map.of(),
                    List.of()
            );
        }
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
        if (!Arrays.equals(compiled.colorAttachmentFormats(), colorAttachmentFormats())) {
            throw new IllegalArgumentException(
                    "Metal pipeline/render-pass color attachment signature mismatch for " + pipeline.getLocation()
                            + ": pipeline=" + Arrays.toString(compiled.colorAttachmentFormats())
                            + ", renderPass=" + Arrays.toString(colorAttachmentFormats())
            );
        }
        if (this.compiledPipeline != compiled) {
            this.compiledPipeline = compiled;
            vertexBuffersDirty = true;
            pipelineDirty = true;
        }
        if (contractPassToken >= 0L) {
            RenderContractRuntime.updatePipeline(contractPassToken, compiled.validationPipelineId());
            RenderContractRuntime.updateShaders(contractPassToken, compiled.validationShaderIds());
            this.contractPipelineId = compiled.validationPipelineId();
        } else {
            this.contractPipelineId = pipeline.getLocation().toString();
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
                    dirtyDescriptorMask |= 1L << resource.bindingIndex();
                }
            }
        }
    }

    @Nullable TextureViewAndSampler boundTexture(final String name) {
        return this.samplers.get(name);
    }

    Map<String, TextureViewAndSampler> boundTextures() {
        return this.samplers;
    }

    @Override
    public void setUniform(final @NonNull String name, final GpuBuffer value) {
        setUniform(name, value.slice());
    }

    @Override
    public void setUniform(final @NonNull String name, final @NonNull GpuBufferSlice value) {
        uniforms.put(name, value);
        markDescriptorDirty(name);
        if ("DynamicTransforms".equals(name) || "Projection".equals(name)) {
            markDescriptorDirty(MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME);
        }
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
        if (contractPassToken >= 0L) {
            RenderContractRuntime.updateScissor(
                    contractPassToken,
                    new com.metallum.client.validation.contract.ScissorRecord(true, x, y, width, height)
            );
        }
    }

    @Override
    public void disableScissor() {
        if (!scissorState.enabled()) {
            return;
        }
        scissorState.disable();
        scissorDirty = true;
        if (contractPassToken >= 0L) {
            RenderContractRuntime.updateScissor(
                    contractPassToken,
                    com.metallum.client.validation.contract.ScissorRecord.disabled()
            );
        }
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
        if (this.indexBuffer == null) {
            Metallum.LOGGER.warn("[metallum] drawIndexed called with null index buffer, skipping draw");
            return;
        }
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);
        drawIndexedNative(enc, nativeIndexBuffer, firstIndex, indexCount, vertexOffset, instanceCount, indexType, firstInstance);
        recordProducer(ProducerType.DRAW_INDEXED, Map.of(
                "indexCount", Integer.toString(indexCount),
                "instanceCount", Integer.toString(instanceCount),
                "firstIndex", Integer.toString(firstIndex),
                "vertexOffset", Integer.toString(vertexOffset),
                "firstInstance", Integer.toString(firstInstance)
        ));
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
        recordProducer(ProducerType.MULTI_DRAW, Map.of(
                "drawCount", Integer.toString(drawCount),
                "instanceCount", Integer.toString(instanceCount)
        ));
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
        recordProducer(ProducerType.MULTI_DRAW, Map.of("drawCount", Integer.toString(drawCount)));
    }

    @Override
    public void drawIndexedIndirect(final @NonNull GpuBufferSlice commands, final int drawCount) {
        if (drawCount <= 0) {
            return;
        }
        MTLPrimitiveType primitiveType = primitiveTopology();
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            throw new UnsupportedOperationException("Metal backend does not support triangle fan indirect draws");
        }
        if (this.indexBuffer == null) {
            Metallum.LOGGER.warn("[metallum] drawIndexedIndirect called with null index buffer, skipping draw");
            return;
        }
        if (commands.buffer().isClosed()) {
            Metallum.LOGGER.warn("[metallum] drawIndexedIndirect called with closed indirect command buffer, skipping draw");
            return;
        }
        long needed = (long) drawCount * VkDrawIndexedIndirectCommand.SIZEOF;
        if (commands.length() < needed) {
            Metallum.LOGGER.warn("[metallum] drawIndexedIndirect command buffer too small: need {} bytes, have {} (drawCount={})", needed, commands.length(), drawCount);
            return;
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
        recordProducer(ProducerType.DRAW_INDIRECT, Map.of("drawCount", Integer.toString(drawCount)));
    }

    @Override
    public <T> void drawMultipleIndexed(
            final Collection<RenderPass.Draw<T>> draws,
            @Nullable final GpuBuffer defaultIndexBuffer,
            @Nullable final IndexType defaultIndexType,
            final @NonNull Collection<String> dynamicUniforms,
            final @NonNull T uniformArgument
    ) {
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
        recordProducer(ProducerType.MULTI_DRAW, Map.of("drawCount", Integer.toString(draws.size())));
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
        recordProducer(ProducerType.DRAW, Map.of(
                "vertexCount", Integer.toString(vertexCount),
                "instanceCount", Integer.toString(instanceCount),
                "firstVertex", Integer.toString(firstVertex),
                "firstInstance", Integer.toString(firstInstance)
        ));
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
        recordProducer(ProducerType.DRAW_INDIRECT, Map.of("drawCount", Integer.toString(drawCount)));
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            metalPool.setValue(index, device.getTimestampNow());
        }
    }

    MTLPixelFormat[] colorAttachmentFormats() {
        return this.colorAttachmentFormats;
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

    void materializePendingClear() {
        if (clearColors != null || clearDepthEnabled) {
            renderEncoder();
        }
    }

    void finishTiming() {
        if (cpuTimingRecorded) {
            return;
        }
        cpuTimingRecorded = true;
        MetalGpuTimingRecorder.recordCpuPass(
                label == null ? "unlabeled render pass" : label,
                cpuTimingStartNanos,
                System.nanoTime()
        );
    }

    void finishContractPass() {
        if (contractPassToken >= 0L) {
            commandEncoder.endContractTraceGroup();
            RenderContractRuntime.endPass(contractPassToken);
        }
    }

    private void recordProducer(
            final ProducerType type,
            final Map<String, String> parameters
    ) {
        if (contractPassToken < 0L) return;
        if (!RenderContractRuntime.producerDetailsCaptured()) {
            RenderContractRuntime.recordProducer(
                    contractPassToken,
                    type,
                    contractPipelineId,
                    parameters,
                    Map.of(),
                    List.of()
            );
            return;
        }
        Map<String, String> boundResources = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, TextureViewAndSampler> entry : samplers.entrySet()) {
            if (entry.getValue().textureView().texture() instanceof MetalGpuTexture texture) {
                boundResources.put(entry.getKey(), texture.getLabel() + "@" + texture.validationResourceId());
            }
        }
        for (Map.Entry<String, GpuTextureView> entry : storageImages.entrySet()) {
            if (entry.getValue().texture() instanceof MetalGpuTexture texture) {
                boundResources.put(entry.getKey(), texture.getLabel() + "@" + texture.validationResourceId());
            }
        }
        RenderContractRuntime.recordProducer(
                contractPassToken,
                type,
                contractPipelineId,
                parameters,
                boundResources,
                List.of()
        );
    }

    private MTLRenderCommandEncoder renderEncoder() {
        if (nativeEncoder != null && commandEncoder.isCurrentEncoder(nativeEncoder)) {
            MetalGpuTimingRecorder.recordRenderEncoderLookup(true);
            return nativeEncoder;
        }
        MetalGpuTimingRecorder.recordRenderEncoderLookup(false);
        MetalGpuTextureView[] colorTextureViews = new MetalGpuTextureView[colorTextures.length];
        int[] clearColorEnabled = new int[colorTextures.length];
        float[] clearColorValues = new float[colorTextures.length * 4];
        for (int index = 0; index < colorTextures.length; index++) {
            GpuTextureView colorTexture = colorTextures[index];
            if (colorTexture != null) {
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
                clearDepthValue,
                label == null ? "unlabeled render pass" : label
        );
        nativeEncoder = encoder;
        clearColors = null;
        clearDepthEnabled = false;
        long generation = commandEncoder.encoderGeneration();
        if (generation != boundEncoderGeneration) {
            // A rebuilt native encoder starts with no state; force a full
            // rebind. The pipelineDirty branch of bindDrawState also refills
            // dirtyDescriptorMask with the pipeline's full resource mask.
            boundEncoderGeneration = generation;
            pipelineDirty = true;
            scissorDirty = true;
            vertexBuffersDirty = true;
        }
        return encoder;
    }

    GpuBufferSlice.MappedView allocateTransient(final long size, final long alignment, @GpuBuffer.Usage final int usage) {
        return commandEncoder.transientMemory().allocateGpuMapped(size, alignment, usage);
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
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }

        if (pipelineDirty) {
            MTLPixelFormat depthFormat = depthAttachmentFormat();
            MTLPixelFormat stencilFormat = stencilAttachmentFormat();
            boolean hasAttachment = depthFormat != MTLPixelFormat.Invalid || stencilFormat != MTLPixelFormat.Invalid;
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
                enc.setDepthBias(
                        compiledPipeline.depthBiasConstant(),
                        compiledPipeline.depthBiasScaleFactor(),
                        0.0f
                );
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

        if (dirtyDescriptorMask != 0) {
            for (MetalCompiledRenderPipeline.ResourceBinding binding : compiledPipeline.resources()) {
                if ((dirtyDescriptorMask & (1L << binding.bindingIndex())) != 0L) {
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

    private void markDescriptorDirty(final String name) {
        if (compiledPipeline != null) {
            MetalCompiledRenderPipeline.ResourceBinding binding = compiledPipeline.resource(name);
            if (binding != null) {
                dirtyDescriptorMask |= 1L << binding.bindingIndex();
            }
        }
    }

    private void pushDescriptor(
            final MTLRenderCommandEncoder enc,
            final MetalCompiledRenderPipeline.ResourceBinding binding
    ) {
        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
            TextureViewAndSampler textureBinding = samplers.get(binding.name());
            if (textureBinding == null) {
                // An Iris terrain override declares the pack's samplers on top
                // of the ones sodium binds; the registry supplies the remainder.
                // Returns null for every non-override pipeline, so a genuine
                // missing binding still fails loudly.
                textureBinding = IrisMetalPipelineOverrides.fallbackTexture(
                        device, compiledPipeline, binding.name(), samplers);
            }
            if (textureBinding == null) {
                throw new IllegalStateException("Missing sampler " + binding.name());
            }

            if (VALIDATION && textureBinding.textureView().isClosed()) {
                throw new IllegalStateException("Sampler " + binding.name() + " texture view has been closed");
            }

            MetalGpuTextureView textureView = (MetalGpuTextureView) textureBinding.textureView();
            MetalGpuSampler sampler = (MetalGpuSampler) textureBinding.sampler();
            if (MetalFxManager.usesTemporalUpscaling()
                    && compiledPipeline.usesStableTerrainSampler(binding)) {
                sampler = device.stableTerrainSampler(sampler);
            }
            enc.setTextureAndSampler(textureView.nativeHandle(), sampler.nativeHandle(), binding.bindingIndex(), binding.stageMask());
            return;
        }

        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER) {
            pushTexelBufferDescriptor(enc, binding);
            return;
        }

        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.STORAGE_IMAGE) {
            GpuTextureView view = storageImages.get(binding.name());
            if (view == null) {
                view = IrisMetalPipelineOverrides.fallbackStorageImage(
                        device, compiledPipeline, binding.name()
                );
            }
            if (!(view instanceof MetalGpuTextureView metalView)
                    || !(metalView.texture() instanceof MetalGpuTexture texture)
                    || view.isClosed() || texture.isClosed()) {
                throw new IllegalStateException("Missing or invalid storage image " + binding.name());
            }
            commandEncoder.flushPendingClear(texture);
            texture.markContentsDirty();
            enc.setTexture(metalView.nativeHandle(), binding.bindingIndex(), binding.stageMask());
            return;
        }

        GpuBufferSlice uniformSlice = uniforms.get(binding.name());
        if (uniformSlice == null
                && binding.kind() == MetalCompiledRenderPipeline.ResourceKind.STORAGE_BUFFER) {
            int logicalBinding = MetalCrossShaderCompiler.storageBufferLogicalBinding(binding.name());
            uniformSlice = storageBuffers.get(logicalBinding);
        }
        if (uniformSlice == null) {
            // The pack's uniform block (see fallbackTexture above for the
            // rationale); null for every non-override pipeline.
            uniformSlice = IrisMetalPipelineOverrides.fallbackUniformForDraw(
                    this, device, compiledPipeline, binding.name(), uniforms
            );
        }
        if (uniformSlice == null) {
            throw new IllegalStateException(
                    "Missing "
                            + (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.STORAGE_BUFFER
                            ? "storage buffer " : "uniform ")
                            + binding.name()
            );
        }
        if (VALIDATION && uniformSlice.buffer().isClosed()) {
            throw new IllegalStateException("Uniform " + binding.name() + " buffer has been closed");
        }

        MetalGpuBuffer uniformBuffer = (MetalGpuBuffer) uniformSlice.buffer();
        enc.setBuffer(uniformBuffer.nativeHandle(), uniformSlice.offset(), binding.bindingIndex(), binding.stageMask());
    }

    private void pushTexelBufferDescriptor(final MTLRenderCommandEncoder enc, final MetalCompiledRenderPipeline.ResourceBinding binding) {
        GpuBufferSlice texelSlice = uniforms.get(binding.name());
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

        enc.setTexture(texelTexture, binding.bindingIndex(), binding.stageMask());
        commandEncoder.queueForDestroy(() -> MetalNativeBridge.metallum_release_object(texelTexture));
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
