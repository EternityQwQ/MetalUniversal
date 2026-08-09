package com.metallum.client.metal.render.mtl;

public enum MTLSamplerAddressMode {
    ClampToEdge(0L),
    MirrorClampToEdge(1L),
    Repeat(2L),
    MirrorRepeat(3L),
    ClampToZero(4L),
    ClampToBorderColor(5L);

    public final long value;

    MTLSamplerAddressMode(long value) {
        this.value = value;
    }
}
