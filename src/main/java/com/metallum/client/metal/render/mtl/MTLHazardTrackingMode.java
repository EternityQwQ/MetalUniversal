package com.metallum.client.metal.render.mtl;

public enum MTLHazardTrackingMode {
    Default(0L),
    Untracked(1L),
    Tracked(2L);

    public final long value;

    MTLHazardTrackingMode(long value) {
        this.value = value;
    }
}
