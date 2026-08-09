package com.metallum.client.metal.render.mtl;

public enum MTLResourceOptions {
    ResourceStorageModeShared(0L),
    ResourceStorageModeManaged(1L << 8),
    ResourceStorageModePrivate(2L << 8),
    ResourceStorageModeMemoryless(3L << 8),
    ResourceCPUCacheModeDefault(0L),
    ResourceCPUCacheModeWriteCombined(1L << 4);

    public final long value;

    MTLResourceOptions(long value) {
        this.value = value;
    }
}
