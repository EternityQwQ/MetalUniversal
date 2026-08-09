package com.metallum.client.metal.render.mtl;

public enum MTLSamplerMinMagFilter {
    Nearest(0L),
    Linear(1L);

    public final long value;

    MTLSamplerMinMagFilter(long value) {
        this.value = value;
    }
}
