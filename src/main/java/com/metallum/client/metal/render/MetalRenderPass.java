package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.HashMap;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
final class MetalRenderPass implements RenderPass {
    static final boolean VALIDATION = SharedConstants.IS_RUNNING_IN_IDE;
    // 1.21.11 的 RenderPass 接口无 MAX_VERTEX_BUFFERS 常量（26.2 有）：按 26.2 值保持
    static final int MAX_VERTEX_BUFFERS = 16;
    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    @Nullable
    private final String label;
    private final GpuTextureView colorTexture;
    @Nullable
    private final GpuTextureView depthTexture;
    @Nullable
    private Vector4fc clearColor;
    private boolean clearDepthEnabled;
    private final double clearDepthValue;
    private final ScissorState scissorState = new ScissorState();
    private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
    private final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
    private final HashMap<String, TextureViewAndSampler> samplers = new HashMap<>();
    private long dirtyDescriptorMask;
    @Nullable
    private MetalCompiledRenderPipeline compiledPipeline;
    @Nullable
    private GpuBuffer indexBuffer;
    private MTLIndexType indexType = MTLIndexType.UInt16;
    private int pushedDebugGroups = 0;
    private boolean scissorDirty = true;
    private boolean vertexBuffersDirty = true;
    private boolean pipelineDirty = true;
    private long diagDrawCount;
    private int diagMaxIndexCount;
    private int diagU32Count;
    private int diagMinBaseVertex = Integer.MAX_VALUE;
    private int diagMaxBaseVertex = Integer.MIN_VALUE;
    private int diagMinIndexOffset = Integer.MAX_VALUE;
    private int diagMaxIndexOffset = Integer.MIN_VALUE;
    private long diagWindowStart;
    private long diagDrawCallCount;
    private int diagMinFirstVertex = Integer.MAX_VALUE;
    private int diagMaxFirstVertex = Integer.MIN_VALUE;
    private int diagMinVertexCount = Integer.MAX_VALUE;
    private int diagMaxVertexCount = Integer.MIN_VALUE;

    MetalRenderPass(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView colorTexture,
            @Nullable final GpuTextureView depthTexture,
            @Nullable final Vector4fc clearColor,
            final boolean clearDepthEnabled,
            final double clearDepthValue
    ) {
        this.device = device;
        this.commandEncoder = encoder;
        this.label = device.useLabels() ? label.get() : null;
        this.colorTexture = colorTexture;
        this.depthTexture = depthTexture;
        this.clearColor = clearColor;
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
        if (this.compiledPipeline != compiled) {
            this.compiledPipeline = compiled;
            vertexBuffersDirty = true;
            pipelineDirty = true;
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
    public void setVertexBuffer(final int slot, @Nullable final GpuBuffer vertexBuffer) {
        if (slot < 0 || slot >= MAX_VERTEX_BUFFERS) {
            throw new IllegalArgumentException("Unsupported Metal vertex buffer slot: " + slot);
        }
        // 1.21.11 传 GpuBuffer（26.2 为 GpuBufferSlice）：内部按整缓冲 slice 存储
        GpuBufferSlice slice = vertexBuffer == null ? null : vertexBuffer.slice();
        if (!sameSlice(vertexBuffers[slot], slice)) {
            vertexBuffers[slot] = slice;
            vertexBuffersDirty = true;
        }
    }

    @Override
    public void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final VertexFormat.IndexType indexType) {
        setIndexBuffer(indexBuffer, MTLIndexType.from(indexType));
    }

    private void setIndexBuffer(@Nullable final GpuBuffer indexBuffer, final MTLIndexType indexType) {
        if (this.indexBuffer != indexBuffer || this.indexType != indexType) {
            this.indexBuffer = indexBuffer;
            this.indexType = indexType;
        }
    }

    @Override
    public void drawIndexed(final int baseVertex, final int indexOffset, final int indexCount, final int instanceCount) {
        // 1.21.11 语义（GlCommandEncoder GL 实参反汇编实证）：
        // (baseVertex, indexBufferOffset, indexCount, instanceCount)
        // ——MCP 参数名（firstIndex, index, indexCount, primCount）有误导性
        if (this.indexBuffer == null) {
            Metallum.LOGGER.warn("[metallum] drawIndexed called with null index buffer, skipping draw");
            return;
        }
        MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);
        drawIndexedNative(enc, nativeIndexBuffer, indexOffset, indexCount, baseVertex, instanceCount, indexType, 0);
        countDraw();
    }

    @Override
    public <T> void drawMultipleIndexed(
            final Collection<RenderPass.Draw<T>> draws,
            @Nullable final GpuBuffer defaultIndexBuffer,
            final VertexFormat.IndexType defaultIndexType,
            final @NonNull Collection<String> dynamicUniforms,
            final @NonNull T uniformArgument
    ) {
        VertexFormat.IndexType fallbackIndexType = defaultIndexType == null ? VertexFormat.IndexType.SHORT : defaultIndexType;

        int drawTotal = draws.size();
        int firstDrawFirstIndex = -1;
        int firstDrawIndexCount = -1;
        int firstDrawSlot = -1;
        GpuBuffer firstDrawVb = null;
        GpuBuffer firstDrawIb = null;
        int i = 0;
        for (RenderPass.Draw<T> draw : draws) {
            if (i == 0) {
                firstDrawFirstIndex = draw.firstIndex();
                firstDrawIndexCount = draw.indexCount();
                firstDrawSlot = draw.slot();
                firstDrawVb = draw.vertexBuffer();
                firstDrawIb = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();
            }
            i++;
            MTLIndexType drawIndexType = MTLIndexType.from(draw.indexType() == null ? fallbackIndexType : draw.indexType());
            GpuBuffer currentIndexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();

            setIndexBuffer(currentIndexBuffer, drawIndexType);
            setVertexBuffer(draw.slot(), draw.vertexBuffer());

            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(uniformArgument, this::setUniform);
            }

            MTLRenderCommandEncoder enc = renderEncoder();
            if (scissorDirty || vertexBuffersDirty || dirtyDescriptorMask != 0L || pipelineDirty) {
                bindDrawState(enc);
            }
            MetalGpuBuffer nativeIndexBuffer = (MetalGpuBuffer) indexBuffer;
            // 1.21.11 的 Draw 无 baseVertex 字段：顶点偏移恒为 0
            drawIndexedNative(enc, nativeIndexBuffer, draw.firstIndex(), draw.indexCount(), 0, 1, drawIndexType, 0);
        }
        // v5 遗漏的诊断：chunk 若走 multidraw 路径，此处必现
        if (Diagnostics.shouldRun("multidraw", 5000L)) {
            com.metallum.Metallum.LOGGER.error("[diag] multiDraw draws={} first={} idxCount={} slot={} vb=0x{} size={} ib=0x{} size={}",
                    drawTotal, firstDrawFirstIndex, firstDrawIndexCount, firstDrawSlot,
                    firstDrawVb == null ? "null" : String.format("%x", System.identityHashCode(firstDrawVb)),
                    firstDrawVb == null ? -1 : firstDrawVb.size(),
                    firstDrawIb == null ? "null" : String.format("%x", System.identityHashCode(firstDrawIb)),
                    firstDrawIb == null ? -1 : firstDrawIb.size());
            // 顶点/索引缓冲 GPU readback：验证 chunk 几何数据是否上传、布局是否正确
            if (firstDrawVb != null) {
                commandEncoder.readbackBuffer("chunkVb", firstDrawVb, 0L, 128);
            }
            if (firstDrawIb != null) {
                commandEncoder.readbackBuffer("chunkIb", firstDrawIb, 0L, 64);
            }
        }
    }

    @Override
    public void draw(final int firstVertex, final int vertexCount) {
        // 1.21.11 语义（GlCommandEncoder GL 实参反汇编实证）：(firstVertex, vertexCount)
        MTLPrimitiveType primitiveType = primitiveTopology();
        MTLRenderCommandEncoder enc = renderEncoder();

        bindDrawState(enc);

        // 统计：1.21.11 chunk 渲染走 draw()（非索引路径），参数范围用于定位画不出根因
        diagDrawCallCount++;
        diagMinFirstVertex = Math.min(diagMinFirstVertex, firstVertex);
        diagMaxFirstVertex = Math.max(diagMaxFirstVertex, firstVertex);
        diagMinVertexCount = Math.min(diagMinVertexCount, vertexCount);
        diagMaxVertexCount = Math.max(diagMaxVertexCount, vertexCount);
        if (Diagnostics.shouldRun("drawstat", 5000L)) {
            com.metallum.Metallum.LOGGER.error("[diag] drawStat draws={} firstVtx=[{}..{}] vtxCount=[{}..{}] mode={}",
                    diagDrawCallCount, diagMinFirstVertex, diagMaxFirstVertex,
                    diagMinVertexCount, diagMaxVertexCount,
                    compiledPipeline == null ? "?" : compiledPipeline.getVertexFormatMode());
            diagDrawCallCount = 0;
            diagMinFirstVertex = Integer.MAX_VALUE;
            diagMaxFirstVertex = Integer.MIN_VALUE;
            diagMinVertexCount = Integer.MAX_VALUE;
            diagMaxVertexCount = Integer.MIN_VALUE;
        }

        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            drawTriangleFan(enc, firstVertex, vertexCount, 1, 0);
        } else {
            enc.drawPrimitives(primitiveType, firstVertex, vertexCount, 1, 0);
        }
        countDraw();
    }

    @Override
    public void close() {
        materializePendingClear();
    }

    MTLPixelFormat colorAttachmentFormat() {
        return ((MetalGpuTexture) colorTexture.texture()).mtlPixelFormat();
    }

    MTLPixelFormat depthAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlPixelFormat();
    }

    MTLPixelFormat stencilAttachmentFormat() {
        if (depthTexture == null) {
            return MTLPixelFormat.Invalid;
        }
        return ((MetalGpuTexture) depthTexture.texture()).mtlStencilPixelFormat();
    }

    void materializePendingClear() {
        if (clearColor != null || clearDepthEnabled) {
            renderEncoder();
        }
    }

    private MTLRenderCommandEncoder renderEncoder() {
        MetalGpuTextureView colorTextureView = (MetalGpuTextureView) colorTexture;
        MetalGpuTextureView depthTextureView = depthTexture == null ? null : (MetalGpuTextureView) depthTexture;
        boolean clearColorNow = clearColor != null;
        boolean clearDepthNow = clearDepthEnabled;
        MTLRenderCommandEncoder encoder = commandEncoder.renderCommandEncoder(
                colorTextureView,
                depthTextureView,
                colorTexture.getWidth(0),
                colorTexture.getHeight(0),
                clearColorNow,
                clearColorNow ? clearColor.x() : 0.0F,
                clearColorNow ? clearColor.y() : 0.0F,
                clearColorNow ? clearColor.z() : 0.0F,
                clearColorNow ? clearColor.w() : 0.0F,
                clearDepthNow,
                clearDepthValue
        );
        clearColor = null;
        clearDepthEnabled = false;
        return encoder;
    }

    void invalidateEncoderState() {
        pipelineDirty = true;
        scissorDirty = true;
        vertexBuffersDirty = true;
    }

    MetalTransientMemory.MappedView allocateTransient(final long size, final long alignment, final int usage) {
        return commandEncoder.transientMemory.allocateGpuMapped(size, alignment, usage, 0L, 1L);
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
            Diagnostics.once("vbuf:" + metalSlot,
                    "pushVertexBuffers slot={} metalSlot={} handle=0x{} offset={} closed={} size={} first16={}",
                    slot, metalSlot, Long.toHexString(nativeVertexBuffer.nativeHandle().address()),
                    vertexBuffer.offset(), nativeVertexBuffer.isClosed(), vertexBuffer.length(),
                    storageHex(nativeVertexBuffer, 16));
            enc.setBuffer(nativeVertexBuffer.nativeHandle(), vertexBuffer.offset(), metalSlot, MetalCompiledRenderPipeline.STAGE_VERTEX);
        }
    }

    private void drawTriangleFan(MTLRenderCommandEncoder encoder, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        int triangleCount = vertexCount - 2;
        int indexCount = triangleCount * 3;
        MTLIndexType fanIndexType = vertexCount - 1 <= 0xFFFF ? MTLIndexType.UInt16 : MTLIndexType.UInt32;

        try (MetalTransientMemory.MappedView mapped = commandEncoder.transientMemory.allocateGpuMapped((long) indexCount * fanIndexType.bytes, fanIndexType.bytes, GpuBuffer.USAGE_INDEX, 0L, 1L)) {
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
        // 窗口统计（5s）：聚合 chunk 大 draw 参数，替代"只显示窗口第一条"（GUI 小 draw 曾独占）
        long now = System.nanoTime();
        if (diagWindowStart == 0L) {
            diagWindowStart = now;
        }
        long elapsedMs = (now - diagWindowStart) / 1_000_000L;
        if (elapsedMs >= 5000L) {
            if (diagDrawCount > 0) {
                com.metallum.Metallum.LOGGER.error("[diag] drawIdxStat prim={} draws={} maxIdx={} u32={} baseVtx=[{},{}] idxOff=[{},{}]",
                        primitiveType, diagDrawCount, diagMaxIndexCount, diagU32Count,
                        diagMinBaseVertex, diagMaxBaseVertex, diagMinIndexOffset, diagMaxIndexOffset);
            }
            diagWindowStart = now;
            diagDrawCount = 0;
            diagMaxIndexCount = 0;
            diagU32Count = 0;
            diagMinBaseVertex = Integer.MAX_VALUE;
            diagMaxBaseVertex = Integer.MIN_VALUE;
            diagMinIndexOffset = Integer.MAX_VALUE;
            diagMaxIndexOffset = Integer.MIN_VALUE;
        }
        diagDrawCount++;
        diagMaxIndexCount = Math.max(diagMaxIndexCount, indexCount);
        if (indexType == MTLIndexType.UInt32) {
            diagU32Count++;
        }
        diagMinBaseVertex = Math.min(diagMinBaseVertex, baseVertex);
        diagMaxBaseVertex = Math.max(diagMaxBaseVertex, baseVertex);
        diagMinIndexOffset = Math.min(diagMinIndexOffset, firstIndex);
        diagMaxIndexOffset = Math.max(diagMaxIndexOffset, firstIndex);

        long indexOffsetBytes = (long) firstIndex * indexType.bytes;
        if (primitiveType == MTLPrimitiveType.TriangleFan) {
            long fanSize = Math.multiplyExact(Math.multiplyExact((long) indexCount - 2L, 3L), Integer.BYTES);
            try (MetalTransientMemory.MappedView mapped = commandEncoder.transientMemory.allocateGpuMapped(fanSize, Integer.BYTES, GpuBuffer.USAGE_INDEX, 0L, 1L)) {
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
            boolean useDepth = depthAttachmentFormat().value != MTLPixelFormat.Invalid.value;
            // 1.21.11 的 attachment 格式来自渲染目标纹理（懒创建 pipeline）
            MemorySegment pipelineHandle = compiledPipeline.getNativePipeline(useDepth, colorAttachmentFormat());
            if (MetalNativeBridge.isNullHandle(pipelineHandle)) {
                throw new IllegalStateException("Native pipeline is unavailable");
            }
            Diagnostics.once("pipe:" + compiledPipeline.getClass().getSimpleName() + "|" + useDepth
                            + "|" + compiledPipeline.depthCompareOp() + "|" + compiledPipeline.depthWrite()
                            + "|" + compiledPipeline.cullMode(),
                    "bindPipeline {} useDepth={} colorFmt={} depthFmt={} cull={} winding={} depthOp={} depthWrite={}",
                    compiledPipeline.getClass().getSimpleName(), useDepth, colorAttachmentFormat(), depthAttachmentFormat(),
                    compiledPipeline.cullMode(), MTLWinding.Clockwise, compiledPipeline.depthCompareOp(), compiledPipeline.depthWrite());
            enc.setRenderPipelineState(pipelineHandle);
            pipelineDirty = false;

            if (useDepth) {
                MemorySegment depthState = compiledPipeline.getDepthStencilState();
                if (MetalNativeBridge.isNullHandle(depthState)) {
                    throw new IllegalStateException("Native depth state is unavailable");
                }
                enc.setDepthStencilState(depthState);
                enc.setDepthBias(
                        compiledPipeline.depthBiasConstant(),
                        compiledPipeline.depthBiasScaleFactor(),
                        0.0f
                );
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

    /**
     * 顶点格式摘要（name type×count @offset，stride）——对照 MSL stage_in 与缓冲布局。
     */
    private String describeVertexFormat() {
        if (compiledPipeline == null) {
            return "?";
        }
        com.mojang.blaze3d.vertex.VertexFormat vf = compiledPipeline.getVertexFormat();
        StringBuilder sb = new StringBuilder();
        sb.append("stride=").append(vf.getVertexSize()).append('[');
        boolean first = true;
        for (com.mojang.blaze3d.vertex.VertexFormatElement e : vf.getElements()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(vf.getElementName(e)).append('(')
                    .append(e.type()).append('x').append(e.count())
                    .append('@').append(vf.getOffset(e)).append(')');
        }
        return sb.append(']').toString();
    }

    private MTLPrimitiveType primitiveTopology() {
        if (compiledPipeline == null) {
            throw new IllegalStateException("Pipeline is missing");
        }
        return compiledPipeline.topology();
    }

    private void pushEffectiveScissor(final MTLRenderCommandEncoder enc) {
        // 1.21.11 无 renderArea 概念：渲染区域即全纹理
        int width = colorTexture.getWidth(0);
        int height = colorTexture.getHeight(0);
        if (!scissorState.enabled()) {
            enc.setScissorRect(0L, 0L, width, height);
            return;
        }

        int left = Math.max(0, scissorState.x());
        int top = Math.max(0, scissorState.y());
        int right = Math.min(width, scissorState.x() + scissorState.width());
        int bottom = Math.min(height, scissorState.y() + scissorState.height());
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
                throw new IllegalStateException("Missing sampler " + binding.name());
            }

            if (VALIDATION && textureBinding.textureView().isClosed()) {
                throw new IllegalStateException("Sampler " + binding.name() + " texture view has been closed");
            }

            MetalGpuTextureView textureView = (MetalGpuTextureView) textureBinding.textureView();
            MetalGpuSampler sampler = (MetalGpuSampler) textureBinding.sampler();
            Diagnostics.once("texbind:" + binding.name(),
                    "pushDescriptor SAMPLED_IMAGE name={} idx={} stage=0x{} texHandle=0x{} smpHandle=0x{} texClosed={}",
                    binding.name(), binding.bindingIndex(), binding.stageMask(),
                    Long.toHexString(textureView.nativeHandle().address()),
                    Long.toHexString(sampler.nativeHandle().address()),
                    textureBinding.textureView().isClosed());
            enc.setTextureAndSampler(textureView.nativeHandle(), sampler.nativeHandle(), binding.bindingIndex(), binding.stageMask());
            return;
        }

        if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER) {
            pushTexelBufferDescriptor(enc, binding);
            return;
        }

        GpuBufferSlice uniformSlice = uniforms.get(binding.name());
        if (uniformSlice == null) {
            throw new IllegalStateException("Missing uniform " + binding.name());
        }
        if (VALIDATION && uniformSlice.buffer().isClosed()) {
            throw new IllegalStateException("Uniform " + binding.name() + " buffer has been closed");
        }

        MetalGpuBuffer uniformBuffer = (MetalGpuBuffer) uniformSlice.buffer();
        Diagnostics.once("ubind:" + binding.name(),
                "pushDescriptor UNIFORM_BUFFER name={} idx={} stage=0x{} handle=0x{} offset={} len={} first64={}",
                binding.name(), binding.bindingIndex(), binding.stageMask(),
                Long.toHexString(uniformBuffer.nativeHandle().address()),
                uniformSlice.offset(), uniformSlice.length(),
                storageHex(uniformBuffer, 64));
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

        TextureFormat texelFormat = binding.texelBufferFormat();
        if (texelFormat == null) {
            throw new IllegalStateException("Texel buffer " + binding.name() + " is missing a format");
        }

        MetalGpuBuffer texelBuffer = (MetalGpuBuffer) texelSlice.buffer();
        long pixelFormat = MTLPixelFormat.from(texelFormat).value;
        int pixelSize = texelFormat.pixelSize();
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

    // ---- draw 统计（品红诊断：确认 draw 是否真正编码） ----
    private static final java.util.concurrent.atomic.AtomicLong TOTAL_DRAWS = new java.util.concurrent.atomic.AtomicLong();

    private static void countDraw() {
        long total = TOTAL_DRAWS.incrementAndGet();
        if (Diagnostics.shouldRun("drawstats", 5000L)) {
            com.metallum.Metallum.LOGGER.error("[diag] draw calls total={}", total);
        }
    }

    /**
     * CPU 可访问缓冲的内容 hex 摘要（Shared storage 直接读；Private 返回 "n/a"）。
     */
    private static String storageHex(final MetalGpuBuffer buffer, final int maxBytes) {
        try {
            java.nio.ByteBuffer storage = buffer.currentStorage();
            if (storage == null) {
                return "null";
            }
            int n = Math.min(storage.remaining(), maxBytes);
            StringBuilder sb = new StringBuilder(n * 2);
            for (int i = 0; i < n; i++) {
                sb.append(String.format("%02x", storage.get(storage.position() + i) & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "n/a(" + e.getClass().getSimpleName() + ")";
        }
    }
}
