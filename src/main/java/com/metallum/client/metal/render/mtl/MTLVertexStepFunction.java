package com.metallum.client.metal.render.mtl;

public enum MTLVertexStepFunction {
    PerVertex(0L),
    PerInstance(1L),
    PerPatch(2L),
    PerPatchControlPoint(3L);

    public final long value;

    MTLVertexStepFunction(long value) {
        this.value = value;
    }
}
