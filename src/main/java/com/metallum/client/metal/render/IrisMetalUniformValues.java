package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.textures.GpuTextureView;
import kroppeb.stareval.function.FunctionReturn;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Fills the generated {@code MetallumIrisUniforms} block once per frame.
 *
 * <p>Iris on GL feeds a shader pack through ~200 individually-registered
 * uniforms. B2-1 does not reproduce that: the translation lane collects every
 * loose uniform a pack's {@code gbuffers_terrain} declares into one std140
 * block (offsets computed by {@link MetalIrisShaderCompiler} and verified
 * against SPIR-V reflection by the offline gate), and this class writes values
 * into it from Iris's registered supplier graph.</p>
 *
 * <p>The production constructor consumes Iris's own {@link CustomUniforms}
 * graph. It contains both the official fixed inputs and the pack's
 * {@code variable.*}/{@code uniform.*} expressions, so values such as
 * {@code daytime}, {@code taaOffset} and {@code lightDirView} use the same
 * suppliers and evaluation order as Iris. The switch below is only for values
 * Iris marks externally managed by the active Mojang/Sodium draw. Sodium's own per-draw values
 * ({@code u_RegionOffset} and friends) are <b>not</b> here — they stay in the
 * push-constant block {@link MetalDrawContext} writes.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalUniformValues implements AutoCloseable {
    private static final float NEAR_PLANE = 0.05f;
    private static final Field CUSTOM_UNIFORM_ORDER = customUniformOrderField();
    private static final Matrix4fc LIGHTMAP_TEXTURE_MATRIX = new Matrix4f(
            1.0f / 256.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f / 256.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f / 256.0f, 0.0f,
            1.0f / 32.0f, 1.0f / 32.0f, 1.0f / 32.0f, 1.0f
    );
    private static final String CORE_MODEL_VIEW_INVERSE = "iris_ModelViewMatInverse";
    private static final String CORE_PROJECTION_INVERSE = "iris_ProjMatInverse";
    private static final String CORE_NORMAL_MATRIX = "iris_NormalMat";

    private final float sunPathRotation;
    private final @Nullable CustomUniforms customUniforms;
    /** Fixed inputs registered by each real Iris ProgramUniforms instance. */
    private final @Nullable CustomUniformFixedInputUniformsHolder programFixedInputs;
    private final @Nullable IrisMetalDynamicUniforms dynamicUniforms;
    private final @Nullable FrameUpdateNotifier updateNotifier;
    private final IntSupplier renderStageSource;
    private final LongSupplier gameTimeSource;
    private final boolean strict;
    private final List<Block> blocks = new ArrayList<>();
    private final java.util.Map<Object, Block> blocksByToken = new java.util.HashMap<>();
    private final Set<String> unsupported = new HashSet<>();
    private final Matrix4f previousModelView = new Matrix4f();
    private final Matrix4f previousProjection = new Matrix4f();
    private final Vector3d previousCameraPosition = new Vector3d();
    private @Nullable Frame currentFrame;
    private HistoryState historyState = HistoryState.UNINITIALIZED;
    private boolean warnedIdentityMatrices;
    private boolean closed;

    private enum HistoryState {
        UNINITIALIZED,
        PREWARMED_NO_HISTORY,
        FIRST_FRAME_ACTIVE,
        HISTORY_VALID
    }

    private enum UniformPhase {
        FRAME,
        HISTORY,
        ONCE,
        PER_TICK,
        PER_FRAME,
        CUSTOM,
        PROGRAM_DRAW,
        DRAW
    }

    private record PlanEntry(
            MetalIrisShaderCompiler.UniformMember member,
            UniformPhase phase,
            @Nullable CachedUniform cachedUniform
    ) {
    }

    private static final class ProgramPlan {
        private final List<PlanEntry> entries;
        private final List<MetalIrisShaderCompiler.UniformMember> dynamicMembers;
        private final Set<CachedUniform> cachedUniforms;
        private final Map<String, PlanEntry> byName;

        private ProgramPlan(final List<PlanEntry> entries) {
            this.entries = List.copyOf(entries);
            this.dynamicMembers = this.entries.stream()
                    .filter(entry -> entry.phase() == UniformPhase.PROGRAM_DRAW)
                    .map(PlanEntry::member)
                    .toList();
            this.cachedUniforms = Collections.newSetFromMap(new IdentityHashMap<>());
            for (PlanEntry entry : this.entries) {
                if (entry.cachedUniform() != null) {
                    this.cachedUniforms.add(entry.cachedUniform());
                }
            }
            Map<String, PlanEntry> byName = new java.util.LinkedHashMap<>();
            for (PlanEntry entry : this.entries) {
                byName.putIfAbsent(entry.member().name(), entry);
            }
            this.byName = Map.copyOf(byName);
        }

        private @Nullable PlanEntry entry(final String name) {
            return this.byName.get(name);
        }

        private boolean requiresMaterialization() {
            return !this.cachedUniforms.isEmpty() || !this.dynamicMembers.isEmpty();
        }
    }

    /** Backend-neutral values whose Iris suppliers observe the active draw. */
    record DrawUniformContext(
            @Nullable GpuTextureView gtexture,
            int atlasWidth,
            int atlasHeight,
            Optional<BlendFunction> blendFunction
    ) {
        private static final DrawUniformContext EMPTY =
                new DrawUniformContext(null, 0, 0, Optional.empty());

        DrawUniformContext {
            Objects.requireNonNull(blendFunction, "blendFunction");
            if (atlasWidth < 0 || atlasHeight < 0) {
                throw new IllegalArgumentException("Iris atlas dimensions must be non-negative");
            }
        }

        static DrawUniformContext empty() {
            return EMPTY;
        }
    }

    /**
     * A registered block. The GPU buffer is allocated lazily: registration
     * happens while the pack loads, which is not necessarily a moment where a
     * device is reachable (the offline gate builds a device of its own and
     * never installs it on RenderSystem).
     */
    private static final class Block {
        private final Object token;
        private final String label;
        private final List<MetalIrisShaderCompiler.UniformMember> layout;
        private final int size;
        private final OptionalDouble alphaTestReference;
        private final ProgramPlan plan;
        private final Set<CachedUniform> onceUpdated = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<CachedUniform, Long> lastTick = new IdentityHashMap<>();
        private final Map<CachedUniform, Integer> lastFrame = new IdentityHashMap<>();
        private boolean programInitialized;
        private long programUpdateCount;
        private long uploadedBytes;
        private @Nullable GpuBuffer buffer;
        private @Nullable ByteBuffer staging;
        private @Nullable MetalDevice device;

        private Block(
                final Object token,
                final String label,
                final List<MetalIrisShaderCompiler.UniformMember> layout,
                final int size,
                final OptionalDouble alphaTestReference,
                final ProgramPlan plan
        ) {
            this.token = token;
            this.label = label;
            this.layout = layout;
            this.size = size;
            this.alphaTestReference = alphaTestReference;
            this.plan = plan;
        }

        private void allocate(final MetalDevice device) {
            if (this.buffer != null) {
                return;
            }
            this.device = device;
            this.buffer = device.createBuffer(
                    () -> "metallum:iris_uniforms/" + this.label,
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    this.size
            );
            // writeToBuffer rejects heap buffers (they would SIGBUS in the
            // staging path), so the scratch has to be direct.
            this.staging = ByteBuffer.allocateDirect(this.size).order(ByteOrder.nativeOrder());
        }
    }

    IrisMetalUniformValues(final float sunPathRotation) {
        this(sunPathRotation, null, null, null, null, () -> 0, false);
    }

    IrisMetalUniformValues(final float sunPathRotation, final IntSupplier renderStageSource) {
        this(sunPathRotation, null, null, null, null, renderStageSource, false);
    }

    IrisMetalUniformValues(
            final float sunPathRotation,
            final CustomUniforms customUniforms,
            final FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource
    ) {
        this(sunPathRotation, customUniforms, null, null, updateNotifier, renderStageSource, true);
    }

    IrisMetalUniformValues(
            final float sunPathRotation,
            final CustomUniforms customUniforms,
            final CustomUniformFixedInputUniformsHolder fixedInputs,
            final FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource
    ) {
        this(sunPathRotation, customUniforms, fixedInputs, null, updateNotifier, renderStageSource, true);
    }

    IrisMetalUniformValues(
            final float sunPathRotation,
            final CustomUniforms customUniforms,
            final CustomUniformFixedInputUniformsHolder fixedInputs,
            final FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource,
            final LongSupplier gameTimeSource
    ) {
        this(
                sunPathRotation, customUniforms, fixedInputs, null, updateNotifier,
                renderStageSource, true, gameTimeSource
        );
    }

    IrisMetalUniformValues(
            final float sunPathRotation,
            final CustomUniforms customUniforms,
            final CustomUniformFixedInputUniformsHolder fixedInputs,
            final IrisMetalDynamicUniforms dynamicUniforms,
            final FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource
    ) {
        this(sunPathRotation, customUniforms, fixedInputs, dynamicUniforms, updateNotifier, renderStageSource, true);
    }

    private IrisMetalUniformValues(
            final float sunPathRotation,
            final @Nullable CustomUniforms customUniforms,
            final @Nullable CustomUniformFixedInputUniformsHolder fixedInputs,
            final @Nullable IrisMetalDynamicUniforms dynamicUniforms,
            final @Nullable FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource,
            final boolean strict
    ) {
        this(
                sunPathRotation, customUniforms, fixedInputs, dynamicUniforms, updateNotifier,
                renderStageSource, strict, IrisMetalUniformValues::currentGameTime
        );
    }

    private IrisMetalUniformValues(
            final float sunPathRotation,
            final @Nullable CustomUniforms customUniforms,
            final @Nullable CustomUniformFixedInputUniformsHolder fixedInputs,
            final @Nullable IrisMetalDynamicUniforms dynamicUniforms,
            final @Nullable FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource,
            final boolean strict,
            final LongSupplier gameTimeSource
    ) {
        if ((customUniforms == null) != (updateNotifier == null)) {
            throw new IllegalArgumentException("Iris custom uniforms and frame notifier must be supplied together");
        }
        this.sunPathRotation = sunPathRotation;
        this.customUniforms = customUniforms;
        this.programFixedInputs = fixedInputs;
        this.dynamicUniforms = dynamicUniforms;
        this.updateNotifier = updateNotifier;
        this.renderStageSource = Objects.requireNonNull(renderStageSource, "renderStageSource");
        this.gameTimeSource = Objects.requireNonNull(gameTimeSource, "gameTimeSource");
        this.strict = strict;
    }

    /**
     * Allocates the block for one terrain kind. Called during registry
     * activation, once per successfully translated program.
     */
    void register(
            final IrisMetalPipelineOverrides.TerrainKind kind,
            final MetalIrisShaderCompiler.GlslProgram program
    ) {
        register(kind, kind.name().toLowerCase(Locale.ROOT), program);
    }

    void register(
            final Object token,
            final String label,
            final MetalIrisShaderCompiler.GlslProgram program
    ) {
        register(
                token,
                label,
                program.uniformLayout(),
                program.uniformBlockSize(),
                program.alphaTestReference()
        );
    }

    void registerCompute(
            final Object token,
            final String label,
            final MetalIrisShaderCompiler.ComputeReflection reflection
    ) {
        register(
                token,
                label,
                reflection.uniformLayout(),
                reflection.uniformBlockSize(),
                OptionalDouble.empty()
        );
    }

    private void register(
            final Object token,
            final String label,
            final List<MetalIrisShaderCompiler.UniformMember> layout,
            final int size,
            final OptionalDouble alphaTestReference
    ) {
        if (layout.isEmpty()) {
            return;
        }
        if (this.strict) {
            requireUniformSources(token, layout);
        }
        Block existing = this.blocksByToken.get(token);
        if (existing != null) {
            if (existing.size != size
                    || !existing.layout.equals(layout)
                    || !existing.alphaTestReference.equals(alphaTestReference)) {
                throw new IllegalStateException(
                        "Iris uniform token was registered with two different layouts or alpha-test references: "
                                + token
                );
            }
            return;
        }
        Block block = new Block(
                token,
                label,
                layout,
                size,
                alphaTestReference,
                buildPlan(token, layout)
        );
        this.blocks.add(block);
        this.blocksByToken.put(token, block);
    }

    private ProgramPlan buildPlan(
            final Object token,
            final List<MetalIrisShaderCompiler.UniformMember> layout
    ) {
        List<PlanEntry> entries = new ArrayList<>(layout.size());
        for (MetalIrisShaderCompiler.UniformMember member : layout) {
            CachedUniform cached = null;
            UniformPhase phase;
            if (this.programFixedInputs != null && this.programFixedInputs.containsKey(member.name())) {
                cached = this.programFixedInputs.getUniform(member.name());
                phase = cachedPhase(cached);
            } else if (this.customUniforms != null && this.customUniforms.hasVariable(member.name())) {
                cached = this.customUniforms.getVariable(member.name()) instanceof CachedUniform value
                        ? value
                        : null;
                if (cached == null) {
                    throw new IllegalStateException(
                            "Iris custom uniform '" + member.name() + "' is not a CachedUniform"
                    );
                }
                phase = cachedPhase(cached);
            } else if (this.dynamicUniforms != null && this.dynamicUniforms.canMaterialize(member)) {
                phase = UniformPhase.PROGRAM_DRAW;
            } else {
                phase = backendPhase(token, member);
            }
            entries.add(new PlanEntry(member, phase, cached));
        }
        return new ProgramPlan(entries);
    }

    private static UniformPhase cachedPhase(final CachedUniform uniform) {
        return switch (uniform.getUpdateFrequency()) {
            case ONCE -> UniformPhase.ONCE;
            case PER_TICK -> UniformPhase.PER_TICK;
            case PER_FRAME -> UniformPhase.PER_FRAME;
            case CUSTOM -> UniformPhase.CUSTOM;
        };
    }

    private static UniformPhase backendPhase(
            final Object token,
            final MetalIrisShaderCompiler.UniformMember member
    ) {
        if ("gbufferPreviousModelView".equals(member.name())
                || "gbufferPreviousProjection".equals(member.name())
                || "previousCameraPosition".equals(member.name())) {
            return UniformPhase.HISTORY;
        }
        if (isLiveFogUniform(member.name())
                || (isCoreDrawUniform(member.name()) && usesMojangCoreTransforms(token))
                || ("iris_currentAlphaTest".equals(member.name())
                && !isFrameOwnedDynamicUniformName(member))) {
            return UniformPhase.DRAW;
        }
        return UniformPhase.FRAME;
    }

    private static boolean isFrameOwnedDynamicUniformName(
            final MetalIrisShaderCompiler.UniformMember member
    ) {
        return "iris_currentAlphaTest".equals(member.name()) && "float".equals(member.type());
    }

    /**
     * The slice to bind for a kind, or {@code null} if the kind has no uniform
     * block. Allocates and fills on first use so that a terrain draw reaching
     * the pass before the first {@link #updateFrame} still binds real values.
     */
    @Nullable
    GpuBufferSlice slice(final IrisMetalPipelineOverrides.TerrainKind kind) {
        return slice((Object) kind);
    }

    @Nullable
    GpuBufferSlice slice(final Object token) {
        if (this.closed) {
            return null;
        }
        Block block = this.blocksByToken.get(token);
        return block != null && block.buffer != null ? block.buffer.slice() : null;
    }

    /**
     * Allocates and fills every registered block. Must run outside any encoder
     * — see {@link IrisMetalPipelineOverrides#updateFrame()}.
     */
    void prewarm(final MetalDevice device) {
        if (this.closed) {
            return;
        }
        Frame frame = null;
        for (Block block : this.blocks) {
            if (block.buffer != null) {
                continue;
            }
            block.allocate(device);
            if (frame == null) {
                frame = sampleFrame();
            }
            upload(block, frame);
        }
        this.currentFrame = frame;
        if (this.historyState == HistoryState.UNINITIALIZED) {
            this.historyState = HistoryState.PREWARMED_NO_HISTORY;
        }
    }

    /**
     * Recomputes and uploads every registered block. Called once per frame from
     * {@link MetalWorldRenderingPipeline#beginLevelRendering()}, before sodium
     * draws terrain.
     */
    void updateFrame() {
        if (this.closed) {
            return;
        }
        if (this.customUniforms != null) {
            try {
                Objects.requireNonNull(this.updateNotifier).onNewFrame();
                this.customUniforms.update();
            } catch (RuntimeException failure) {
                if (this.strict) {
                    throw new IllegalStateException("Iris uniform graph failed to update", failure);
                }
                if (this.unsupported.add("<custom-uniform-frame>")) {
                    Metallum.LOGGER.warn("[metallum-iris] Iris uniform graph failed to update", failure);
                }
            }
        }
        Frame frame = sampleFrame();
        this.currentFrame = frame;
        if (this.historyState == HistoryState.UNINITIALIZED
                || this.historyState == HistoryState.PREWARMED_NO_HISTORY) {
            this.historyState = HistoryState.FIRST_FRAME_ACTIVE;
        }
        for (Block block : this.blocks) {
            if (block.buffer != null) {
                upload(block, frame);
            }
        }
        this.previousModelView.set(frame.modelView());
        this.previousProjection.set(frame.projection());
        this.previousCameraPosition.set(frame.cameraPosition());
        this.historyState = HistoryState.HISTORY_VALID;
    }

    /**
     * Reproduces one fixed Iris {@code ProgramUniforms.update()} boundary for
     * a generation-owned block. Dynamic values are deliberately handled by the
     * caller first; this method only advances cached fixed/custom values in
     * Iris's once, tick, frame and custom phases.
     */
    private IrisMetalDynamicUniforms.@Nullable DrawSnapshot beginProgram(
            final Block block,
            final DrawUniformContext context
    ) {
        if (this.dynamicUniforms != null) {
            // The block token is the Metal-side identity of one generated
            // Iris program. The layout list is only its reflected ABI and is
            // not a program identity; using it would merge repeated uses of
            // one block and lose the real Program.use() boundary.
            this.dynamicUniforms.beginProgram(block.token, block.plan.dynamicMembers);
        }
        IrisMetalDynamicUniforms.DrawSnapshot dynamicSnapshot = this.dynamicUniforms == null
                ? null
                : this.dynamicUniforms.snapshot(block.plan.dynamicMembers, context);
        long gameTime = this.gameTimeSource.getAsLong();
        int frame = frameCounter();
        boolean firstUse = !block.programInitialized;
        for (PlanEntry entry : block.plan.entries) {
            CachedUniform cached = entry.cachedUniform();
            if (cached == null) {
                continue;
            }
            switch (entry.phase()) {
                case ONCE -> {
                    if (firstUse) {
                        cached.update();
                        block.onceUpdated.add(cached);
                    }
                }
                case PER_TICK -> {
                    Long last = block.lastTick.get(cached);
                    if (firstUse || last == null || last.longValue() != gameTime) {
                        cached.update();
                        block.lastTick.put(cached, gameTime);
                    }
                }
                case PER_FRAME -> {
                    Integer last = block.lastFrame.get(cached);
                    if (firstUse || last == null || last.intValue() != frame) {
                        cached.update();
                        block.lastFrame.put(cached, frame);
                    }
                }
                case CUSTOM -> {
                    // CustomUniforms owns its dependency-topological update
                    // once per frame. A standalone cached custom input has no
                    // graph owner and follows Iris's program-draw boundary.
                    if (this.customUniforms == null) {
                        cached.update();
                    }
                }
                default -> {
                }
            }
        }
        block.programInitialized = true;
        block.programUpdateCount++;
        return dynamicSnapshot;
    }

    private static long currentGameTime() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        return level == null ? 0L : level.getGameTime();
    }

    /**
     * Exercises the same per-block Program.use() commit without allocating a
     * GPU buffer. The production draw path reaches {@link #beginProgram(Block,
     * DrawUniformContext)} through {@link #materializeDraw(Object, ByteBuffer,
     * ByteBuffer, ByteBuffer, DrawUniformContext)}.
     */
    void beginProgramForTests(final Object token, final DrawUniformContext context) {
        Block block = findBlock(token);
        if (block == null) {
            throw new IllegalStateException("Iris uniform block is not registered for " + token);
        }
        beginProgram(block, Objects.requireNonNull(context, "context"));
    }

    /**
     * Mirrors Iris's two fixed-input update surfaces without double-running a
     * stateful supplier. CustomUniforms exposes its dependency order only as a
     * private field in the pinned Iris build, so admission fails closed if the
     * fixed-version contract changes instead of silently using stale values.
     */
    static void updateUnvisitedFixedInputs(
            final CustomUniforms customUniforms,
            final CustomUniformFixedInputUniformsHolder fixedInputs
    ) {
        Set<CachedUniform> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            Object order = CUSTOM_UNIFORM_ORDER.get(customUniforms);
            if (!(order instanceof Collection<?> collection)) {
                throw new IllegalStateException(
                        "Iris CustomUniforms.uniformOrder is not a collection: "
                                + (order == null ? "null" : order.getClass().getName())
                );
            }
            for (Object entry : collection) {
                if (!(entry instanceof CachedUniform uniform)) {
                    throw new IllegalStateException(
                            "Iris CustomUniforms.uniformOrder contains "
                                    + (entry == null ? "null" : entry.getClass().getName())
                    );
                }
                visited.add(uniform);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException(
                    "Could not inspect Iris 1.11.2 CustomUniforms.uniformOrder", failure
            );
        }
        for (CachedUniform uniform : fixedInputs.getAll()) {
            if (!visited.contains(uniform)) {
                uniform.update();
            }
        }
    }

    private static Field customUniformOrderField() {
        try {
            Field field = CustomUniforms.class.getDeclaredField("uniformOrder");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** Current Iris-compatible frame counter for diagnostics and pass tracing. */
    int frameCounter() {
        return SystemTimeUniforms.COUNTER.getAsInt();
    }

    /**
     * The CPU-side bytes last uploaded for a kind, or {@code null} if the block
     * has not been allocated. The uniform buffer itself is write-only on the
     * GPU (no {@code USAGE_MAP_READ}), so this staging copy is what the offline
     * gate asserts the std140 writer against.
     */
    @Nullable
    ByteBuffer lastUpload(final IrisMetalPipelineOverrides.TerrainKind kind) {
        return lastUpload((Object) kind);
    }

    @Nullable
    ByteBuffer lastUpload(final Object token) {
        for (Block block : this.blocks) {
            if (block.token.equals(token)) {
                return block.staging;
            }
        }
        return null;
    }

    private void upload(final Block block, final Frame frame) {
        ByteBuffer staging = block.staging;
        zero(staging);
        for (MetalIrisShaderCompiler.UniformMember member : block.layout) {
            // Mojang core draws receive the three Iris externally-managed
            // matrices from their transient DynamicTransforms/Projection
            // blocks. Sodium terrain has no such blocks, so its identical
            // Iris ABI members must be written from the captured frame here.
            if (isDynamicDrawUniform(member.name())
                    && !(isFrameDerivedMatrix(member) && !usesMojangCoreTransforms(block.token))
                    && !isFrameOwnedDynamicUniform(block, member)) {
                continue;
            }
            write(staging, member, frame, block.alphaTestReference);
        }
        staging.rewind();
        IrisMetalPassTrace.observeUniformSnapshot(block.label, "frame", block.layout, staging);
        block.uploadedBytes += staging.remaining();
        block.device.createCommandEncoder().writeToBuffer(block.buffer.slice(), staging);
    }

    int coreDrawBlockSize(final ShaderKey key) {
        return drawBlockSize(key);
    }

    int drawBlockSize(final Object token) {
        Block block = findBlock(token);
        return block != null && (block.plan.requiresMaterialization()
                || block.layout.stream().anyMatch(member -> isDynamicDrawUniform(member.name())))
                ? block.size
                : 0;
    }

    long programUpdateCount(final Object token) {
        Block block = findBlock(token);
        return block == null ? 0L : block.programUpdateCount;
    }

    long uploadedBytes(final Object token) {
        Block block = findBlock(token);
        return block == null ? 0L : block.uploadedBytes;
    }

    boolean requiresDynamicTransforms(final Object token) {
        Block block = findBlock(token);
        return usesMojangCoreTransforms(token)
                && block != null && block.layout.stream().anyMatch(member ->
                CORE_MODEL_VIEW_INVERSE.equals(member.name()) || CORE_NORMAL_MATRIX.equals(member.name()));
    }

    boolean requiresProjection(final Object token) {
        Block block = findBlock(token);
        return usesMojangCoreTransforms(token)
                && block != null && block.layout.stream().anyMatch(member ->
                CORE_PROJECTION_INVERSE.equals(member.name()));
    }

    void materializeCoreDraw(
            final ShaderKey key,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection
    ) {
        materializeDraw(key, output, dynamicTransforms, projection);
    }

    void materializeDraw(
            final Object token,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection,
            final DrawUniformContext context
    ) {
        Block block = findBlock(token);
        if (block == null || block.staging == null) {
            throw new IllegalStateException("Iris uniform block is not prepared for " + token);
        }
        IrisMetalDynamicUniforms.DrawSnapshot dynamicSnapshot = beginProgram(block, context);
        materializeDrawUniforms(
                block.staging,
                block.layout,
                output,
                dynamicTransforms,
                projection,
                this.renderStageSource.getAsInt(),
                CapturedRenderingState.INSTANCE.getCurrentRenderedEntity(),
                CapturedRenderingState.INSTANCE.getTextureReloadCount(),
                context,
                this.dynamicUniforms,
                usesMojangCoreTransforms(token),
                dynamicSnapshot
        );
        writeProgramCachedUniforms(output, block.plan);
        IrisMetalPassTrace.observeUniformSnapshot(block.label, "draw", block.layout, output);
    }

    private static void writeProgramCachedUniforms(
            final ByteBuffer destination,
            final ProgramPlan plan
    ) {
        for (PlanEntry entry : plan.entries) {
            CachedUniform cached = entry.cachedUniform();
            if (cached == null) {
                continue;
            }
            if (entry.member().arrayCount() != 0) {
                throw new IllegalStateException(
                        "Iris program uniform cannot materialize array member '"
                                + entry.member().name() + "' (count=" + entry.member().arrayCount() + ")"
                );
            }
            writeCachedUniform(destination, entry.member(), cached);
        }
    }

    void materializeDraw(
            final Object token,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection
    ) {
        materializeDraw(token, output, dynamicTransforms, projection, DrawUniformContext.empty());
    }

    static void materializeCoreDrawUniforms(
            final ByteBuffer base,
            final List<MetalIrisShaderCompiler.UniformMember> layout,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection
    ) {
        materializeDrawUniforms(
                base, layout, output, dynamicTransforms, projection, 0,
                CapturedRenderingState.INSTANCE.getCurrentRenderedEntity(),
                CapturedRenderingState.INSTANCE.getTextureReloadCount(),
                DrawUniformContext.empty(), null, true, null
        );
    }

    static void materializeDrawUniforms(
            final ByteBuffer base,
            final List<MetalIrisShaderCompiler.UniformMember> layout,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection,
            final int renderStage
    ) {
        materializeDrawUniforms(
                base, layout, output, dynamicTransforms, projection, renderStage,
                CapturedRenderingState.INSTANCE.getCurrentRenderedEntity(),
                CapturedRenderingState.INSTANCE.getTextureReloadCount(),
                DrawUniformContext.empty(), null, false, null
        );
    }

    static void materializeDrawUniforms(
            final ByteBuffer base,
            final List<MetalIrisShaderCompiler.UniformMember> layout,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection,
            final int renderStage,
            final int entityId,
            final int textureReloadCount,
            final DrawUniformContext context
    ) {
        materializeDrawUniforms(
                base, layout, output, dynamicTransforms, projection, renderStage,
                entityId, textureReloadCount, context, null, false, null
        );
    }

    private static void materializeDrawUniforms(
            final ByteBuffer base,
            final List<MetalIrisShaderCompiler.UniformMember> layout,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection,
            final int renderStage,
            final int entityId,
            final int textureReloadCount,
            final DrawUniformContext context,
            final @Nullable IrisMetalDynamicUniforms dynamicUniforms,
            final boolean coreDraw,
            final IrisMetalDynamicUniforms.@Nullable DrawSnapshot dynamicSnapshot
    ) {
        Objects.requireNonNull(context, "context");
        ByteBuffer destination = output.slice().order(output.order());
        ByteBuffer source = base.duplicate().order(base.order());
        source.clear();
        if (destination.remaining() < source.remaining()) {
            throw new IllegalArgumentException(
                    "Iris core transient block is " + destination.remaining()
                            + " bytes, expected at least " + source.remaining()
            );
        }
        destination.put(source);
        // Iris registers these fog suppliers as dynamic values.  The frame
        // upload happens before Mojang's FogRenderer has populated Sodium's
        // FogStorage, so refresh them at the same draw/pass boundary where
        // native Iris evaluates the suppliers.
        refreshLiveFogUniforms(destination, layout);

        boolean needsModelView = coreDraw && layout.stream().anyMatch(member ->
                CORE_MODEL_VIEW_INVERSE.equals(member.name()) || CORE_NORMAL_MATRIX.equals(member.name()));
        boolean needsProjection = coreDraw
                && layout.stream().anyMatch(member -> CORE_PROJECTION_INVERSE.equals(member.name()));
        Matrix4f modelViewInverse = needsModelView
                ? readMat4(dynamicTransforms, "DynamicTransforms").invert()
                : null;
        Matrix4f projectionInverse = needsProjection
                ? MetalIrisDepthConvention.packProjection(readMat4(projection, "Projection")).invert()
                : null;
        Matrix3f normalMatrix = modelViewInverse == null
                ? null
                : modelViewInverse.transpose3x3(new Matrix3f());

        for (MetalIrisShaderCompiler.UniformMember member : layout) {
            if (dynamicUniforms != null
                    && (dynamicSnapshot == null || dynamicUniforms.contains(dynamicSnapshot, member.name()))
                    && (dynamicSnapshot == null
                    ? dynamicUniforms.write(member, destination, context)
                    : dynamicUniforms.write(member, destination, context, dynamicSnapshot))) {
                continue;
            }
            switch (member.name()) {
                case CORE_MODEL_VIEW_INVERSE -> {
                    if (coreDraw) {
                        requireCoreDrawType(member, "mat4");
                        putMat4(destination, member.offset(), Objects.requireNonNull(modelViewInverse));
                    }
                }
                case CORE_PROJECTION_INVERSE -> {
                    if (coreDraw) {
                        requireCoreDrawType(member, "mat4");
                        putMat4(destination, member.offset(), Objects.requireNonNull(projectionInverse));
                    }
                }
                case CORE_NORMAL_MATRIX -> {
                    if (coreDraw) {
                        requireCoreDrawType(member, "mat3");
                        putMat3(destination, member.offset(), Objects.requireNonNull(normalMatrix));
                    }
                }
                case "renderStage" -> {
                    requireDynamicDrawType(member, "int");
                    destination.putInt(member.offset(), renderStage);
                }
                case "entityId" -> {
                    requireDynamicDrawType(member, "int");
                    destination.putInt(member.offset(), entityId);
                }
                case "atlasSize" -> {
                    requireDynamicDrawType(member, "ivec2");
                    putIVec2(
                            destination,
                            member.offset(),
                            context.atlasWidth(),
                            context.atlasHeight()
                    );
                }
                case "gtextureId" -> {
                    requireDynamicDrawType(member, "int");
                    destination.putInt(member.offset(), logicalTextureId(context.gtexture()));
                }
                case "textureReloadCount" -> {
                    requireDynamicDrawType(member, "int");
                    destination.putInt(member.offset(), textureReloadCount);
                }
                case "gtextureSize" -> {
                    requireDynamicDrawType(member, "ivec2");
                    GpuTextureView texture = context.gtexture();
                    putIVec2(
                            destination,
                            member.offset(),
                            texture == null ? 0 : texture.getWidth(0),
                            texture == null ? 0 : texture.getHeight(0)
                    );
                }
                case "blendFunc" -> {
                    requireDynamicDrawType(member, "ivec4");
                    int[] blend = irisBlendFunc(context.blendFunction());
                    putIVec4(destination, member.offset(), blend[0], blend[1], blend[2], blend[3]);
                }
                default -> {
                }
            }
        }
    }

    private @Nullable Block findBlock(final Object token) {
        return this.blocksByToken.get(token);
    }

    /**
     * Iris identifies shadow Sodium terrain with {@link ShaderKey} constants,
     * but those programs still execute through Sodium's chunk draw and do not
     * bind Mojang's core {@code DynamicTransforms}/{@code Projection} blocks.
     */
    static boolean usesMojangCoreTransforms(final Object token) {
        if (!(token instanceof ShaderKey key)) {
            return false;
        }
        return key != ShaderKey.SODIUM_TERRAIN_SOLID
                && key != ShaderKey.SODIUM_TERRAIN_CUTOUT
                && key != ShaderKey.SODIUM_TERRAIN_TRANSLUCENT
                && key != ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID
                && key != ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT
                && key != ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT;
    }

    private static boolean isCoreDrawUniform(final String name) {
        return CORE_MODEL_VIEW_INVERSE.equals(name)
                || CORE_PROJECTION_INVERSE.equals(name)
                || CORE_NORMAL_MATRIX.equals(name);
    }

    private static boolean isDynamicDrawUniform(final String name) {
        return isCoreDrawUniform(name)
                || isLiveFogUniform(name)
                || switch (name) {
                    case "entityId", "atlasSize", "gtextureId", "textureReloadCount",
                            "gtextureSize", "blendFunc", "renderStage", "fogMode", "fogShape",
                            "fogDensity", "fogStart", "fogEnd", "fogColor",
                            "iris_currentAlphaTest", "alphaTestRef" -> true;
                    default -> false;
                };
    }

    private static boolean isLiveFogUniform(final String name) {
        return switch (name) {
            case "iris_FogColor", "iris_FogDensity",
                    "iris_FogStart", "iris_FogEnd" -> true;
            default -> false;
        };
    }

    private static boolean isFrameOwnedDynamicUniform(
            final Block block,
            final MetalIrisShaderCompiler.UniformMember member
    ) {
        return "iris_currentAlphaTest".equals(member.name())
                && block.alphaTestReference.isPresent();
    }

    /**
     * Strict production blocks may only contain members with a real Iris
     * fixed/custom supplier, a real Iris dynamic supplier, or one of the
     * explicitly backend-owned draw values. The relaxed constructor is kept
     * for source/layout unit tests, but production never zero-fills a member
     * that bypassed Iris's registration graph.
     */
    private void requireUniformSources(
            final Object token,
            final List<MetalIrisShaderCompiler.UniformMember> layout
    ) {
        for (MetalIrisShaderCompiler.UniformMember member : layout) {
            String name = member.name();
            if (this.programFixedInputs != null && this.programFixedInputs.containsKey(name)) {
                continue;
            }
            if (this.customUniforms != null && this.customUniforms.hasVariable(name)) {
                continue;
            }
            if (this.dynamicUniforms != null && this.dynamicUniforms.canMaterialize(member)) {
                continue;
            }
            if (isBackendOwnedUniform(token, member)) {
                continue;
            }
            throw new IllegalStateException(
                    "Iris uniform '" + name + "' (" + member.type()
                            + ") is absent from the fixed/custom/dynamic supplier graph"
            );
        }
    }

    private static boolean isBackendOwnedUniform(
            final Object token,
            final MetalIrisShaderCompiler.UniformMember member
    ) {
        if ("iris_LightmapTextureMatrix".equals(member.name())) {
            return member.arrayCount() == 0 && "mat4".equals(member.type());
        }
        if (isCoreDrawUniform(member.name())) {
            return usesMojangCoreTransforms(token) || isFrameDerivedMatrix(member);
        }
        if (isFrameDerivedMatrix(member)) {
            return true;
        }
        // These names are externally managed by Iris but are filled from the
        // live camera fog record at the draw boundary.
        return isLiveFogUniform(member.name());
    }

    private static boolean isFrameDerivedMatrix(
            final MetalIrisShaderCompiler.UniformMember member
    ) {
        if (member.arrayCount() != 0) {
            return false;
        }
        return switch (member.name()) {
            case "gbufferModelView", "gbufferModelViewInverse", "iris_ModelViewMatrix",
                    "iris_ModelViewMatrixInverse", "shadowModelView", "shadowModelViewInverse",
                    "gbufferProjection", "gbufferProjectionInverse", "iris_ProjectionMatrix",
                    "iris_ProjectionMatrixInverse", "iris_ModelViewMatInverse", "iris_ProjMatInverse",
                    "shadowProjection", "shadowProjectionInverse",
                    "gbufferPreviousModelView", "gbufferPreviousProjection" -> "mat4".equals(member.type());
            case "iris_NormalMat", "normalMatrix" -> "mat3".equals(member.type());
            default -> false;
        };
    }

    private static void refreshLiveFogUniforms(
            final ByteBuffer destination,
            final List<MetalIrisShaderCompiler.UniformMember> layout
    ) {
        if (layout.stream().noneMatch(member -> isLiveFogUniform(member.name()))) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        FogParameters fogParameters = liveFogParameters(minecraft);
        writeLiveFogUniforms(
                destination,
                layout,
                fogParameters,
                liveFogColor(minecraft, CapturedRenderingState.INSTANCE.getFogColor()),
                CapturedRenderingState.INSTANCE.getFogDensity()
        );
    }

    /**
     * Returns the fog state for the camera being rendered.  Iris's Sodium
     * supplier reads FogStorage, which is updated from this same FogData by
     * the setupFog return hook.  The camera state is the authoritative value
     * during the first render after a reload, before that storage hook has
     * run for the new frame.
     */
    private static @Nullable FogParameters liveFogParameters(final @Nullable Minecraft minecraft) {
        FogData cameraFog = currentCameraFogData(minecraft);
        if (cameraFog != null) {
            return fogParameters(cameraFog);
        }
        if (minecraft != null && minecraft.gameRenderer instanceof FogStorage fogStorage) {
            return fogStorage.sodium$getFogParameters();
        }
        return null;
    }

    private static @Nullable FogData currentCameraFogData(final @Nullable Minecraft minecraft) {
        if (minecraft == null || minecraft.gameRenderer == null) {
            return null;
        }
        var cameraState = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (cameraState == null || !cameraState.initialized || cameraState.fogData == null
                || cameraState.fogData.color == null) {
            return null;
        }
        return cameraState.fogData;
    }

    /** Converts the fixed Minecraft fog record without changing its values. */
    static FogParameters fogParameters(final FogData data) {
        Vector4f color = Objects.requireNonNull(data.color, "fog color");
        return new FogParameters(
                color.x,
                color.y,
                color.z,
                color.w,
                data.environmentalStart,
                data.environmentalEnd,
                data.renderDistanceStart,
                data.renderDistanceEnd
        );
    }

    private static Vector3d liveFogColor(
            final @Nullable Minecraft minecraft,
            final Vector3d captured
    ) {
        FogData cameraFog = currentCameraFogData(minecraft);
        if (cameraFog == null || cameraFog.color == null) {
            return captured;
        }
        return new Vector3d(cameraFog.color.x, cameraFog.color.y, cameraFog.color.z);
    }

    /** Writes Iris's live fog suppliers; kept pure so the contract is unit-testable. */
    static void writeLiveFogUniforms(
            final ByteBuffer destination,
            final List<MetalIrisShaderCompiler.UniformMember> layout,
            final @Nullable FogParameters fogParameters,
            final Vector3d capturedFogColor,
            final float capturedFogDensity
    ) {
        Vector4f irisColor = fogParameters == null ? null : irisFogColor(fogParameters);
        for (MetalIrisShaderCompiler.UniformMember member : layout) {
            int at = member.offset();
            switch (member.name()) {
                case "fogColor", "skyColor" -> {
                    requireDynamicDrawType(member, "vec3");
                    putVec3(destination, at, capturedFogColor);
                }
                case "iris_FogColor" -> {
                    if (irisColor != null) {
                        requireDynamicDrawType(member, "vec4");
                        putVec4(destination, at, irisColor.x, irisColor.y, irisColor.z, irisColor.w);
                    }
                }
                case "iris_FogDensity" -> {
                    requireDynamicDrawType(member, "float");
                    destination.putFloat(at, irisFogDensity(capturedFogDensity));
                }
                case "iris_FogStart" -> {
                    if (fogParameters != null) {
                        requireDynamicDrawType(member, "float");
                        destination.putFloat(at, fogParameters.environmentalStart());
                    }
                }
                case "iris_FogEnd" -> {
                    if (fogParameters != null) {
                        requireDynamicDrawType(member, "float");
                        destination.putFloat(at, fogParameters.environmentalEnd());
                    }
                }
                default -> {
                }
            }
        }
    }

    static boolean requiresDrawContext(
            final List<MetalIrisShaderCompiler.UniformMember> layout
    ) {
        return layout.stream().anyMatch(member -> isDynamicDrawUniform(member.name()));
    }

    private static int logicalTextureId(final @Nullable GpuTextureView view) {
        if (view == null) {
            return 0;
        }
        if (!(view.texture() instanceof MetalGpuTexture texture)) {
            throw new IllegalStateException("Iris draw texture is not backed by Metal");
        }
        return texture.iris$getGlId();
    }

    static int logicalTextureIdForDynamic(final GpuTextureView view) {
        return logicalTextureId(view);
    }

    static int[] irisBlendFunc(final Optional<BlendFunction> blendFunction) {
        if (blendFunction.isEmpty()) {
            return new int[]{0, 0, 0, 0};
        }
        BlendFunction function = blendFunction.get();
        return new int[]{
                glBlendFactor(function.color().sourceFactor()),
                glBlendFactor(function.color().destFactor()),
                glBlendFactor(function.alpha().sourceFactor()),
                glBlendFactor(function.alpha().destFactor())
        };
    }

    private static int glBlendFactor(final BlendFactor factor) {
        return switch (factor) {
            case ZERO -> 0;
            case ONE -> 1;
            case SRC_COLOR -> 0x0300;
            case ONE_MINUS_SRC_COLOR -> 0x0301;
            case SRC_ALPHA -> 0x0302;
            case ONE_MINUS_SRC_ALPHA -> 0x0303;
            case DST_ALPHA -> 0x0304;
            case ONE_MINUS_DST_ALPHA -> 0x0305;
            case DST_COLOR -> 0x0306;
            case ONE_MINUS_DST_COLOR -> 0x0307;
            case SRC_ALPHA_SATURATE -> 0x0308;
            case CONSTANT_COLOR -> 0x8001;
            case ONE_MINUS_CONSTANT_COLOR -> 0x8002;
            case CONSTANT_ALPHA -> 0x8003;
            case ONE_MINUS_CONSTANT_ALPHA -> 0x8004;
        };
    }

    private static Matrix4f readMat4(final @Nullable ByteBuffer source, final String blockName) {
        if (source == null) {
            throw new IllegalStateException("Iris core draw requires bound " + blockName + " uniform data");
        }
        ByteBuffer data = source.duplicate().order(source.order());
        if (data.remaining() < 16 * Float.BYTES) {
            throw new IllegalStateException(
                    "Iris core draw " + blockName + " uniform is " + data.remaining()
                            + " bytes, expected at least " + (16 * Float.BYTES)
            );
        }
        return new Matrix4f().set(data.position(), data);
    }

    private static void requireCoreDrawType(
            final MetalIrisShaderCompiler.UniformMember member,
            final String expected
    ) {
        if (member.arrayCount() != 0 || !expected.equals(member.type())) {
            throw new IllegalStateException(
                    "Iris core draw uniform '" + member.name() + "' must be " + expected
                            + ", got " + member.type() + (member.arrayCount() == 0 ? "" : "[]")
            );
        }
    }

    private static void requireDynamicDrawType(
            final MetalIrisShaderCompiler.UniformMember member,
            final String expected
    ) {
        if (member.arrayCount() != 0 || !expected.equals(member.type())) {
            throw new IllegalStateException(
                    "Iris dynamic uniform '" + member.name() + "' must be " + expected
                            + ", got " + member.type() + (member.arrayCount() == 0 ? "" : "[]")
            );
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.dynamicUniforms != null) {
            this.dynamicUniforms.close();
        }
        for (Block block : this.blocks) {
            if (block.buffer != null) {
                block.buffer.close();
            }
        }
        this.blocks.clear();
        this.blocksByToken.clear();
    }

    // ------------------------------------------------------------------
    // Frame sampling
    // ------------------------------------------------------------------

    private record Frame(
            Matrix4f modelView,
            Matrix4f modelViewInverse,
            Matrix4f projection,
            Matrix4f projectionInverse,
            Matrix3f normalMatrix,
            Vector3d cameraPosition,
            Vector4f sunPosition,
            Vector4f moonPosition,
            Vector4f shadowLightPosition,
            Vector4f upPosition,
            Vector3d fogColor,
            Vector4f irisFogColor,
            float fogDensity,
            float fogStart,
            float fogEnd,
            float tickDelta,
            float frameTime,
            float sunAngle,
            float shadowAngle,
            float rainStrength,
            float screenBrightness,
            float viewWidth,
            float viewHeight,
            float far,
            float frameTimeCounter,
            int worldTime,
            int worldDay,
            int frameCounter
    ) {
    }

    /**
     * Samples the frame, falling back to a neutral frame if any game state is
     * not reachable. A uniform fill runs on the render thread every frame; a
     * throw here would kill the client over a value that is only ever an input
     * to shading, so the failure is reported once and the frame degrades to
     * defaults instead.
     */
    private Frame sampleFrame() {
        try {
            return sampleLiveFrame();
        } catch (RuntimeException t) {
            if (this.strict) {
                throw new IllegalStateException("Could not sample Iris frame uniforms", t);
            }
            if (this.unsupported.add("<frame>")) {
                Metallum.LOGGER.warn(
                        "[metallum-iris] could not sample frame state for the pack uniform block;"
                                + " falling back to neutral values", t
                );
            }
            return neutralFrame();
        }
    }

    /** Neutral frame: identity transforms, no weather, no time. */
    private Frame neutralFrame() {
        SystemFrameTime systemTime = systemFrameTime();
        return new Frame(
                new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix3f(),
                new Vector3d(),
                new Vector4f(0.0f, 100.0f, 0.0f, 0.0f),
                new Vector4f(0.0f, -100.0f, 0.0f, 0.0f),
                new Vector4f(0.0f, 100.0f, 0.0f, 0.0f),
                new Vector4f(0.0f, 100.0f, 0.0f, 0.0f),
                new Vector3d(), new Vector4f(1.0f), 0.0f, 0.0f, 256.0f, 0.0f, systemTime.frameTime(),
                0.25f, 0.25f, 0.0f, 1.0f, 1.0f, 1.0f, 256.0f,
                systemTime.frameTimeCounter(), 0, 0, systemTime.frameCounter()
        );
    }

    private Frame sampleLiveFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        ClientLevel level = minecraft.level;

        Matrix4f modelView = new Matrix4f(state.getGbufferModelView());
        Matrix4f projection = MetalIrisDepthConvention.packProjection(state.getGbufferProjection());
        warnIfUnfilled(modelView, projection);

        Matrix4f modelViewInverse = new Matrix4f(modelView).invert();
        Matrix4f projectionInverse = new Matrix4f(projection).invert();
        Matrix3f normalMatrix = new Matrix3f(modelView).invert().transpose();

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 cameraPos = camera == null ? Vec3.ZERO : camera.position();
        Vector3d cameraPosition = new Vector3d(cameraPos.x, cameraPos.y, cameraPos.z);

        float sunAngle = CelestialUniforms.getSunAngle(true) / 360.0f;
        // getShadowLightPosition is the only celestial vector Iris exposes
        // publicly; the sun/moon pair is the same axis with the day/night sign,
        // which is exactly how CelestialUniforms derives them.
        CelestialUniforms celestial = new CelestialUniforms(this.sunPathRotation);
        Vector4f shadowLight = celestial.getShadowLightPosition();
        boolean day = CelestialUniforms.isDay();
        Vector4f sun = day
                ? new Vector4f(shadowLight)
                : new Vector4f(-shadowLight.x, -shadowLight.y, -shadowLight.z, shadowLight.w);
        Vector4f moon = new Vector4f(-sun.x, -sun.y, -sun.z, sun.w);
        // upPosition: world up mapped into view space, at Iris's 100-unit scale.
        Vector4f up = new Vector4f(0.0f, 100.0f, 0.0f, 0.0f).mul(modelView);

        float tickDelta = state.getTickDelta();
        SystemFrameTime systemTime = systemFrameTime();
        int renderDistance = minecraft.options == null ? 8 : minecraft.options.getEffectiveRenderDistance();
        var mainTarget = minecraft.gameRenderer.mainRenderTarget();
        FogParameters fogParameters = liveFogParameters(minecraft);
        if (fogParameters == null) {
            fogParameters = FogParameters.NONE;
        }
        Vector3d fogColor = liveFogColor(minecraft, state.getFogColor());

        return new Frame(
                modelView,
                modelViewInverse,
                projection,
                projectionInverse,
                normalMatrix,
                cameraPosition,
                sun,
                moon,
                shadowLight,
                up,
                fogColor,
                irisFogColor(fogParameters),
                irisFogDensity(state.getFogDensity()),
                fogParameters.environmentalStart(),
                fogParameters.environmentalEnd(),
                tickDelta,
                systemTime.frameTime(),
                sunAngle,
                CelestialUniforms.getSunAngle(day) / 360.0f,
                level == null ? 0.0f : level.getRainLevel(tickDelta),
                minecraft.options == null ? 1.0f : minecraft.options.gamma().get().floatValue(),
                mainTarget.width,
                mainTarget.height,
                renderDistance * 16.0f,
                systemTime.frameTimeCounter(),
                level == null ? 0 : (int) (level.getDefaultClockTime() % 24000L),
                level == null ? 0 : (int) (level.getDefaultClockTime() / 24000L),
                systemTime.frameCounter()
        );
    }

    /**
     * Reads the same timer and counter objects that native Iris registers in
     * {@code SystemTimeUniforms.addSystemTimeUniforms}. Iris advances them from
     * its {@code MixinGameRenderer} at the start of every rendered frame.
     */
    static SystemFrameTime systemFrameTime() {
        return new SystemFrameTime(
                SystemTimeUniforms.TIMER.getLastFrameTime(),
                SystemTimeUniforms.TIMER.getFrameTimeCounter(),
                SystemTimeUniforms.COUNTER.getAsInt()
        );
    }

    /** Matches Iris FogUniforms/IrisInternalUniforms' max(0, captured density) supplier. */
    static float irisFogDensity(final float capturedDensity) {
        return Math.max(0.0f, capturedDensity);
    }

    /** Matches IrisInternalUniforms' FogStorage-backed iris_FogColor supplier. */
    static Vector4f irisFogColor(final FogParameters parameters) {
        if (parameters == FogParameters.NONE) {
            return new Vector4f(1.0f);
        }
        return new Vector4f(parameters.red(), parameters.green(), parameters.blue(), parameters.alpha());
    }

    record SystemFrameTime(float frameTime, float frameTimeCounter, int frameCounter) {
    }

    private void warnIfUnfilled(final Matrix4f modelView, final Matrix4f projection) {
        if (this.warnedIdentityMatrices || !(modelView.equals(new Matrix4f(), 0.0f) || projection.equals(new Matrix4f(), 0.0f))) {
            return;
        }
        this.warnedIdentityMatrices = true;
        Metallum.LOGGER.warn(
                "[metallum-iris] CapturedRenderingState still holds identity matrices at frame time;"
                        + " pack terrain will be shaded with no camera transform."
                        + " Iris's own capture mixins are expected to fill these — check they are applied."
        );
    }

    // ------------------------------------------------------------------
    // std140 writing
    // ------------------------------------------------------------------

    private void write(
            final ByteBuffer out,
            final MetalIrisShaderCompiler.UniformMember member,
            final Frame frame,
            final OptionalDouble alphaTestReference
    ) {
        if (writeOfficialUniform(out, member, alphaTestReference)) {
            return;
        }
        int at = member.offset();
        switch (member.name()) {
            // --- matrices (exact) ---
            case "gbufferModelView", "iris_ModelViewMatrix", "shadowModelView" -> putMat4(out, at, frame.modelView());
            case "gbufferModelViewInverse", "iris_ModelViewMatrixInverse", "shadowModelViewInverse" ->
                    putMat4(out, at, frame.modelViewInverse());
            case "gbufferProjection", "iris_ProjectionMatrix", "shadowProjection" -> putMat4(out, at, frame.projection());
            case "gbufferProjectionInverse", "iris_ProjectionMatrixInverse", "shadowProjectionInverse" ->
                    putMat4(out, at, frame.projectionInverse());
            case "gbufferPreviousModelView" -> putMat4(out, at, this.previousModelView);
            case "gbufferPreviousProjection" -> putMat4(out, at, this.previousProjection);
            // Iris 1.11.2 registers these names as externally-managed core
            // uniforms. Sodium terrain has no Mojang transient blocks, so
            // the same frame matrices are its authoritative source.
            case CORE_MODEL_VIEW_INVERSE -> putMat4(out, at, frame.modelViewInverse());
            case CORE_PROJECTION_INVERSE -> putMat4(out, at, frame.projectionInverse());
            case CORE_NORMAL_MATRIX -> putMat3(out, at, frame.normalMatrix());
            case "normalMatrix" -> putMat3(out, at, frame.normalMatrix());

            // --- positions (exact) ---
            case "cameraPosition" -> putVec3(out, at, frame.cameraPosition());
            case "previousCameraPosition" -> putVec3(out, at, this.previousCameraPosition);
            case "sunPosition" -> putVec3(out, at, frame.sunPosition().x, frame.sunPosition().y, frame.sunPosition().z);
            case "moonPosition" -> putVec3(out, at, frame.moonPosition().x, frame.moonPosition().y, frame.moonPosition().z);
            case "shadowLightPosition" ->
                    putVec3(out, at, frame.shadowLightPosition().x, frame.shadowLightPosition().y, frame.shadowLightPosition().z);
            case "upPosition" -> putVec3(out, at, frame.upPosition().x, frame.upPosition().y, frame.upPosition().z);

            // --- externally-managed Mojang/Sodium fog state ---
            case "fogColor", "skyColor" -> putVec3(out, at, frame.fogColor());
            case "iris_FogColor" -> putVec4(
                    out,
                    at,
                    frame.irisFogColor().x,
                    frame.irisFogColor().y,
                    frame.irisFogColor().z,
                    frame.irisFogColor().w
            );
            case "fogDensity", "iris_FogDensity" -> out.putFloat(at, frame.fogDensity());
            case "fogStart", "iris_FogStart" -> out.putFloat(at, frame.fogStart());
            case "fogEnd", "iris_FogEnd" -> out.putFloat(at, frame.fogEnd());

            // --- time (exact) ---
            case "frameTimeCounter" -> out.putFloat(at, frame.frameTimeCounter());
            case "frameTime" -> out.putFloat(at, frame.frameTime());
            case "frameCounter" -> out.putInt(at, frame.frameCounter());
            case "framemod8" -> out.putFloat(at, frame.frameCounter() % 8);
            case "framemod2" -> out.putFloat(at, frame.frameCounter() % 2);
            case "worldTime" -> out.putInt(at, frame.worldTime());
            case "worldDay" -> out.putInt(at, frame.worldDay());
            case "sunAngle", "timeAngle" -> out.putFloat(at, frame.sunAngle());
            case "shadowAngle" -> out.putFloat(at, frame.shadowAngle());
            case "sunPathRotation" -> out.putFloat(at, this.sunPathRotation);

            // --- viewport (exact) ---
            case "viewWidth" -> out.putFloat(at, frame.viewWidth());
            case "viewHeight" -> out.putFloat(at, frame.viewHeight());
            case "aspectRatio" -> out.putFloat(at, frame.viewWidth() / Math.max(1.0f, frame.viewHeight()));
            case "near" -> out.putFloat(at, NEAR_PLANE);
            case "far" -> out.putFloat(at, frame.far());

            // --- weather / player state ---
            case "rainStrength", "wetness" -> out.putFloat(at, frame.rainStrength());
            case "screenBrightness" -> out.putFloat(at, frame.screenBrightness());
            case "eyeAltitude" -> out.putFloat(at, (float) frame.cameraPosition().y);

            default -> reportUnsupported(out, member);
        }
    }

    /** Writes a value evaluated by Iris's own fixed/custom uniform graph. */
    boolean writeOfficialUniform(
            final ByteBuffer out,
            final MetalIrisShaderCompiler.UniformMember member
    ) {
        return writeOfficialUniform(out, member, OptionalDouble.empty());
    }

    private boolean writeOfficialUniform(
            final ByteBuffer out,
            final MetalIrisShaderCompiler.UniformMember member,
            final OptionalDouble alphaTestReference
    ) {
        if ("renderStage".equals(member.name())) {
            requireDynamicDrawType(member, "int");
            // Iris 1.11.2 CommonUniforms reads
            // GbufferPrograms.getCurrentPhase().ordinal(). The owning Metal
            // pipeline supplies the same WorldRenderingPhase state directly.
            out.putInt(member.offset(), this.renderStageSource.getAsInt());
            return true;
        }
        if ("iris_currentAlphaTest".equals(member.name())) {
            if (member.arrayCount() != 0 || !"float".equals(member.type())) {
                throw new IllegalStateException(
                        "Iris internal uniform 'iris_currentAlphaTest' must be float, got "
                                + member.type() + (member.arrayCount() == 0 ? "" : "[]")
                );
            }
            out.putFloat(
                    member.offset(),
                    (float) alphaTestReference.orElseGet(
                            CapturedRenderingState.INSTANCE::getCurrentAlphaTest
                    )
            );
            return true;
        }
        if ("iris_LightmapTextureMatrix".equals(member.name())) {
            if (member.arrayCount() != 0 || !"mat4".equals(member.type())) {
                throw new IllegalStateException(
                        "Iris built-in uniform 'iris_LightmapTextureMatrix' must be mat4, got "
                                + member.type() + (member.arrayCount() == 0 ? "" : "[]")
                );
            }
            // Sodium supplies unpacked light coordinates in [0, 240]. Iris's
            // built-in replacement maps them to the centers of the 16 texels.
            putMat4(out, member.offset(), LIGHTMAP_TEXTURE_MATRIX);
            return true;
        }
        // ProgramUniforms owns a separate fixed-input instance from the
        // CustomUniforms dependency graph. Prefer the program cache whenever
        // both holders expose the same common uniform name.
        if (this.programFixedInputs != null && this.programFixedInputs.containsKey(member.name())) {
            return writeFixedInput(out, member);
        }
        if (this.customUniforms == null || !this.customUniforms.hasVariable(member.name())) {
            return false;
        }
        // UniformMember uses 0 for an ordinary scalar/vector/matrix and a
        // positive value only for an explicit GLSL array declarator.
        if (member.arrayCount() > 0) {
            throw new IllegalStateException(
                    "Iris uniform graph cannot supply array member '" + member.name()
                            + "' (count=" + member.arrayCount() + ")"
            );
        }

        Object expression = this.customUniforms.getVariable(member.name());
        if (!(expression instanceof CachedUniform uniform)) {
            throw new IllegalStateException(
                    "Iris custom uniform '" + member.name() + "' is not a CachedUniform"
            );
        }
        return writeCachedUniform(out, member, uniform);
    }

    private static boolean writeCachedUniform(
            final ByteBuffer out,
            final MetalIrisShaderCompiler.UniformMember member,
            final CachedUniform uniform
    ) {
        FunctionReturn value = new FunctionReturn();
        uniform.writeTo(value);
        int at = member.offset();
        switch (member.type()) {
            case "bool" -> out.putInt(at, value.booleanReturn ? 1 : 0);
            case "int" -> out.putInt(at, value.intReturn);
            case "float" -> out.putFloat(at, value.floatReturn);
            case "vec2" -> {
                Vector2f vector = customObject(member, value, Vector2f.class);
                putVec2(out, at, vector.x, vector.y);
            }
            case "vec3" -> {
                Vector3f vector = customVector3(member, value.objectReturn, "Iris uniform");
                putVec3(out, at, vector.x, vector.y, vector.z);
            }
            case "vec4" -> {
                Vector4f vector = customObject(member, value, Vector4f.class);
                putVec4(out, at, vector.x, vector.y, vector.z, vector.w);
            }
            case "ivec2" -> {
                Vector2i vector = customObject(member, value, Vector2i.class);
                putIVec2(out, at, vector.x, vector.y);
            }
            case "ivec3" -> {
                Vector3i vector = customObject(member, value, Vector3i.class);
                putIVec3(out, at, vector.x, vector.y, vector.z);
            }
            case "ivec4" -> {
                Vector4i vector = customObject(member, value, Vector4i.class);
                putIVec4(out, at, vector.x, vector.y, vector.z, vector.w);
            }
            case "mat4" -> putMat4(
                    out,
                    at,
                    packProjectionUniform(member.name(), customObject(member, value, Matrix4fc.class))
            );
            default -> throw new IllegalStateException(
                    "Iris uniform graph produced unsupported GLSL type '" + member.type()
                            + "' for '" + member.name() + "'"
            );
        }
        return true;
    }

    private boolean writeFixedInput(
            final ByteBuffer out,
            final MetalIrisShaderCompiler.UniformMember member
    ) {
        if (this.programFixedInputs == null || !this.programFixedInputs.containsKey(member.name())) {
            return false;
        }
        if (member.arrayCount() > 0) {
            throw new IllegalStateException(
                    "Iris fixed uniform graph cannot supply array member '" + member.name()
                            + "' (count=" + member.arrayCount() + ")"
            );
        }
        return writeCachedUniform(out, member, this.programFixedInputs.getUniform(member.name()));
    }

    private static <T> T fixedObject(
            final MetalIrisShaderCompiler.UniformMember member,
            final FunctionReturn value,
            final Class<T> expected
    ) {
        if (!expected.isInstance(value.objectReturn)) {
            throw new IllegalStateException(
                    "Iris fixed uniform '" + member.name() + "' (" + member.type() + ") evaluated to "
                            + (value.objectReturn == null ? "null" : value.objectReturn.getClass().getName())
                            + ", expected " + expected.getName()
            );
        }
        return expected.cast(value.objectReturn);
    }

    private static Matrix4fc packProjectionUniform(final String name, final Matrix4fc value) {
        return packProjectionUniform(name, value, MetalIrisDepthConvention.enabledForMetalBackend());
    }

    static Matrix4fc packProjectionUniform(
            final String name,
            final Matrix4fc value,
            final boolean enabled
    ) {
        return switch (name) {
            case "gbufferProjection", "gbufferPreviousProjection", "dhProjection", "dhPreviousProjection",
                    "iris_ProjectionMatrix" ->
                    MetalIrisDepthConvention.packProjection(value, enabled);
            case "gbufferProjectionInverse", "dhProjectionInverse", "iris_ProjectionMatrixInverse" ->
                    MetalIrisDepthConvention.packProjectionInverse(value, enabled);
            default -> value;
        };
    }

    private static <T> T customObject(
            final MetalIrisShaderCompiler.UniformMember member,
            final FunctionReturn value,
            final Class<T> expected
    ) {
        if (!expected.isInstance(value.objectReturn)) {
            throw new IllegalStateException(
                    "Iris uniform '" + member.name() + "' (" + member.type() + ") evaluated to "
                            + (value.objectReturn == null ? "null" : value.objectReturn.getClass().getName())
                            + ", expected " + expected.getName()
            );
        }
        return expected.cast(value.objectReturn);
    }

    private static Vector3f customVector3(
            final MetalIrisShaderCompiler.UniformMember member,
            final Object value,
            final String source
    ) {
        if (value instanceof Vector3f vector) {
            return vector;
        }
        if (value instanceof Vector3d vector) {
            return new Vector3f((float) vector.x, (float) vector.y, (float) vector.z);
        }
        throw new IllegalStateException(
                source + " '" + member.name() + "' (" + member.type() + ") evaluated to "
                        + (value == null ? "null" : value.getClass().getName())
                        + ", expected Vector3f or Vector3d"
        );
    }

    private void reportUnsupported(final ByteBuffer out, final MetalIrisShaderCompiler.UniformMember member) {
        if (this.strict) {
            throw new IllegalStateException(
                    "Iris uniform '" + member.name() + "' (" + member.type()
                            + ") has no Metal or Iris value source"
            );
        }
        // Translation-only tests deliberately use the legacy relaxed constructor.
        if (this.unsupported.add(member.name())) {
            Metallum.LOGGER.debug(
                    "[metallum-iris] uniform '{}' ({}) has no value source; zero-filled",
                    member.name(), member.type()
            );
        }
    }

    private static void zero(final ByteBuffer buffer) {
        for (int index = 0; index + Long.BYTES <= buffer.capacity(); index += Long.BYTES) {
            buffer.putLong(index, 0L);
        }
        for (int index = buffer.capacity() & ~(Long.BYTES - 1); index < buffer.capacity(); index++) {
            buffer.put(index, (byte) 0);
        }
    }

    /** std140 mat4: four column-major vec4s, 16 bytes each. */
    private static void putMat4(final ByteBuffer out, final int offset, final Matrix4fc matrix) {
        float[] values = new float[16];
        matrix.get(values);
        for (int index = 0; index < 16; index++) {
            out.putFloat(offset + index * Float.BYTES, values[index]);
        }
    }

    /** std140 mat3: three columns padded to a vec4 stride, 12 useful bytes each. */
    private static void putMat3(final ByteBuffer out, final int offset, final Matrix3f matrix) {
        float[] values = new float[9];
        matrix.get(values);
        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) {
                out.putFloat(offset + column * 16 + row * Float.BYTES, values[column * 3 + row]);
            }
        }
    }

    private static void putVec3(final ByteBuffer out, final int offset, final Vector3d value) {
        putVec3(out, offset, (float) value.x, (float) value.y, (float) value.z);
    }

    private static void putVec2(final ByteBuffer out, final int offset, final float x, final float y) {
        out.putFloat(offset, x);
        out.putFloat(offset + 4, y);
    }

    private static void putVec3(final ByteBuffer out, final int offset, final float x, final float y, final float z) {
        out.putFloat(offset, x);
        out.putFloat(offset + 4, y);
        out.putFloat(offset + 8, z);
    }

    private static void putVec4(
            final ByteBuffer out, final int offset, final float x, final float y, final float z, final float w
    ) {
        putVec3(out, offset, x, y, z);
        out.putFloat(offset + 12, w);
    }

    private static void putIVec2(final ByteBuffer out, final int offset, final int x, final int y) {
        out.putInt(offset, x);
        out.putInt(offset + 4, y);
    }

    private static void putIVec3(
            final ByteBuffer out,
            final int offset,
            final int x,
            final int y,
            final int z
    ) {
        putIVec2(out, offset, x, y);
        out.putInt(offset + 8, z);
    }

    private static void putIVec4(
            final ByteBuffer out,
            final int offset,
            final int x,
            final int y,
            final int z,
            final int w
    ) {
        putIVec3(out, offset, x, y, z);
        out.putInt(offset + 12, w);
    }
}
