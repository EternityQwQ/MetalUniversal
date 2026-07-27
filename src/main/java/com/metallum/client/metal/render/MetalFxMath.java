package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2fc;

/**
 * Pure-math helpers shared by the MetalFX temporal pipeline. Everything here
 * is JOML-only — no Minecraft types — so the unit tests for Halton, jitter
 * and motion-vector scale can run without a client.
 *
 * <p>The {@code CameraRenderState}-dependent helpers (vertical field of view,
 * view-matrix extraction, aspect adjustment) live in MetalFxManager
 * directly, because they read fields whose names are pinned to the MC 26.2
 * camera state contract and would otherwise need an import that breaks the
 * "no MC types" invariant of this class.
 */
@Environment(EnvType.CLIENT)
final class MetalFxMath {
    /**
     * Scene-cut distance threshold (in blocks). A camera displacement larger
     * than this between two frames is treated as a teleport and forces a
     * history reset, since temporal reconstruction across a teleport produces
     * severe ghosting. The value matches the reference implementation's
     * measured envelope for first-person traversal plus a safety margin.
     */
    static final double SCENE_CUT_DISTANCE_BLOCKS = 32.0;

    /**
     * Halton phase count for a given render scale, following the MetalFX
     * guidance used by this project: {@code ceil(8 / scale^2)}. This yields
     * 8 phases at 1.0, 18 at 0.67, and 32 at 0.5 — denser jitter for lower
     * render scales so the upscaler sees more sub-pixel offsets per cycle.
     */
    static int phaseCount(final float scale) {
        float s = Math.max(scale, 0.01f);
        return (int) Math.ceil(8.0f / (s * s));
    }

    /**
     * Scales a display dimension by the render scale, rounding to the nearest
     * integer. Used both for the reported render resolution and for auxiliary
     * texture dimensions.
     */
    static int scaledDimension(final int dimension, final float scale) {
        return Math.max(1, Math.round(dimension * scale));
    }

    /**
     * Returns {@code true} only when every component of the matrix is finite.
     * Used to reject NaN/Inf-poisoned transforms before they enter the motion
     * state store — a single bad transform would corrupt history for every
     * object observed afterwards.
     */
    static boolean isFinite(final Matrix4fc m) {
        return Float.isFinite(m.m00()) && Float.isFinite(m.m01()) && Float.isFinite(m.m02()) && Float.isFinite(m.m03())
                && Float.isFinite(m.m10()) && Float.isFinite(m.m11()) && Float.isFinite(m.m12()) && Float.isFinite(m.m13())
                && Float.isFinite(m.m20()) && Float.isFinite(m.m21()) && Float.isFinite(m.m22()) && Float.isFinite(m.m23())
                && Float.isFinite(m.m30()) && Float.isFinite(m.m31()) && Float.isFinite(m.m32()) && Float.isFinite(m.m33());
    }

    /**
     * One-based Halton sample for the radical-inverse sequence {@code (base)}
     * at index {@code index} (1-based), centered to {@code [-0.5, 0.5)}.
     *
     * <p>One-based means {@code halton(1, b) == 0.5 - 0.5 = 0}; this matches
     * the Game Porting Toolkit convention where the first phase produces no
     * jitter. Centering keeps the sample symmetric around the pixel centre so
     * a static scene still emits zero motion while the phase advances.</p>
     */
    static float halton(final int index, final int base) {
        int i = Math.max(index, 1);
        float f = 1.0f;
        float result = 0.0f;
        while (i > 0) {
            f /= base;
            result += f * (i % base);
            i /= base;
        }
        return result - 0.5f;
    }

    /**
     * Pixel jitter for phase {@code phase} of a {@code phaseCount}-phase
     * Halton cycle. X uses base 2, Y uses base 3, both centered to
     * {@code [-0.5, 0.5)}. The jitter is in render-resolution pixels and is
     * passed unchanged to MetalFX.
     */
    static Vector2f pixelJitter(final int phase, final int phaseCount) {
        int p = ((phase % phaseCount) + phaseCount) % phaseCount;
        // Shift to 1-based so phase 0 maps to the first Halton sample.
        int oneBased = p + 1;
        return new Vector2f(halton(oneBased, 2), halton(oneBased, 3));
    }

    /**
     * Converts a pixel jitter (in render-resolution pixels, centered) to the
     * clip-space jitter applied to the projection matrix's clip coordinates:
     * {@code (2 * x / renderWidth, -2 * y / renderHeight)}.
     *
     * <p>The Y sign is negated because framebuffer Y points down while clip
     * space Y points up — the same convention as {@link MetalMotionContract}'s
     * motion-vector Y subtraction.</p>
     */
    static Vector2f clipJitter(final Vector2fc pixelJitter, final int renderWidth, final int renderHeight) {
        return new Vector2f(
                2.0f * pixelJitter.x() / renderWidth,
                -2.0f * pixelJitter.y() / renderHeight
        );
    }

    /**
     * Applies a clip-space jitter to a JOML perspective projection in place.
     *
     * <p>JOML's {@code PerspectiveMatrix} is right-handed: clip-space
     * {@code w = -z_view} (camera looks down -Z). The jitter offsets live in
     * the projection's {@code [2][0]} / {@code [2][1]} entries (JOML's
     * column-major {@code m20} / {@code m21}), which add to the clip-space X
     * and Y after the perspective divide. This mirrors the Game Porting
     * Toolkit's {@code applyProjectionJitter} but accounts for JOML's
     * right-handed convention rather than copying a left-handed matrix edit
     * verbatim — a sign error here makes a static scene report non-zero
     * motion and corrupts temporal accumulation.</p>
     */
    static void applyProjectionJitter(final Matrix4f projection, final Vector2fc clipJitter) {
        projection.m20(clipJitter.x());
        projection.m21(clipJitter.y());
    }

    /**
     * Motion-vector scale passed to MetalFX: {@code (inputWidth / 2,
     * inputHeight / 2)}. MetalFX multiplies the per-texel motion (in NDC)
     * by this to recover pixel-space motion. Delegates to
     * {@link MetalMotionContract#motionVectorScale} so the contract has a
     * single source of truth.
     */
    static Vector2f motionVectorScale(final int inputWidth, final int inputHeight) {
        return MetalMotionContract.motionVectorScale(inputWidth, inputHeight);
    }

    /**
     * Returns {@code true} when the camera displacement between two frames
     * exceeds the scene-cut threshold (a teleport). Such a jump invalidates
     * every previous-frame assumption and forces a history reset.
     */
    static boolean exceedsSceneCutDistance(final double dx, final double dy, final double dz) {
        return (dx * dx + dy * dy + dz * dz) > (SCENE_CUT_DISTANCE_BLOCKS * SCENE_CUT_DISTANCE_BLOCKS);
    }

    /**
     * Conservative dilation radius for the cutout-reactive mask, in the
     * 1–3 texel range. Lower render scales and larger jitter magnitudes get
     * a wider radius so the reactive band covers the extra sub-pixel spread
     * of cutout foliage edges. Capped at 3 because beyond that the band
     * starts suppressing valid temporal accumulation on solid geometry.
     */
    static int cutoutReactiveRadius(final float scale, final Vector2fc pixelJitter) {
        float jitterMag = Math.abs(pixelJitter.x()) + Math.abs(pixelJitter.y());
        int base = scale < 0.5f ? 2 : 1;
        int radius = base + (jitterMag > 0.3f ? 1 : 0);
        return Math.min(3, Math.max(1, radius));
    }

    private MetalFxMath() {
    }
}
