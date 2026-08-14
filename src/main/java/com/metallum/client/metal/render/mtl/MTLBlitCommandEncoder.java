package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLBlitCommandEncoder extends MTLCommandEncoder {

    MTLBlitCommandEncoder(final MemorySegment handle) {
        super(handle);
    }

    public void copyFromBufferToBuffer(
            final MemorySegment sourceBuffer,
            final long sourceOffset,
            final MemorySegment destinationBuffer,
            final long destinationOffset,
            final long length
    ) {
        MetalNativeBridge.MTLBlitCommandEncoder_copyFromBufferToBuffer(
                handle(), sourceBuffer, sourceOffset, destinationBuffer, destinationOffset, length
        );
    }

    public void copyFromBufferToTexture(
            final MemorySegment sourceBuffer,
            final long sourceOffset,
            final MemorySegment texture,
            final long mipLevel,
            final long slice,
            final long x,
            final long y,
            final long width,
            final long height,
            final long bytesPerRow,
            final long bytesPerImage
    ) {
        MetalNativeBridge.MTLBlitCommandEncoder_copyFromBufferToTexture(
                handle(), sourceBuffer, sourceOffset, texture, mipLevel, slice, x, y, width, height, bytesPerRow, bytesPerImage
        );
    }

    public void copyFromTextureToTexture(
            final MemorySegment sourceTexture,
            final MemorySegment destinationTexture,
            final long mipLevel,
            final long sourceX,
            final long sourceY,
            final long destX,
            final long destY,
            final long width,
            final long height
    ) {
        MetalNativeBridge.MTLBlitCommandEncoder_copyFromTextureToTexture(
                handle(), sourceTexture, destinationTexture, mipLevel, sourceX, sourceY, destX, destY, width, height
        );
    }

    public void copyFromTextureToBuffer(
            final MemorySegment sourceTexture,
            final MemorySegment destinationBuffer,
            final long destinationOffset,
            final long mipLevel,
            final long slice,
            final long x,
            final long y,
            final long width,
            final long height,
            final long bytesPerRow,
            final long bytesPerImage
    ) {
        MetalNativeBridge.MTLBlitCommandEncoder_copyFromTextureToBuffer(
                handle(), sourceTexture, destinationBuffer, destinationOffset, mipLevel, slice, x, y, width, height, bytesPerRow, bytesPerImage
        );
    }

    public void updateFence(final MemorySegment fence) {
        MetalNativeBridge.MTLBlitCommandEncoder_updateFence(handle(), fence);
    }

    public void waitForFence(final MemorySegment fence) {
        MetalNativeBridge.MTLBlitCommandEncoder_waitForFence(handle(), fence);
    }
}
