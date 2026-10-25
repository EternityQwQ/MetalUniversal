package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLCommandBuffer {
    private MemorySegment handle;

    MTLCommandBuffer(final MemorySegment handle) {
        this.handle = handle;
    }

    public MTLBlitCommandEncoder makeBlitCommandEncoder() {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeBlitCommandEncoder(handle());
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create MTLBlitCommandEncoder");
        }
        return new MTLBlitCommandEncoder(encoder);
    }

    public MTLComputeCommandEncoder makeComputeCommandEncoder() {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeComputeCommandEncoder(handle());
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create MTLComputeCommandEncoder");
        }
        return new MTLComputeCommandEncoder(encoder);
    }

    public MTLRenderCommandEncoder makeRenderCommandEncoder(
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearDepthEnabled,
            final double clearDepth
    ) {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoder(
                handle(),
                colorTexture,
                depthTexture,
                viewportWidth,
                viewportHeight,
                clearColorEnabled,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                clearDepthEnabled,
                clearDepth
        );
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create MTLRenderCommandEncoder");
        }
        return new MTLRenderCommandEncoder(encoder);
    }

    public MTLRenderCommandEncoder makeRenderCommandEncoderV2(
            final MemorySegment[] colorTextures,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int[] clearColorEnabled,
            final float[] clearColors,
            final int clearDepthEnabled,
            final double clearDepth
    ) {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoderV2(
                handle(),
                colorTextures,
                depthTexture,
                viewportWidth,
                viewportHeight,
                clearColorEnabled,
                clearColors,
                clearDepthEnabled,
                clearDepth
        );
        if (MetalNativeBridge.isNullHandle(encoder)) {
            throw new IllegalStateException("Failed to create indexed MTLRenderCommandEncoder");
        }
        return new MTLRenderCommandEncoder(encoder);
    }

    public void clearColorDepthTexturesRegion(
            final MemorySegment colorTexture,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight,
            final MemorySegment globalFence
    ) {
        MetalNativeBridge.MTLCommandBuffer_clearColorDepthTexturesRegion(
                handle(),
                colorTexture,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                depthTexture,
                clearDepth,
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                globalFence
        );
    }

    public void encodePresentTextureToDrawable(final MemorySegment layer, final MemorySegment sourceTexture, final MemorySegment globalFence) {
        MetalNativeBridge.MTLCommandBuffer_encodePresentTextureToDrawable(handle(), layer, sourceTexture, globalFence);
    }

    public void commitWithSignal(final MemorySegment semaphore) {
        MetalNativeBridge.MTLCommandBuffer_commitWithSignal(handle(), semaphore);
    }

    /** Metal {@code GPUStartTime} in seconds; NaN when unavailable. */
    public double gpuStartTime() {
        return MetalNativeBridge.MTLCommandBuffer_gpuStartTime(handle());
    }

    /** Metal {@code GPUEndTime} in seconds; NaN when unavailable. */
    public double gpuEndTime() {
        return MetalNativeBridge.MTLCommandBuffer_gpuEndTime(handle());
    }

    public void pushDebugGroup(final String label) {
        MetalNativeBridge.MTLCommandBuffer_pushDebugGroup(handle(), label);
    }

    public void popDebugGroup() {
        MetalNativeBridge.MTLCommandBuffer_popDebugGroup(handle());
    }

    public void close() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return;
        }
        MetalNativeBridge.metallum_release_object(handle);
        handle = MemorySegment.NULL;
    }

    private MemorySegment handle() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            throw new IllegalStateException("MTLCommandBuffer is closed");
        }
        return handle;
    }
}
