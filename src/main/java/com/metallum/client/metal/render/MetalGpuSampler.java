package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;

/** A Metal {@code MTLSamplerState}. Ported from {@code MetalGpuSampler}. */
public final class MetalGpuSampler {
    private final long handle;
    private boolean closed;

    public MetalGpuSampler(long handle) {
        this.handle = handle;
    }

    public long handle() {
        return handle;
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
