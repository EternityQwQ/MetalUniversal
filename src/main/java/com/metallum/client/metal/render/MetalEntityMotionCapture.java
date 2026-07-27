package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Render-thread carrier for one ordinary-entity motion draw.
 *
 * <p>Minecraft 26.2 records model submits first and later batches their
 * geometry by render type. Object motion cannot therefore be represented by a
 * draw-global uniform unless each entity draw is deliberately split. This
 * carrier preserves the entity observation across those two phases and binds
 * it to the exact staged draw/execute-info pair that owns the vertices.</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalEntityMotionCapture {
    public record Diagnostics(
            int statesAttached,
            int entitySubmissionsMatched,
            int modelSubmitsCaptured,
            int modelBuildsMatched,
            int splitChecksMatched,
            int drawsAttached,
            int executesTransferred,
            int executesConsumed,
            int motionDrawsEncoded,
            // Subset of motionDrawsEncoded that came from the core/item family.
            // Dropped items, item frames and held items are the only source, so a
            // scene with dropped items in view and a zero here means the item
            // motion path is not reaching the interpolator.
            int itemMotionDrawsEncoded,
            // Subset of motionDrawsEncoded that came from the core/block family.
            // Falling blocks and block entities are the only source, so a scene
            // with a falling block in view and a zero here means the block motion
            // path is not reaching the interpolator.
            int blockMotionDrawsEncoded,
            @Nullable String lastMotionDrawSkip,
            @Nullable String lastVertexShader
    ) {
    }

    public record Sample(
            long objectId,
            long generation,
            Matrix4f currentObject,
            @Nullable Matrix4f previousObject
    ) {
        public Sample {
            currentObject = new Matrix4f(currentObject);
            previousObject = previousObject == null ? null : new Matrix4f(previousObject);
        }

        @Override
        public Matrix4f currentObject() {
            return new Matrix4f(currentObject);
        }

        @Override
        public @Nullable Matrix4f previousObject() {
            return previousObject == null ? null : new Matrix4f(previousObject);
        }

        public boolean hasPrevious() {
            return previousObject != null;
        }
    }

    private static final ThreadLocal<Sample> ENTITY_SUBMISSION = new ThreadLocal<>();
    private static final ThreadLocal<Sample> MODEL_BUILD = new ThreadLocal<>();
    private static final Map<Object, Sample> STATES = new IdentityHashMap<>();
    private static final Map<Object, Sample> SUBMITS = new IdentityHashMap<>();
    private static final Map<StagedVertexBuffer.Draw, Sample> DRAWS = new IdentityHashMap<>();
    private static final Map<StagedVertexBuffer.ExecuteInfo, Sample> EXECUTES = new IdentityHashMap<>();
    private static int statesAttached;
    private static int entitySubmissionsMatched;
    private static int modelSubmitsCaptured;
    private static int modelBuildsMatched;
    private static int splitChecksMatched;
    private static int drawsAttached;
    private static int executesTransferred;
    private static int executesConsumed;
    private static int motionDrawsEncoded;
    private static int itemMotionDrawsEncoded;
    private static int blockMotionDrawsEncoded;
    private static @Nullable String lastMotionDrawSkip;
    private static @Nullable String lastVertexShader;

    private MetalEntityMotionCapture() {
    }

    public static void beginFrame() {
        ENTITY_SUBMISSION.remove();
        MODEL_BUILD.remove();
        STATES.clear();
        SUBMITS.clear();
        DRAWS.clear();
        EXECUTES.clear();
        statesAttached = 0;
        entitySubmissionsMatched = 0;
        modelSubmitsCaptured = 0;
        modelBuildsMatched = 0;
        splitChecksMatched = 0;
        drawsAttached = 0;
        executesTransferred = 0;
        executesConsumed = 0;
        motionDrawsEncoded = 0;
        itemMotionDrawsEncoded = 0;
        blockMotionDrawsEncoded = 0;
        lastMotionDrawSkip = null;
        lastVertexShader = null;
    }

    public static void attachState(final Object state, final Sample sample) {
        if (state != null && sample != null) {
            STATES.put(state, sample);
            statesAttached++;
        }
    }

    public static void beginEntitySubmission(final Object state) {
        Sample sample = STATES.get(state);
        if (sample == null) {
            ENTITY_SUBMISSION.remove();
        } else {
            ENTITY_SUBMISSION.set(sample);
            entitySubmissionsMatched++;
        }
    }

    public static void endEntitySubmission() {
        ENTITY_SUBMISSION.remove();
    }

    public static void captureModelSubmit(final Object submit) {
        Sample sample = ENTITY_SUBMISSION.get();
        if (submit != null && sample != null) {
            SUBMITS.put(submit, sample);
            modelSubmitsCaptured++;
        }
    }

    public static void beginModelBuild(final Object submit) {
        beginBuild(submit, false);
    }

    /**
     * {@code ItemFeatureRenderer.buildGroup} walks its submit list twice — main
     * geometry first, then the enchantment foil — so the owning entity has to
     * survive the first pass. {@link #beginFrame()} bounds the map instead.
     */
    public static void beginItemBuild(final Object submit) {
        beginBuild(submit, true);
    }

    /**
     * Moving blocks are keyed by their {@code MovingBlockRenderState} rather than
     * by the submit record.
     *
     * <p>{@code MovingBlockFeatureRenderer.buildGroup} inlines its per-submit work
     * in the loop body, so the submit itself is only a local there. The render
     * state is reachable at both ends — it is a constructor argument of the submit
     * and the level argument of the {@code tesselateBlock} call — and one falling
     * block owns one render state, so it identifies the same thing.</p>
     *
     * <p>The owner is retained rather than consumed, because a single block model
     * can tesselate into the solid, cutout and translucent render types and a
     * future caller may bracket each separately. {@link #beginFrame()} clears the
     * map every frame, so retaining cannot leak across frames.</p>
     */
    public static void beginMovingBlockBuild(final Object renderState) {
        beginBuild(renderState, true);
    }

    private static void beginBuild(final Object submit, final boolean retainOwner) {
        Sample sample = retainOwner ? SUBMITS.get(submit) : SUBMITS.remove(submit);
        if (sample == null) {
            MODEL_BUILD.remove();
        } else {
            MODEL_BUILD.set(sample);
            modelBuildsMatched++;
        }
    }

    public static void endModelBuild() {
        MODEL_BUILD.remove();
    }

    public static boolean shouldSplitEntityDraw(final RenderPipeline pipeline) {
        Sample sample = MODEL_BUILD.get();
        if (sample == null || pipeline == null) {
            return false;
        }
        lastVertexShader = pipeline.getVertexShader().toString();
        boolean matched = MetalEntityMotionPipeline.isSplittableVertexShader(pipeline);
        if (matched) {
            splitChecksMatched++;
        }
        return matched;
    }

    public static void attachDraw(final StagedVertexBuffer.Draw draw) {
        Sample sample = MODEL_BUILD.get();
        if (draw != null && sample != null) {
            DRAWS.put(draw, sample);
            drawsAttached++;
        }
    }

    public static void transferExecute(
            final StagedVertexBuffer.Draw draw,
            final StagedVertexBuffer.ExecuteInfo executeInfo
    ) {
        Sample sample = DRAWS.remove(draw);
        if (sample != null && executeInfo != null) {
            EXECUTES.put(executeInfo, sample);
            executesTransferred++;
        }
    }

    @Nullable
    public static Sample takeExecute(final StagedVertexBuffer.ExecuteInfo executeInfo) {
        Sample sample = EXECUTES.remove(executeInfo);
        if (sample != null) {
            executesConsumed++;
        }
        return sample;
    }

    public static Diagnostics diagnostics() {
        return new Diagnostics(
                statesAttached,
                entitySubmissionsMatched,
                modelSubmitsCaptured,
                modelBuildsMatched,
                splitChecksMatched,
                drawsAttached,
                executesTransferred,
                executesConsumed,
                motionDrawsEncoded,
                itemMotionDrawsEncoded,
                blockMotionDrawsEncoded,
                lastMotionDrawSkip,
                lastVertexShader
        );
    }

    static void recordMotionDrawEncoded(final RenderPipeline source) {
        motionDrawsEncoded++;
        if (source != null) {
            switch (source.getVertexShader().getPath()) {
                case "core/item" -> itemMotionDrawsEncoded++;
                case "core/block" -> blockMotionDrawsEncoded++;
                default -> {
                    // core/entity carries no subset counter of its own; it is
                    // motionDrawsEncoded minus the two subsets.
                }
            }
        }
        lastMotionDrawSkip = null;
    }

    static void recordMotionDrawSkip(final String reason) {
        lastMotionDrawSkip = reason;
    }

    static Matrix4f objectCurrentToPrevious(final Sample sample) {
        Matrix4fc previous = sample.previousObject();
        Matrix4f inverseCurrent = sample.currentObject();
        if (previous == null || !inverseCurrent.invert().isFinite()) {
            return new Matrix4f();
        }
        return new Matrix4f(previous).mul(inverseCurrent);
    }
}
