package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * Shared screen-space motion contract for the Java and native render paths.
 *
 * <p>Motion is stored in normalized device coordinates and points from the
 * current top-left screen pixel to the corresponding previous pixel. X is
 * {@code previousNdc.x - currentNdc.x}; Y is {@code currentNdc.y -
 * previousNdc.y} because clip-space Y points up while framebuffer Y points
 * down. MetalFX converts these values to pixels with
 * {@code (inputWidth / 2, inputHeight / 2)}.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalMotionContract {
    static final float HOMOGENEOUS_EPSILON = 1.0E-6F;
    static final float MAX_REASONABLE_NDC_MOTION = 32.0F;

    private MetalMotionContract() {
    }

    record VertexMotion(
            Vector4f currentRasterClip,
            Vector2f currentUnjitteredNdc,
            Vector2f previousUnjitteredNdc,
            Vector2f motionNdc,
            boolean valid
    ) {
        VertexMotion {
            currentRasterClip = new Vector4f(currentRasterClip);
            currentUnjitteredNdc = new Vector2f(currentUnjitteredNdc);
            previousUnjitteredNdc = new Vector2f(previousUnjitteredNdc);
            motionNdc = new Vector2f(motionNdc);
        }

        @Override
        public Vector4f currentRasterClip() {
            return new Vector4f(currentRasterClip);
        }

        @Override
        public Vector2f currentUnjitteredNdc() {
            return new Vector2f(currentUnjitteredNdc);
        }

        @Override
        public Vector2f previousUnjitteredNdc() {
            return new Vector2f(previousUnjitteredNdc);
        }

        @Override
        public Vector2f motionNdc() {
            return new Vector2f(motionNdc);
        }
    }

    record MergedMotion(Vector2f motionNdc, boolean objectMotionUsed, boolean historyRejected) {
        MergedMotion {
            motionNdc = new Vector2f(motionNdc);
        }

        @Override
        public Vector2f motionNdc() {
            return new Vector2f(motionNdc);
        }
    }

    static VertexMotion projectVertex(
            final Vector4fc localVertex,
            final Matrix4fc currentCameraJittered,
            final Matrix4fc currentCameraUnjittered,
            final Matrix4fc currentObject,
            final Matrix4fc previousCameraUnjittered,
            final Matrix4fc previousObject
    ) {
        Matrix4f currentObjectToWorld = new Matrix4f(currentObject);
        Matrix4f previousObjectToWorld = new Matrix4f(previousObject);
        Matrix4f currentRaster = new Matrix4f(currentCameraJittered).mul(currentObjectToWorld);
        Matrix4f currentUnjittered = new Matrix4f(currentCameraUnjittered).mul(currentObjectToWorld);
        Matrix4f previousUnjittered = new Matrix4f(previousCameraUnjittered).mul(previousObjectToWorld);

        Vector4f rasterClip = new Vector4f(localVertex).mul(currentRaster);
        Vector4f currentClip = new Vector4f(localVertex).mul(currentUnjittered);
        Vector4f previousClip = new Vector4f(localVertex).mul(previousUnjittered);
        if (!isFinite(rasterClip) || !isFinite(currentClip) || !isFinite(previousClip)
                || !validHomogeneousW(currentClip.w) || !validHomogeneousW(previousClip.w)) {
            return invalid(rasterClip);
        }

        Vector2f currentNdc = new Vector2f(currentClip.x / currentClip.w, currentClip.y / currentClip.w);
        Vector2f previousNdc = new Vector2f(previousClip.x / previousClip.w, previousClip.y / previousClip.w);
        Vector2f motion = new Vector2f(
                previousNdc.x - currentNdc.x,
                currentNdc.y - previousNdc.y
        );
        if (!isFinite(currentNdc) || !isFinite(previousNdc) || !isFinite(motion)
                || Math.abs(motion.x) > MAX_REASONABLE_NDC_MOTION
                || Math.abs(motion.y) > MAX_REASONABLE_NDC_MOTION) {
            return invalid(rasterClip);
        }
        return new VertexMotion(rasterClip, currentNdc, previousNdc, motion, true);
    }

    static Vector2f motionVectorScale(final int inputWidth, final int inputHeight) {
        if (inputWidth <= 0 || inputHeight <= 0) {
            throw new IllegalArgumentException("Motion input dimensions must be positive");
        }
        return new Vector2f(inputWidth * 0.5F, inputHeight * 0.5F);
    }

    /**
     * Reference merge semantics shared by the numerical tests and the native
     * per-pixel merge shader. An invalid object sample never becomes a zero
     * velocity object; it falls back to camera motion and rejects history.
     */
    static MergedMotion merge(
            final Vector2f cameraMotion,
            final Vector2f objectMotion,
            final boolean objectValid,
            final boolean disoccluded
    ) {
        Vector2f selected = new Vector2f(cameraMotion);
        boolean objectUsed = false;
        boolean rejected = disoccluded;
        if (objectValid) {
            if (isFinite(objectMotion)
                    && Math.abs(objectMotion.x) <= MAX_REASONABLE_NDC_MOTION
                    && Math.abs(objectMotion.y) <= MAX_REASONABLE_NDC_MOTION) {
                selected.set(objectMotion);
                objectUsed = true;
            } else {
                rejected = true;
            }
        }
        if (!isFinite(selected)
                || Math.abs(selected.x) > MAX_REASONABLE_NDC_MOTION
                || Math.abs(selected.y) > MAX_REASONABLE_NDC_MOTION) {
            selected.zero();
            rejected = true;
        }
        return new MergedMotion(selected, objectUsed, rejected);
    }

    static boolean validHomogeneousW(final float w) {
        return Float.isFinite(w) && Math.abs(w) > HOMOGENEOUS_EPSILON && w > 0.0F;
    }

    private static VertexMotion invalid(final Vector4f rasterClip) {
        return new VertexMotion(
                rasterClip,
                new Vector2f(),
                new Vector2f(),
                new Vector2f(),
                false
        );
    }

    private static boolean isFinite(final Vector4fc vector) {
        return Float.isFinite(vector.x()) && Float.isFinite(vector.y())
                && Float.isFinite(vector.z()) && Float.isFinite(vector.w());
    }

    private static boolean isFinite(final Vector2f vector) {
        return Float.isFinite(vector.x) && Float.isFinite(vector.y);
    }
}
