package com.metallum.client.metal.render;

import org.joml.Matrix4f;

/**
 * OpenGL-equivalent depth range for Metal.
 *
 * <p>Metal's rasterizer clips clip-space z outside [0,1] and has no
 * {@code glDepthRange}; OpenGL maps clip z from [-1,1] into [0,1] instead.
 * While an Iris pack is in use the engine switches its projections to
 * OpenGL-style [-1,1] depth, so the backend must apply the same remap.
 *
 * <p>In JOML the matrix is addressed column-first: the output-z row is
 * {@code (m02, m12, m22, m32)} and the output-w row is
 * {@code (m03, m13, m23, m33)}. The remap replaces the z row with
 * {@code (z + w) / 2} so that clip z = -1 maps to 0 and z = +1 maps to 1
 * while x, y and w are untouched.
 */
public final class ProjectionDepthRemap {
    private ProjectionDepthRemap() {
    }

    public static void apply(final Matrix4f matrix) {
        float z0 = matrix.m02();
        float z1 = matrix.m12();
        float z2 = matrix.m22();
        float z3 = matrix.m32();
        float w0 = matrix.m03();
        float w1 = matrix.m13();
        float w2 = matrix.m23();
        float w3 = matrix.m33();
        matrix.m02(0.5F * z0 + 0.5F * w0);
        matrix.m12(0.5F * z1 + 0.5F * w1);
        matrix.m22(0.5F * z2 + 0.5F * w2);
        matrix.m32(0.5F * z3 + 0.5F * w3);
    }
}
