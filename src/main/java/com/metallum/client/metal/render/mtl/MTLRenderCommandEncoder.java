package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLRenderCommandEncoder extends MTLCommandEncoder {

    MTLRenderCommandEncoder(final MemorySegment handle) {
        super(handle);
    }

    public void setRenderPipelineState(final MemorySegment pipeline) {
        MetalNativeBridge.MTLRenderCommandEncoder_setRenderPipelineState(handle(), pipeline);
    }

    public void setDepthStencilState(final MemorySegment depthStencilState) {
        MetalNativeBridge.MTLRenderCommandEncoder_setDepthStencilState(handle(), depthStencilState);
    }

    public void setDepthBias(final float depthBias, final float slopeScale, final float clamp) {
        MetalNativeBridge.MTLRenderCommandEncoder_setDepthBias(handle(), depthBias, slopeScale, clamp);
    }

    public void setFrontFacingWinding(final MTLWinding winding) {
        MetalNativeBridge.MTLRenderCommandEncoder_setFrontFacingWinding(handle(), winding.value);
    }

    public void setCullMode(final MTLCullMode cullMode) {
        MetalNativeBridge.MTLRenderCommandEncoder_setCullMode(handle(), cullMode.value);
    }

    public void setTriangleFillMode(final MTLTriangleFillMode fillMode) {
        MetalNativeBridge.MTLRenderCommandEncoder_setTriangleFillMode(handle(), fillMode.value);
    }

    public void setBuffer(final MemorySegment buffer, final long offset, final long index, final int stageMask) {
        MetalNativeBridge.MTLRenderCommandEncoder_setBuffer(handle(), buffer, offset, index, stageMask);
    }

    public void setBufferOffset(final long offset, final long index, final int stageMask) {
        MetalNativeBridge.MTLRenderCommandEncoder_setBufferOffset(handle(), offset, index, stageMask);
    }

    public void setTexture(final MemorySegment texture, final long index, final int stageMask) {
        MetalNativeBridge.MTLRenderCommandEncoder_setTexture(handle(), texture, index, stageMask);
    }

    public void setTextureAndSampler(final MemorySegment texture, final MemorySegment sampler, final long index, final int stageMask) {
        MetalNativeBridge.MTLRenderCommandEncoder_setTextureAndSampler(handle(), texture, sampler, index, stageMask);
    }

    public void setScissorRect(final long x, final long y, final long width, final long height) {
        MetalNativeBridge.MTLRenderCommandEncoder_setScissorRect(handle(), x, y, width, height);
    }

    public void clearDraw(
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final boolean clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final boolean clearDepthEnabled,
            final double clearDepth
    ) {
        MetalNativeBridge.MTLRenderCommandEncoder_clearDraw(
                handle(),
                colorTexture,
                depthTexture,
                viewportWidth,
                viewportHeight,
                clearColorEnabled ? 1 : 0,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                clearDepthEnabled ? 1 : 0,
                clearDepth
        );
    }

    public void drawPrimitives(final MTLPrimitiveType primitiveType, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        MetalNativeBridge.MTLRenderCommandEncoder_drawPrimitives(handle(), primitiveType.value, firstVertex, vertexCount, instanceCount, baseInstance);
    }

    public void drawIndexedPrimitives(final MTLPrimitiveType primitiveType, final int indexCount, final MTLIndexType indexType, final MemorySegment indexBuffer, final long offset, final int instanceCount, final int baseVertex, final int baseInstance) {
        MetalNativeBridge.MTLRenderCommandEncoder_drawIndexedPrimitives(handle(), primitiveType.value, indexCount, indexType.value, indexBuffer, offset, instanceCount, baseVertex, baseInstance);
    }

    public void drawIndexedPrimitivesIndirect(final MTLPrimitiveType primitiveType, final MTLIndexType indexType, final MemorySegment indexBuffer, final MemorySegment indirectBuffer, final long indirectBufferOffset, final int drawCount, final long stride) {
        MetalNativeBridge.MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(handle(), primitiveType.value, indexType.value, indexBuffer, indirectBuffer, indirectBufferOffset, drawCount, stride);
    }

    public void drawPrimitivesIndirect(final MTLPrimitiveType primitiveType, final MemorySegment indirectBuffer, final long indirectBufferOffset, final int drawCount, final long stride) {
        MetalNativeBridge.MTLRenderCommandEncoder_drawPrimitivesIndirect(handle(), primitiveType.value, indirectBuffer, indirectBufferOffset, drawCount, stride);
    }

    public void drawIndexedPrimitivesTriangleFan(final MemorySegment indexBuffer, final MemorySegment fanIndexBuffer, final long fanIndexBufferOffset, final long indexType, final long offset, final int indexCount, final int baseVertex, final int instanceCount, final int baseInstance) {
        MetalNativeBridge.MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(handle(), indexBuffer, fanIndexBuffer, fanIndexBufferOffset, indexType, offset, indexCount, baseVertex, instanceCount, baseInstance);
    }

    public void updateFence(final MemorySegment fence, final MTLRenderStages stages) {
        MetalNativeBridge.MTLRenderCommandEncoder_updateFence(handle(), fence, stages.value);
    }

    public void waitForFence(final MemorySegment fence, final MTLRenderStages stages) {
        MetalNativeBridge.MTLRenderCommandEncoder_waitForFence(handle(), fence, stages.value);
    }
}
