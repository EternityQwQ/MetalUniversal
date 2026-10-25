package com.metallum.client.metal.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectionDepthRemapTest {
    @Test
    void remapsPerspectiveZWithoutTouchingXorY() {
        Matrix4f projection = new Matrix4f().setPerspective(
                (float) Math.toRadians(70.0), 1.5F, 0.05F, 1000.0F, false
        );
        Vector4f point = new Vector4f(10.0F, 5.0F, -100.0F, 1.0F);

        Vector4f before = projection.transform(new Vector4f(point));
        float x = before.x / before.w;
        float y = before.y / before.w;
        float z = before.z / before.w;

        ProjectionDepthRemap.apply(projection);

        Vector4f after = projection.transform(new Vector4f(point));
        assertEquals(x, after.x / after.w, 1.0E-6);
        assertEquals(y, after.y / after.w, 1.0E-6);
        assertEquals((z + 1.0F) / 2.0F, after.z / after.w, 1.0E-6);
    }

    @Test
    void remapsOrthographicZForGuiStyleProjection() {
        Matrix4f projection = new Matrix4f().setOrtho(0.0F, 100.0F, 100.0F, 0.0F, -1000.0F, 1000.0F, false);
        Vector4f point = new Vector4f(0.0F, 0.0F, 500.0F, 1.0F);

        Vector4f before = projection.transform(new Vector4f(point));
        float z = before.z / before.w;

        ProjectionDepthRemap.apply(projection);

        Vector4f after = projection.transform(new Vector4f(point));
        assertEquals((z + 1.0F) / 2.0F, after.z / after.w, 1.0E-6);
        assertEquals(before.x / before.w, after.x / after.w, 1.0E-6);
        assertEquals(before.y / before.w, after.y / after.w, 1.0E-6);
    }
}
