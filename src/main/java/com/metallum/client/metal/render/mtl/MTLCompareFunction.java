package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
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

    MTLCompareFunction(final long value) {
        this.value = value;
    }

    public static MTLCompareFunction from(final com.mojang.blaze3d.platform.DepthTestFunction op) {
        return switch (op) {
            case NO_DEPTH_TEST -> Always;
            case EQUAL_DEPTH_TEST -> Equal;
            case LESS_DEPTH_TEST -> Less;
            case LEQUAL_DEPTH_TEST -> LessEqual;
            case GREATER_DEPTH_TEST -> Greater;
        };
    }
}
