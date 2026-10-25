package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generation-owned Iris execution graph.
 *
 * <p>The graph is deliberately independent of Iris's OpenGL renderer. Iris
 * owns source resolution and directives; this class owns the Metal plans,
 * pipeline states, target-side transitions and execution order for one world
 * generation. A failed required binding is an error, never an empty pass.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalExecutionGraph implements AutoCloseable {
    private static final Pattern COMPUTE_BINDING = Pattern.compile(
            "layout\\s*\\(([^)]*\\bbinding\\s*=\\s*(\\d+)[^)]*)\\)\\s*"
                    + "(?:readonly\\s+|writeonly\\s+|coherent\\s+|volatile\\s+|restrict\\s+)*"
                    + "(uniform|buffer)\\s+([A-Za-z_]\\w*)(?:\\s+([A-Za-z_]\\w*))?"
    );
    private static final Pattern COMPUTE_LOCAL_SIZE = Pattern.compile(
            "local_size_x\\s*=\\s*(\\d+).*?local_size_y\\s*=\\s*(\\d+).*?local_size_z\\s*=\\s*(\\d+)",
            Pattern.DOTALL
    );

    /** Dedupes the one-line probe for legacy sampler targets a generation does not own. */
    private static final Set<String> REPORTED_MISSING_SAMPLER_TARGETS = new HashSet<>();

    enum Stage {
        SETUP(ProgramArrayId.Setup, TextureStage.SETUP, null),
        BEGIN(ProgramArrayId.Begin, TextureStage.BEGIN, "begin_pre"),
        SHADOW_COMPOSITE(ProgramArrayId.ShadowComposite, TextureStage.SHADOWCOMP, null),
        PREPARE(ProgramArrayId.Prepare, TextureStage.PREPARE, "prepare_pre"),
        DEFERRED(ProgramArrayId.Deferred, TextureStage.DEFERRED, "deferred_pre"),
        COMPOSITE(ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, "composite_pre"),
        FINAL(null, TextureStage.COMPOSITE_AND_FINAL, null);

        private final @Nullable ProgramArrayId arrayId;
        private final TextureStage textureStage;
        private final @Nullable String preFlipDirective;

        Stage(
                final @Nullable ProgramArrayId arrayId,
                final TextureStage textureStage,
                final @Nullable String preFlipDirective
        ) {
            this.arrayId = arrayId;
            this.textureStage = textureStage;
            this.preFlipDirective = preFlipDirective;
        }
    }

    record FlipTransition(BitSet readsFromAlt, BitSet stateAfter) {
        FlipTransition {
            readsFromAlt = (BitSet) readsFromAlt.clone();
            stateAfter = (BitSet) stateAfter.clone();
        }

        @Override
        public BitSet readsFromAlt() {
            return (BitSet) readsFromAlt.clone();
        }

        @Override
        public BitSet stateAfter() {
            return (BitSet) stateAfter.clone();
        }
    }

    /**
     * One planned raster pass. The flip snapshots are copied in <em>and</em>
     * out: {@code executeStage} aliases {@code state}/{@code shadowState} to
     * {@link #stateAfter()}/{@link #readsFromAlt()}, and the next frame's
     * {@code beginFrame} clears those graph fields in place. Without the
     * accessor copies, that clear also wiped the plan's bookmark, leaving every
     * later frame with an empty flip state (MakeUp's exposure history stopped
     * being canonicalized back to {@code main} and its output went black).
     * Mirrors {@link FlipTransition}'s defensive accessors.
     */
    record RasterPlan(
            Stage stage,
            int index,
            String name,
            IrisMetalGlslLinker.LinkedRasterProgram program,
            int[] drawBuffers,
            BitSet readsFromAlt,
            BitSet stateAfter,
            String uniformToken
    ) {
        RasterPlan {
            drawBuffers = drawBuffers.clone();
            readsFromAlt = (BitSet) readsFromAlt.clone();
            stateAfter = (BitSet) stateAfter.clone();
        }

        @Override
        public int[] drawBuffers() {
            return drawBuffers.clone();
        }

        @Override
        public BitSet readsFromAlt() {
            return (BitSet) readsFromAlt.clone();
        }

        @Override
        public BitSet stateAfter() {
            return (BitSet) stateAfter.clone();
        }
    }

    private record ComputeBinding(int binding, String declarationKind, String type, String name) {
        boolean image() {
            return type.contains("image");
        }

        boolean sampler() {
            return type.contains("sampler") || type.contains("texture");
        }

        boolean buffer() {
            return declarationKind.equals("buffer") || (!image() && !sampler());
        }
    }

    private record ComputePlan(
            @Nullable Stage stage,
            TextureStage textureStage,
            int index,
            ComputeSource source,
            IrisMetalProgramFrontend.ComputeProgram program,
            IrisMetalGlslLinker.LinkedComputeProgram linked,
            List<ComputeBinding> bindings,
            @Nullable BitSet readsFromAlt,
            String token
    ) {
        ComputePlan {
            Objects.requireNonNull(textureStage, "textureStage");
            Objects.requireNonNull(linked, "linked");
            bindings = List.copyOf(bindings);
            readsFromAlt = readsFromAlt == null ? null : (BitSet) readsFromAlt.clone();
        }

        @Override
        public @Nullable BitSet readsFromAlt() {
            return readsFromAlt == null ? null : (BitSet) readsFromAlt.clone();
        }
    }

    private record OrderedOperation(
            @Nullable RasterPlan raster,
            @Nullable ComputePlan compute
    ) {
        OrderedOperation {
            if ((raster == null) == (compute == null)) {
                throw new IllegalArgumentException("An Iris graph operation must contain exactly one plan");
            }
        }

        int index() {
            return raster == null ? compute.index() : raster.index();
        }
    }

    private final int generation;
    private final ProgramSet programSet;
    private final IrisMetalWorldPrograms programs;
    private final int targetCount;
    private final EnumMap<Stage, List<RasterPlan>> rasterPlans = new EnumMap<>(Stage.class);
    private final EnumMap<Stage, List<ComputePlan>> computePlans = new EnumMap<>(Stage.class);
    private final List<RasterPlan> shadowRasterPlans = new ArrayList<>();
    private final EnumMap<Stage, List<OrderedOperation>> orderedOperations = new EnumMap<>(Stage.class);
    private final Map<RasterPlan, MetalCompiledRenderPipeline> rasterPipelines = new IdentityHashMap<>();
    private final Map<RasterPlan, MetalCompiledRenderPipeline> shadowRasterPipelines = new IdentityHashMap<>();
    private final Map<ComputePlan, MetalComputePipeline> computePipelines = new IdentityHashMap<>();
    private final List<ComputePlan> finalComputePlans = new ArrayList<>();
    /** Top-level {@code shaders/shadow.csh}: dispatched at the shadow-map extent at frame start. */
    private final List<ComputePlan> shadowComputes = new ArrayList<>();
    private @Nullable RasterPlan finalPlan;
    private @Nullable MetalCompiledRenderPipeline finalPipeline;
    private @Nullable IrisMetalCenterDepthSampler centerDepthSampler;
    private BitSet state = new BitSet();
    private BitSet shadowState = new BitSet();
    private boolean shadowFullClearRequired = true;
    private final Set<String> skippedPasses = new HashSet<>();
    private final Set<String> stripPassesHitThisFrame = new HashSet<>();
    private final Set<String> reportedShadowReceipts = new HashSet<>();
    /** Passes whose declared-but-inactive sampler skip was already reported (plan names). */
    private final Set<String> reportedInactiveSamplers = new HashSet<>();
    private @Nullable IrisMetalRuntimeReceipts receipts;
    private int stripIndex;
    private boolean warnedZeroVl;
    private boolean warnedZeroBloom;
    private boolean warnedScaledFinalCopy;
    private boolean warnedDebugViewBlitter;
    /** View strings already receipted into the probe file, one line each. */
    private final Set<String> recordedDebugViewTargets = new HashSet<>();
    /** Flip/side receipts already written to the probe (content-deduped). */
    private final Set<String> recordedFlipReceipts = new LinkedHashSet<>();
    /** Frames begun this generation; labels the canonicalization receipts. */
    private int frameNumber;
    private @Nullable MetalDevice preparedDevice;
    private @Nullable IrisMetalDebugViewBlitter debugViewBlitter;
    private @Nullable GpuFormat debugViewBlitterFormat;
    private @Nullable MetalDevice debugViewBlitterDevice;
    private boolean prepared;
    private boolean closed;

    IrisMetalExecutionGraph(
            final int generation,
            final ProgramSet programSet,
            final IrisMetalWorldPrograms programs,
            final int targetCount
    ) {
        if (generation <= 0 || targetCount <= 0) {
            throw new IllegalArgumentException("Invalid Iris execution graph identity");
        }
        this.generation = generation;
        this.programSet = Objects.requireNonNull(programSet, "programSet");
        this.programs = Objects.requireNonNull(programs, "programs");
        if (programs.generation() != generation) {
            throw new IllegalArgumentException("Execution graph crossed program generation");
        }
        this.targetCount = targetCount;
        for (Stage stage : Stage.values()) {
            rasterPlans.put(stage, new ArrayList<>());
            computePlans.put(stage, new ArrayList<>());
            orderedOperations.put(stage, new ArrayList<>());
        }
        plan();
    }

    private void plan() {
        for (ComputeSource source : programSet.getSetup()) {
            if (source != null && source.isValid()) {
                computePlans.get(Stage.SETUP).add(
                        planCompute(Stage.SETUP, TextureStage.SETUP, -1, source, null)
                );
            }
        }

        BitSet current = new BitSet(targetCount);
        for (Stage stage : new Stage[]{
                Stage.BEGIN, Stage.PREPARE, Stage.DEFERRED, Stage.COMPOSITE
        }) {
            if (stage.preFlipDirective != null) {
                applyPreFlips(current, programSet.getPackDirectives()
                        .getExplicitFlips(stage.preFlipDirective), targetCount);
            }
            ProgramSource[] sources = programSet.getComposite(stage.arrayId);
            ComputeSource[][] computes = programSet.getCompute(stage.arrayId);
            int count = Math.max(sources.length, computes.length);
            for (int index = 0; index < count; index++) {
                if (index < computes.length && computes[index] != null) {
                    for (ComputeSource compute : computes[index]) {
                        if (compute != null && compute.isValid()) {
                            computePlans.get(stage).add(planCompute(
                                    stage, stage.textureStage, index, compute, null
                            ));
                        }
                    }
                }
                if (index >= sources.length) {
                    continue;
                }
                ProgramSource source = sources[index];
                if (source == null || !source.isValid()) {
                    continue;
                }
                int[] drawBuffers = validateDrawBuffers(
                        source.getName(), source.getDirectives().getDrawBuffers()
                );
                FlipTransition transition = transition(
                        current,
                        drawBuffers,
                        source.getDirectives().getExplicitFlips(),
                        targetCount
                );
                current = transition.stateAfter();
                String token = token(stage, index, source.getName());
                IrisMetalGlslLinker.LinkedRasterProgram linked = programs.composite(
                        source, stage.textureStage
                );
                rasterPlans.get(stage).add(new RasterPlan(
                        stage, index, source.getName(), linked, drawBuffers,
                        transition.readsFromAlt(), transition.stateAfter(), token
                ));
            }
        }
        ComputeSource[] shadowComputeSources = programSet.getShadowCompute();
        for (int index = 0; index < shadowComputeSources.length; index++) {
            ComputeSource source = shadowComputeSources[index];
            if (source != null && source.isValid()) {
                shadowComputes.add(planCompute(
                        null, TextureStage.GBUFFERS_AND_SHADOW, index, source, null
                ));
            }
        }
        BitSet shadowCurrent = new BitSet();
        applyPreFlips(
                shadowCurrent,
                programSet.getPackDirectives().getExplicitFlips("shadowcomp_pre"),
                shadowTargetCount()
        );
        ComputeSource[][] shadowComputesBySlot = programSet.getCompute(ProgramArrayId.ShadowComposite);
        ProgramSource[] shadowSources = programSet.getComposite(ProgramArrayId.ShadowComposite);
        int shadowCount = Math.max(shadowSources.length, shadowComputesBySlot.length);
        for (int index = 0; index < shadowCount; index++) {
            // Snapshot shared by this slot's compute and raster programs: the
            // compute declarations at a slot run before that slot's raster and
            // must sample the same ping-pong sides it does.
            BitSet slotReads = (BitSet) shadowCurrent.clone();
            if (index < shadowComputesBySlot.length && shadowComputesBySlot[index] != null) {
                for (ComputeSource compute : shadowComputesBySlot[index]) {
                    if (compute != null && compute.isValid()) {
                        computePlans.get(Stage.SHADOW_COMPOSITE).add(planCompute(
                                Stage.SHADOW_COMPOSITE, TextureStage.SHADOWCOMP, index, compute, slotReads
                        ));
                    }
                }
            }
            if (index >= shadowSources.length) {
                continue;
            }
            ProgramSource source = shadowSources[index];
            if (source == null || !source.isValid()) {
                continue;
            }
            int[] drawBuffers = validateDrawBuffers(
                    source.getName(), source.getDirectives().getDrawBuffers(), shadowTargetCount()
            );
            FlipTransition transition = transition(
                    shadowCurrent, drawBuffers, source.getDirectives().getExplicitFlips(), shadowTargetCount()
            );
            shadowCurrent = transition.stateAfter();
            String token = token(Stage.SHADOW_COMPOSITE, index, source.getName());
            shadowRasterPlans.add(new RasterPlan(
                    Stage.SHADOW_COMPOSITE,
                    index,
                    source.getName(),
                    programs.composite(source, TextureStage.SHADOWCOMP),
                    drawBuffers,
                    transition.readsFromAlt(),
                    transition.stateAfter(),
                    token
            ));
        }
        shadowState = new BitSet();
        state = new BitSet();

        Optional<ProgramSource> finalSource = programSet.get(ProgramId.Final);
        if (finalSource.isPresent() && finalSource.get().isValid()) {
            ProgramSource source = finalSource.get();
            int[] drawBuffers = validateDrawBuffers(source.getName(), source.getDirectives().getDrawBuffers());
            IrisMetalGlslLinker.LinkedRasterProgram linked = programs.finalProgram();
            if (linked == null) {
                throw new IllegalStateException("Final program resolution disappeared during graph planning");
            }
            finalPlan = new RasterPlan(
                    Stage.COMPOSITE, -1, source.getName(), linked, drawBuffers,
                    current, current, token(Stage.COMPOSITE, -1, source.getName())
            );
        }

        for (ComputeSource source : programSet.getFinalCompute()) {
            if (source != null && source.isValid()) {
                finalComputePlans.add(planCompute(
                        Stage.FINAL, TextureStage.COMPOSITE_AND_FINAL, -1, source, null
                ));
            }
        }
        rebuildOrderedOperations();
    }

    private void rebuildOrderedOperations() {
        for (Stage stage : Stage.values()) {
            List<OrderedOperation> operations = orderedOperations.get(stage);
            operations.clear();
            if (stage == Stage.FINAL) {
                for (ComputePlan plan : finalComputePlans) {
                    operations.add(new OrderedOperation(null, plan));
                }
                continue;
            }

            List<ComputePlan> computes = computePlans.get(stage);
            List<RasterPlan> rasters = stage == Stage.SHADOW_COMPOSITE
                    ? shadowRasterPlans
                    : rasterPlans.get(stage);
            for (ComputePlan compute : computes) {
                operations.add(new OrderedOperation(null, compute));
            }
            for (RasterPlan raster : rasters) {
                operations.add(new OrderedOperation(raster, null));
            }
            operations.sort((left, right) -> {
                int byIndex = Integer.compare(left.index(), right.index());
                if (byIndex != 0) {
                    return byIndex;
                }
                // A compute declaration at a slot runs before that slot's
                // raster program, matching Iris's barrier/dispatch order.
                return left.compute() == null ? 1 : -1;
            });
        }
    }

    private ComputePlan planCompute(
            final @Nullable Stage stage,
            final TextureStage textureStage,
            final int index,
            final ComputeSource source,
            final @Nullable BitSet readsFromAlt
    ) {
        IrisMetalProgramFrontend.ComputeProgram patched = programs.compute(source, textureStage);
        IrisMetalGlslLinker.LinkedComputeProgram linked = IrisMetalGlslLinker.linkCompute(
                source.getName(), patched.patchedSource()
        );
        return new ComputePlan(
                stage, textureStage, index, source, patched, linked,
                reflectComputeBindings(linked.glsl(), source.getName()),
                readsFromAlt,
                token(stage, index, source.getName())
        );
    }

    void setCenterDepthSampler(final @Nullable IrisMetalCenterDepthSampler sampler) {
        ensureOpen();
        this.centerDepthSampler = sampler;
    }

    void prepare(
            final MetalDevice device,
            final IrisMetalWorldResources resources,
            final IrisMetalUniformValues uniformValues,
            final GpuFormat mainColorFormat
    ) {
        ensureOpen();
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(uniformValues, "uniformValues");
        this.preparedDevice = device;
        IrisMetalRenderTargets targets = resources.renderTargets();
        if (targets.colorTargets().targetCount() != targetCount) {
            throw new IllegalStateException("Execution graph target count changed within generation");
        }
        if (!prepared) {
            validatePlanExtents(targets);
            for (Stage stage : Stage.values()) {
                for (OrderedOperation operation : orderedOperations.get(stage)) {
                    if (operation.compute() != null) {
                        registerComputeUniforms(uniformValues, operation.compute());
                        computePipelines.put(
                                operation.compute(), compileCompute(device, operation.compute())
                        );
                        continue;
                    }
                    RasterPlan plan = operation.raster();
                    uniformValues.register(plan.uniformToken(), plan.name(), plan.program());
                    MetalCompiledRenderPipeline pipeline = stage == Stage.SHADOW_COMPOSITE
                            ? compileShadowRaster(
                                    device, resources.shadowTargets(), plan.program(), plan.name()
                            )
                            : compileRaster(device, targets, plan.program(), plan.name(), null);
                    if (stage == Stage.SHADOW_COMPOSITE) {
                        shadowRasterPipelines.put(plan, pipeline);
                    } else {
                        rasterPipelines.put(plan, pipeline);
                    }
                }
            }
            for (ComputePlan plan : shadowComputes) {
                registerComputeUniforms(uniformValues, plan);
                computePipelines.put(plan, compileCompute(device, plan));
            }
            if (finalPlan != null) {
                int[] finalBuffers = finalPlan.drawBuffers();
                if (finalBuffers.length != 1 || finalBuffers[0] != 0) {
                    throw new IllegalStateException(
                            "Iris final output must write exactly DRAWBUFFERS:0; got "
                                    + java.util.Arrays.toString(finalBuffers)
                    );
                }
                uniformValues.register(finalPlan.uniformToken(), finalPlan.name(), finalPlan.program());
                finalPipeline = compileRaster(
                        device, targets, finalPlan.program(), finalPlan.name(), mainColorFormat
                );
            }
            prepared = true;
        }
        if (finalPlan != null && mainColorFormat == null) {
            throw new IllegalArgumentException("Final Iris pass requires a main color format");
        }
    }

    void executeSetup(final IrisMetalWorldResources resources) {
        executeStage(Stage.SETUP, resources);
    }

    void executeBegin(final IrisMetalWorldResources resources) {
        executeStage(Stage.BEGIN, resources);
    }

    /**
     * Upstream {@code CompositeRenderer.recalculateSizes} rejects a pass whose
     * draw buffers disagree on size ("Pass widths must match"). Surface the
     * same failure during {@code prepare} so a mis-scaled pack fails before
     * any Metal attachment validation, not in the middle of a frame.
     */
    private void validatePlanExtents(final IrisMetalRenderTargets targets) {
        for (List<RasterPlan> plans : rasterPlans.values()) {
            for (RasterPlan plan : plans) {
                validatePlanExtent(targets, plan.name(), plan.drawBuffers());
            }
        }
        if (finalPlan != null) {
            validatePlanExtent(targets, finalPlan.name(), finalPlan.drawBuffers());
        }
    }

    private static void validatePlanExtent(
            final IrisMetalRenderTargets targets,
            final String name,
            final int[] drawBuffers
    ) {
        int width = targets.targetWidth(drawBuffers[0]);
        int height = targets.targetHeight(drawBuffers[0]);
        for (int slot = 1; slot < drawBuffers.length; slot++) {
            int slotWidth = targets.targetWidth(drawBuffers[slot]);
            int slotHeight = targets.targetHeight(drawBuffers[slot]);
            if (slotWidth != width || slotHeight != height) {
                throw new IllegalStateException(
                        "Iris pass " + name + ": Pass widths must match (colortex" + drawBuffers[0]
                                + " " + width + "x" + height + " vs colortex" + drawBuffers[slot]
                                + " " + slotWidth + "x" + slotHeight + ")"
                );
            }
        }
    }

    void executePrepare(final IrisMetalWorldResources resources) {
        executeStage(Stage.PREPARE, resources);
    }

    void executeDeferred(final IrisMetalWorldResources resources) {
        executeStage(Stage.DEFERRED, resources);
    }

    void executeComposite(final IrisMetalWorldResources resources) {
        if (MetalDebugSwitches.SKIP_POST) {
            return;
        }
        executeStage(Stage.COMPOSITE, resources);
    }

    void executeShadowComposite(final IrisMetalWorldResources resources, final boolean castersRendered) {
        ensurePrepared();
        IrisMetalShadowTargets shadows = resources.shadowTargets();
        if (shadows == null) {
            // Defensive: production generations always own shadow targets.
            // The stage must be a no-op, not an exception, even if shadowcomp
            // programs exist.
            return;
        }
        if (!castersRendered) {
            // The real caster pass did not run this frame (shadowPass=off,
            // no shadow render-list scope, fallback matrices, ...). Keep the
            // pre-M6.1 deterministic "nothing occludes" state so BSL's
            // shadowcomp cannot consume undefined contents.
            clearEmptyShadowTargets(shadows);
        }
        if (MetalDebugSwitches.NO_SHADOWS) {
            // Debug kill switch: leave the cleared maps and skip shadowcomp.
            return;
        }
        // Depth and shadowcolor mipmaps are generated after the caster pass
        // and before shadowcomp consumes the maps, mirroring upstream
        // ShadowRenderer.generateMipmaps() ahead of compositeRenderer.renderAll().
        MetalCommandEncoder encoder = activeEncoder();
        shadows.generateDepthMipmaps(encoder);
        shadows.generateColorMipmaps(encoder);

        IrisMetalRenderTargets targets = resources.renderTargets();
        int slot = Integer.MIN_VALUE;
        BitSet slotReads = null;
        for (OrderedOperation operation : orderedOperations.get(Stage.SHADOW_COMPOSITE)) {
            if (operation.index() != slot) {
                // One flip snapshot per slot: its computes and its raster
                // program must sample the same ping-pong sides.
                slot = operation.index();
                slotReads = shadowSlotReads(operation);
                shadows.publishFlipState(slotReads);
                shadowState = slotReads;
            }
            if (operation.compute() != null) {
                executeCompute(
                        operation.compute(), resources, slotReads,
                        targets.width(), targets.height()
                );
                continue;
            }
            RasterPlan plan = operation.raster();
            executeShadowRaster(plan, shadowRasterPipelines.get(plan), resources, shadows);
            shadows.publishFlipState(plan.stateAfter());
            shadowState = plan.stateAfter();
        }
        if (shadowReceiptsEnabled()) {
            recordShadowReceiptOnce("shadow.composite extent=" + targets.width() + "x" + targets.height()
                    + " passes=" + shadowRasterPlans.size()
                    + " computes=" + computePlans.get(Stage.SHADOW_COMPOSITE).size());
        }
    }

    /** The flip snapshot for one shadowcomp slot (compute-first slot ordering). */
    private BitSet shadowSlotReads(final OrderedOperation operation) {
        if (operation.raster() != null) {
            return operation.raster().readsFromAlt();
        }
        BitSet snapshot = operation.compute().readsFromAlt();
        if (snapshot == null) {
            throw new IllegalStateException(
                    "Shadow compute slot has no flip snapshot: "
                            + operation.compute().source().getName()
            );
        }
        return snapshot;
    }

    /**
     * Engine-space reverse-z far value both shadow depth maps are seeded with
     * at frame start. {@code MetalCommandEncoder.irisDepthClear} complements it
     * exactly once, so under a shaderpack the maps are cleared to {@code 1.0}
     * (standard-z "nothing occludes").
     */
    static final double SHADOW_FAR_ENGINE_DEPTH = 0.0;

    /**
     * Seeds shadowtex0 (depth with translucents) and shadowtex1 (no
     * translucents) to the far value at the start of the frame. The default
     * {@code clear} mode clears both maps directly; the
     * {@code -Dmetallum.iris.shadowSeed=copy} rollback clears shadowtex0 and
     * blits it into shadowtex1 (one more 16MB copy and blit encoder per frame).
     */
    void clearShadowDepth(final IrisMetalWorldResources resources) {
        IrisMetalShadowTargets shadows = resources.shadowTargets();
        if (shadows == null) {
            return;
        }
        seedShadowDepth(activeEncoder(), shadows, MetalDebugSwitches.SHADOW_SEED_CLEAR);
    }

    /**
     * Frame-start shadow depth seed. Both modes hand the encoder the same
     * engine-space value, so shadowtex1 always ends up holding what a copy of a
     * just-cleared shadowtex0 would have transferred.
     */
    private static void seedShadowDepth(
            final MetalCommandEncoder encoder,
            final IrisMetalShadowTargets shadows,
            final boolean clearBoth
    ) {
        encoder.clearDepthTexture(shadows.shadowDepthTexture(), SHADOW_FAR_ENGINE_DEPTH);
        if (clearBoth) {
            // shadowtex1 (no translucents) is a separate depth texture; packs
            // sample both, so it must start at the same far value instead of
            // holding undefined contents.
            encoder.clearDepthTexture(
                    shadows.shadowDepthNoTranslucentsTexture(), SHADOW_FAR_ENGINE_DEPTH
            );
        } else {
            shadows.captureNoTranslucentsDepth(encoder);
        }
    }

    /**
     * A: top-level {@code shaders/shadow.csh} dispatch at the shadow-map extent.
     * Upstream runs it in {@code beginLevelRendering}, before the shadowcolor
     * clears and before the caster pass, sampling/writing colortex and the
     * shadow maps through the {@code GBUFFERS_AND_SHADOW} texture stage.
     */
    void executeShadowComputes(final IrisMetalWorldResources resources) {
        ensurePrepared();
        IrisMetalShadowTargets shadows = resources.shadowTargets();
        if (shadows == null || shadowComputes.isEmpty()) {
            return;
        }
        for (ComputePlan plan : shadowComputes) {
            executeCompute(plan, resources, state, shadows.resolution(), shadows.resolution());
        }
    }

    /**
     * Clears the pack's shadowcolor targets on both ping-pong sides. A target
     * with {@code clear=false} is skipped unless this is the first clear of
     * the generation (upstream {@code isFullClearRequired} semantics: the
     * first frame and any resize clear every target). Upstream emits two
     * clears per target, one per side.
     */
    void clearShadowColors(final IrisMetalWorldResources resources) {
        IrisMetalShadowTargets shadows = resources.shadowTargets();
        if (shadows == null) {
            return;
        }
        clearShadowColors(shadows);
    }

    private void clearShadowColors(final IrisMetalShadowTargets shadows) {
        boolean fullClear = shadowFullClearRequired;
        int count = shadows.colorTargets().targetCount();
        int clearCount = 0;
        for (int index = 0; index < count; index++) {
            if (fullClear || shadows.clearsColor(index)) {
                clearCount++;
            }
        }
        int[] drawBuffers = new int[clearCount];
        Vector4fc[] clearColors = new Vector4fc[clearCount];
        int slot = 0;
        for (int index = 0; index < count; index++) {
            if (fullClear || shadows.clearsColor(index)) {
                drawBuffers[slot] = index;
                clearColors[slot] = shadows.colorClearColor(index);
                slot++;
            }
        }
        if (clearCount > 0) {
            clearShadowColorSide(shadows, drawBuffers, clearColors, false);
            clearShadowColorSide(shadows, drawBuffers, clearColors, true);
        }
        if (shadowReceiptsEnabled()) {
            recordShadowReceiptOnce("shadow.init resolution=" + shadows.resolution()
                    + " targets=" + count + " sides=main+alt fullClear=" + fullClear);
        }
        shadowFullClearRequired = false;
    }

    private void clearShadowColorSide(
            final IrisMetalShadowTargets shadows,
            final int[] drawBuffers,
            final Vector4fc[] clearColors,
            final boolean alt
    ) {
        MetalCommandEncoder encoder = activeEncoder();
        try (IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor =
                     shadows.createShadowColorClearDescriptor(
                             alt ? "Iris shadow-color-alt init" : "Iris shadow-color init",
                             drawBuffers, clearColors, alt
                     )) {
            encoder.createRenderPass(descriptor.descriptor());
            // This pass performs no draws, so its encoder is only materialized
            // by submitRenderPass. Submit while the descriptor's views are
            // still open: a try-with-resources would close them first and the
            // deferred materialization would then read a closed view.
            encoder.submitRenderPass();
        }
    }

    /**
     * Fallback clear used when the real caster pass did not run this frame
     * ({@code shadowPass=off}, no Sodium shadow render-list scope, fallback
     * matrices, ...), keeping the pre-M6.1 deterministic no-shadow state.
     */
    private void clearEmptyShadowTargets(final IrisMetalShadowTargets shadows) {
        seedShadowDepth(activeEncoder(), shadows, MetalDebugSwitches.SHADOW_SEED_CLEAR);
        clearShadowColors(shadows);
    }

    /**
     * Copies shadowtex0 into shadowtex1 after the opaque terrain and entity
     * caster passes and before translucent terrain, so water is absent from
     * shadowtex1 (the pack's "no translucent shadow" map).
     */
    void captureShadowNoTranslucents(final IrisMetalWorldResources resources) {
        IrisMetalShadowTargets shadows = resources.shadowTargets();
        if (shadows == null) {
            return;
        }
        shadows.captureNoTranslucentsDepth(activeEncoder());
    }

    void captureNoTranslucentsDepth(final IrisMetalWorldResources resources, final GpuTexture sceneDepth) {
        IrisMetalRenderTargets targets = resources.renderTargets();
        MetalCommandEncoder encoder = activeEncoder();
        targets.captureMainDepth(encoder, sceneDepth);
        targets.captureNoTranslucentsDepth(encoder, targets.mainDepthTexture());
    }

    /**
     * M1: captures depthtex1 from the Iris mainDepth only (the vanilla depth
     * was folded into it by {@link IrisMetalWorldBridge}'s first frame rewrite).
     */
    void captureNoTranslucentsDepthOnly(final IrisMetalWorldResources resources) {
        IrisMetalRenderTargets targets = resources.renderTargets();
        MetalCommandEncoder encoder = activeEncoder();
        targets.captureNoTranslucentsDepth(encoder, targets.mainDepthTexture());
    }

    void sampleCenterDepth(final GpuTextureView sceneDepth, final float frameTime) {
        if (centerDepthSampler != null) {
            centerDepthSampler.sample(sceneDepth, frameTime);
        }
    }

    void captureNoHandDepth(final IrisMetalWorldResources resources, final GpuTexture sceneDepth) {
        IrisMetalRenderTargets targets = resources.renderTargets();
        MetalCommandEncoder encoder = activeEncoder();
        targets.captureMainDepth(encoder, sceneDepth);
        targets.captureNoHandDepth(encoder, targets.mainDepthTexture());
    }

    /**
     * M1: captures depthtex2 from the Iris mainDepth only; see
     * {@link #captureNoTranslucentsDepthOnly}.
     */
    void captureNoHandDepthOnly(final IrisMetalWorldResources resources) {
        IrisMetalRenderTargets targets = resources.renderTargets();
        MetalCommandEncoder encoder = activeEncoder();
        targets.captureNoHandDepth(encoder, targets.mainDepthTexture());
    }

    void captureFinalDepth(final IrisMetalWorldResources resources, final GpuTexture sceneDepth) {
        resources.renderTargets().captureMainDepth(activeEncoder(), sceneDepth);
    }

    void executeFinal(
            final IrisMetalWorldResources resources,
            final GpuTextureView mainColor
    ) {
        // Stage-strip tiles are indexed per frame; reset at the frame's last
        // boundary so the next frame's first stage pass starts at entry 0.
        this.stripIndex = 0;
        this.stripPassesHitThisFrame.clear();
        ensurePrepared();
        final boolean stageStripped = !MetalDebugSwitches.STAGE_STRIP.isEmpty();
        if (stageStripped) {
            // Stage-strip mode: the stage passes tiled their outputs onto the
            // main target; leave those tiles untouched. The history snapshot
            // stays intact so the next beginFrame still canonicalizes it.
            return;
        }
        IrisMetalRenderTargets targets = resources.renderTargets();
        IrisMetalPingPongTargets colors = targets.colorTargets();
        colors.restore(state);
        if (MetalDebugSwitches.SKIP_POST) {
            copyColortex0ToMain(colors, targets, mainColor);
        } else {
            executeStage(Stage.FINAL, resources);
            colors.restore(state);
            if (finalPlan == null) {
                copyColortex0ToMain(colors, targets, mainColor);
            } else {
                executeRaster(finalPlan, finalPipeline, resources, mainColor);
            }
        }
        copyDebugView(resources, colors, targets, mainColor);
        targets.resetMipmaps();
        BitSet canonicalized = new BitSet(colors.targetCount());
        for (int target = state.nextSetBit(0); target >= 0; target = state.nextSetBit(target + 1)) {
            MetalGpuTexture source = colors.readTexture(target);
            MetalGpuTexture destination = colors.mainTexture(target);
            if (source != destination) {
                activeEncoder().copyTextureToTexture(
                        source, destination, 0, 0, 0, 0, 0,
                        colors.width(target), colors.height(target), "history.final"
                );
                canonicalized.set(target);
            }
        }
        if (MetalDebugSwitches.FLIP_TRACE) {
            recordFlipReceipt("flip canon frame=" + this.frameNumber
                    + " bits=" + formatBits(canonicalized));
        }
        if (consumesHistorySnapshot(stageStripped, MetalDebugSwitches.FRAME_HISTORY)) {
            // The read sides were just copied into main. Consume the snapshot
            // so the next beginFrame does not repeat the same blit; an early
            // exit (stage strip) leaves it for that boundary instead.
            state.clear();
            shadowState.clear();
        }
    }

    /**
     * Whether a completed {@code executeFinal} consumed the frame-history
     * snapshot it canonicalized. Only a non-stage-strip final pass may clear
     * it, and only while frame-boundary canonicalization is enabled; the
     * native-free frame-flow tests pin this contract.
     */
    static boolean consumesHistorySnapshot(final boolean stageStrip, final boolean frameHistory) {
        return !stageStrip && frameHistory;
    }

    /**
     * Fallback blit used by SKIP_POST and by packs without a final program.
     * Metal texture copies cannot scale, so the region is clamped to both the
     * colortex0 and main-target extents; a scaled-down colortex0 cannot be
     * upscaled and leaves the remainder of the main target stale (warned once).
     */
    private void copyColortex0ToMain(
            final IrisMetalPingPongTargets colors,
            final IrisMetalRenderTargets targets,
            final GpuTextureView mainColor
    ) {
        int sourceWidth = colors.width(0);
        int sourceHeight = colors.height(0);
        int copyWidth = Math.min(sourceWidth, targets.width());
        int copyHeight = Math.min(sourceHeight, targets.height());
        if ((copyWidth != targets.width() || copyHeight != targets.height()) && !warnedScaledFinalCopy) {
            warnedScaledFinalCopy = true;
            Metallum.LOGGER.warn(
                    "[metallum-iris] colortex0 is {}x{} while the main target is {}x{}; copying only the "
                            + "overlapping region (Metal texture copies do not scale)",
                    sourceWidth, sourceHeight, targets.width(), targets.height()
            );
        }
        activeEncoder().copyTextureToTexture(
                colors.readTexture(0), mainColor.texture(), 0, 0, 0, 0, 0, copyWidth, copyHeight,
                "colortex0.fallback"
        );
    }

    /**
     * Samples one of the pack color targets (or shadow targets) over the
     * normal final output, selected via
     * {@code metallum.iris.debug.view=colortexN} or {@code shadowcolorN}.
     *
     * <p>Always goes through {@link IrisMetalDebugViewBlitter}: a raw Metal
     * texture blit cannot convert pixel formats, and copying MakeUp's R16F
     * {@code gaux3} (2 bytes/pixel) into the wider main target triggered a GPU
     * fault and froze the device. There is deliberately no raw-copy fallback;
     * when the blitter cannot be built the frame is left untouched. Used only
     * for on-device artifact bisection; a no-op when the switch is unset.
     */
    private void copyDebugView(
            final IrisMetalWorldResources resources,
            final IrisMetalPingPongTargets colors,
            final IrisMetalRenderTargets targets,
            final GpuTextureView mainColor
    ) {
        int shadowIndex = debugShadowViewIndex();
        if (shadowIndex != -1) {
            IrisMetalShadowTargets shadows = resources.shadowTargets();
            if (shadowIndex < 0 || shadows == null
                    || shadowIndex >= shadows.colorTargets().targetCount()) {
                warnInvalidDebugView("shadowcolorN");
                return;
            }
            int size = Math.min(
                    shadows.resolution(), Math.min(targets.width(), targets.height())
            );
            // Shadow targets are RGBA16F (8 B/px) against the 4-byte main
            // target: the same illegal-blit class as gaux3.
            blitDebugView(
                    mainColor,
                    shadows.colorView(shadowIndex, shadowReadSnapshot()),
                    size,
                    size
            );
            return;
        }
        int index = debugViewIndex();
        if (index < 0) {
            if (index == DEBUG_VIEW_INVALID) {
                warnInvalidDebugView("colortexN");
            }
            return;
        }
        if (index >= colors.targetCount()) {
            warnInvalidDebugView("colortexN (out of range)");
            return;
        }
        blitDebugView(
                mainColor,
                colors.readView(index, state),
                Math.min(targets.width(), colors.width(index)),
                Math.min(targets.height(), colors.height(index))
        );
    }

    /**
     * Sampled debug-view blit. The caller runs after the final pass has been
     * submitted, so the blitter's own encoder is ordered after it on the
     * device queue.
     */
    private void blitDebugView(
            final GpuTextureView mainColor,
            final GpuTextureView source,
            final int width,
            final int height
    ) {
        IrisMetalDebugViewBlitter blitter = debugViewBlitter(mainColor.texture().getFormat());
        if (blitter == null) {
            return;
        }
        if (width <= 0 || height <= 0) {
            return;
        }
        blitter.blit(source, mainColor, width, height);
        // Receipt for the probe file: distinguishes "the overlay really drew"
        // from a black debug view that carries no information. The gain shows
        // the multiplier baked into the blit shader.
        String view = MetalDebugSwitches.VIEW;
        if (this.recordedDebugViewTargets.add(view)) {
            MetalProbeReport.record("debug view target=" + view + " blitter=ok gain="
                    + IrisMetalDebugViewBlitter.formatGain(blitter.gain()));
        }
    }

    /**
     * Lazily compiled sampled blit cached per device+destination format;
     * {@code null} when no device was prepared or the blitter failed to build.
     * A failed build is warned once and never falls back to a raw texture copy.
     */
    private @Nullable IrisMetalDebugViewBlitter debugViewBlitter(final GpuFormat destinationFormat) {
        MetalDevice device = this.preparedDevice;
        if (device == null) {
            warnDebugViewBlitter("no prepared Metal device");
            return null;
        }
        if (this.debugViewBlitter != null
                && this.debugViewBlitterFormat == destinationFormat
                && this.debugViewBlitterDevice == device) {
            return this.debugViewBlitter;
        }
        if (this.debugViewBlitter != null) {
            this.debugViewBlitter.close();
            this.debugViewBlitter = null;
        }
        try {
            this.debugViewBlitter = new IrisMetalDebugViewBlitter(
                    device, generation, destinationFormat, MetalDebugSwitches.VIEW_GAIN
            );
            this.debugViewBlitterFormat = destinationFormat;
            this.debugViewBlitterDevice = device;
            return this.debugViewBlitter;
        } catch (RuntimeException failure) {
            this.debugViewBlitter = null;
            this.debugViewBlitterFormat = null;
            this.debugViewBlitterDevice = null;
            warnDebugViewBlitter(failure.toString());
            return null;
        }
    }

    private void warnDebugViewBlitter(final String reason) {
        if (this.warnedDebugViewBlitter) {
            return;
        }
        this.warnedDebugViewBlitter = true;
        Metallum.LOGGER.warn(
                "[metallum-iris][debug] debug-view sampled blit unavailable ({});"
                        + " leaving the frame untouched (no raw-copy fallback)",
                reason
        );
        MetalProbeReport.record("debug view blitter unavailable: " + reason);
    }

    private void warnInvalidDebugView(final String expected) {
        if (!warnedInvalidDebugView) {
            warnedInvalidDebugView = true;
            Metallum.LOGGER.warn(
                    "[metallum-iris][debug] invalid debug view '{}' (expected {}); ignoring",
                    MetalDebugSwitches.VIEW, expected
            );
        }
    }

    /**
     * Parses {@code metallum.iris.debug.view}. Returns {@code -1} when unset,
     * the requested target index when valid, and {@link #DEBUG_VIEW_INVALID}
     * when the format does not match {@code colortex<decimal>}.
     */
    private int debugViewIndex() {
        String view = MetalDebugSwitches.VIEW;
        if (view.isEmpty()) {
            return -1;
        }
        if (!view.startsWith("colortex")) {
            return DEBUG_VIEW_INVALID;
        }
        try {
            int index = Integer.parseInt(view.substring("colortex".length()));
            if (index < 0) {
                return DEBUG_VIEW_INVALID;
            }
            return index;
        } catch (NumberFormatException malformed) {
            return DEBUG_VIEW_INVALID;
        }
    }

    /** Parses a {@code shadowcolorN} debug view; {@code -1} when it is not one. */
    private static int debugShadowViewIndex() {
        String view = MetalDebugSwitches.VIEW;
        if (!view.startsWith("shadowcolor")) {
            return -1;
        }
        try {
            int index = Integer.parseInt(view.substring("shadowcolor".length()));
            return index < 0 ? DEBUG_VIEW_INVALID : index;
        } catch (NumberFormatException malformed) {
            return DEBUG_VIEW_INVALID;
        }
    }

    private static final int DEBUG_VIEW_INVALID = -2;

    /** Dedupes the single warn for an unparseable/out-of-range debug view. */
    private static boolean warnedInvalidDebugView;

    private void executeStage(final Stage stage, final IrisMetalWorldResources resources) {
        ensurePrepared();
        if (stage.preFlipDirective != null) {
            applyPreFlips(state, programSet.getPackDirectives().getExplicitFlips(stage.preFlipDirective), targetCount);
        }
        IrisMetalRenderTargets targets = resources.renderTargets();
        for (OrderedOperation operation : orderedOperations.get(stage)) {
            if (operation.compute() != null) {
                executeCompute(
                        operation.compute(), resources, state,
                        targets.width(), targets.height()
                );
                continue;
            }
            RasterPlan plan = operation.raster();
            if (MetalDebugSwitches.shouldSkipPass(plan.name())) {
                // Diagnostic bisection: make this raster pass an identity
                // operation. Its output side is filled by copying the
                // input side so later passes never read stale or wrong
                // ping-pong contents; all flip bookkeeping matches the
                // normal branch exactly.
                IrisMetalPingPongTargets colors = resources.renderTargets().colorTargets();
                colors.restore(plan.readsFromAlt());
                for (int target : plan.drawBuffers()) {
                    MetalGpuTexture read = colors.readTexture(target);
                    MetalGpuTexture write = colors.writeTexture(target);
                    if (read != write) {
                        activeEncoder().copyTextureToTexture(read, write, 0, 0, 0, 0, 0,
                                colors.width(target), colors.height(target));
                    }
                }
                colors.restore(plan.stateAfter());
                state = plan.stateAfter();
                if (skippedPasses.add(plan.name())) {
                    Metallum.LOGGER.warn("[metallum-iris][debug] skipping raster pass '{}'", plan.name());
                }
                continue;
            }
            resources.renderTargets().colorTargets().restore(plan.readsFromAlt());
            recordRasterFlipReceipt(stage, plan, resources.renderTargets().colorTargets());
            executeRaster(plan, rasterPipelines.get(plan), resources, null);
            resources.renderTargets().colorTargets().restore(plan.stateAfter());
            state = plan.stateAfter();
            zeroCompositeLightmap(plan, resources);
            zeroBloom(plan, resources);
            stripTile(plan, resources);
        }
    }

    /**
     * Flip/side receipt for the MakeUp black-screen bisection. Called right
     * after {@code restore(plan.readsFromAlt())}, so the sides read here are the
     * ones the pass actually uses: composite reports colortex6's read/write
     * side, final reports colortex6/colortex1's read side. Deduplicated by
     * content and capped by {@link #recordFlipReceipt}.
     */
    private void recordRasterFlipReceipt(
            final Stage stage,
            final RasterPlan plan,
            final IrisMetalPingPongTargets colors
    ) {
        if (!MetalDebugSwitches.FLIP_TRACE) {
            return;
        }
        if (stage == Stage.COMPOSITE && plan.name().startsWith("composite") && colors.targetCount() > 6) {
            recordFlipReceipt("flip " + plan.name()
                    + " t6 read=" + flipSide(colors, 6, false)
                    + " write=" + flipSide(colors, 6, true));
        } else if (stage == Stage.FINAL && colors.targetCount() > 6) {
            recordFlipReceipt("flip " + plan.name()
                    + " t6 read=" + flipSide(colors, 6, false)
                    + " t1 read=" + flipSide(colors, 1, false));
        }
    }

    private static String flipSide(
            final IrisMetalPingPongTargets colors,
            final int target,
            final boolean write
    ) {
        MetalGpuTexture texture = write ? colors.writeTexture(target) : colors.readTexture(target);
        return texture == colors.mainTexture(target) ? "main" : "alt";
    }

    /** One content-deduplicated, capped probe line per distinct flip receipt. */
    private void recordFlipReceipt(final String line) {
        if (this.recordedFlipReceipts.size() >= FLIP_RECEIPT_LIMIT) {
            return;
        }
        if (this.recordedFlipReceipts.add(line)) {
            MetalProbeReport.record(line);
        }
    }

    /** Cap on distinct flip receipt lines, so a shifting flip state cannot flood the probe. */
    private static final int FLIP_RECEIPT_LIMIT = 20;

    private static String formatBits(final BitSet bits) {
        StringBuilder text = new StringBuilder("[");
        for (int index = bits.nextSetBit(0); index >= 0; index = bits.nextSetBit(index + 1)) {
            if (text.length() > 1) {
                text.append(", ");
            }
            text.append(index);
        }
        return text.append(']').toString();
    }

    /**
     * {@code metallum.iris.debug.zeroVl} probe: right after the composite pass
     * (composite0) writes colortex1, clear its read side to (0,0,0,1) so every
     * later pass sees an empty vl buffer. On-device artifact bisection only;
     * a no-op when the switch is off or the pack has a single color target.
     */
    private void zeroCompositeLightmap(final RasterPlan plan, final IrisMetalWorldResources resources) {
        if (!MetalDebugSwitches.ZERO_VL || !"composite".equals(plan.name())) {
            return;
        }
        IrisMetalRenderTargets targets = resources.renderTargets();
        IrisMetalPingPongTargets colors = targets.colorTargets();
        if (colors.targetCount() <= 1) {
            return;
        }
        MetalGpuTextureView lightmapView = new MetalGpuTextureView(colors.readTexture(1), 0, 1);
        Vector4fc clearColor = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(
                () -> "Iris zeroVl probe: " + plan.name()
        ).withColorAttachment(lightmapView, Optional.of(clearColor))
                .withRenderArea(new RenderPass.RenderArea(0, 0, colors.width(1), colors.height(1)));
        MetalCommandEncoder encoder = activeEncoder();
        // No draws: the encoder materializes the pass on submit, which must
        // happen while the descriptor's views are still open.
        encoder.createRenderPass(descriptor);
        encoder.submitRenderPass();
        if (!this.warnedZeroVl) {
            this.warnedZeroVl = true;
            Metallum.LOGGER.warn("[metallum-iris][debug] zeroVl probe: cleared colortex1 after '{}'", plan.name());
        }
    }

    /**
     * {@code metallum.iris.debug.zeroBloom} probe: after composite4 writes
     * colortex1, clear both ping-pong sides to (0,0,0,1) so every later pass
     * sees an empty bloom buffer regardless of flip side. On-device artifact
     * bisection only; a no-op when the switch is off or the pack has a single
     * color target.
     */
    private void zeroBloom(final RasterPlan plan, final IrisMetalWorldResources resources) {
        if (!MetalDebugSwitches.ZERO_BLOOM || !"composite4".equals(plan.name())) {
            return;
        }
        IrisMetalRenderTargets targets = resources.renderTargets();
        IrisMetalPingPongTargets colors = targets.colorTargets();
        if (colors.targetCount() <= 1) {
            return;
        }
        Vector4fc clearColor = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
        MetalGpuTextureView readSide = new MetalGpuTextureView(colors.readTexture(1), 0, 1);
        MetalGpuTextureView writeSide = new MetalGpuTextureView(colors.writeTexture(1), 0, 1);
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(
                () -> "Iris zeroBloom probe: " + plan.name()
        ).withColorAttachment(readSide, Optional.of(clearColor))
                .withColorAttachment(writeSide, Optional.of(clearColor))
                .withRenderArea(new RenderPass.RenderArea(0, 0, colors.width(1), colors.height(1)));
        MetalCommandEncoder encoder = activeEncoder();
        // No draws: submit while the descriptor's views are still open.
        encoder.createRenderPass(descriptor);
        encoder.submitRenderPass();
        if (!this.warnedZeroBloom) {
            this.warnedZeroBloom = true;
            Metallum.LOGGER.warn("[metallum-iris][debug] zeroBloom probe: cleared both colortex1 sides after '{}'", plan.name());
        }
    }

    /**
     * {@code metallum.iris.debug.stageStrip} probe: copy a center crop of each
     * listed pass's chosen target into a 2x3 grid of tiles on the main target,
     * in list order, one tile per frame occurrence. The frame's final blit is
     * suppressed in {@link #executeFinal}, so the tiles stay visible.
     */
    private void stripTile(final RasterPlan plan, final IrisMetalWorldResources resources) {
        List<MetalDebugSwitches.StripEntry> entries = MetalDebugSwitches.STAGE_STRIP;
        if (entries.isEmpty() || this.stripIndex >= entries.size()) {
            return;
        }
        IrisMetalRenderTargets targets = resources.renderTargets();
        int mainWidth = targets.width();
        int mainHeight = targets.height();
        if (mainWidth < 4 || mainHeight < 3) {
            return;
        }
        MetalDebugSwitches.StripEntry entry = entries.get(this.stripIndex);
        if (!entry.passName().equals(plan.name()) || !this.stripPassesHitThisFrame.add(plan.name())) {
            return;
        }
        // Consume the entry even if the copy itself cannot run, so the tile
        // cursor never stalls on an unusable entry.
        int tile = this.stripIndex;
        this.stripIndex++;
        IrisMetalPingPongTargets colors = targets.colorTargets();
        if (entry.targetIndex() >= colors.targetCount()) {
            return;
        }
        GpuTexture main = mainTargetTexture();
        if (main == null) {
            return;
        }
        // Crop the target's center band, clamped to the main-target tile grid:
        // a scaled target is smaller than the destination, and a copy cannot
        // upscale.
        int sourceWidth = colors.width(entry.targetIndex());
        int sourceHeight = colors.height(entry.targetIndex());
        int cropWidth = Math.min(sourceWidth / 2, mainWidth / 2);
        int cropHeight = Math.min(sourceHeight / 3, mainHeight / 3);
        if (cropWidth <= 0 || cropHeight <= 0) {
            return;
        }
        // 2 columns x 3 rows; crop the center band of the source target.
        activeEncoder().copyTextureToTexture(
                colors.readTexture(entry.targetIndex()), main, 0,
                (tile % 2) * (mainWidth / 2), (tile / 2) * cropHeight,
                sourceWidth / 4, sourceHeight / 4,
                cropWidth, cropHeight, "stageStrip"
        );
    }

    /** The same main-target texture {@link #executeFinal} blits into. */
    private GpuTexture mainTargetTexture() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameRenderer == null) {
            return null;
        }
        RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
        return target == null ? null : target.getColorTexture();
    }

    private void executeCompute(
            final ComputePlan plan,
            final IrisMetalWorldResources resources,
            final BitSet readsFromAlt,
            final int extentWidth,
            final int extentHeight
    ) {
        MetalComputePipeline pipeline = computePipelines.get(plan);
        if (pipeline == null) {
            throw new IllegalStateException(
                    "Iris compute plan has no compiled pipeline: " + plan.source().getName()
            );
        }
        try (MetalComputePass pass = activeEncoder().createComputePass()) {
            pass.setPipeline(pipeline);
            bindCompute(pass, plan, resources, readsFromAlt);
            dispatchCompute(pass, plan, extentWidth, extentHeight);
        }
    }

    private void bindCompute(
            final MetalComputePass pass,
            final ComputePlan plan,
            final IrisMetalWorldResources resources,
            final BitSet readsFromAlt
    ) {
        IrisMetalRenderTargets targets = resources.renderTargets();
        IrisMetalComputeResources computeResources = resources.computeResources();
        boolean waterShadow = plan.linked().resourceNames().contains("watershadow");
        for (ComputeBinding binding : plan.bindings()) {
            if (binding.buffer()) {
                if (computeResources == null) {
                    throw new IllegalStateException(
                            "Iris compute " + plan.source().getName()
                                    + " requires generation-owned SSBO binding " + binding.binding()
                                    + " but this resource set has no compute resources"
                    );
                }
                GpuBufferSlice slice = computeResources.storageBuffer(binding.binding());
                if (slice == null) {
                    throw new IllegalStateException(
                            "Iris compute " + plan.source().getName()
                                    + " is missing SSBO binding " + binding.binding()
                    );
                }
                pass.bindBuffer(binding.binding(), (MetalGpuBuffer) slice.buffer(), slice.offset());
                continue;
            }
            if (binding.image()) {
                MetalGpuTextureView image = computeResources == null
                        ? null
                        : computeResources.storageImage(binding.name());
                if (image == null) {
                    MetalRenderPass.TextureViewAndSampler target = textureBinding(
                            binding.name(), plan.textureStage(), targets, resources, readsFromAlt, waterShadow
                    );
                    image = target == null ? null : (MetalGpuTextureView) target.textureView();
                }
                if (image == null) {
                    throw new IllegalStateException(
                            "Iris compute " + plan.source().getName()
                                    + " is missing required storage image '" + binding.name() + "'"
                    );
                }
                pass.bindTextureView(binding.binding(), image);
            } else {
                MetalRenderPass.TextureViewAndSampler texture = computeResources == null
                        ? null
                        : computeResources.sampledImage(binding.name());
                if (texture == null) {
                    texture = textureBinding(
                            binding.name(), plan.textureStage(), targets, resources, readsFromAlt, waterShadow
                    );
                }
                if (texture == null) {
                    throw new IllegalStateException(
                            "Iris compute " + plan.source().getName()
                                    + " is missing required sampled image '" + binding.name() + "'"
                    );
                }
                pass.bindTextureView(binding.binding(), (MetalGpuTextureView) texture.textureView());
                pass.bindSampler(binding.binding(), ((MetalGpuSampler) texture.sampler()).nativeHandle());
            }
        }
        if (plan.linked().uniformBinding() >= 0) {
            com.mojang.blaze3d.buffers.GpuBufferSlice slice = uniformSlice(plan.token());
            if (slice == null) {
                throw new IllegalStateException(
                        "Iris compute " + plan.source().getName()
                                + " is missing its hoisted uniform block"
                );
            }
            pass.bindBuffer(
                    plan.linked().uniformBinding(),
                    (MetalGpuBuffer) slice.buffer(),
                    slice.offset()
            );
        }
    }

    private void dispatchCompute(
            final MetalComputePass pass,
            final ComputePlan plan,
            final int extentWidth,
            final int extentHeight
    ) {
        if (plan.source().getWorkGroups() != null) {
            org.joml.Vector3i groups = plan.source().getWorkGroups();
            pass.dispatchGroups(groups.x(), groups.y(), groups.z());
            recordShadowCompute(plan, extentWidth, extentHeight,
                    groups.x() + "x" + groups.y() + "x" + groups.z());
            return;
        }
        org.joml.Vector2f relative = plan.source().getWorkGroupRelative();
        float scaleX = relative == null ? 1.0F : relative.x();
        float scaleY = relative == null ? 1.0F : relative.y();
        int threadsX = Math.max(1, (int) Math.ceil(extentWidth * scaleX));
        int threadsY = Math.max(1, (int) Math.ceil(extentHeight * scaleY));
        pass.dispatchThreadsCovering(threadsX, threadsY, 1);
        recordShadowCompute(plan, extentWidth, extentHeight, "threads=" + threadsX + "x" + threadsY);
    }

    private void recordShadowCompute(
            final ComputePlan plan,
            final int extentWidth,
            final int extentHeight,
            final String groups
    ) {
        if (plan.textureStage() != TextureStage.GBUFFERS_AND_SHADOW
                && plan.textureStage() != TextureStage.SHADOWCOMP) {
            return;
        }
        if (shadowReceiptsEnabled()) {
            recordShadowReceiptOnce("shadow.compute name=" + plan.source().getName()
                    + " extent=" + extentWidth + "x" + extentHeight + " groups=" + groups);
        }
    }

    void attachReceipts(final IrisMetalRuntimeReceipts receipts) {
        ensureOpen();
        this.receipts = Objects.requireNonNull(receipts, "receipts");
    }

    /** True when a receipt sink is configured; keeps receipt strings out of the per-frame path. */
    private boolean shadowReceiptsEnabled() {
        return receipts != null && receipts.enabled();
    }

    private void recordShadowReceiptOnce(final String event) {
        IrisMetalRuntimeReceipts current = this.receipts;
        if (current != null && current.enabled() && reportedShadowReceipts.add(event)) {
            current.recordEvent(event);
        }
    }

    private void executeRaster(
            final RasterPlan plan,
            final @Nullable MetalCompiledRenderPipeline pipeline,
            final IrisMetalWorldResources resources,
            final @Nullable GpuTextureView overrideColor
    ) {
        if (pipeline == null) {
            throw new IllegalStateException("Iris raster plan has no compiled pipeline: " + plan.name());
        }
        IrisMetalRenderTargets targets = resources.renderTargets();
        try {
            Set<Integer> readTargets = colorSamplerTargets(plan.program());
            for (int target : plan.program().program().directives().getMipmappedBuffers()) {
                targets.enableReadMipmaps(target);
                activeEncoder().generateMipmaps(targets.colorTargets().readTexture(target));
            }
            MetalCommandEncoder encoder = activeEncoder();
            IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor;
            if (overrideColor != null) {
                RenderPassDescriptor direct = RenderPassDescriptor.create(
                        () -> "Iris final: " + plan.name()
                ).withColorAttachment(overrideColor, Optional.empty())
                        .withRenderArea(new RenderPass.RenderArea(0, 0, targets.width(), targets.height()));
                descriptor = new IrisMetalRenderTargets.RenderPassDescriptorWithViews(
                        direct, new MetalGpuTextureView[0]
                );
            } else {
                descriptor = targets.createWriteDescriptor(
                        "Iris " + plan.stage().name().toLowerCase() + ": " + plan.name(),
                        plan.drawBuffers(), null, false, null,
                        readTargets.stream().mapToInt(Integer::intValue).toArray()
                );
            }
            try {
                MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor.descriptor());
                pass.setCompiledPipeline(pipeline);
                bindRaster(pass, pipeline, plan, resources, targets);
                GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
                pass.setIndexBuffer(indices, RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type());
                pass.setVertexBuffer(0, net.irisshaders.iris.pathways.FullScreenQuadRenderer.INSTANCE.getQuad().slice());
                pass.drawIndexed(6, 1, 0, 0, 0);
            } finally {
                // Submit while the descriptor's views are still open: pending clears
                // are materialized against those views, and closing first would both
                // crash and mask the real failure from the try body.
                try {
                    encoder.submitRenderPass();
                } finally {
                    descriptor.close();
                }
            }
        } finally {
            targets.resetMipmaps();
        }
    }

    private void bindRaster(
            final MetalRenderPass pass,
            final MetalCompiledRenderPipeline pipeline,
            final RasterPlan plan,
            final IrisMetalWorldResources resources,
            final IrisMetalRenderTargets targets
    ) {
        if (plan.program().uniformBlockNames().contains(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME)) {
            com.mojang.blaze3d.buffers.GpuBufferSlice slice = uniformSlice(plan.uniformToken());
            if (slice == null) {
                throw new IllegalStateException("Missing uniform block for Iris pass " + plan.name());
            }
            pass.setUniform(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME, slice);
        }
        for (String block : plan.program().uniformBlockNames()) {
            if (!IrisMetalGlslLinker.UNIFORM_BLOCK_NAME.equals(block)) {
                throw new IllegalStateException(
                        "Iris pass " + plan.name() + " requires unsupported uniform block " + block
                );
            }
        }
        // A shaderpack's linked source declares every sampler a shared include
        // mentions, but only the ones the compiled MSL actually samples are
        // active (stageMask != 0; MetalRenderPass.pushDescriptor skips the
        // rest). Legacy compatibility declarations such as CR's gaux2/gaux4/
        // normals/specular/tex have no render-target mapping and are inactive;
        // requiring a binding for them aborted the pass before its draw and the
        // deferred clear then crashed in submitRenderPass, masking the real
        // error. Bind only active declarations and keep the strict failure for
        // an active sampler the binding table cannot resolve.
        Set<String> activeSampledImages = activeSampledImages(pipeline);
        boolean waterShadow = false;
        for (IrisMetalGlslLinker.SamplerDecl sampler : plan.program().samplers()) {
            if ("watershadow".equals(sampler.name())) {
                waterShadow = true;
                break;
            }
        }
        List<IrisMetalGlslLinker.SamplerDecl> declaredInactive = new ArrayList<>();
        for (IrisMetalGlslLinker.SamplerDecl sampler : activeSampledDeclarations(
                plan.program().samplers(), activeSampledImages
        )) {
            MetalRenderPass.TextureViewAndSampler binding = textureBinding(
                    sampler.name(), plan.stage().textureStage, targets, resources, plan.readsFromAlt(), waterShadow
            );
            if (binding == null) {
                throw new IllegalStateException(
                        "Iris pass " + plan.name() + " is missing required sampler '" + sampler.name() + "'"
                );
            }
            pass.bindTexture(sampler.name(), binding.textureView(), binding.sampler());
        }
        for (IrisMetalGlslLinker.SamplerDecl sampler : plan.program().samplers()) {
            if (sampler.sampled() && !activeSampledImages.contains(sampler.name())) {
                declaredInactive.add(sampler);
            }
        }
        if (!declaredInactive.isEmpty() && this.reportedInactiveSamplers.add(plan.name())) {
            Metallum.LOGGER.debug(
                    "[metallum-iris] pass '{}' skipped {} declared-but-inactive samplers: {}",
                    plan.name(), declaredInactive.size(), declaredInactive
            );
        }
        IrisMetalComputeResources computeResources = resources.computeResources();
        for (MetalCompiledRenderPipeline.ResourceBinding binding : pipeline.resources()) {
            if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.STORAGE_BUFFER) {
                if (computeResources == null) {
                    throw new IllegalStateException(
                            "Iris pass " + plan.name() + " requires generation-owned SSBO resources"
                    );
                }
                int logicalBinding = MetalCrossShaderCompiler.storageBufferLogicalBinding(binding.name());
                GpuBufferSlice slice = computeResources.storageBuffer(logicalBinding);
                if (slice == null) {
                    throw new IllegalStateException(
                            "Iris pass " + plan.name() + " is missing SSBO binding " + logicalBinding
                    );
                }
                pass.bindStorageBuffer(logicalBinding, slice);
            } else if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.STORAGE_IMAGE) {
                GpuTextureView view = storageImageBinding(
                        binding.name(), plan.stage().textureStage, targets, resources, plan.readsFromAlt()
                );
                if (view == null) {
                    throw new IllegalStateException(
                            "Iris pass " + plan.name() + " is missing storage image '" + binding.name() + "'"
                    );
                }
                pass.bindStorageImage(binding.name(), view);
            }
        }
    }

    private void executeShadowRaster(
            final RasterPlan plan,
            final MetalCompiledRenderPipeline pipeline,
            final IrisMetalWorldResources resources,
            final IrisMetalShadowTargets shadows
    ) {
        MetalCommandEncoder encoder = activeEncoder();
        IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor =
                shadows.createShadowCompositeDescriptor(
                        "Iris shadowcomp: " + plan.name(),
                        plan.drawBuffers(),
                        plan.readsFromAlt(),
                        0,
                        0,
                        shadows.resolution(),
                        shadows.resolution()
                );
        try {
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor.descriptor());
            pass.setCompiledPipeline(pipeline);
            bindRaster(pass, pipeline, plan, resources, resources.renderTargets());
            GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);
            pass.setIndexBuffer(indices, RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type());
            pass.setVertexBuffer(0, net.irisshaders.iris.pathways.FullScreenQuadRenderer.INSTANCE.getQuad().slice());
            pass.drawIndexed(6, 1, 0, 0, 0);
        } finally {
            // Submit while the descriptor's views are still open: pending clears
            // are materialized against those views, and closing first would both
            // crash and mask the real failure from the try body.
            try {
                encoder.submitRenderPass();
            } finally {
                descriptor.close();
            }
        }
    }

    private com.mojang.blaze3d.buffers.GpuBufferSlice uniformSlice(final String token) {
        return uniformValues == null ? null : uniformValues.slice(token);
    }

    private @Nullable IrisMetalUniformValues uniformValues;

    void attachUniformValues(final IrisMetalUniformValues values) {
        ensureOpen();
        this.uniformValues = Objects.requireNonNull(values, "values");
    }

    BitSet shadowReadSnapshot() {
        ensureOpen();
        return (BitSet) shadowState.clone();
    }

    /**
     * {@code shadow.csh} computes / shadowcomp computes plan counts, for the
     * M6.4 shadow-status probe line (one summary instead of per-pass events).
     */
    String shadowComputeCounts() {
        ensureOpen();
        return shadowComputes.size() + "/" + computePlans.get(Stage.SHADOW_COMPOSITE).size();
    }

    /**
     * The planned raster passes in execution order (setup/begin/prepare/
     * deferred/composite), then the final pass under stage {@code "FINAL"}.
     * Package-private so the native-free frame-flow tests can pin each pass's
     * draw buffers and ping-pong read sides without a Metal device.
     */
    List<PlannedPass> plannedPasses() {
        ensureOpen();
        List<PlannedPass> result = new ArrayList<>();
        for (Stage stage : new Stage[]{
                Stage.SETUP, Stage.BEGIN, Stage.PREPARE, Stage.DEFERRED, Stage.COMPOSITE
        }) {
            for (RasterPlan plan : rasterPlans.get(stage)) {
                result.add(new PlannedPass(
                        stage.name(), plan.name(), plan.drawBuffers(),
                        plan.readsFromAlt(), plan.stateAfter()
                ));
            }
        }
        if (finalPlan != null) {
            result.add(new PlannedPass(
                    "FINAL", finalPlan.name(), finalPlan.drawBuffers(),
                    finalPlan.readsFromAlt(), finalPlan.stateAfter()
            ));
        }
        return result;
    }

    /** One planned raster pass, exposed for the native-free frame-flow tests. */
    record PlannedPass(
            String stage,
            String name,
            int[] drawBuffers,
            BitSet readsFromAlt,
            BitSet stateAfter
    ) {
        PlannedPass {
            drawBuffers = drawBuffers.clone();
            readsFromAlt = (BitSet) readsFromAlt.clone();
            stateAfter = (BitSet) stateAfter.clone();
        }

        @Override
        public int[] drawBuffers() {
            return drawBuffers.clone();
        }

        @Override
        public BitSet readsFromAlt() {
            return (BitSet) readsFromAlt.clone();
        }

        @Override
        public BitSet stateAfter() {
            return (BitSet) stateAfter.clone();
        }
    }

    void beginFrame(final IrisMetalWorldResources resources, final Vector4fc fogColor) {
        ensureOpen();
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(fogColor, "fogColor");
        this.frameNumber++;
        canonicalizeHistory(resources);
        state.clear();
        shadowState.clear();
        resources.renderTargets().colorTargets().restore(state);
        IrisMetalShadowTargets shadows = resources.shadowTargets();
        if (shadows != null) {
            shadows.publishFlipState(shadowState);
            shadows.resetMipmaps();
        }
        resources.renderTargets().clearForFrame(activeEncoder(), fogColor);
        if (resources.computeResources() != null) {
            resources.computeResources().clearForFrame(activeEncoder());
        }
    }

    /**
     * Moves the previous frame's read side into each target's main texture
     * before the per-frame flip reset, using the previous frame's end
     * {@link #state} snapshot. A normal frame canonicalizes in
     * {@link #executeFinal} and consumes the snapshot there, so this boundary
     * pass is a no-op; the snapshot survives only when the final pass did not
     * run (stage strip, debug early exit or an aborted frame), and then this
     * pass keeps cross-frame readers correct. Without the fallback the next
     * frame's first reader samples the never-written main side: MakeUp's
     * {@code composite} would read a zero {@code gaux3} exposure history every
     * frame, collapse auto-exposure to {@code (1-k)*f}, and render black with
     * only saturated sky/sun pixels surviving.
     * {@code -Dmetallum.iris.frameHistory=off} restores the old behavior
     * (canonicalization only in {@link #executeFinal}); no snapshot is consumed
     * in that mode.
     */
    private void canonicalizeHistory(final IrisMetalWorldResources resources) {
        if (!MetalDebugSwitches.FRAME_HISTORY) {
            return;
        }
        IrisMetalPingPongTargets colors = resources.renderTargets().colorTargets();
        BitSet canonicalized = new BitSet(colors.targetCount());
        for (int target = state.nextSetBit(0); target >= 0; target = state.nextSetBit(target + 1)) {
            MetalGpuTexture source = colors.readTexture(target, state);
            MetalGpuTexture destination = colors.mainTexture(target);
            if (source != destination) {
                activeEncoder().copyTextureToTexture(
                        source, destination, 0, 0, 0, 0, 0,
                        colors.width(target), colors.height(target), "history.canon"
                );
                canonicalized.set(target);
            }
        }
        if (MetalDebugSwitches.FLIP_TRACE) {
            recordFlipReceipt("flip canon frame=" + this.frameNumber
                    + " bits=" + formatBits(canonicalized));
        }
    }

    private MetalRenderPass.TextureViewAndSampler textureBinding(
            final String name,
            final TextureStage stage,
            final IrisMetalRenderTargets targets,
            final IrisMetalWorldResources resources,
            final BitSet readsFromAlt
    ) {
        return textureBinding(name, stage, targets, resources, readsFromAlt, false);
    }

    /**
     * Legacy depth alias resolution, shared with the world/terrain bridges.
     *
     * <p>Upstream {@code IrisSamplers.addShadowSamplers} (pin 20e226b:143-152)
     * maps {@code watershadow} to shadowtex0 (depth with translucents) and
     * {@code shadow} to shadowtex1 (no translucents) when the program declares
     * {@code watershadow}; without it, {@code shadow} maps to shadowtex0. The
     * bridge paths have no per-program declaration view and pass
     * {@code waterShadow=false}, which matches the common legacy packs that
     * use {@code shadow} without {@code watershadow} (Bliss/Chocapic13).
     *
     * @return shadow depth index 0/1, or -1 when {@code name} is not a depth alias
     */
    static int legacyShadowDepth(final String name, final boolean waterShadow) {
        return switch (name) {
            case "watershadow" -> 0;
            case "shadow" -> waterShadow ? 1 : 0;
            default -> name.startsWith("shadowtex1") ? 1 : name.startsWith("shadowtex") ? 0 : -1;
        };
    }

    private MetalRenderPass.TextureViewAndSampler textureBinding(
            final String name,
            final TextureStage stage,
            final IrisMetalRenderTargets targets,
            final IrisMetalWorldResources resources,
            final BitSet readsFromAlt,
            final boolean waterShadow
    ) {
        MetalRenderPass.TextureViewAndSampler standard = null;
        IrisMetalShadowTargets shadows = resources.shadowTargets();
        if (name.equals(IrisMetalCenterDepthSampler.SAMPLER_NAME)
                || name.equals("centerDepthSmooth")) {
            standard = centerDepthSampler == null ? null : centerDepthSampler.binding();
        } else if (name.equals("noisetex")) {
            standard = resources.noiseTexture().binding();
        } else if (name.equals("depthtex0")) {
            standard = new MetalRenderPass.TextureViewAndSampler(targets.mainDepthView(), targets.depthSampler());
        } else if (name.equals("depthtex1")) {
            standard = new MetalRenderPass.TextureViewAndSampler(targets.noTranslucentsDepthView(), targets.depthSampler());
        } else if (name.equals("depthtex2")) {
            standard = new MetalRenderPass.TextureViewAndSampler(targets.noHandDepthView(), targets.depthSampler());
        } else if (shadows != null && (name.startsWith("shadowtex") || name.startsWith("shadowcolor")
                || name.equals("shadow") || name.equals("watershadow"))) {
            int shadowDepth = legacyShadowDepth(name, waterShadow);
            if (shadowDepth >= 0) {
                boolean comparison = !name.endsWith("HW");
                standard = new MetalRenderPass.TextureViewAndSampler(
                        shadowDepth == 0 ? shadows.shadowDepthView() : shadows.shadowDepthNoTranslucentsView(),
                        shadows.depthSampler(shadowDepth, comparison)
                );
            } else {
                int shadowColor = IrisMetalRenderTargets.shadowColorIndex(name);
                if (shadowColor >= 0 && shadowColor < shadowTargetCount()) {
                    standard = new MetalRenderPass.TextureViewAndSampler(
                            shadows.colorView(shadowColor, shadowState), shadows.colorSampler(shadowColor)
                    );
                }
            }
        } else {
            // The shared table also maps the legacy aliases (eg gaux3 ->
            // colortex6, composite -> colortex3) that pre-colortex packs declare
            // in composite/deferred/final programs; without it the strict
            // binding below threw "missing required sampler 'gaux3'".
            int color = IrisMetalRenderTargets.renderTargetIndex(name);
            if (color >= 0) {
                if (color >= targets.colorTargets().targetCount()) {
                    if (name.startsWith("colortex")) {
                        // Explicit colortexN stays strict: an out-of-range
                        // request is a pack/generation error, not an alias
                        // drift.
                        throw new IllegalStateException("Iris sampler target out of range: " + name);
                    }
                    // Legacy names can outrun a generation that owns fewer
                    // targets; mirror the world bridge: bind the shared white
                    // pixel so the draw stays alive without masking a valid
                    // target, recorded once per name.
                    if (REPORTED_MISSING_SAMPLER_TARGETS.add(name + ":" + color)) {
                        MetalProbeReport.record("graph sampler fallback name=" + name
                                + " target=" + color
                                + " owned=" + targets.colorTargets().targetCount());
                    }
                    standard = resources.whitePixel().binding();
                } else {
                    standard = new MetalRenderPass.TextureViewAndSampler(
                            targets.colorTargets().readView(color, readsFromAlt), targets.colorSampler(color)
                    );
                }
            } else {
                int image = parseSuffix(name, "colorimg");
                if (image >= 0) {
                    if (image >= targets.colorTargets().targetCount()) {
                        throw new IllegalStateException("Iris image target out of range: " + name);
                    }
                    standard = new MetalRenderPass.TextureViewAndSampler(
                            targets.colorTargets().writeView(image, readsFromAlt), targets.colorSampler(image)
                    );
                }
            }
        }
        if (standard == null && resources.computeResources() != null) {
            standard = resources.computeResources().sampledImage(name);
        }
        MetalRenderPass.TextureViewAndSampler override = resources.customTextures()
                .resolve(stage, name);
        return override == null ? standard : override;
    }

    private @Nullable GpuTextureView storageImageBinding(
            final String name,
            final TextureStage stage,
            final IrisMetalRenderTargets targets,
            final IrisMetalWorldResources resources,
            final BitSet readsFromAlt
    ) {
        IrisMetalComputeResources computeResources = resources.computeResources();
        if (computeResources != null) {
            MetalGpuTextureView custom = computeResources.storageImage(name);
            if (custom != null) {
                return custom;
            }
        }
        MetalRenderPass.TextureViewAndSampler standard = textureBinding(
                name, stage, targets, resources, readsFromAlt
        );
        return standard == null ? null : standard.textureView();
    }

    private MetalCompiledRenderPipeline compileRaster(
            final MetalDevice device,
            final IrisMetalRenderTargets targets,
            final IrisMetalGlslLinker.LinkedRasterProgram program,
            final String role,
            final @Nullable GpuFormat overrideFormat
    ) {
        int[] buffers = validateDrawBuffers(program.name(), program.program().drawBuffers());
        ColorTargetState[] colorTargets = new ColorTargetState[buffers.length];
        Map<String, GpuFormat> vertexFormats = new HashMap<>();
        DefaultVertexFormat.POSITION_TEX.getElements().forEach(element ->
                vertexFormats.put(element.name(), element.format())
        );
        for (int index = 0; index < buffers.length; index++) {
            colorTargets[index] = new ColorTargetState(
                    Optional.empty(),
                    overrideFormat == null ? targets.colorTargets().format(buffers[index]) : overrideFormat,
                    ColorTargetState.WRITE_ALL
            );
        }
        try {
            MetalCompiledRenderPipeline pipeline = MetalCrossShaderCompiler.compileShaderpack(
                    device,
                    "iris/gen" + generation + "/graph/" + role + "/" + program.name(),
                    program.vertexGlsl(), program.fragmentGlsl(), null,
                    vertexFormats, false, false,
                    com.mojang.blaze3d.platform.PolygonMode.FILL,
                    PrimitiveTopology.QUADS,
                    new com.mojang.blaze3d.vertex.VertexFormat[]{DefaultVertexFormat.POSITION_TEX},
                    (DepthStencilState) null,
                    colorTargets,
                    false
            );
            if (!pipeline.isValid()) {
                pipeline.close();
                throw new IllegalStateException("Invalid Metal pipeline for Iris pass " + program.name());
            }
            return pipeline;
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to compile Iris pass " + program.name(), failure);
        }
    }

    private MetalCompiledRenderPipeline compileShadowRaster(
            final MetalDevice device,
            final @Nullable IrisMetalShadowTargets shadows,
            final IrisMetalGlslLinker.LinkedRasterProgram program,
            final String role
    ) {
        if (shadows == null) {
            throw new IllegalStateException("Missing generation-owned shadow targets for " + role);
        }
        int[] buffers = validateDrawBuffers(program.name(), program.program().drawBuffers(), shadowTargetCount());
        ColorTargetState[] colorTargets = new ColorTargetState[buffers.length];
        Map<String, GpuFormat> vertexFormats = new HashMap<>();
        DefaultVertexFormat.POSITION_TEX.getElements().forEach(element ->
                vertexFormats.put(element.name(), element.format())
        );
        for (int index = 0; index < buffers.length; index++) {
            colorTargets[index] = new ColorTargetState(
                    Optional.empty(), shadows.colorFormat(buffers[index]), ColorTargetState.WRITE_ALL
            );
        }
        try {
            return MetalCrossShaderCompiler.compileShaderpack(
                    device,
                    "iris/gen" + generation + "/shadowcomp/" + role + "/" + program.name(),
                    program.vertexGlsl(), program.fragmentGlsl(), null,
                    vertexFormats, false, false,
                    com.mojang.blaze3d.platform.PolygonMode.FILL,
                    PrimitiveTopology.QUADS,
                    new com.mojang.blaze3d.vertex.VertexFormat[]{DefaultVertexFormat.POSITION_TEX},
                    null,
                    colorTargets,
                    false
            );
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to compile Iris shadow composite " + program.name(), failure);
        }
    }

    private static void registerComputeUniforms(
            final IrisMetalUniformValues uniformValues,
            final ComputePlan plan
    ) {
        if (plan.linked().uniformLayout().isEmpty()) {
            return;
        }
        uniformValues.registerCompute(
                plan.token(),
                plan.source().getName(),
                plan.linked().uniformLayout(),
                plan.linked().uniformBlockSize()
        );
    }

    private MetalComputePipeline compileCompute(final MetalDevice device, final ComputePlan plan) {
        try {
            return MetalComputePipeline.compileGlsl(
                    device,
                    "iris/gen" + generation + "/compute/" + plan.source().getName(),
                    plan.linked().glsl()
            );
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Failed to compile Iris compute " + plan.source().getName(), failure
            );
        }
    }

    private static List<ComputeBinding> reflectComputeBindings(final String source, final String name) {
        Matcher matcher = COMPUTE_BINDING.matcher(source);
        List<ComputeBinding> result = new ArrayList<>();
        Set<Integer> used = new LinkedHashSet<>();
        while (matcher.find()) {
            int binding = Integer.parseInt(matcher.group(2));
            String declarationKind = matcher.group(3);
            String type = matcher.group(4);
            String variable = matcher.group(5);
            if (IrisMetalGlslLinker.UNIFORM_BLOCK_NAME.equals(type)) {
                // The hoisted std140 block is bound separately at dispatch,
                // not through the pack SSBO/image lookup.
                continue;
            }
            if (variable == null || variable.isBlank()) {
                variable = type;
            }
            if (!used.add(binding)) {
                throw new IllegalStateException("Iris compute " + name + " reuses binding " + binding);
            }
            result.add(new ComputeBinding(binding, declarationKind, type, variable));
        }
        Matcher local = COMPUTE_LOCAL_SIZE.matcher(source);
        if (!local.find()) {
            throw new IllegalStateException("Iris compute " + name + " has no literal local_size declaration");
        }
        return List.copyOf(result);
    }

    private static String token(final @Nullable Stage stage, final int index, final String name) {
        return "iris:graph:" + (stage == null ? "SHADOW_TOP" : stage.name()) + ":" + index + ":" + name;
    }

    private Set<Integer> colorSamplerTargets(final IrisMetalGlslLinker.LinkedRasterProgram program) {
        Set<Integer> result = new LinkedHashSet<>();
        for (IrisMetalGlslLinker.SamplerDecl sampler : program.samplers()) {
            int target = parseSuffix(sampler.name(), "colortex");
            if (target >= 0) {
                result.add(target);
            }
        }
        return result;
    }

    private static int parseSuffix(final String name, final String prefix) {
        if (!name.startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * Names of the sampled images the compiled shaderpack pipeline actually
     * reads (per-stage compact remap leaves inactive declarations at
     * {@code stageMask == 0}; {@code MetalRenderPass.pushDescriptor} skips
     * those).
     */
    static Set<String> activeSampledImages(final MetalCompiledRenderPipeline pipeline) {
        Set<String> active = new HashSet<>();
        for (MetalCompiledRenderPipeline.ResourceBinding binding : pipeline.resources()) {
            if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE
                    && binding.stageMask() != 0) {
                active.add(binding.name());
            }
        }
        return active;
    }

    /** Declared samplers (not storage images) that the pipeline compiles as active. */
    static List<IrisMetalGlslLinker.SamplerDecl> activeSampledDeclarations(
            final List<IrisMetalGlslLinker.SamplerDecl> declared,
            final Set<String> activeSampledImages
    ) {
        List<IrisMetalGlslLinker.SamplerDecl> result = new ArrayList<>();
        for (IrisMetalGlslLinker.SamplerDecl sampler : declared) {
            if (sampler.sampled() && activeSampledImages.contains(sampler.name())) {
                result.add(sampler);
            }
        }
        return result;
    }

    private int shadowTargetCount() {
        return programSet.getPack().hasFeature(FeatureFlags.HIGHER_SHADOWCOLOR)
                ? net.irisshaders.iris.shaderpack.properties.PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS
                : net.irisshaders.iris.shaderpack.properties.PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_OF;
    }

    static int[] validateDrawBuffers(final String name, final int[] drawBuffers, final int targetCount) {
        if (drawBuffers == null || drawBuffers.length == 0) {
            throw new IllegalStateException("Iris pass " + name + " has no DRAWBUFFERS");
        }
        BitSet seen = new BitSet(targetCount);
        int[] copy = drawBuffers.clone();
        for (int target : copy) {
            if (target < 0 || target >= targetCount) {
                throw new IllegalStateException(
                        "Iris pass " + name + " DRAWBUFFERS target " + target + " is out of range"
                );
            }
            if (!seen.get(target)) {
                seen.set(target);
            } else {
                throw new IllegalStateException(
                        "Iris pass " + name + " repeats DRAWBUFFERS target " + target
                );
            }
        }
        return copy;
    }

    private int[] validateDrawBuffers(final String name, final int[] drawBuffers) {
        return validateDrawBuffers(name, drawBuffers, targetCount);
    }

    static FlipTransition transition(
            final BitSet before,
            final int[] drawBuffers,
            final Map<Integer, Boolean> explicitFlips,
            final int targetCount
    ) {
        BitSet reads = (BitSet) before.clone();
        BitSet after = (BitSet) before.clone();
        for (int target : drawBuffers) {
            if (target < 0 || target >= targetCount) {
                throw new IllegalArgumentException("DRAWBUFFERS target out of range: " + target);
            }
            if (explicitFlips.get(target) != Boolean.FALSE) {
                after.flip(target);
            }
        }
        explicitFlips.forEach((target, shouldFlip) -> {
            if (target == null || target < 0 || target >= targetCount) {
                throw new IllegalArgumentException("Explicit flip target out of range: " + target);
            }
            if (Boolean.TRUE.equals(shouldFlip)) {
                after.flip(target);
            }
        });
        return new FlipTransition(reads, after);
    }

    static void applyPreFlips(
            final BitSet state,
            final Map<Integer, Boolean> flips,
            final int targetCount
    ) {
        flips.forEach((target, shouldFlip) -> {
            if (target == null || target < 0 || target >= targetCount) {
                throw new IllegalArgumentException("Pre-flip target out of range: " + target);
            }
            if (Boolean.TRUE.equals(shouldFlip)) {
                state.flip(target);
            }
        });
    }

    private MetalCommandEncoder activeEncoder() {
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            throw new IllegalStateException("Iris execution graph has no active Metal device");
        }
        return device.createCommandEncoder();
    }

    private void ensurePrepared() {
        ensureOpen();
        if (!prepared) {
            throw new IllegalStateException("Iris execution graph generation " + generation + " is not prepared");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Iris execution graph generation " + generation + " is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (MetalComputePipeline pipeline : computePipelines.values()) {
            pipeline.close();
        }
        for (MetalCompiledRenderPipeline pipeline : rasterPipelines.values()) {
            pipeline.close();
        }
        for (MetalCompiledRenderPipeline pipeline : shadowRasterPipelines.values()) {
            pipeline.close();
        }
        computePipelines.clear();
        rasterPipelines.clear();
        shadowRasterPipelines.clear();
        finalPipeline = null;
        centerDepthSampler = null;
        if (debugViewBlitter != null) {
            debugViewBlitter.close();
            debugViewBlitter = null;
        }
        debugViewBlitterFormat = null;
        debugViewBlitterDevice = null;
        preparedDevice = null;
    }
}
