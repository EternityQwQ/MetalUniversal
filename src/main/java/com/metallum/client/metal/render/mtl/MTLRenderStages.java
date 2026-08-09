package com.metallum.client.metal.render.mtl;

public enum MTLRenderStages {
    Vertex(0L),
    Fragment(1L),
    Tile(2L),
    Object(3L),
    Mesh(4L);

    public final long value;

    MTLRenderStages(long value) {
        this.value = value;
    }
}
