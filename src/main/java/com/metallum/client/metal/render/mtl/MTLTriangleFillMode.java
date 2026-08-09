package com.metallum.client.metal.render.mtl;

public enum MTLTriangleFillMode {
    Fill(0L),
    Lines(1L);

    public final long value;

    MTLTriangleFillMode(long value) {
        this.value = value;
    }
}
