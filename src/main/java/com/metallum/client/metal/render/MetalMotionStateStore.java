package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Frame-transactional current/previous transform storage.
 *
 * <p>Renderers may observe an object more than once in a frame. The pending
 * map is replaced only after the frame's output was successfully encoded, so
 * a failed frame cannot destroy the previous transform needed by the next
 * valid frame. The caller supplies a generation in addition to an object id;
 * this prevents an id-reused object from inheriting unrelated history.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalMotionStateStore {
    record ObjectKey(long id, long generation) {
    }

    private final Map<ObjectKey, Matrix4f> previous = new HashMap<>();
    private final Map<ObjectKey, Matrix4f> pending = new HashMap<>();
    private boolean frameOpen;
    private long missingPreviousCount;

    void beginFrame() {
        pending.clear();
        frameOpen = true;
    }

    void observe(final ObjectKey key, final Matrix4fc currentTransform) {
        if (!frameOpen) {
            throw new IllegalStateException("Motion state observed outside a frame transaction");
        }
        if (key == null || currentTransform == null || !MetalFxMath.isFinite(currentTransform)) {
            return;
        }
        pending.put(key, new Matrix4f(currentTransform));
    }

    @Nullable
    Matrix4f previous(final ObjectKey key) {
        Matrix4f value = previous.get(key);
        if (value == null) {
            missingPreviousCount++;
            return null;
        }
        return new Matrix4f(value);
    }

    boolean hasPrevious(final ObjectKey key) {
        return previous.containsKey(key);
    }

    void commitSubmittedFrame() {
        if (!frameOpen) {
            return;
        }
        previous.clear();
        for (Map.Entry<ObjectKey, Matrix4f> entry : pending.entrySet()) {
            previous.put(entry.getKey(), new Matrix4f(entry.getValue()));
        }
        pending.clear();
        frameOpen = false;
    }

    void discardFrame() {
        pending.clear();
        frameOpen = false;
    }

    void reset() {
        boolean wasOpen = frameOpen;
        previous.clear();
        pending.clear();
        frameOpen = wasOpen;
    }

    long missingPreviousCount() {
        return missingPreviousCount;
    }

    void clearStatistics() {
        missingPreviousCount = 0L;
    }
}
