package com.metallum.client.metal.render.mtl;

public enum MTLTextureUsage {
    Unknown(0L),
    ShaderRead(1L << 0),
    ShaderWrite(1L << 1),
    RenderTarget(1L << 2),
    PixelFormatView(1L << 3),
    ShaderAtomic(1L << 5);

    public final long value;

    MTLTextureUsage(long value) {
        this.value = value;
    }

    public static long of(long... usages) {
        long bits = 0L;
        for (long u : usages) {
            bits |= u;
        }
        return bits;
    }
}
