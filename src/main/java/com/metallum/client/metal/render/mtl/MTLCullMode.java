package com.metallum.client.metal.render.mtl;

public enum MTLCullMode {
    None(0L),
    Front(1L),
    Back(2L);

    public final long value;

    MTLCullMode(long value) {
        this.value = value;
    }
}
