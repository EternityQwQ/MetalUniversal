package com.metallum.client.metal.render.mtl;

public enum MTLWinding {
    Clockwise(0L),
    CounterClockwise(1L);

    public final long value;

    MTLWinding(long value) {
        this.value = value;
    }
}
