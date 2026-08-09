package com.metallum.client.metal.render.mtl;

public enum MTLColorWriteMask {
    None(0L),
    Red(1L << 0),
    Green(1L << 1),
    Blue(1L << 2),
    Alpha(1L << 3),
    All(Red.value | Green.value | Blue.value | Alpha.value);

    public final long value;

    MTLColorWriteMask(long value) {
        this.value = value;
    }
}
