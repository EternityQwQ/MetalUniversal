package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import kroppeb.stareval.function.FunctionReturn;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.item.IrisItemLightProvider;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.cached.BooleanCachedUniform;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Fills the generated {@code MetallumIrisUniforms} block once per frame.
 *
 * <p>Iris on GL feeds a shader pack through ~200 individually-registered
 * uniforms. B2-1 does not reproduce that: the translation lane collects every
 * loose uniform a pack's {@code gbuffers_terrain} declares into one std140
 * block (offsets computed by {@link MetalCrossShaderCompiler} and verified
 * against SPIR-V reflection by the offline gate), and this class writes values
 * into it by name.</p>
 *
 * <p>The production constructor consumes Iris's own {@link CustomUniforms}
 * graph. It contains both the official fixed inputs and the pack's
 * {@code variable.*}/{@code uniform.*} expressions, so values such as
 * {@code daytime}, {@code taaOffset} and {@code lightDirView} use the same
 * suppliers and evaluation order as Iris. The switch below is only for values
 * Iris marks externally managed by the active Mojang/Sodium draw.</p>
 *
 * <p>Values marked <i>exact</i> come from real game state; <i>approximate</i>
 * ones are documented at their case labels. Sodium's own per-draw values
 * ({@code u_RegionOffset} and friends) are <b>not</b> here — they stay in the
 * push-constant block {@link MetalDrawContext} writes.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalUniformValues implements AutoCloseable {
    private static final float NEAR_PLANE = 0.05f;
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
    private final @Nullable PackShadowDirectives shadowDirectives;
    private final @Nullable CustomUniforms customUniforms;
    private final @Nullable FrameUpdateNotifier updateNotifier;
    private final IntSupplier renderStageSource;
    private final boolean strict;
    /**
     * Pack directive {@code oldHandLight}: main-hand emission is raised to the
     * off-hand's when the off-hand is brighter. Only the switch fallbacks for
     * {@code heldBlockLightValue} use it; the production custom-uniform graph
     * applies it itself inside Iris's {@code IdMapUniforms.HeldItemSupplier}.
     */
    private final boolean oldHandLight;
    private final List<Block> blocks = new ArrayList<>();
    private final Set<String> unsupported = new HashSet<>();
    private final Matrix4f previousModelView = new Matrix4f();
    private final Matrix4f previousProjection = new Matrix4f();
    private final Vector3d previousCameraPosition = new Vector3d();
    private @Nullable ShadowMatrixSet currentShadowMatrices;
    private boolean warnedIdentityMatrices;
    private boolean warnedShadowFallback;
    private boolean closed;

    /**
     * A registered block. The GPU buffer is allocated lazily: registration
     * happens while the pack loads, which is not necessarily a moment where a
     * device is reachable (the offline gate builds a device of its own and
     * never installs it on RenderSystem).
     */
    private static final class Block {
        private final Object token;
        private final String label;
        private final List<IrisMetalGlslLinker.UniformMember> layout;
        private final int size;
        private final OptionalDouble alphaTestReference;
        private @Nullable GpuBuffer buffer;
        private @Nullable ByteBuffer staging;
        private @Nullable MetalDevice device;

        private Block(
                final Object token,
                final String label,
                final List<IrisMetalGlslLinker.UniformMember> layout,
                final int size,
                final OptionalDouble alphaTestReference
        ) {
            this.token = token;
            this.label = label;
            this.layout = layout;
            this.size = size;
            this.alphaTestReference = alphaTestReference;
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
        this(sunPathRotation, null, null, () -> 0, false, null, false);
    }

    IrisMetalUniformValues(final float sunPathRotation, final IntSupplier renderStageSource) {
        this(sunPathRotation, null, null, renderStageSource, false, null, false);
    }

    IrisMetalUniformValues(
            final float sunPathRotation,
            final CustomUniforms customUniforms,
            final FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource,
            final PackShadowDirectives shadowDirectives
    ) {
        this(sunPathRotation, customUniforms, updateNotifier, renderStageSource, shadowDirectives, false);
    }

    IrisMetalUniformValues(
            final float sunPathRotation,
            final CustomUniforms customUniforms,
            final FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource,
            final PackShadowDirectives shadowDirectives,
            final boolean oldHandLight
    ) {
        this(sunPathRotation, customUniforms, updateNotifier, renderStageSource, true, shadowDirectives, oldHandLight);
    }

    private IrisMetalUniformValues(
            final float sunPathRotation,
            final @Nullable CustomUniforms customUniforms,
            final @Nullable FrameUpdateNotifier updateNotifier,
            final IntSupplier renderStageSource,
            final boolean strict,
            final @Nullable PackShadowDirectives shadowDirectives,
            final boolean oldHandLight
    ) {
        if ((customUniforms == null) != (updateNotifier == null)) {
            throw new IllegalArgumentException("Iris custom uniforms and frame notifier must be supplied together");
        }
        this.sunPathRotation = sunPathRotation;
        this.shadowDirectives = shadowDirectives;
        this.customUniforms = customUniforms;
        this.updateNotifier = updateNotifier;
        this.renderStageSource = Objects.requireNonNull(renderStageSource, "renderStageSource");
        this.strict = strict;
        this.oldHandLight = oldHandLight;
    }

    private static OptionalDouble alphaTestReference(final IrisMetalGlslLinker.LinkedRasterProgram program) {
        return OptionalDouble.of(program.program().alphaTest().reference());
    }

    /**
     * Allocates the block for one terrain kind. Called during registry
     * activation, once per successfully translated program.
     */
    void register(
            final ShaderKey kind,
            final IrisMetalGlslLinker.LinkedRasterProgram program
    ) {
        register(kind, kind.getName(), program);
    }

    void register(
            final Object token,
            final String label,
            final IrisMetalGlslLinker.LinkedRasterProgram program
    ) {
        if (program.uniformLayout().isEmpty()) {
            return;
        }
        register(token, label, program.uniformLayout(), program.uniformBlockSize(),
                alphaTestReference(program));
    }

    /**
     * Registers the hoisted std140 block of a compute program. Compute has no
     * alpha test, so its block carries no reference value.
     */
    void registerCompute(
            final Object token,
            final String label,
            final List<IrisMetalGlslLinker.UniformMember> layout,
            final int size
    ) {
        if (layout.isEmpty()) {
            return;
        }
        register(token, label, layout, size, OptionalDouble.empty());
    }

    private void register(
            final Object token,
            final String label,
            final List<IrisMetalGlslLinker.UniformMember> layout,
            final int size,
            final OptionalDouble alphaTestReference
    ) {
        for (Block block : this.blocks) {
            if (block.token.equals(token)) {
                if (block.size != size
                        || !block.layout.equals(layout)
                        || !block.alphaTestReference.equals(alphaTestReference)) {
                    throw new IllegalStateException(
                            "Iris uniform token was registered with two different layouts or alpha-test references: "
                                    + token
                    );
                }
                return;
            }
        }
        this.blocks.add(new Block(token, label, layout, size, alphaTestReference));
    }

    /**
     * The slice to bind for a kind, or {@code null} if the kind has no uniform
     * block. Allocates and fills on first use so that a terrain draw reaching
     * the pass before the first {@link #updateFrame} still binds real values.
     */
    @Nullable
    GpuBufferSlice slice(final ShaderKey kind) {
        return slice((Object) kind);
    }

    @Nullable
    GpuBufferSlice slice(final Object token) {
        if (this.closed) {
            return null;
        }
        for (Block block : this.blocks) {
            if (block.token.equals(token) && block.buffer != null) {
                return block.buffer.slice();
            }
        }
        return null;
    }

    /**
     * Allocates and fills every registered block. Must run outside any encoder
     * — see {@link MetalWorldRenderingPipeline#beginLevelRendering()}.
     */
    void prewarm(final MetalDevice device) {
        if (this.closed || this.blocks.isEmpty()) {
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
            } catch (Throwable failure) {
                if (this.strict) {
                    throw new IllegalStateException("Iris uniform graph failed to update", failure);
                }
                if (this.unsupported.add("<custom-uniform-frame>")) {
                    Metallum.LOGGER.warn("[metallum-iris] Iris uniform graph failed to update", failure);
                }
            }
        }
        if (this.blocks.isEmpty()) {
            return;
        }
        Frame frame = sampleFrame();
        logFrameUniforms(frame);
        long perfUploadBytes = 0L;
        for (Block block : this.blocks) {
            if (block.buffer != null) {
                upload(block, frame);
                if (MetalPerformanceProbe.ENABLED) {
                    perfUploadBytes += block.size;
                }
            }
        }
        MetalPerformanceProbe.recordUniformUpload(perfUploadBytes);
        this.previousModelView.set(frame.modelView());
        this.previousProjection.set(frame.projection());
        this.previousCameraPosition.set(frame.cameraPosition());
    }

    /** Last wall-clock nanosecond at which {@link #logFrameUniforms} printed. */
    private static long lastUniformLogNanos;

    /**
     * {@code -Dmetallum.iris.debug.logUniforms}: once per second, logs the
     * frame inputs the uniform block is filled from, so the H3 frame-time
     * values can be settled from a release log without a debugger. The same
     * values are recorded into {@link MetalProbeReport} (the game's log file is
     * not retrievable on the target device): first 3 lines, then one per
     * minute, capped at {@link #UNIFORM_PROBE_LINE_LIMIT}. Inert when the
     * switch is unset.
     */
    private static void logFrameUniforms(final Frame frame) {
        if (!MetalDebugSwitches.LOG_UNIFORMS) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastUniformLogNanos < 1_000_000_000L) {
            return;
        }
        lastUniformLogNanos = now;
        Metallum.LOGGER.info(
                "[metallum-iris][debug] uniforms frameTime={} frameTimeCounter={}"
                        + " frameCounter={} viewWidth={} viewHeight={}",
                frame.frameTime(), frame.frameTimeCounter(), frame.frameCounter(),
                frame.viewWidth(), frame.viewHeight()
        );
        recordUniformsToProbe(frame, now);
    }

    /** Probe-file cap for the per-frame uniform log. */
    private static final int UNIFORM_PROBE_LINE_LIMIT = 20;
    private static int uniformProbeLines;
    private static long lastUniformProbeNanos;

    /** First 3 samples, then one per minute, total capped; never throws. */
    private static synchronized void recordUniformsToProbe(final Frame frame, final long now) {
        if (uniformProbeLines >= UNIFORM_PROBE_LINE_LIMIT) {
            return;
        }
        if (uniformProbeLines >= 3 && now - lastUniformProbeNanos < 60_000_000_000L) {
            return;
        }
        uniformProbeLines++;
        lastUniformProbeNanos = now;
        MetalProbeReport.record("uniforms frameTime=" + frame.frameTime()
                + " frameTimeCounter=" + frame.frameTimeCounter()
                + " frameCounter=" + frame.frameCounter()
                + " viewWidth=" + frame.viewWidth()
                + " viewHeight=" + frame.viewHeight());
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
    ByteBuffer lastUpload(final ShaderKey kind) {
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
        for (IrisMetalGlslLinker.UniformMember member : block.layout) {
            if (usesMojangCoreTransforms(block.token) && isCoreDrawUniform(member.name())) {
                continue;
            }
            write(staging, member, frame, block.alphaTestReference);
        }
        staging.rewind();
        block.device.createCommandEncoder().writeToBuffer(block.buffer.slice(), staging);
    }

    int drawBlockSize(final Object token) {
        Block block = findBlock(token);
        return block != null && block.layout.stream().anyMatch(member -> isDynamicDrawUniform(member.name()))
                ? block.size
                : 0;
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

    void materializeDraw(
            final Object token,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection
    ) {
        Block block = findBlock(token);
        if (block == null || block.staging == null) {
            throw new IllegalStateException("Iris uniform block is not prepared for " + token);
        }
        materializeDrawUniforms(
                block.staging,
                block.layout,
                output,
                dynamicTransforms,
                projection,
                this.renderStageSource.getAsInt(),
                usesMojangCoreTransforms(token)
        );
    }

    static void materializeDrawUniforms(
            final ByteBuffer base,
            final List<IrisMetalGlslLinker.UniformMember> layout,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection,
            final int renderStage
    ) {
        materializeDrawUniforms(
                base, layout, output, dynamicTransforms, projection, renderStage, false
        );
    }

    private static void materializeDrawUniforms(
            final ByteBuffer base,
            final List<IrisMetalGlslLinker.UniformMember> layout,
            final ByteBuffer output,
            final @Nullable ByteBuffer dynamicTransforms,
            final @Nullable ByteBuffer projection,
            final int renderStage,
            final boolean coreDraw
    ) {
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

        for (IrisMetalGlslLinker.UniformMember member : layout) {
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
                default -> {
                }
            }
        }
    }

    private @Nullable Block findBlock(final Object token) {
        for (Block block : this.blocks) {
            if (block.token.equals(token)) {
                return block;
            }
        }
        return null;
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
        return isCoreDrawUniform(name) || "renderStage".equals(name);
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
            final IrisMetalGlslLinker.UniformMember member,
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
            final IrisMetalGlslLinker.UniformMember member,
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
        for (Block block : this.blocks) {
            if (block.buffer != null) {
                block.buffer.close();
            }
        }
        this.blocks.clear();
    }

    // ------------------------------------------------------------------
    // Frame sampling
    // ------------------------------------------------------------------

    private record Frame(
            Matrix4f modelView,
            Matrix4f modelViewInverse,
            Matrix4f projection,
            Matrix4f projectionInverse,
            Matrix4f engineProjection,
            Matrix4f shadowModelView,
            Matrix4f shadowModelViewInverse,
            Matrix4f shadowProjection,
            Matrix4f shadowProjectionInverse,
            Matrix3f normalMatrix,
            Vector3d cameraPosition,
            Vector4f sunPosition,
            Vector4f moonPosition,
            Vector4f shadowLightPosition,
            Vector4f upPosition,
            Vector3d fogColor,
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
            int frameCounter,
            int moonPhase,
            float cloudHeight,
            float nightVision,
            float blindFactor,
            float darknessFactor,
            int bedrockLevel,
            int currentRenderedEntity,
            Vector2i atlasSize,
            int heldItemId,
            int heldItemId2,
            int heldBlockLightValue,
            int heldBlockLightValue2,
            boolean isElytraFlying,
            boolean heavyFog,
            float playerMood,
            float darknessLightFactor,
            float velocity,
            Vector4f lightningBoltPosition,
            int isEyeInWater,
            Vector2i eyeBrightness,
            Vector3d eyePosition,
            Vector3d relativeEyePosition,
            boolean isNether
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
        } catch (Throwable t) {
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
                new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f(),
                new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f(),
                new Matrix3f(),
                new Vector3d(),
                new Vector4f(0.0f, 100.0f, 0.0f, 0.0f),
                new Vector4f(0.0f, -100.0f, 0.0f, 0.0f),
                new Vector4f(0.0f, 100.0f, 0.0f, 0.0f),
                new Vector4f(0.0f, 100.0f, 0.0f, 0.0f),
                new Vector3d(), 0.0f, 0.0f, 256.0f, 0.0f, systemTime.frameTime(),
                0.25f, 0.25f, 0.0f, 1.0f, 1.0f, 1.0f, 256.0f,
                systemTime.frameTimeCounter(), 0, 0, systemTime.frameCounter(),
                0, 192.0f, 0.0f, 0.0f, 0.0f, 0,
                -1, new Vector2i(), -1, -1, 0, 0, false, false, 0.0f, 0.0f, 0.0f, new Vector4f(),
                0, new Vector2i(), new Vector3d(), new Vector3d(), false
        );
    }

    private Frame sampleLiveFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        ClientLevel level = minecraft.level;

        Matrix4f modelView = new Matrix4f(state.getGbufferModelView());
        // Engine-space (zero-to-one) projection, as Sodium draws it. Pack-space
        // matrices convert this for fragment reconstruction; the vertex-stage
        // member keeps the engine values so gl_Position stays in the pass's
        // depth convention (see MetalDebugSwitches.VERTEX_ENGINE_PROJECTION).
        Matrix4f engineProjection = new Matrix4f(state.getGbufferProjection());
        Matrix4f projection = MetalIrisDepthConvention.packProjection(engineProjection);
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
        float shadowAngle = CelestialUniforms.getSunAngle(day) / 360.0f;
        ShadowMatrixSet shadowMatrices = computeShadowMatrices(
                modelView, modelViewInverse, projection, projectionInverse,
                engineProjection, cameraPosition, shadowAngle
        );
        this.currentShadowMatrices = shadowMatrices;
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
        var fogParameters = ((FogStorage) minecraft.gameRenderer).sodium$getFogParameters();

        // Sky / celestial / effect uniforms (M4). Sources mirror Iris GL:
        // WorldTimeUniforms (moonPhase, via the camera environment attribute
        // probe), IrisExclusiveUniforms (cloudHeight, bedrockLevel) and
        // CommonUniforms/HardcodedCustomUniforms (nightVision, blindFactor,
        // darknessFactor).
        int moonPhase = camera == null ? 0
                : camera.attributeProbe()
                .getValue(EnvironmentAttributes.MOON_PHASE, tickDelta)
                .index();
        float cloudHeight = camera == null || level == null
                ? 192.0f
                : camera.attributeProbe()
                .getValue(EnvironmentAttributes.CLOUD_HEIGHT, tickDelta);
        float nightVision = 0.0f;
        float blindFactor = 0.0f;
        float darknessFactor = 0.0f;
        var cameraEntity = minecraft.getCameraEntity();
        if (cameraEntity instanceof LivingEntity living) {
            try {
                float nightVisionScale = GameRenderer.nightVisionScale(living, tickDelta);
                if (nightVisionScale > 0.0f) {
                    nightVision = Mth.clamp(nightVisionScale, 0.0f, 1.0f);
                }
            } catch (NullPointerException ignored) {
                // Iris's CommonUniforms catches the same NPE: the vanilla scale
                // helper assumes the entity actually has the effect.
            }
            MobEffectInstance blindness = living.getEffect(MobEffects.BLINDNESS);
            if (blindness != null) {
                float blindnessValue = blindness.isInfiniteDuration()
                        ? 1.0f
                        : Mth.clamp(blindness.getDuration() / 20.0f, 0.0f, 1.0f);
                float blindFactorSqrt = Mth.clamp(blindnessValue * 2.0f - 1.0f, 0.0f, 1.0f);
                blindFactor = blindFactorSqrt * blindFactorSqrt;
            }
            MobEffectInstance darkness = living.getEffect(MobEffects.DARKNESS);
            if (darkness != null) {
                darknessFactor = darkness.getBlendFactor(living, tickDelta);
            }
        }
        int bedrockLevel = level == null ? 0 : level.dimensionType().minY();

        // IrisExclusiveUniforms.eyePosition (pinned 20e226b line 80) and
        // relativeEyePosition (line 82): the camera entity's interpolated eye
        // position and the unshifted camera position minus it. Both are zero
        // without a camera entity.
        Vector3d eyePosition = new Vector3d();
        if (cameraEntity != null) {
            Vec3 eyes = cameraEntity.getEyePosition(tickDelta);
            eyePosition.set(eyes.x, eyes.y, eyes.z);
        }
        Vector3d relativeEyePosition = new Vector3d(cameraPosition).sub(eyePosition);
        int eyeInWater = eyeInWater(minecraft);
        Vector2i eyeBrightness = sampleEyeBrightness(minecraft);

        // World-program uniforms whose upstream value sources live in Iris's
        // dynamic holder (CommonUniforms.addDynamicUniforms) or in a per-draw
        // attribute. The production custom-uniform graph carries most of the
        // player-state names below; these sampled values are the writer's
        // switch fallbacks and the only source for entityId and atlasSize.
        int currentRenderedEntity = state.getCurrentRenderedEntity();
        float darknessLightFactor = state.getDarknessLightFactor();
        Vector2i atlasSize = sampleAtlasSize();

        LocalPlayer player = minecraft.player;
        int heldItemId = -1;
        int heldItemId2 = -1;
        int heldBlockLightValue = 0;
        int heldBlockLightValue2 = 0;
        boolean isElytraFlying = false;
        if (player != null) {
            isElytraFlying = player.isFallFlying();
            heldItemId = heldItemId(player, InteractionHand.MAIN_HAND);
            heldItemId2 = heldItemId(player, InteractionHand.OFF_HAND);
            heldBlockLightValue = heldBlockLightValue(player, InteractionHand.MAIN_HAND, this.oldHandLight);
            heldBlockLightValue2 = heldBlockLightValue(player, InteractionHand.OFF_HAND, false);
        }
        float playerMood = minecraft.getCameraEntity() instanceof LocalPlayer cameraPlayer
                ? Mth.clamp(cameraPlayer.getCurrentMood(), 0.0f, 1.0f)
                : 0.0f;
        boolean heavyFog = level != null
                && minecraft.gui.hud.getBossOverlay().shouldCreateWorldFog();
        // Upstream reads getGameTimeDeltaPartialTick(true) here (ignore freeze),
        // while CapturedRenderingState.tickDelta comes from the (false) variant.
        float lightningPartialTick = minecraft.getDeltaTracker() == null
                ? tickDelta
                : minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vector4f lightningBoltPosition = sampleLightningBolt(level, lightningPartialTick, cameraPosition);
        // Upstream HardcodedCustomUniforms.getVelocity (never registered by the
        // port's non-dynamic graph): camera travel since the previous sampled
        // frame, i.e. the same delta the CameraPositionTracker smooths on.
        float velocity = (float) cameraPosition.distance(this.previousCameraPosition);
        // Sildur's Vibrant declares `uniform bool isNether;` with neither an
        // Iris nor an OptiFine supplier (deferred.fsh:80 and its per-dimension
        // variants). The faithful value is the current dimension; upstream's
        // own dimension check is WorldTimeUniforms.java:33
        // (`Iris.getCurrentDimension() == DimensionId.NETHER`).
        boolean isNether = Iris.getCurrentDimension() == DimensionId.NETHER;

        return new Frame(
                modelView,
                modelViewInverse,
                projection,
                projectionInverse,
                engineProjection,
                shadowMatrices.modelView(),
                shadowMatrices.modelViewInverse(),
                shadowMatrices.packProjection(),
                shadowMatrices.packProjectionInverse(),
                normalMatrix,
                cameraPosition,
                sun,
                moon,
                shadowLight,
                up,
                state.getFogColor(),
                state.getFogDensity(),
                fogParameters.environmentalStart(),
                fogParameters.environmentalEnd(),
                tickDelta,
                systemTime.frameTime(),
                sunAngle,
                shadowAngle,
                level == null ? 0.0f : level.getRainLevel(tickDelta),
                minecraft.options == null ? 1.0f : minecraft.options.gamma().get().floatValue(),
                mainTarget.width,
                mainTarget.height,
                renderDistance * 16.0f,
                systemTime.frameTimeCounter(),
                level == null ? 0 : (int) (level.getDefaultClockTime() % 24000L),
                level == null ? 0 : (int) (level.getDefaultClockTime() / 24000L),
                systemTime.frameCounter(),
                moonPhase,
                cloudHeight,
                nightVision,
                blindFactor,
                darknessFactor,
                bedrockLevel,
                currentRenderedEntity,
                atlasSize,
                heldItemId,
                heldItemId2,
                heldBlockLightValue,
                heldBlockLightValue2,
                isElytraFlying,
                heavyFog,
                playerMood,
                darknessLightFactor,
                velocity,
                lightningBoltPosition,
                eyeInWater,
                eyeBrightness,
                eyePosition,
                relativeEyePosition,
                isNether
        );
    }

    /**
     * Upstream {@code IdMapUniforms.HeldItemSupplier.update()}: the pack's
     * item id map keyed by the stack's item-model id (falling back to the
     * registry id). Returns -1 when there is no item-id map yet.
     */
    private static int heldItemId(final LocalPlayer player, final InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Item item = stack.getItem();
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        Identifier id = model != null ? model : BuiltInRegistries.ITEM.getKey(item);
        Object2IntFunction<NamespacedId> itemIds = WorldRenderingSettings.INSTANCE.getItemIds();
        if (itemIds == null) {
            return -1;
        }
        return itemIds.applyAsInt(new NamespacedId(id.getNamespace(), id.getPath()));
    }

    /**
     * Upstream {@code IdMapUniforms.HeldItemSupplier.update()}: the held
     * item's light emission. With the pack's {@code oldHandLight} directive
     * the main hand reports the brighter of the two hands.
     */
    private static int heldBlockLightValue(
            final LocalPlayer player,
            final InteractionHand hand,
            final boolean applyOldHandLight
    ) {
        ItemStack stack = player.getItemInHand(hand);
        int emission = ((IrisItemLightProvider) stack.getItem()).getLightEmission(player, stack);
        if (applyOldHandLight && hand == InteractionHand.MAIN_HAND) {
            ItemStack offHand = player.getItemInHand(InteractionHand.OFF_HAND);
            int offEmission = ((IrisItemLightProvider) offHand.getItem()).getLightEmission(player, offHand);
            if (emission < offEmission) {
                emission = offEmission;
            }
        }
        return emission;
    }

    /**
     * Upstream {@code IrisExclusiveUniforms}: the first lightning bolt in the
     * level relative to the unshifted camera position, w=1; (0,0,0,0) when no
     * bolt exists (or there is no level).
     */
    private static Vector4f sampleLightningBolt(
            final @Nullable ClientLevel level,
            final float partialTick,
            final Vector3d cameraPosition
    ) {
        if (level == null) {
            return new Vector4f();
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof LightningBolt bolt) {
                Vec3 pos = bolt.getPosition(partialTick);
                return new Vector4f(
                        (float) (pos.x - cameraPosition.x),
                        (float) (pos.y - cameraPosition.y),
                        (float) (pos.z - cameraPosition.z),
                        1.0f
                );
            }
        }
        return new Vector4f();
    }

    /**
     * Upstream {@code CommonUniforms.addDynamicUniforms}'s atlasSize. The
     * world pipeline's albedo texture is the block atlas, read through the
     * engine texture manager because the Metal path resolves Sampler0 at draw
     * time instead of from a GL texture binding. Returns (0,0) when the atlas
     * is not resident, matching the upstream zero fallback.
     */
    private static Vector2i sampleAtlasSize() {
        AbstractTexture atlas = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS);
        if (atlas == null || atlas.getTexture() == null) {
            return new Vector2i();
        }
        return new Vector2i(atlas.getTexture().getWidth(0), atlas.getTexture().getHeight(0));
    }

    /**
     * Upstream {@code CommonUniforms.isEyeInWater()} (pinned 20e226b,
     * {@code CommonUniforms.java:363-376}): the fluid the main camera is in,
     * with lava reported as air for spectators. {@code Camera.getFluidInCamera}
     * and {@code FogType} both exist unchanged in the 26.2 tree the pinned Iris
     * release compiles against.
     */
    private static int eyeInWater(final Minecraft minecraft) {
        Camera camera = minecraft.gameRenderer.mainCamera();
        if (camera == null) {
            return 0;
        }
        FogType submersionType = camera.getFluidInCamera();
        boolean isSpectator = minecraft.player != null && minecraft.player.isSpectator();
        if (submersionType == FogType.WATER) {
            return 1;
        } else if (!isSpectator && submersionType == FogType.LAVA) {
            return 2;
        } else if (submersionType == FogType.POWDER_SNOW) {
            return 3;
        }
        return 0;
    }

    /**
     * Upstream {@code CommonUniforms.getEyeBrightness()} (20e226b
     * {@code CommonUniforms.java:295-307}): the block and sky light at the
     * camera entity's eye block, each already scaled by 16. Zero without a
     * camera entity or level.
     */
    private static Vector2i sampleEyeBrightness(final Minecraft minecraft) {
        var cameraEntity = minecraft.getCameraEntity();
        ClientLevel level = minecraft.level;
        if (cameraEntity == null || level == null) {
            return new Vector2i();
        }
        Vec3 feet = cameraEntity.position();
        Vec3 eyes = new Vec3(feet.x, cameraEntity.getEyeY(), feet.z);
        BlockPos eyeBlockPos = BlockPos.containing(eyes);
        int blockLight = level.getBrightness(LightLayer.BLOCK, eyeBlockPos);
        int skyLight = level.getBrightness(LightLayer.SKY, eyeBlockPos);
        return new Vector2i(blockLight * 16, skyLight * 16);
    }

    /**
     * Builds the pack shadow matrices the same way upstream GL Iris does
     * ({@code ShadowMatrices}/{@code ShadowRenderer}): an orthographic
     * projection over the shadow distance plus a sun-relative, grid-snapped
     * model view. Falls back to the camera matrices — the previous behavior —
     * when the pack requests a perspective shadow projection (not implemented
     * here), the directives are missing, or the A/B switch is enabled.
     */
    private ShadowMatrixSet computeShadowMatrices(
            final Matrix4f cameraModelView,
            final Matrix4f cameraModelViewInverse,
            final Matrix4f cameraProjectionPack,
            final Matrix4f cameraProjectionInversePack,
            final Matrix4f cameraProjectionZeroToOne,
            final Vector3d cameraPosition,
            final float shadowAngle
    ) {
        if (MetalDebugSwitches.NO_SHADOW_MATRICES || this.shadowDirectives == null
                || this.shadowDirectives.getFov() != null) {
            if (!MetalDebugSwitches.NO_SHADOW_MATRICES && !this.warnedShadowFallback) {
                this.warnedShadowFallback = true;
                Metallum.LOGGER.warn(
                        "[metallum-iris] shadow matrices unavailable ({}); falling back to camera matrices",
                        this.shadowDirectives == null
                                ? "no shadow directives"
                                : "pack requests a perspective shadow projection"
                );
            }
            return new ShadowMatrixSet(
                    cameraModelView,
                    cameraModelViewInverse,
                    cameraProjectionZeroToOne,
                    cameraProjectionPack,
                    cameraProjectionInversePack,
                    false
            );
        }

        float halfPlaneLength = this.shadowDirectives.getDistance();
        // Ortho is built in the port's zero-to-one depth convention for the
        // engine (Sodium ChunkRenderMatrices) and converted into the [-1, 1]
        // space packs expect for the uniform block. Computing both here keeps
        // the sun-angle rotation and grid snapping identical between the
        // caster pass and the shaderpack uniforms.
        Matrix4f zeroToOneProjection = new Matrix4f().setOrthoSymmetric(
                halfPlaneLength * 2.0F, halfPlaneLength * 2.0F,
                this.shadowDirectives.getNearPlane(), this.shadowDirectives.getFarPlane(), true
        );
        Matrix4f shadowProjection = MetalIrisDepthConvention.packProjection(zeroToOneProjection);
        Matrix4f shadowProjectionInverse = new Matrix4f(shadowProjection).invert();

        float skyAngle = shadowAngle < 0.25F ? shadowAngle + 0.75F : shadowAngle - 0.25F;
        Matrix4f shadowModelView = new Matrix4f()
                .rotateX((float) Math.toRadians(90.0))
                .rotateZ((float) Math.toRadians(skyAngle * -360.0F))
                .rotateX((float) Math.toRadians(this.sunPathRotation));
        float intervalSize = this.shadowDirectives.getIntervalSize();
        if (Math.abs(intervalSize) != 0.0F) {
            shadowModelView.translate(
                    (float) cameraPosition.x % intervalSize - intervalSize / 2.0F,
                    (float) cameraPosition.y % intervalSize - intervalSize / 2.0F,
                    (float) cameraPosition.z % intervalSize - intervalSize / 2.0F
            );
        }
        Matrix4f shadowModelViewInverse = new Matrix4f(shadowModelView).invert();

        return new ShadowMatrixSet(
                shadowModelView,
                shadowModelViewInverse,
                zeroToOneProjection,
                shadowProjection,
                shadowProjectionInverse,
                true
        );
    }

    /**
     * Shadow matrices from the current captured frame state. Used as the
     * fallback when a caller (the caster pass) asks before the per-frame
     * uniform sample has produced {@link #currentShadowMatrices()}.
     */
    private ShadowMatrixSet computeLiveShadowMatrices() {
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        Matrix4f modelView = new Matrix4f(state.getGbufferModelView());
        Matrix4f projectionPack = MetalIrisDepthConvention.packProjection(state.getGbufferProjection());
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        Vec3 cameraPos = camera == null ? Vec3.ZERO : camera.position();
        Vector3d cameraPosition = new Vector3d(cameraPos.x, cameraPos.y, cameraPos.z);
        float shadowAngle = CelestialUniforms.getSunAngle(CelestialUniforms.isDay()) / 360.0f;
        return computeShadowMatrices(
                modelView,
                new Matrix4f(modelView).invert(),
                projectionPack,
                new Matrix4f(projectionPack).invert(),
                new Matrix4f(state.getGbufferProjection()),
                cameraPosition,
                shadowAngle
        );
    }

    /**
     * The shadow matrices computed by this frame's uniform sample (or a live
     * computation if the frame has not been sampled yet), shared with the
     * shadow caster pass so grid snapping happens exactly once per frame.
     */
    ShadowMatrixSet currentShadowMatrices() {
        ShadowMatrixSet cached = this.currentShadowMatrices;
        if (cached != null) {
            return cached;
        }
        ShadowMatrixSet computed = computeLiveShadowMatrices();
        this.currentShadowMatrices = computed;
        return computed;
    }

    /**
     * One shadow-matrix computation, two depth conventions: the engine
     * zero-to-one ortho for {@code ChunkRenderMatrices}/culling and the pack
     * [-1, 1] ortho for the uniform block. {@code packValid} is false when the
     * pack cannot use real shadow matrices (no directives, perspective FOV or
     * the A/B switch), in which case the camera matrices are carried through.
     */
    record ShadowMatrixSet(
            Matrix4f modelView,
            Matrix4f modelViewInverse,
            Matrix4f zeroToOneProjection,
            Matrix4f packProjection,
            Matrix4f packProjectionInverse,
            boolean packValid
    ) {
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
            final IrisMetalGlslLinker.UniformMember member,
            final Frame frame,
            final OptionalDouble alphaTestReference
    ) {
        if (writeOfficialUniform(out, member, alphaTestReference)) {
            return;
        }
        int at = member.offset();
        switch (member.name()) {
            // --- matrices (exact) ---
            case "gbufferModelView", "iris_ModelViewMatrix" -> putMat4(out, at, frame.modelView());
            case "shadowModelView" -> putMat4(out, at, frame.shadowModelView());
            case "gbufferModelViewInverse", "iris_ModelViewMatrixInverse" -> putMat4(out, at, frame.modelViewInverse());
            case "shadowModelViewInverse" -> putMat4(out, at, frame.shadowModelViewInverse());
            case "gbufferProjection", "iris_ProjectionMatrix" -> putMat4(out, at, frame.projection());
            // Vertex-stage twin of the pack projection (see
            // MetalDebugSwitches.VERTEX_ENGINE_PROJECTION): gl_Position computed
            // in vertex code must stay in the engine's zero-to-one drawing
            // space, while the fragment stage keeps the OpenGL-space member.
            case IrisMetalGlslLinker.VERTEX_ENGINE_PROJECTION_UNIFORM ->
                    putMat4(out, at, frame.engineProjection());
            case "shadowProjection" -> putMat4(out, at, frame.shadowProjection());
            case "gbufferProjectionInverse", "iris_ProjectionMatrixInverse" -> putMat4(out, at, frame.projectionInverse());
            case "shadowProjectionInverse" -> putMat4(out, at, frame.shadowProjectionInverse());
            case "gbufferPreviousModelView" -> putMat4(out, at, this.previousModelView);
            case "gbufferPreviousProjection" -> putMat4(out, at, this.previousProjection);
            case "iris_NormalMat", "normalMatrix" -> putMat3(out, at, frame.normalMatrix());

            // --- positions (exact) ---
            case "cameraPosition" -> putVec3(out, at, frame.cameraPosition());
            case "previousCameraPosition" -> putVec3(out, at, this.previousCameraPosition);
            // IrisExclusiveUniforms.java:80/82: real eye position and the
            // unshifted camera position minus it, not zero.
            case "relativeEyePosition" -> putVec3(out, at, frame.relativeEyePosition());
            case "eyePosition" -> putVec3(out, at, frame.eyePosition());
            case "sunPosition" -> putVec3(out, at, frame.sunPosition().x, frame.sunPosition().y, frame.sunPosition().z);
            case "moonPosition" -> putVec3(out, at, frame.moonPosition().x, frame.moonPosition().y, frame.moonPosition().z);
            case "shadowLightPosition" ->
                    putVec3(out, at, frame.shadowLightPosition().x, frame.shadowLightPosition().y, frame.shadowLightPosition().z);
            case "upPosition" -> putVec3(out, at, frame.upPosition().x, frame.upPosition().y, frame.upPosition().z);

            // --- externally-managed Mojang/Sodium fog state ---
            case "fogColor", "skyColor" -> putVec3(out, at, frame.fogColor());
            case "iris_FogColor" ->
                    putVec4(out, at, (float) frame.fogColor().x, (float) frame.fogColor().y, (float) frame.fogColor().z, 1.0f);
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
            // VanillaUniforms.java:14 (world programs): main render target size.
            case "iris_ScreenSize" -> putVec2(out, at, frame.viewWidth(), frame.viewHeight());
            case "near" -> out.putFloat(at, NEAR_PLANE);
            case "far" -> out.putFloat(at, frame.far());

            // --- weather / player state ---
            case "rainStrength", "wetness" -> out.putFloat(at, frame.rainStrength());
            case "screenBrightness" -> out.putFloat(at, frame.screenBrightness());
            // timeBrightness peaks at noon: upstream HardcodedCustomUniforms
            // (20e226b L145-158) derives it from timeAngle = worldDayTime /
            // 24000, i.e. max(sin(2*pi*timeAngle), 0). frame.worldTime() is the
            // same dayTime % 24000 the hardcoded uniform uses; frame.sunAngle()
            // is the eased OptiFine celestial angle and is not this input.
            case "timeBrightness" -> out.putFloat(at, Math.max(0.0f,
                    (float) Math.sin(frame.worldTime() / 24000.0 * Math.PI * 2.0)));
            case "eyeBrightness" -> putIVec2(out, at, frame.eyeBrightness().x, frame.eyeBrightness().y);
            // CommonUniforms.generalCommonUniforms (line 176) computes
            // eyeBrightnessSmooth as a SmoothedVec2f seeded from getEyeBrightness.
            // Reproducing the smoothing needs PackDirectives.eyeBrightnessHalfLife,
            // which is not plumbed into this writer; the production custom-uniform
            // graph supplies the true smoothed value, so the no-graph fallback
            // writes the unsmoothed brightness.
            case "eyeBrightnessSmooth" -> putIVec2(out, at, frame.eyeBrightness().x, frame.eyeBrightness().y);
            case "eyeAltitude" -> out.putFloat(at, (float) frame.cameraPosition().y);
            // CommonUniforms.isEyeInWater (20e226b CommonUniforms.java:363-376).
            case "isEyeInWater" -> out.putInt(at, frame.isEyeInWater());
            // TODO(M6.1.1): upstream HardcodedCustomUniforms.getShadowFade()
            // (20e226b L161-163) feeds CelestialUniforms.getSunAngle in degrees
            // into a 0..1-shaped expression; that looks dimensionally broken in
            // the pinned tree, so the faithful-value fallback is unclear. BSL
            // does not consume this uniform (its custom-uniform graph wins), so
            // the old 0.0 fallback is kept until an active pack needs it.
            case "shadowFade" -> out.putFloat(at, 0.0f);

            // --- sky / celestial / effect uniforms (M4) ---
            case "moonPhase" -> out.putInt(at, frame.moonPhase());
            case "cloudHeight" -> out.putFloat(at, frame.cloudHeight());
            case "nightVision" -> out.putFloat(at, frame.nightVision());
            case "blindFactor" -> out.putFloat(at, frame.blindFactor());
            case "darknessFactor" -> out.putFloat(at, frame.darknessFactor());
            case "bedrockLevel" -> out.putInt(at, frame.bedrockLevel());
            // OptiFine biome flags: Iris GL has no supplier for these either,
            // so the GLSL default of 0 is the faithful value. They are listed
            // explicitly so the sky programs (which declare them) are accepted
            // in strict mode instead of being reported as port gaps.
            case "isDesert", "isMesa", "isCold", "isSwamp", "isMushroom",
                 "isSavanna", "isJungle" -> out.putFloat(at, 0.0f);

            // --- dynamic / per-draw world uniforms (CR world programs) ---
            // entityId: CommonUniforms.addDynamicUniforms' fallback supplier
            // (CommonUniforms.java:73) reading the captured draw entity. The
            // entity path goes through its own attribute in Iris; this is the
            // value prewarm and non-entity draws see (-1 by default).
            case "entityId" -> out.putInt(at, frame.currentRenderedEntity());
            // CommonUniforms.addDynamicUniforms (CommonUniforms.java:96): the
            // resource reload counter, state-only and safe to read per fill.
            case "textureReloadCount" -> out.putInt(at, CapturedRenderingState.INSTANCE.getTextureReloadCount());
            // entityColor / blockEntityId / currentRenderedItemId: the ONCE
            // defaults generalCommonUniforms registers when no vertex
            // attribute or draw-time item id supplies them (CommonUniforms
            // .java:162-164).
            case "entityColor" -> putVec4(out, at, 0.0f, 0.0f, 0.0f, 0.0f);
            case "blockEntityId", "currentRenderedItemId" -> out.putInt(at, -1);
            // IdMapUniforms.java:33-37 held-item uniforms. Frame sampling
            // mirrors HeldItemSupplier.update(), including invalidate(): id -1
            // and light 0 with no player.
            case "heldItemId" -> out.putInt(at, frame.heldItemId());
            case "heldItemId2" -> out.putInt(at, frame.heldItemId2());
            case "heldBlockLightValue" -> out.putInt(at, frame.heldBlockLightValue());
            case "heldBlockLightValue2" -> out.putInt(at, frame.heldBlockLightValue2());
            // IrisExclusiveUniforms.java:58/65 PER_TICK booleans.
            case "isElytraFlying" -> out.putInt(at, frame.isElytraFlying() ? 1 : 0);
            case "heavyFog" -> out.putInt(at, frame.heavyFog() ? 1 : 0);
            // Sildur's Vibrant 2.02 declares `uniform bool isNether;` in
            // deferred.fsh:80 (and the per-dimension copies) but neither the
            // pack nor upstream Iris/OptiFine registers a supplier; upstream
            // GL leaves the GLSL default (false) in every dimension. The port
            // supplies the faithful dimension answer instead: true in the
            // Nether, matching upstream WorldTimeUniforms.java:33
            // (`Iris.getCurrentDimension() == DimensionId.NETHER`).
            case "isNether" -> out.putInt(at, frame.isNether() ? 1 : 0);
            // CommonUniforms.getPlayerMood (CommonUniforms.java:173) and
            // CapturedRenderingState.getDarknessLightFactor (line 149).
            case "playerMood" -> out.putFloat(at, frame.playerMood());
            case "darknessLightFactor" -> out.putFloat(at, frame.darknessLightFactor());
            // HardcodedCustomUniforms.getVelocity (pinned tree L54): camera
            // travel since the previous sampled frame. The port never registers
            // that holder, so CR's unconditional `uniform float velocity;`
            // needs this case or prewarm throws.
            case "velocity" -> out.putFloat(at, frame.velocity());
            // IrisExclusiveUniforms.java:92-102: bolt position relative to the
            // unshifted camera, w=1; all zeros without a bolt.
            case "lightningBoltPosition" -> putVec4(
                    out, at,
                    frame.lightningBoltPosition().x,
                    frame.lightningBoltPosition().y,
                    frame.lightningBoltPosition().z,
                    frame.lightningBoltPosition().w
            );
            // CommonUniforms.addDynamicUniforms atlasSize (line 58): for world
            // passes the albedo texture is the block atlas, sampled into the
            // frame.
            case "atlasSize" -> putIVec2(out, at, frame.atlasSize().x, frame.atlasSize().y);
            // maxBlindnessDarkness has no upstream Iris supplier anywhere (not
            // in generalCommonUniforms, not in ExternallyManagedUniforms, and
            // absent from the pinned tree). Packs that define it, like CR's
            // `uniform.float.maxBlindnessDarkness = max(blindness,
            // darknessFactor)`, are evaluated by the custom-uniform graph; when
            // nothing supplies it Iris GL leaves the GLSL default. Write the
            // faithful 0, following the biome-flags precedent above.
            case "maxBlindnessDarkness" -> out.putFloat(at, 0.0f);
            // MakeUp-UltraFast 9.5f defines ditherShift/taaOffset only inside its
            // `#if AA_TYPE > 0` block in shaders.properties, but lib/dither.glsl
            // declares `uniform float ditherShift;` under MC_VERSION >= 11300
            // unconditionally whenever it is included, so AA-off links it with
            // no supplier. (All taa_offset.glsl include sites happen to be
            // AA-gated today, so AA-off does not declare taaOffset; its default
            // keeps the option-gated pair symmetric.) Upstream Iris semantics
            // for a declared uniform with no supplier is the GLSL default:
            // write 0. The pack custom-uniform graph is consulted first, so an
            // AA-enabled config still uses the pack's own expression.
            case "ditherShift" -> out.putFloat(at, 0.0f);
            case "taaOffset" -> putVec2(out, at, 0.0f, 0.0f);
            // Complementary Unbound r5.9.3 defines these biome smooth-flags in
            // shaders.properties:269-275 as smooth(..., if(in(biome, BIOME_*), 1, 0), ...),
            // but no upstream Iris build (pinned 20e226b included) supplies the
            // BIOME_* constants, so stareval drops the variables and desktop GL
            // leaves the GLSL default 0. Write that faithful 0; if upstream ever
            // adds the constants, the custom-uniform graph resolves first and
            // these cases become inert.
            case "inNetherWastes", "inCrimsonForest", "inWarpedForest",
                 "inBasaltDeltas", "inSoulValley", "inPaleGarden", "inSulfurCaves" ->
                    out.putFloat(at, 0.0f);
            // Solas Shader V3.7b (shaders.properties:169-183) uses the same
            // OptiFine biome flags with modern BIOME_* constants that pinned
            // Iris does not define (`BIOME_JAGGED_PEAKS`, `BIOME_CHERRY_GROVE`,
            // `BIOME_LUSH_CAVES`, `BIOME_DEEP_DARK`, `BIOME_PALE_GARDEN`), so
            // stareval drops the expressions and desktop GL leaves the GLSL
            // default 0. Write that faithful zero; expressions that reference
            // at least one known constant (isDesert/isSwamp/...) resolve in the
            // graph as before and are unaffected.
            case "isSnowy", "isCherryGrove", "isLushCaves", "isDeepDark", "isPaleGarden" ->
                    out.putFloat(at, 0.0f);
            // Nostalgia v5.1 declares `uniform vec2 skyCaptureResolution` in
            // world0/world1/deferred.fsh and sspt.fsh, but neither the pack nor
            // pinned Iris registers a supplier (no `uniform.vec2.` entry, no
            // upstream uniform). Desktop GL leaves it (0,0); Nostalgia guards
            // its use, so the GLSL default is the faithful value.
            case "skyCaptureResolution" -> putVec2(out, at, 0.0f, 0.0f);
            // Mellow v3.4.1a integrates Voxy (an absent mod): under
            // `#ifndef VOXY_TERRAIN` its normal gbuffers/deferred/composite
            // programs declare the vx* family, and the following
            // `#ifdef VOXY / #else` declares dh* because the port (like any
            // build without the mod) never defines VOXY. Both families are in
            // the linked uniform block. Desktop GL treats statically unused
            // vx* as inactive and never assigns them without a Voxy mod, so
            // they read the GLSL default zero. The dh* matrix family is
            // supplied by MatrixUniforms (pinned Iris), so only the vx* names
            // need writer defaults; the pack custom-uniform graph is
            // consulted first, so a future Voxy bridge that starts supplying
            // these names would win automatically.
            case "vxProjInv", "vxProj", "vxProjPrev", "vxModelView", "vxModelViewInv",
                 "vxModelViewPrev" ->
                    putZeroMat4(out, at);
            case "vxRenderDistance" -> out.putInt(at, 0);
            // Mellow defines these custom uniforms in shaders.properties but the
            // pinned stareval (20e226b) cannot resolve their expressions:
            // nightStrength/dayStrength use an undefined `pi` constant
            // (lines 213-214), and the MC_VERSION >= 12104 fogAmount branch
            // (line 239) references the undefined BIOME_PALE_GARDEN constant.
            // The upstream graph drops those variables, and desktop GL leaves
            // the declared uniforms at the GLSL default 0. Write that faithful
            // zero; the graph is consulted first, so a future upstream fix
            // makes these cases inert automatically.
            case "nightStrength", "dayStrength", "fogAmount" -> out.putFloat(at, 0.0f);
            // Bliss v2.1.2 (Chocapic13 lineage) declares these legacy values in
            // its gbuffers/dimension includes. Pinned Iris 20e226b supplies none
            // of them (no CommonUniforms/IrisExclusiveUniforms entry) and the
            // pack defines no custom uniform for them either, so the graph has
            // no variable and desktop GL leaves the declared uniform at the
            // GLSL default. Declaration sites: sunIntensity/skyIntensity/
            // moonIntensity/nsunColor in dimensions/all_translucent.fsh:85-101,
            // skyIntensityNight in world1/gbuffers_weather.fsh:13, farPlane in
            // composite1.fsh:108, lightCol in gbuffers_weather.fsh:9,
            // Moon_Weather_properties in composite11.fsh:43. Write the faithful
            // zero; the graph is consulted first, so if upstream ever adds a
            // supplier these cases become inert.
            //
            // Deliberately NOT defaulted here: dhFarPlane/dhNearPlane
            // (CommonUniforms.java:183-184), currentPlayerHealth/
            // maxPlayerHealth (IrisExclusiveUniforms.java:64/66) and the
            // gameplay flags they feed (CriticalDamageTaken/oneHeart/
            // threeHeart/MinorDamageTaken, shaders.properties:469-482) all
            // resolve through the production custom-uniform graph.
            case "skyIntensityNight", "skyIntensity", "moonIntensity", "sunIntensity",
                 "farPlane" -> out.putFloat(at, 0.0f);
            case "sunColor", "nsunColor" -> putVec3(out, at, 0.0f, 0.0f, 0.0f);
            case "Moon_Weather_properties", "lightCol" -> putVec4(out, at, 0.0f, 0.0f, 0.0f, 0.0f);

            default -> reportUnsupported(out, member);
        }
    }

    /**
     * Test gate: dispatches one linked uniform member through the real
     * value-source resolution using a neutral frame. Build the writer with the
     * relaxed constructor so unsupported names are recorded by
     * {@link #reportUnsupported} instead of thrown; {@link #unsupportedNames()}
     * then tells the gate which members a live writer would have rejected.
     * The scratch buffer is returned so gates can assert the written bytes.
     */
    ByteBuffer writeUniformForGate(final IrisMetalGlslLinker.UniformMember member) {
        ByteBuffer scratch = ByteBuffer.allocate(
                member.offset() + Math.max(1, member.byteSize())
        ).order(ByteOrder.nativeOrder());
        write(scratch, member, neutralFrame(), OptionalDouble.empty());
        return scratch;
    }

    /** Names recorded as having no value source (relaxed writers only). */
    Set<String> unsupportedNames() {
        return Set.copyOf(this.unsupported);
    }

    /** Writes a value evaluated by Iris's own fixed/custom uniform graph. */
    boolean writeOfficialUniform(
            final ByteBuffer out,
            final IrisMetalGlslLinker.UniformMember member
    ) {
        return writeOfficialUniform(out, member, OptionalDouble.empty());
    }

    private boolean writeOfficialUniform(
            final ByteBuffer out,
            final IrisMetalGlslLinker.UniformMember member,
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
        // iris_currentAlphaTest and alphaTestRef are the same upstream supplier
        // (IrisInternalUniforms.java:41/45, both float PER-draw); alphaTestRef is
        // OptiFine compatibility and had no writer case before.
        if ("iris_currentAlphaTest".equals(member.name()) || "alphaTestRef".equals(member.name())) {
            if (member.arrayCount() != 0 || !"float".equals(member.type())) {
                throw new IllegalStateException(
                        "Iris internal uniform '" + member.name() + "' must be float, got "
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

        FunctionReturn value = new FunctionReturn();
        var variable = this.customUniforms.getVariable(member.name());
        variable.evaluateTo(this.customUniforms, value);
        int at = member.offset();
        switch (member.type()) {
            case "bool" -> out.putInt(at, value.booleanReturn ? 1 : 0);
            // Upstream may supply a bool for a uniform a pack declares as int
            // (Nostalgia's `uniform int hideGUI` vs CommonUniforms.java:144
            // `.uniform1b(... "hideGUI" ...)`). GL uploads bools as 0/1 ints;
            // FunctionReturn.booleanReturn is the only field a
            // BooleanCachedUniform fills, so an int member fed by a Boolean
            // graph variable must read it instead of the untouched intReturn.
            case "int" -> out.putInt(at,
                    variable instanceof BooleanCachedUniform
                            ? (value.booleanReturn ? 1 : 0)
                            : value.intReturn);
            case "float" -> out.putFloat(at, value.floatReturn);
            case "vec2" -> {
                Vector2f vector = customObject(member, value, Vector2f.class);
                putVec2(out, at, vector.x, vector.y);
            }
            case "vec3" -> {
                Vector3f vector = customObject(member, value, Vector3f.class);
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

    private static Matrix4fc packProjectionUniform(final String name, final Matrix4fc value) {
        return switch (name) {
            case "gbufferProjection", "gbufferPreviousProjection", "iris_ProjectionMatrix" ->
                    MetalIrisDepthConvention.packProjection(value);
            case "gbufferProjectionInverse", "iris_ProjectionMatrixInverse" ->
                    MetalIrisDepthConvention.packProjectionInverse(value);
            default -> value;
        };
    }

    private static <T> T customObject(
            final IrisMetalGlslLinker.UniformMember member,
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

    private void reportUnsupported(final ByteBuffer out, final IrisMetalGlslLinker.UniformMember member) {
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

    /** GLSL default for a mat4 uniform that no supplier ever assigned. */
    private static void putZeroMat4(final ByteBuffer out, final int offset) {
        for (int index = 0; index < 16; index++) {
            out.putFloat(offset + index * Float.BYTES, 0.0f);
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
