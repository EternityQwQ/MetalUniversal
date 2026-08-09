package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;

/**
 * A Metal {@code MTLTexture}. Ported from {@code MetalGpuTexture}, decoupled
 * from {@code com.mojang.blaze3d.textures.GpuTexture}.
 */
public final class MetalGpuTexture {
    private final long handle;
    private final int width;
    private final int height;
    private final int depth;
    private final int mipLevels;
    private final int sampleCount;
    private final MTLPixelFormat pixelFormat;
    private boolean closed;

    public MetalGpuTexture(long handle, int width, int height, int depth, int mipLevels, int sampleCount, MTLPixelFormat pixelFormat) {
        this.handle = handle;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.mipLevels = mipLevels;
        this.sampleCount = sampleCount;
        this.pixelFormat = pixelFormat;
    }

    public long handle() {
        return handle;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int depth() {
        return depth;
    }

    public int mipLevels() {
        return mipLevels;
    }

    public int sampleCount() {
        return sampleCount;
    }

    public MTLPixelFormat pixelFormat() {
        return pixelFormat;
    }

    public long createView(long deviceHandle, MTLPixelFormat format, int baseLevel, int levelCount, int baseSlice, int sliceCount, int usage) {
        return MetalNativeBridge.metallum_create_texture_view(deviceHandle, handle, format.value, baseLevel, levelCount, baseSlice, sliceCount, usage);
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!MetalNativeBridge.isNull(handle)) {
            MetalNativeBridge.metallum_release_object(handle);
        }
    }
}
