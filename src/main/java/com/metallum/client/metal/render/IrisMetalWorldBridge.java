package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Non-terrain world-program wiring: routes entity / block-entity /
 * moving-block (M1), first-person hand / held item / glint (M2) and
 * particles / weather (M3) draws through the shaderpack's {@code gbuffers_*}
 * programs into the Iris gbuffer, so the final composite no longer erases
 * them.
 *
 * <p>Three entry points cooperate like the terrain bridge:
 * {@link #armForDraw} (from {@code PreparedRenderType.drawFromBuffer} HEAD)
 * resolves the draw's {@link RenderPipeline} to a whitelisted shader key and
 * stashes it as the pending key, {@link #rewriteWorldDescriptor} (from
 * {@code MetalCommandEncoder.createRenderPass}) consumes that key and swaps
 * the vanilla pass attachments for the Iris gbuffer, and
 * {@link #installPipeline} (from {@code IrisRenderPassMixin.setPipeline}
 * HEAD) installs the generation-owned compiled shaderpack pipeline for the
 * draw. Whichever entry point misses, the pass stays vanilla. Fully inert
 * unless the world pass override is enabled (on by default;
 * {@code -Dmetallum.iris.worldPass=off} disables it).</p>
 */
@Environment(EnvType.CLIENT)
public final class IrisMetalWorldBridge {
    /**
     * World-override whitelist: shader keys whose draws are taken over;
     * everything else stays vanilla. Covers M1 (entities, block entities,
     * moving blocks), the reachable M2 hand/held-item keys produced by the
     * {@link MetalIrisPipelines} selectors ({@code HandRenderer}-active
     * branches), the constant glint mapping, and M3 particles/weather. The
     * fullbright HAND_*_BRIGHT and HAND_TEXT_INTENSITY variants are
     * deliberately absent: no selector can produce them (upstream
     * {@code IrisPipelines} never returns them either).
     *
     * <p>M5: 26.2 renders solid/cutout moving blocks (falling anvils/sand,
     * piston heads) with {@code RenderPipelines.SOLID_BLOCK}/{@code CUTOUT_BLOCK},
     * which upstream maps to {@code TERRAIN_SOLID}/{@code TERRAIN_CUTOUT}.
     * Those are vanilla-patch {@code gbuffers_terrain} programs drawn through
     * {@code PreparedRenderType.drawFromBuffer}, not Sodium terrain, so they
     * are whitelisted here (the Sodium terrain keys stay with
     * {@link IrisMetalTerrainBridge}).</p>
     *
     * <p>M6.2: during the shadow pass the same feature-renderer draws resolve
     * through {@link MetalIrisPipelines}' {@code assignToShadow} table, so the
     * shadow keys produced by that table are whitelisted as well (entities,
     * block entities, moving blocks, text, lines, leashes, particles,
     * beacon/end-portal block entities and lightning).</p>
     */
    static final Set<ShaderKey> WORLD_OVERRIDE_KEYS = Set.of(
            ShaderKey.ENTITIES_SOLID,
            ShaderKey.ENTITIES_CUTOUT,
            ShaderKey.ENTITIES_CUTOUT_DIFFUSE,
            ShaderKey.ENTITIES_TRANSLUCENT,
            ShaderKey.ENTITIES_EYES,
            ShaderKey.ENTITIES_EYES_TRANS,
            ShaderKey.BLOCK_ENTITY,
            ShaderKey.BLOCK_ENTITY_BRIGHT,
            ShaderKey.BLOCK_ENTITY_DIFFUSE,
            ShaderKey.BE_TRANSLUCENT,
            ShaderKey.TERRAIN_SOLID,
            ShaderKey.TERRAIN_CUTOUT,
            ShaderKey.MOVING_BLOCK,
            ShaderKey.HAND_CUTOUT,
            ShaderKey.HAND_CUTOUT_DIFFUSE,
            ShaderKey.HAND_TRANSLUCENT,
            ShaderKey.HAND_WATER_DIFFUSE,
            ShaderKey.HAND_TEXT,
            ShaderKey.HAND_TEXT_TRANSLUCENT,
            ShaderKey.GLINT,
            ShaderKey.PARTICLES,
            ShaderKey.PARTICLES_TRANS,
            ShaderKey.WEATHER,
            ShaderKey.SKY_BASIC,
            ShaderKey.SKY_BASIC_COLOR,
            ShaderKey.SKY_TEXTURED,
            ShaderKey.SHADOW_TERRAIN_CUTOUT,
            ShaderKey.SHADOW_TRANSLUCENT,
            ShaderKey.SHADOW_ENTITIES_CUTOUT,
            ShaderKey.SHADOW_BLOCK,
            ShaderKey.SHADOW_TEX,
            ShaderKey.SHADOW_PARTICLES,
            ShaderKey.SHADOW_LINES,
            ShaderKey.SHADOW_LEASH,
            ShaderKey.SHADOW_TEXT,
            ShaderKey.SHADOW_TEXT_INTENSITY,
            ShaderKey.SHADOW_TEXT_BG,
            ShaderKey.SHADOW_BASIC,
            ShaderKey.SHADOW_BEACON_BEAM,
            // M6.4: main-pass lightning, matching upstream
            // IrisPipelines.assignToMain(LIGHTNING -> LIGHTNING). Runs through
            // the same POSITION_COLOR no-albedo path as SHADOW_LIGHTNING.
            ShaderKey.LIGHTNING,
            ShaderKey.SHADOW_LIGHTNING
    );

    private static final ThreadLocal<WorldContext> ACTIVE_WORLD_PASS = new ThreadLocal<>();
    /**
     * Draw-time arming signal set by {@link #armForDraw} at
     * {@code PreparedRenderType.drawFromBuffer} HEAD and consumed by
     * {@link #rewriteWorldDescriptor} when that same draw creates its render
     * pass. 26.2 dispatches entity / block-entity draws by pipeline identity,
     * so this replaces the dead phase-based gate.
     */
    private static final ThreadLocal<ShaderKey> PENDING_WORLD_KEY = new ThreadLocal<>();
    private static final Set<String> REPORTED_INSTALLS = new HashSet<>();
    private static final Set<String> REPORTED_SKIPS = new HashSet<>();
    private static final Set<String> REPORTED_PIPELINE_SKIPS = new HashSet<>();
    private static final Set<String> REPORTED_SHADOW_ATTACHMENTS = new HashSet<>();
    private static final Set<String> REPORTED_MISSING_SAMPLER_TARGETS = new HashSet<>();
    private static final Set<String> REPORTED_NO_ALBEDO_FALLBACKS = new HashSet<>();
    private static final Set<String> REPORTED_CUSTOM_SAMPLERS = new HashSet<>();
    private static final Set<String> REPORTED_INPUT_AUDITS = new HashSet<>();
    private static final Set<String> REPORTED_PER_DRAW_SKIPS = new HashSet<>();
    private static final Set<String> REPORTED_PER_DRAW_MATERIALIZATIONS = new HashSet<>();
    private static boolean drawVertexBuffersReported;
    private static boolean mainDepthCapturedThisFrame;
    private static boolean samplersReported;

    private IrisMetalWorldBridge() {
    }

    /** Resets the per-frame lazy main-depth capture; called from {@code beginLevelRendering}. */
    static void beginFrame() {
        ACTIVE_WORLD_PASS.remove();
        PENDING_WORLD_KEY.remove();
        mainDepthCapturedThisFrame = false;
    }

    /** Clears the active world pass; called from {@code MetalIrisClearMixin} at pass submit. */
    public static void endPass() {
        ACTIVE_WORLD_PASS.remove();
        PENDING_WORLD_KEY.remove();
    }

    /**
     * Arms the world override for the draw that is about to be encoded,
     * called from {@code PreparedRenderType.drawFromBuffer} HEAD. Returns
     * {@code false} (and clears any stale pending key) whenever the draw must
     * stay vanilla: world pass override disabled, no Metal world pipeline,
     * shadow pass, or the source pipeline does not map to a whitelisted
     * shader key. On success the resolved key is stashed in
     * {@link #PENDING_WORLD_KEY} and consumed by
     * {@link #rewriteWorldDescriptor} for this same draw.
     */
    public static boolean armForDraw(final RenderPipeline source) {
        if (!MetalDebugSwitches.WORLD_PASS) {
            return false;
        }
        PENDING_WORLD_KEY.remove();
        MetalWorldRenderingPipeline pipeline = activePipeline();
        if (pipeline == null) {
            return false;
        }
        ShaderKey key = MetalIrisPipelines.getShaderKeyForPipeline(pipeline, source);
        if (key == null || !WORLD_OVERRIDE_KEYS.contains(key)) {
            recordPipelineSkip(source, key, "not-whitelisted");
            return false;
        }
        PENDING_WORLD_KEY.set(key);
        return true;
    }

    /**
     * Arms the world override with an explicitly known shader key, for draw
     * paths that build their own render pass instead of going through
     * {@code PreparedRenderType.drawFromBuffer}: 26.2's
     * {@code QuadParticleFeatureRenderer.executeGroup} and
     * {@code WeatherEffectRenderer.render}. Applies the same gates as the
     * pipeline overload (world-pass switch, active pipeline, shadow pass,
     * whitelist) and stashes the key for the next pass creation, which
     * {@link #rewriteWorldDescriptor} consumes.
     */
    public static boolean armForDraw(final @Nullable ShaderKey key) {
        if (!MetalDebugSwitches.WORLD_PASS) {
            return false;
        }
        PENDING_WORLD_KEY.remove();
        MetalWorldRenderingPipeline pipeline = activePipeline();
        if (pipeline == null) {
            return false;
        }
        if (key == null || !WORLD_OVERRIDE_KEYS.contains(key)) {
            if (key != null) {
                recordSkip(key, "not-whitelisted");
            }
            return false;
        }
        PENDING_WORLD_KEY.set(key);
        return true;
    }

    /**
     * Rewrites the descriptor of a vanilla world pass into an Iris gbuffer
     * write descriptor when the draw that is creating the pass was armed by
     * {@link #armForDraw} with a whitelisted shader key. The pending key
     * is consumed here (and nowhere else), so a draw that was not armed, or
     * whose re-creation races a pass boundary, can never take over an
     * unrelated pass. The first rewritten pass of each frame also lazily
     * copies the vanilla main depth (which by then contains the terrain) into
     * the Iris depthtex0 texture, but only when that pass's program actually
     * declares {@code depthtex0} ({@code -Dmetallum.iris.debug.depthCapture=eager}
     * restores the unconditional capture).
     *
     * <p>By default the rewritten pass keeps the <em>vanilla</em> depth
     * attachment: taken-over draws must test/write the same depth buffer the
     * terrain bridge uses, or translucent terrain (water) passes would never
     * see entity/hand depth and would draw straight through them.
     * {@code -Dmetallum.iris.worldPass.depthVanilla=false} restores the old
     * Iris-depthtex0 attachment for A/B comparison.</p>
     */
    public static RenderPassDescriptor rewriteWorldDescriptor(
            final MetalDevice device,
            final RenderPassDescriptor descriptor
    ) {
        if (!MetalDebugSwitches.WORLD_PASS) {
            return descriptor;
        }
        // Consume the draw's arming signal before any further gate, so a skip
        // can never leave the key pending for the next, unrelated pass.
        ShaderKey key = consumePendingKey();
        if (key == null) {
            return descriptor;
        }
        MetalWorldRenderingPipeline pipeline = activePipeline();
        if (pipeline == null) {
            return descriptor;
        }
        if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
            // M6.2: entity / block-entity / moving-block draws resolve through
            // the shadow mapping table and are rewritten into the shadow
            // targets instead of the main gbuffer. The frame-state gate below
            // is about the main GL framebuffer binding, which does not apply
            // while the standalone caster pass runs.
            RenderPassDescriptor shadowDescriptor = rewriteShadowDescriptor(pipeline, key, descriptor);
            return shadowDescriptor == null ? descriptor : shadowDescriptor;
        }
        if (!pipeline.shouldOverrideCoreShaders(true)) {
            return descriptor;
        }
        ProgramRequest request = shaderKeyToProgramRequest(key);
        Optional<IrisMetalGlslLinker.LinkedRasterProgram> linked =
                pipeline.programs().vanilla(
                        request.program(), request.alphaTest(), request.lines(), request.clouds(), request.inputs()
                );
        if (linked.isEmpty()) {
            recordSkip(key, "no-program");
            return descriptor;
        }
        IrisMetalGlslLinker.LinkedRasterProgram program = linked.orElseThrow();
        int[] drawBuffers = program.program().drawBuffers();
        if (drawBuffers.length == 0) {
            drawBuffers = new int[]{0};
        }
        IrisMetalRenderTargets renderTargets = pipeline.resources().renderTargets();
        GpuTexture mainSceneDepth = vanillaMainDepthTexture();
        if (shouldCaptureMainDepth(
                MetalDebugSwitches.DEPTH_CAPTURE_LAZY,
                mainDepthCapturedThisFrame,
                isSkyKey(key),
                passTargetsTexture(descriptor, mainSceneDepth),
                () -> declaresDepthtex0(program.samplers())
        )) {
            // Only seed depthtex0 from a pass that actually targets the vanilla
            // main scene depth, and never from a sky pass: the sky is the first
            // thing the framegraph renders, so capturing there would freeze
            // depthtex0 before terrain depth exists. Weather (WEATHER_TARGET)
            // and improved-transparency particle passes own separate depth
            // buffers for the same reason. The translucent/hand boundary
            // captures in MetalWorldRenderingPipeline still update depthtex0
            // later.
            mainDepthCapturedThisFrame = true;
            renderTargets.captureMainDepth(device.createCommandEncoder(), mainSceneDepth);
        }
        RenderPassDescriptor.Attachment<OptionalDouble> vanillaDepth =
                MetalDebugSwitches.WORLD_PASS_DEPTH_VANILLA ? descriptor.depthAttachment() : null;
        GpuTextureView depthView = vanillaDepth == null ? null : vanillaDepth.textureView();
        OptionalDouble depthClear = vanillaDepth == null || vanillaDepth.clearValue() == null
                ? OptionalDouble.empty()
                : vanillaDepth.clearValue();
        ACTIVE_WORLD_PASS.set(new WorldContext(pipeline, key));
        recordInstall(key, program.name(), drawBuffers, "draw");
        return renderTargets.createWorldWriteDescriptor(
                descriptor.label().get(), drawBuffers, descriptor.renderArea, depthView, depthClear
        );
    }

    /**
     * M6.2 caster descriptor: swaps the draw's engine attachments for the
     * generation-owned shadow targets (shadowtex0 depth + the program's
     * DRAWBUFFERS shadowcolor targets) while the shadow pass is active.
     * Returns {@code null} when the draw must stay vanilla.
     */
    private static @Nullable RenderPassDescriptor rewriteShadowDescriptor(
            final MetalWorldRenderingPipeline pipeline,
            final ShaderKey key,
            final RenderPassDescriptor descriptor
    ) {
        ProgramRequest request = shaderKeyToProgramRequest(key);
        Optional<IrisMetalGlslLinker.LinkedRasterProgram> linked =
                pipeline.programs().vanilla(
                        request.program(), request.alphaTest(), request.lines(), request.clouds(), request.inputs()
                );
        if (linked.isEmpty()) {
            recordSkip(key, "no-program");
            return null;
        }
        IrisMetalGlslLinker.LinkedRasterProgram program = linked.orElseThrow();
        int[] drawBuffers = program.program().drawBuffers();
        if (drawBuffers.length == 0) {
            drawBuffers = new int[]{0};
        }
        IrisMetalShadowTargets shadows = pipeline.resources().shadowTargets();
        if (shadows == null) {
            recordSkip(key, "no-shadow-targets");
            return null;
        }
        ACTIVE_WORLD_PASS.set(new WorldContext(pipeline, key));
        recordInstall(key, program.name(), drawBuffers, "shadow");
        recordShadowAttachment(pipeline, program, drawBuffers, shadows);
        // B (M6.3): caster draws never clear. The vanilla draw's color/depth
        // clears must not be forwarded to the shadow attachments: a clear here
        // would wipe shadowcolor0 / shadowtex0 mid-pass (upstream clears only
        // at frame start, before any caster).
        return shadows.createShadowGbufferDescriptor(
                descriptor.label().get(),
                drawBuffers,
                null,
                null
        ).descriptor();
    }

    /**
     * E (M6.3): once per caster program, record the shadow attachments the
     * draw writes so a receipt shows the colored-shadow path is live.
     */
    private static void recordShadowAttachment(
            final MetalWorldRenderingPipeline pipeline,
            final IrisMetalGlslLinker.LinkedRasterProgram program,
            final int[] drawBuffers,
            final IrisMetalShadowTargets shadows
    ) {
        StringBuilder formats = new StringBuilder();
        for (int buffer : drawBuffers) {
            if (formats.length() > 0) {
                formats.append(',');
            }
            formats.append(buffer).append(':').append(shadows.colorFormat(buffer));
        }
        String key = program.name() + ":" + formats;
        if (REPORTED_SHADOW_ATTACHMENTS.add(key)) {
            pipeline.receipts().recordEvent("shadow.caster.attachment program=" + program.name()
                    + " colors=" + formats
                    + " depth=" + shadows.shadowDepthTexture().getFormat());
        }
    }

    /**
     * Installs the generation-owned shaderpack pipeline for a non-terrain
     * world draw, bypassing RenderPass's vanilla format/count validation the
     * same way {@link IrisMetalTerrainBridge#installPipeline} does for Sodium.
     * Returns {@code false} (leaving the pass vanilla) unless a rewritten
     * world pass is active and the mapped key is on the world-override whitelist.
     */
    public static boolean installPipeline(
            final RenderPassBackend backend,
            final RenderPipeline source
    ) {
        if (!MetalDebugSwitches.WORLD_PASS) {
            return false;
        }
        if (!(backend instanceof MetalRenderPass metalPass)) {
            return false;
        }
        WorldContext context = currentContext();
        if (context == null) {
            // No rewritten pass for this draw. When the pipeline nevertheless
            // maps to a whitelisted key, a bypass arming path (particles /
            // weather) either did not fire or its pending key was lost; record
            // once per source so a real-device run can confirm the bypass
            // arming works.
            MetalWorldRenderingPipeline active = activePipeline();
            if (active != null && !ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
                ShaderKey resolved = MetalIrisPipelines.getShaderKeyForPipeline(active, source);
                if (resolved != null && WORLD_OVERRIDE_KEYS.contains(resolved)) {
                    recordPipelineSkip(source, resolved, "no-context");
                }
            }
            return false;
        }
        MetalWorldRenderingPipeline pipeline = context.pipeline();
        ShaderKey key = MetalIrisPipelines.getShaderKeyForPipeline(pipeline, source);
        if (key == null || !WORLD_OVERRIDE_KEYS.contains(key)) {
            // A context exists only for armed, whitelisted draws; reaching this
            // means the source pipeline no longer resolves the same way as at
            // arm time. Record once per pipeline to make real-device triage
            // possible instead of failing silently.
            recordPipelineSkip(source, key, "not-whitelisted");
            return false;
        }
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            throw new IllegalStateException("Iris Metal world override has no active Metal device");
        }
        if (!pipeline.compiledPrograms().isOwnedBy(device)) {
            throw new IllegalStateException("Iris Metal world override crossed Metal device ownership");
        }
        ProgramRequest request = shaderKeyToProgramRequest(key);
        Optional<IrisMetalGlslLinker.LinkedRasterProgram> linked =
                pipeline.programs().vanilla(
                        request.program(), request.alphaTest(), request.lines(), request.clouds(), request.inputs()
                );
        if (linked.isEmpty()) {
            recordSkip(key, "no-program");
            return false;
        }
        IrisMetalCompiledPrograms.RasterState state = IrisMetalCompiledPrograms.RasterState.from(
                source, source.getVertexFormatBinding(0)
        );
        Optional<MetalCompiledRenderPipeline> compiled = pipeline.compiledPrograms().vanilla(
                request.program(), request.alphaTest(), request.lines(), request.clouds(), request.inputs(), state
        );
        if (compiled.isEmpty()) {
            recordSkip(key, "no-compiled");
            return false;
        }
        IrisMetalGlslLinker.LinkedRasterProgram program = linked.orElseThrow();
        MetalCompiledRenderPipeline resolved = compiled.orElseThrow();
        checkAttachmentSignature(program, resolved, metalPass, key);
        metalPass.setCompiledPipeline(resolved);
        if (resolved.resource(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME) != null) {
            metalPass.setUniform(
                    IrisMetalGlslLinker.UNIFORM_BLOCK_NAME,
                    pipeline.uniformSlice(key)
            );
        }
        recordInstall(key, program.name(), program.program().drawBuffers(), "install");
        recordInputAudit(key, source, resolved);
        return true;
    }

    /**
     * One-shot per-key probe of the vertex input contract actually used for a
     * taken-over draw: the runtime vertex format elements (which double as the
     * MTL vertex descriptor attributes, assigned sequentially), the generic
     * fallback inputs, and the Metal buffer slots reserved for them. The
     * per-draw bound slot list is recorded by
     * {@link #recordDrawVertexBuffers} from the first draw.
     */
    private static void recordInputAudit(
            final ShaderKey key,
            final RenderPipeline source,
            final MetalCompiledRenderPipeline compiled
    ) {
        if (!REPORTED_INPUT_AUDITS.add(key.getName())) {
            return;
        }
        StringBuilder audit = new StringBuilder("world override attrs key=").append(key.getName());
        VertexFormat format = source.getVertexFormatBinding(0);
        if (format == null) {
            audit.append(" format=<null>");
        } else {
            List<VertexFormatElement> elements = format.getElements();
            audit.append(" formatStride=").append(format.getVertexSize()).append('B');
            for (int location = 0; location < elements.size(); location++) {
                VertexFormatElement element = elements.get(location);
                audit.append(" [").append(location).append(':').append(element.name())
                        .append('@').append(element.offset())
                        .append(' ').append(element.format()).append(']');
            }
        }
        audit.append(" firstSlot=").append(compiled.firstAvailableVertexBufferSlot())
                .append(" vertexBufferCount=").append(compiled.vertexBufferCount())
                .append(" genericSlot=").append(compiled.genericVertexBufferSlot())
                .append(" generic=").append(compiled.genericVertexInputs());
        MetalProbeReport.record(audit.toString());
    }

    /**
     * One-shot record of the vertex buffers the engine actually bound for the
     * first world-override draw, so a real-device run can be checked against
     * the {@link #recordInputAudit} contract.
     */
    static void recordDrawVertexBuffers(final MetalRenderPass metalPass) {
        if (drawVertexBuffersReported || currentContext() == null) {
            return;
        }
        drawVertexBuffersReported = true;
        StringBuilder slots = new StringBuilder();
        int boundCount = 0;
        for (int slot = 0; slot < MetalRenderPass.MAX_VERTEX_BUFFERS; slot++) {
            if (!metalPass.isVertexBufferBound(slot)) {
                continue;
            }
            if (boundCount++ > 0) {
                slots.append(',');
            }
            slots.append(slot);
        }
        MetalProbeReport.record(
                "world override draw vertexBuffers bound=[" + slots + "] count=" + boundCount
        );
    }

    /**
     * Materializes the per-draw members of the shaderpack uniform block
     * ({@code iris_NormalMat}, {@code iris_ModelViewMatInverse},
     * {@code iris_ProjMatInverse}, {@code renderStage}) from the engine's
     * {@code DynamicTransforms}/{@code Projection} for the draw that is binding
     * state now. The per-frame upload skips those members (they must be
     * per-draw), which left {@code iris_NormalMat} zeroed and made directional
     * lighting collapse to a whole-object constant that flickered with any
     * perturbation.
     *
     * <p>Returns {@code null} when the switch is off, no world override is
     * active, the program declares no per-draw members, or the engine bytes are
     * not host-visible; the caller then keeps the shared per-frame slice (the
     * legacy zero/constant behavior). The returned slice is a transient-memory
     * allocation valid until the encoder rotates after submit — the same
     * lifetime the engine's own per-draw uniform slices rely on.</p>
     */
    static @Nullable GpuBufferSlice materializePerDrawUniforms(final MetalRenderPass metalPass) {
        if (!MetalDebugSwitches.WORLD_PASS || !MetalDebugSwitches.WORLD_PASS_PER_DRAW_NORMALS) {
            return null;
        }
        WorldContext context = currentContext();
        if (context == null) {
            return null;
        }
        IrisMetalUniformValues values = context.pipeline().uniformValues();
        int size = values.drawBlockSize(context.key());
        if (size <= 0) {
            return null;
        }
        boolean needsModelView = values.requiresDynamicTransforms(context.key());
        boolean needsProjection = values.requiresProjection(context.key());
        ByteBuffer dynamicTransforms = needsModelView
                ? metalPass.uniformBytes("DynamicTransforms") : null;
        ByteBuffer projection = needsProjection ? metalPass.uniformBytes("Projection") : null;
        if (needsModelView && dynamicTransforms == null) {
            recordPerDrawSkip(context, "missing-dynamic-transforms");
            return null;
        }
        if (needsProjection && projection == null) {
            recordPerDrawSkip(context, "missing-projection");
            return null;
        }
        try (GpuBufferSlice.MappedView output = metalPass.allocateTransient(
                size, metalPass.minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
            values.materializeDraw(context.key(), output.data(), dynamicTransforms, projection);
            recordPerDrawMaterialization(context, size);
            return output.slice();
        }
    }

    private static void recordPerDrawSkip(final WorldContext context, final String reason) {
        if (REPORTED_PER_DRAW_SKIPS.add(context.key().getName() + ":" + reason)) {
            MetalProbeReport.record("world override perDraw skip key=" + context.key().getName()
                    + " reason=" + reason);
        }
    }

    private static void recordPerDrawMaterialization(final WorldContext context, final int size) {
        if (REPORTED_PER_DRAW_MATERIALIZATIONS.add(context.key().getName())) {
            MetalProbeReport.record("world override perDraw materialized key=" + context.key().getName()
                    + " bytes=" + size);
        }
    }

    /**
     * The resolved program's DRAWBUFFERS must match the attachments the pass
     * was rewritten to; when variants disagree (e.g. an entity pass whose key
     * differs from the phase's primary key) this surfaces as a diagnostic
     * exception instead of silently drawing into the wrong attachments.
     */
    private static void checkAttachmentSignature(
            final IrisMetalGlslLinker.LinkedRasterProgram program,
            final MetalCompiledRenderPipeline compiled,
            final MetalRenderPass metalPass,
            final ShaderKey key
    ) {
        MTLPixelFormat[] expected = compiled.colorAttachmentFormats();
        MTLPixelFormat[] actual = metalPass.colorAttachmentFormats();
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException(
                    "Iris Metal world pass attachment signature mismatch: key=" + key
                            + " program=" + program.name()
                            + " drawBuffers=" + Arrays.toString(program.program().drawBuffers())
                            + " pipelineFormats=" + Arrays.toString(expected)
                            + " renderPassFormats=" + Arrays.toString(actual)
            );
        }
    }

    /**
     * Reads and clears the draw-time arming signal. 26.2 dispatches entity /
     * block-entity draws by pipeline identity, so the whitelist decision is
     * made in {@link #armForDraw} and only the consuming draw inherits it.
     */
    private static @Nullable ShaderKey consumePendingKey() {
        ShaderKey key = PENDING_WORLD_KEY.get();
        PENDING_WORLD_KEY.remove();
        return key;
    }

    /**
     * Derives the vanilla program request for a shader key, mirroring
     * {@code IrisRenderingPipeline.createShader}: {@code isFullbright} comes
     * from {@code shouldIgnoreLightmap}, {@code isLines}/{@code isGlint}/
     * {@code isText} from the key, {@code isIE} is always {@code false}.
     */
    public static ProgramRequest shaderKeyToProgramRequest(final ShaderKey key) {
        boolean lines = key == ShaderKey.LINES;
        boolean clouds = key == ShaderKey.CLOUDS || key == ShaderKey.CLOUDS_SODIUM;
        ShaderAttributeInputs inputs = new ShaderAttributeInputs(
                key.getVertexFormat(),
                key.shouldIgnoreLightmap(),
                lines,
                key.isGlint(),
                key.isText(),
                false
        );
        return new ProgramRequest(key.getProgram(), key.getAlphaTest(), lines, clouds, inputs);
    }

    /** Resolves gbuffer/texture samplers for non-terrain world draws (generic subset of the terrain table). */
    static MetalRenderPass.@Nullable TextureViewAndSampler fallbackSampler(
            final String name,
            final Map<String, MetalRenderPass.TextureViewAndSampler> bound
    ) {
        WorldContext context = currentContext();
        if (context == null) {
            return null;
        }
        if (MetalDebugSwitches.LOG_SAMPLERS && !samplersReported) {
            samplersReported = true;
            Metallum.LOGGER.warn("[metallum-iris][debug] worldPass samplers: {}", bound.keySet());
            MetalProbeReport.record("worldPass samplers=" + bound.keySet());
        }
        // Gbuffer world-override draws and shadow-caster draws both use
        // TextureStage.GBUFFERS_AND_SHADOW (texture.gbuffers.*). Stage custom
        // textures take precedence over every name-based default, exactly like
        // upstream's ProgramSamplers.CustomTextureSamplerInterceptor; a miss
        // below must keep the existing legacy/alias resolution unchanged.
        MetalRenderPass.TextureViewAndSampler custom = context.pipeline().resources()
                .customTextures()
                .resolve(TextureStage.GBUFFERS_AND_SHADOW, name);
        if (custom != null) {
            recordCustomSamplerOverride("world", name);
        }
        return IrisMetalCustomTextures.overrideFirst(
                custom,
                () -> standardSampler(context, name, bound)
        );
    }

    /**
     * The albedo alias names upstream's {@code IrisSamplers.addLevelSamplers}
     * binds to the shared white pixel when a level render type has no texture
     * ({@code hasTexture == false}). Pure so
     * {@code IrisMetalWorldSamplerFallbackTest} can pin the set without a
     * Metal device.
     */
    static boolean isAlbedoAlias(final String name) {
        return switch (name) {
            case "gtexture", "texture", "tex", "u_MainSampler" -> true;
            default -> false;
        };
    }

    private static MetalRenderPass.@Nullable TextureViewAndSampler standardSampler(
            final WorldContext context,
            final String name,
            final Map<String, MetalRenderPass.TextureViewAndSampler> bound
    ) {
        // 26.2's vanilla-core transformer re-exposes the engine's render-type
        // textures under Iris names, so a patchVanilla program declares those
        // instead of the names the engine binds: the albedo bound as Sampler0
        // becomes "gtexture" (and upstream's texture/tex/u_MainSampler
        // aliases), the overlay bound as Sampler1 by RenderSetup.useOverlay
        // becomes "iris_overlay" (EntityPatcher), and the lightmap bound as
        // Sampler2 by useLightmap becomes "lightmap". Resolve them from the
        // engine-bound map when present.
        if (isAlbedoAlias(name)) {
            MetalRenderPass.TextureViewAndSampler albedo = bound.get("Sampler0");
            if (albedo != null) {
                return albedo;
            }
            // Render types without a texture bind no Sampler0 (26.2's LIGHTNING
            // and SHADOW_LIGHTNING use POSITION_COLOR), while the shaderpack
            // programs still declare and sample "tex". Upstream
            // IrisSamplers.addLevelSamplers binds the shared white pixel to
            // tex/texture/gtexture/u_MainSampler when hasTexture == false;
            // without the same fallback here the first shadow-casting
            // lightning bolt aborts pushDescriptor with "Missing sampler tex".
            if (REPORTED_NO_ALBEDO_FALLBACKS.add(name)) {
                MetalProbeReport.record("world override sampler fallback name=" + name
                        + " reason=no-albedo");
            }
            return context.pipeline().resources().whitePixel().binding();
        }
        MetalRenderPass.TextureViewAndSampler vanillaAlias = switch (name) {
            case "iris_overlay" -> bound.get("Sampler1");
            case "lightmap" -> bound.get("Sampler2");
            default -> null;
        };
        if (vanillaAlias != null) {
            return vanillaAlias;
        }
        // Fullbright / overlay-less render types (eg the eyes pass, which binds
        // only Sampler0) leave Sampler1/Sampler2 unbound while the program
        // still declares iris_overlay/lightmap. Upstream Iris substitutes a
        // 1x1 white pixel: a white overlay gives entityColor = (1,1,1,0), whose
        // rgb the entity workaround zeroes (no hurt tint), and a white lightmap
        // yields fullbright. Missing anything else keeps the caller's
        // Missing sampler diagnostic.
        if ("iris_overlay".equals(name) || "lightmap".equals(name)) {
            return context.pipeline().resources().whitePixel().binding();
        }
        if ("noisetex".equals(name)) {
            return context.pipeline().resources().noiseTexture().binding();
        }
        if (IrisMetalPbrDefaults.isPbrSampler(name)) {
            // Upstream level samplers bind the neutral PBR defaults when no
            // _n/_s texture was loaded (Sildur's nMap, Unbound labPBR).
            return context.pipeline().resources().pbrDefaults().binding(name);
        }
        IrisMetalRenderTargets renderTargets = context.pipeline().resources().renderTargets();
        GpuTextureView depthView = switch (name) {
            case "depthtex0" -> renderTargets.mainDepthView();
            case "depthtex1" -> renderTargets.noTranslucentsDepthView();
            case "depthtex2" -> renderTargets.noHandDepthView();
            default -> null;
        };
        if (depthView != null) {
            return new MetalRenderPass.TextureViewAndSampler(
                    depthView,
                    renderTargets.depthSampler()
            );
        }
        int colorIndex = IrisMetalRenderTargets.renderTargetIndex(name);
        if (colorIndex >= 0) {
            if (colorIndex >= renderTargets.colorTargets().targetCount()) {
                // Legacy names (eg gaux1 -> colortex4) can outrun a generation
                // that owns fewer targets. Binding the shared white pixel keeps
                // the draw alive without masking a valid target; the miss is
                // recorded once per name.
                if (REPORTED_MISSING_SAMPLER_TARGETS.add(name + ":" + colorIndex)) {
                    MetalProbeReport.record("world override sampler fallback name=" + name
                            + " target=" + colorIndex
                            + " owned=" + renderTargets.colorTargets().targetCount());
                }
                return context.pipeline().resources().whitePixel().binding();
            }
            return new MetalRenderPass.TextureViewAndSampler(
                    renderTargets.colorTargets().readView(colorIndex),
                    renderTargets.colorSampler(colorIndex)
            );
        }
        IrisMetalShadowTargets shadowTargets = context.pipeline().resources().shadowTargets();
        if (shadowTargets == null) {
            return null;
        }
        // Shared legacy depth aliases: shadowtex0/1 (+HW/DH variants),
        // watershadow -> shadowtex0, and bare `shadow` -> shadowtex0 here
        // (the bridge has no per-program declaration view; see the helper).
        int shadowDepthIndex = IrisMetalExecutionGraph.legacyShadowDepth(name, false);
        if (shadowDepthIndex >= 0) {
            boolean comparison = !name.endsWith("HW");
            return new MetalRenderPass.TextureViewAndSampler(
                    shadowDepthIndex == 0
                            ? shadowTargets.shadowDepthView()
                            : shadowTargets.shadowDepthNoTranslucentsView(),
                    shadowTargets.depthSampler(shadowDepthIndex, comparison)
            );
        }
        int shadowColorTarget = IrisMetalRenderTargets.shadowColorIndex(name);
        if (shadowColorTarget >= 0) {
            return new MetalRenderPass.TextureViewAndSampler(
                    shadowTargets.colorView(shadowColorTarget, context.pipeline().shadowReadSnapshot()),
                    shadowTargets.colorSampler(shadowColorTarget)
            );
        }
        return null;
    }

    /** Records the first custom-texture hit per (kind, sampler) so a probe run can confirm the override. */
    private static void recordCustomSamplerOverride(final String where, final String name) {
        if (REPORTED_CUSTOM_SAMPLERS.add(where + ":" + name)) {
            MetalProbeReport.record(where + " sampler custom name=" + name + " stage=gbuffers");
        }
    }

    private static void recordInstall(
            final ShaderKey key,
            final String program,
            final int[] drawBuffers,
            final String where
    ) {
        if (REPORTED_INSTALLS.add(key.getName())) {
            MetalProbeReport.record("world override install " + where
                    + " key=" + key.getName()
                    + " program=" + program
                    + " drawBuffers=" + Arrays.toString(drawBuffers));
        }
    }

    private static void recordSkip(final ShaderKey key, final String reason) {
        if (REPORTED_SKIPS.add(key.getName() + ":" + reason)) {
            MetalProbeReport.record("world override skip key=" + key.getName() + " reason=" + reason);
        }
    }

    /**
     * Diagnostic for draws that were considered but left vanilla. Deduped per
     * (reason, key, source pipeline) so repeated draws do not flood the probe
     * report.
     */
    private static void recordPipelineSkip(
            final RenderPipeline source,
            final @Nullable ShaderKey key,
            final String reason
    ) {
        String keyName = key == null ? "<none>" : key.getName();
        if (REPORTED_PIPELINE_SKIPS.add(reason + ":" + keyName + ":" + source.getLocation())) {
            MetalProbeReport.record("world override skip key=" + keyName
                    + " source=" + source.getLocation()
                    + " reason=" + reason);
        }
    }

    /**
     * Sky keys render first in the frame, before terrain depth exists, so the
     * lazy depthtex0 seed must ignore their passes.
     */
    private static boolean isSkyKey(final ShaderKey key) {
        return key == ShaderKey.SKY_BASIC
                || key == ShaderKey.SKY_BASIC_COLOR
                || key == ShaderKey.SKY_TEXTURED;
    }

    /**
     * Whether this takeover seeds depthtex0 from the vanilla scene depth.
     *
     * <p>{@code lazy} (the default) requires the program about to be encoded to
     * declare {@code depthtex0}: a draw that never samples it does not pay for
     * the full-screen depth copy, and leaving the once-per-frame flag unset
     * lets a later draw that does sample it still capture this frame.
     * {@code eager} restores the old unconditional capture
     * ({@code -Dmetallum.iris.debug.depthCapture=eager}).</p>
     *
     * <p>The declaration is a supplier so the per-pass sampler scan only runs
     * once the cheap gates have passed.</p>
     *
     * @param lazy                       {@code depthCapture=lazy} (default)
     * @param alreadyCapturedThisFrame   depthtex0 was already seeded this frame
     * @param sky                        the pass belongs to a sky program
     * @param targetsMainSceneDepth      the pass's depth attachment is the vanilla main depth
     * @param programDeclaresDepthtex0   whether the linked program declares {@code depthtex0}
     * @return {@code true} when the vanilla main depth must be copied into depthtex0
     */
    static boolean shouldCaptureMainDepth(
            final boolean lazy,
            final boolean alreadyCapturedThisFrame,
            final boolean sky,
            final boolean targetsMainSceneDepth,
            final BooleanSupplier programDeclaresDepthtex0
    ) {
        if (alreadyCapturedThisFrame || sky || !targetsMainSceneDepth) {
            return false;
        }
        return !lazy || programDeclaresDepthtex0.getAsBoolean();
    }

    /** True when the linked program declares the {@code depthtex0} sampler. */
    static boolean declaresDepthtex0(final List<IrisMetalGlslLinker.SamplerDecl> samplers) {
        for (IrisMetalGlslLinker.SamplerDecl sampler : samplers) {
            if (sampler.sampled() && "depthtex0".equals(sampler.name())) {
                return true;
            }
        }
        return false;
    }

    /** True when the pass's depth attachment is {@code texture} (null never matches). */
    private static boolean passTargetsTexture(
            final RenderPassDescriptor descriptor,
            final @Nullable GpuTexture texture
    ) {
        if (texture == null) {
            return false;
        }
        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        if (depthAttachment == null) {
            return false;
        }
        GpuTextureView passDepth = depthAttachment.textureView();
        return passDepth != null && passDepth.texture() == texture;
    }

    private static @Nullable WorldContext currentContext() {
        WorldContext context = ACTIVE_WORLD_PASS.get();
        if (context == null) {
            return null;
        }
        if (activePipeline() != context.pipeline()) {
            ACTIVE_WORLD_PASS.remove();
            throw new IllegalStateException("Iris Metal world context crossed world generations");
        }
        return context;
    }

    private static @Nullable MetalWorldRenderingPipeline activePipeline() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        return pipeline instanceof MetalWorldRenderingPipeline metal ? metal : null;
    }

    /** A shader key and the compilation knobs needed to resolve its program. */
    public record ProgramRequest(
            ProgramId program,
            AlphaTest alphaTest,
            boolean lines,
            boolean clouds,
            ShaderAttributeInputs inputs
    ) {
    }

    private record WorldContext(
            MetalWorldRenderingPipeline pipeline,
            ShaderKey key
    ) {
    }

    /**
     * The vanilla main render target's depth texture, used to recognize passes
     * that actually target the main scene (weather and improved-transparency
     * particles build passes on their own targets).
     */
    private static @Nullable GpuTexture vanillaMainDepthTexture() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            return null;
        }
        RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
        return target == null ? null : target.getDepthTexture();
    }
}
