package com.metallum.client.metal.render.mtl;

public enum MTLSamplerMipFilter {
    NotMipmapped(0L),
    Nearest(1L),
    Linear(2L);

    public final long value;

    MTLSamplerMipFilter(long value) {
        this.value = value;
    }
}
