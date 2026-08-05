package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLPrimitiveType {
    Point(0L),
    Line(1L),
    LineStrip(2L),
    Triangle(3L),
    TriangleStrip(4L),
    TriangleFan(5L);

    public final long value;

    MTLPrimitiveType(final long value) {
        this.value = value;
    }

    public static MTLPrimitiveType from(final com.mojang.blaze3d.vertex.VertexFormat.Mode mode) {
        return switch (mode) {
            case TRIANGLES, QUADS, LINES -> Triangle;
            case TRIANGLE_STRIP -> TriangleStrip;
            case DEBUG_LINES -> Line;
            case DEBUG_LINE_STRIP -> LineStrip;
            case POINTS -> Point;
            case TRIANGLE_FAN -> TriangleFan;
        };
    }
}
