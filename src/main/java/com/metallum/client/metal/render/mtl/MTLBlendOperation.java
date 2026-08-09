package com.metallum.client.metal.render.mtl;

public enum MTLBlendOperation {
    Add(0L),
    Subtract(1L),
    ReverseSubtract(2L),
    Min(3L),
    Max(4L);

    public final long value;

    MTLBlendOperation(long value) {
        this.value = value;
    }
}
