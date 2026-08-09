package com.metallum.client.metal.render.mtl;

public enum MTLCompareFunction {
    Never(0L),
    Less(1L),
    Equal(2L),
    LessEqual(3L),
    Greater(4L),
    NotEqual(5L),
    GreaterEqual(6L),
    Always(7L);

    public final long value;

    MTLCompareFunction(long value) {
        this.value = value;
    }
}
