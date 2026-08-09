package com.metallum.client.metal.render.mtl;

public enum MTLStorageMode {
    Shared(0L),
    Managed(1L),
    Private(2L),
    Memoryless(3L);

    public final long value;

    MTLStorageMode(long value) {
        this.value = value;
    }
}
