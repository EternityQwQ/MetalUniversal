package com.metallum.client.metal.render.mtl;

public enum MTLPrimitiveType {
    Point(0L),
    Line(1L),
    LineStrip(2L),
    Triangle(3L),
    TriangleStrip(4L),
    TriangleFan(5L);

    public final long value;

    MTLPrimitiveType(long value) {
        this.value = value;
    }
}
