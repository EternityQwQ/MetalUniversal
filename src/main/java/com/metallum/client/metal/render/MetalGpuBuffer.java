package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;

/**
 * A Metal {@code MTLBuffer}. Ported from {@code MetalGpuBuffer}, decoupled
 * from {@code com.mojang.blaze3d.buffers.GpuBuffer}.
 */
public final class MetalGpuBuffer {
    private final long handle;
    private final long size;
    private boolean closed;

    public MetalGpuBuffer(long handle, long size) {
        this.handle = handle;
        this.size = size;
    }

    public long handle() {
        return handle;
    }

    public long size() {
        return size;
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
