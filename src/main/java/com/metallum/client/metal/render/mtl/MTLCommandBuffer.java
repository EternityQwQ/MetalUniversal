package com.metallum.client.metal.render.mtl;

/** Lightweight Java wrapper around a Metal {@code MTLCommandBuffer} handle. */
public final class MTLCommandBuffer {
    private final long handle;

    public MTLCommandBuffer(long handle) {
        this.handle = handle;
    }

    public long handle() {
        return handle;
    }
}
