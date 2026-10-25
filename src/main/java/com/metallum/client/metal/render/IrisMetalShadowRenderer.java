package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.ViewportProvider;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.caffeinemc.mods.sodium.mixin.core.render.world.FrustumAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.mixinterface.ShadowRenderListAccess;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.properties.ShadowCullState;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.frustum.BoxCuller;
import net.irisshaders.iris.shadows.frustum.advanced.AdvancedShadowCullingFrustum;
import net.irisshaders.iris.shadows.frustum.fallback.BoxCullingFrustum;
import net.irisshaders.iris.shadows.frustum.fallback.NonCullingFrustum;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * M6.1/M6.2: the real shadow caster pass — terrain, entities and block
 * entities. Mirrors upstream {@code ShadowRenderer.renderShadows}:
 *
 * <ol>
 * <li>flips Iris's process-wide {@link ShadowRenderer#ACTIVE} flag so the
 *     Sodium integration shipped inside the Iris jar swaps to its shadow
 *     render lists, and this port's terrain bridge maps Sodium draws to the
 *     {@code SHADOW_SODIUM_TERRAIN_*} keys;</li>
 * <li>installs the pack shadow matrices (sun-relative view, GL ortho) on
 *     Sodium's {@code ChunkRenderMatrices};</li>
 * <li>runs {@code setupTerrain} with the shadow culling frustum, then draws
 *     the opaque terrain group;</li>
 * <li>M6.2: extracts/submits entities and block entities into a dedicated
 *     {@link FeatureRenderDispatcher} and renders them through the same
 *     {@code PreparedRenderType.drawFromBuffer} choke point the main pass
 *     uses — {@code IrisMetalWorldBridge} maps those draws through the shadow
 *     key table into the shadow targets;</li>
 * <li>copies shadowtex0 to shadowtex1, then optionally draws the translucent
 *     terrain group.</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalShadowRenderer {
    private final MetalWorldRenderingPipeline pipeline;
    private final Map<String, String> reportedCulling = new java.util.HashMap<>();
    private @Nullable String reportedDepthMode;
    private @Nullable String reportedEntityFrustum;
    private boolean warnedSafeZone;
    private @Nullable LevelRenderState entityLevelRenderState;
    private @Nullable SubmitNodeStorage entitySubmitStorage;
    private @Nullable FeatureRenderDispatcher entityFeatureDispatcher;
    private @Nullable RenderBuffers entityBuffers;
    private @Nullable ProjectionMatrixBuffer entityProjectionBuffer;

    IrisMetalShadowRenderer(final MetalWorldRenderingPipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    /**
     * Renders the shadow caster pass. Returns {@code true} only when the pass
     * actually ran; any gate miss returns {@code false} so the caller can fall
     * back to the pre-M6.1 placeholder clear.
     */
    boolean render(
            final LevelRendererAccessor levelRenderer,
            final Camera camera,
            final CameraRenderState cameraRenderState
    ) {
        if (!MetalDebugSwitches.SHADOW_PASS) {
            this.pipeline.receipts().recordEvent("shadow.pass.disabled");
            return false;
        }
        IrisMetalShadowTargets shadows = this.pipeline.resources().shadowTargets();
        if (shadows == null) {
            this.pipeline.receipts().recordEvent("shadow.pass.no-targets");
            return false;
        }
        if (!this.pipeline.resources().hasShadowProgram()) {
            // The dimension's program set has no shadow caster program (e.g.
            // BSL nether with MULTICOLORED_BLOCKLIGHT off). Targets still
            // exist so samplers resolve; the caller clears them to fully lit.
            this.pipeline.receipts().recordEvent("shadow.pass.no-program");
            return false;
        }
        PackShadowDirectives directives = this.pipeline.shadowDirectives();
        if (!directives.shouldRenderTerrain()) {
            this.pipeline.receipts().recordEvent("shadow.pass.no-terrain");
            return false;
        }
        if (IrisVideoSettings.getOverriddenShadowDistance(IrisVideoSettings.shadowDistance) == 0) {
            this.pipeline.receipts().recordEvent("shadow.pass.zero-distance");
            return false;
        }
        if (!(levelRenderer instanceof LevelRendererExtension extension)) {
            this.pipeline.receipts().recordEvent("shadow.scope.unavailable");
            return false;
        }
        SodiumWorldRenderer sodiumWorldRenderer = extension.sodium$getWorldRenderer();
        ShadowRenderListAccess scope = sodiumWorldRenderer instanceof ShadowRenderListAccess access
                ? access
                : null;
        if (sodiumWorldRenderer == null || scope == null) {
            this.pipeline.receipts().recordEvent("shadow.scope.unavailable");
            return false;
        }
        IrisMetalUniformValues.ShadowMatrixSet matrixSet =
                this.pipeline.uniformValues().currentShadowMatrices();
        if (!matrixSet.packValid()) {
            // No real shadow matrices (no directives / perspective pack / A-B
            // switch). Rendering the camera matrices into the shadow map would
            // only produce garbage; fall back to the cleared placeholder.
            this.pipeline.receipts().recordEvent("shadow.pass.fallback-matrices");
            return false;
        }

        Frustum frustum = createFrustum(
                directives, matrixSet, directives.getDistanceRenderMul(), "shadow.culling"
        );
        Vec3 cameraPos = camera.position();
        double cameraX = cameraPos.x;
        double cameraY = cameraPos.y;
        double cameraZ = cameraPos.z;
        frustum.prepare(cameraX, cameraY, cameraZ);
        // E (M6.3): the pack can constrain the entity shadow distance
        // independently of the terrain distance (upstream
        // entityShadowDistanceMultiplier). 1.0/negative shares the terrain
        // frustum exactly as upstream.
        Frustum entityFrustum = frustum;
        float entityDistanceMultiplier = directives.getEntityShadowDistanceMul();
        if (entityDistanceMultiplier == 1.0F || entityDistanceMultiplier < 0.0F) {
            reportEntityFrustum("shared");
        } else {
            entityFrustum = createFrustum(
                    directives, matrixSet,
                    directives.getDistanceRenderMul() * entityDistanceMultiplier,
                    "shadow.entityCulling"
            );
            entityFrustum.prepare(cameraX, cameraY, cameraZ);
            reportEntityFrustum("separate");
        }
        boolean spectator = camera.entity() != null && camera.entity().isSpectator();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true);
        // GL-NDC shadow depth: the caster vertex shader emulates GL's
        // NDC->depth viewport transform (see IrisMetalShadowDepthFix), so the
        // engine projection must be the GL-space pack matrix exactly like
        // upstream GL Iris hands Sodium. With the A/B switch off, fall back to
        // the zero-to-one ortho (pre-fix behavior).
        Matrix4f shadowProjection = MetalDebugSwitches.SHADOW_DEPTH_FIX
                ? new Matrix4f(matrixSet.packProjection())
                : new Matrix4f(matrixSet.zeroToOneProjection());
        reportDepthMode(MetalDebugSwitches.SHADOW_DEPTH_FIX ? "gl-ndc" : "zero-to-one");
        ChunkRenderMatrices shadowMatrices = new ChunkRenderMatrices(
                shadowProjection,
                new Matrix4f(matrixSet.modelView())
        );
        ChunkRenderMatrices savedMatrices = extension.sodium$getMatrices();

        pipeline.setPhase(WorldRenderingPhase.NONE);
        ShadowRenderer.ACTIVE = true;
        this.pipeline.receipts().recordEvent("shadow.pass.begin");
        try {
            scope.iris$beginShadowRenderListScope();
            extension.sodium$setMatrices(shadowMatrices);
            // Cancelled by Iris's own MixinSodiumWorldRenderer while ACTIVE is
            // set; kept for parity with upstream's call order.
            sodiumWorldRenderer.scheduleTerrainUpdate();
            sodiumWorldRenderer.setupTerrain(
                    camera,
                    ((ViewportProvider) frustum).sodium$createViewport(),
                    ((FogStorage) Minecraft.getInstance().gameRenderer).sodium$getFogParameters(),
                    spectator,
                    false,
                    ((FrustumAccessor) frustum).sodium$getMatrix()
            );
            pipeline.setPhase(WorldRenderingPhase.TERRAIN_SOLID);
            sodiumWorldRenderer.drawChunkLayer(
                    ChunkSectionLayerGroup.OPAQUE, shadowMatrices, cameraX, cameraY, cameraZ, sampler
            );
            pipeline.setPhase(WorldRenderingPhase.NONE);
            // M6.2: entities and block entities are submitted/rendered after
            // the opaque terrain and before the shadowtex1 copy, matching
            // upstream ShadowRenderer (translucent water then only shows up in
            // shadowtex0).
            pipeline.setPhase(WorldRenderingPhase.ENTITIES);
            renderEntityAndBlockEntityCasters(
                    levelRenderer, sodiumWorldRenderer, camera, entityFrustum, directives,
                    matrixSet, cameraX, cameraY, cameraZ
            );
            pipeline.setPhase(WorldRenderingPhase.NONE);
            this.pipeline.captureShadowNoTranslucents();
            if (directives.shouldRenderTranslucent()) {
                pipeline.setPhase(WorldRenderingPhase.TERRAIN_TRANSLUCENT);
                sodiumWorldRenderer.drawChunkLayer(
                        ChunkSectionLayerGroup.TRANSLUCENT, shadowMatrices, cameraX, cameraY, cameraZ, sampler
                );
                pipeline.setPhase(WorldRenderingPhase.NONE);
            }
            recordTerrainStats(directives.shouldRenderTranslucent());
        } finally {
            pipeline.setPhase(WorldRenderingPhase.NONE);
            extension.sodium$setMatrices(savedMatrices);
            scope.iris$endShadowRenderListScope();
            ShadowRenderer.ACTIVE = false;
            this.pipeline.receipts().recordEvent("shadow.pass.end");
        }
        return true;
    }

    private void recordTerrainStats(final boolean translucent) {
        IrisMetalRuntimeReceipts receipts = this.pipeline.receipts();
        if (!receipts.enabled()) {
            // No receipt sink: skip sectionStatistics() and the string build.
            return;
        }
        String stats;
        try {
            var extractor = Minecraft.getInstance().levelExtractor;
            stats = extractor == null ? "<none>" : extractor.sectionStatistics();
        } catch (Throwable ignored) {
            stats = "<unavailable>";
        }
        receipts.recordEvent(
                "shadow.terrain stats=" + stats + " translucent=" + translucent
        );
    }

    /**
     * M6.2 entity/block-entity caster submission, mirroring upstream
     * {@code ShadowRenderer}: entity meshes are submitted against the shadow
     * model view while the engine model view is identity (so the baked poses
     * carry the view exactly once), and the engine projection is the pack GL
     * shadow ortho consumed by the shadow programs' {@code iris_ProjMat}.
     * Renders through a dedicated {@link FeatureRenderDispatcher}, whose
     * {@code PreparedRenderType.drawFromBuffer} draws are taken over by
     * {@code IrisMetalWorldBridge}.
     */
    private void renderEntityAndBlockEntityCasters(
            final LevelRendererAccessor levelRenderer,
            final SodiumWorldRenderer sodiumWorldRenderer,
            final Camera camera,
            final Frustum frustum,
            final PackShadowDirectives directives,
            final IrisMetalUniformValues.ShadowMatrixSet matrices,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        ensureEntityState(minecraft);
        LevelRenderState state = this.entityLevelRenderState;
        SubmitNodeStorage storage = this.entitySubmitStorage;
        float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();

        camera.extractRenderState(state.cameraRenderState, tickDelta);
        Matrix4f savedViewRotation = new Matrix4f(state.cameraRenderState.viewRotationMatrix);
        Matrix4f savedProjection = new Matrix4f(state.cameraRenderState.projectionMatrix);
        state.cameraRenderState.viewRotationMatrix = new Matrix4f(matrices.modelView());
        state.cameraRenderState.projectionMatrix = new Matrix4f(matrices.packProjection());
        state.reset();

        PoseStack modelView = new PoseStack();
        modelView.mulPose(matrices.modelView());

        GpuBufferSlice savedProjectionBuffer = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType savedProjectionType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(
                this.entityProjectionBuffer.getBuffer(matrices.packProjection()),
                ProjectionType.ORTHOGRAPHIC
        );
        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().identity();
        try {
            EntityRenderDispatcher dispatcher = levelRenderer.getEntityRenderDispatcher();
            if (directives.shouldRenderEntities()) {
                extractVisibleEntities(state, dispatcher, frustum, minecraft);
            } else if (directives.shouldRenderPlayer()) {
                extractPlayer(state, dispatcher, minecraft);
            }
            for (EntityRenderState entityState : state.entityRenderStates) {
                dispatcher.submit(
                        entityState,
                        state.cameraRenderState,
                        entityState.x - cameraX,
                        entityState.y - cameraY,
                        entityState.z - cameraZ,
                        modelView,
                        storage
                );
            }

            if (directives.shouldRenderBlockEntities() || directives.shouldRenderLightBlockEntities()) {
                // The shadow render-list scope is active, so Sodium extracts
                // the block entities visible to the shadow frustum.
                sodiumWorldRenderer.extractBlockEntities(
                        camera, tickDelta, minecraft.level.destructionProgress(), state
                );
            }
            if (!directives.shouldRenderBlockEntities() && directives.shouldRenderLightBlockEntities()) {
                // E (M6.3): pack wants only light-emitting block entities
                // (upstream extractVisibleBlockEntities' lightsOnly filter).
                state.blockEntityRenderStates.removeIf(
                        blockEntityState -> blockEntityState.blockState.getLightEmission() == 0
                );
            }
            BlockEntityRenderDispatcher blockDispatcher = minecraft.getBlockEntityRenderDispatcher();
            for (BlockEntityRenderState blockState : state.blockEntityRenderStates) {
                BlockPos pos = blockState.blockPos;
                modelView.pushPose();
                modelView.translate(pos.getX() - cameraX, pos.getY() - cameraY, pos.getZ() - cameraZ);
                blockDispatcher.submit(blockState, modelView, storage, state.cameraRenderState);
                modelView.popPose();
            }

            this.entityFeatureDispatcher.renderAllFeatures(storage);
            this.entityBuffers.endFrame();
        } finally {
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.setProjectionMatrix(savedProjectionBuffer, savedProjectionType);
            state.cameraRenderState.viewRotationMatrix = savedViewRotation;
            state.cameraRenderState.projectionMatrix = savedProjection;
        }
        this.pipeline.receipts().recordEvent(
                "shadow.casters entities=" + state.entityRenderStates.size()
                        + " blockEntities=" + state.blockEntityRenderStates.size()
        );
    }

    private void ensureEntityState(final Minecraft minecraft) {
        if (this.entityLevelRenderState != null) {
            return;
        }
        this.entityLevelRenderState = new LevelRenderState();
        this.entitySubmitStorage = new SubmitNodeStorage();
        this.entityBuffers = new RenderBuffers(Runtime.getRuntime().availableProcessors());
        this.entityFeatureDispatcher = new FeatureRenderDispatcher(
                this.entityBuffers,
                minecraft.getModelManager(),
                minecraft.getAtlasManager(),
                minecraft.font,
                minecraft.gameRenderer.gameRenderState()
        );
        this.entityProjectionBuffer = new ProjectionMatrixBuffer("Iris shadow projection");
    }

    /** Entity extraction mirroring upstream {@code ShadowRenderer.extractVisibleEntities}. */
    private static void extractVisibleEntities(
            final LevelRenderState state,
            final EntityRenderDispatcher dispatcher,
            final Frustum frustum,
            final Minecraft minecraft
    ) {
        Vec3 cameraPos = minecraft.gameRenderer.mainCamera().position();
        double cameraX = cameraPos.x;
        double cameraY = cameraPos.y;
        double cameraZ = cameraPos.z;
        TickRateManager tickRateManager = minecraft.level.tickRateManager();
        Entity.setViewScale(Mth.clamp(
                minecraft.options.getEffectiveRenderDistance() / 8.0,
                1.0,
                2.5
        ) * minecraft.options.entityDistanceScaling().get());
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof AbstractClientPlayer player && player.isSpectator()) {
                continue;
            }
            if (!dispatcher.shouldRender(entity, frustum, cameraX, cameraY, cameraZ)
                    && !entity.hasIndirectPassenger(minecraft.player)) {
                continue;
            }
            BlockPos pos = entity.blockPosition();
            if (!minecraft.level.isOutsideBuildHeight(pos.getY())
                    && !minecraft.levelRenderer.isSectionCompiledAndVisible(pos)) {
                continue;
            }
            if (entity.tickCount == 0) {
                entity.xOld = entity.getX();
                entity.yOld = entity.getY();
                entity.zOld = entity.getZ();
            }
            float partialTick = minecraft.getDeltaTracker()
                    .getGameTimeDeltaPartialTick(!tickRateManager.isEntityFrozen(entity));
            state.entityRenderStates.add(dispatcher.extractEntity(entity, partialTick));
        }
    }

    /** Player-only extraction for packs that disable generic entity shadows. */
    private static void extractPlayer(
            final LevelRenderState state,
            final EntityRenderDispatcher dispatcher,
            final Minecraft minecraft
    ) {
        Player player = minecraft.player;
        if (player == null) {
            return;
        }
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (!player.isSpectator() && !player.isInvisible()) {
            state.entityRenderStates.add(dispatcher.extractEntity(player, partialTick));
        }
        if (player.getVehicle() != null) {
            state.entityRenderStates.add(dispatcher.extractEntity(player.getVehicle(), partialTick));
        }
    }

    /** Releases the caster-pass render state; called from pipeline destroy. */
    void close() {
        if (this.entityFeatureDispatcher != null) {
            this.entityFeatureDispatcher.close();
            this.entityFeatureDispatcher = null;
        }
        if (this.entityBuffers != null) {
            this.entityBuffers.close();
            this.entityBuffers = null;
        }
        if (this.entityProjectionBuffer != null) {
            this.entityProjectionBuffer.close();
            this.entityProjectionBuffer = null;
        }
        this.entityLevelRenderState = null;
        this.entitySubmitStorage = null;
    }

    /**
     * Culling frustum selection: pack {@code shadow.culling} (DEFAULT/ADVANCED
     * use the advanced frustum, DISTANCE the box frustum) with SAFE_ZONE
     * downgraded to advanced for v1, overridable via
     * {@code -Dmetallum.iris.debug.shadowCulling=advanced|box|none}.
     */
    private Frustum createFrustum(
            final PackShadowDirectives directives,
            final IrisMetalUniformValues.ShadowMatrixSet matrixSet,
            final float renderMultiplier,
            final String receiptKey
    ) {
        String forced = MetalDebugSwitches.SHADOW_CULLING;
        if ("none".equalsIgnoreCase(forced)) {
            reportCulling(receiptKey, "none (forced)");
            return new NonCullingFrustum();
        }
        if ("advanced".equalsIgnoreCase(forced)) {
            reportCulling(receiptKey, "advanced (forced)");
            return advancedFrustum(directives, matrixSet, renderMultiplier);
        }
        if ("box".equalsIgnoreCase(forced)) {
            return boxFrustum(directives, renderMultiplier, "box (forced)", receiptKey);
        }
        ShadowCullState state = directives.getCullingState();
        if (state == ShadowCullState.SAFE_ZONE) {
            if (!this.warnedSafeZone) {
                this.warnedSafeZone = true;
                Metallum.LOGGER.warn(
                        "[metallum-iris] shadow.culling=SAFE_ZONE is not implemented yet;"
                                + " falling back to advanced culling"
                );
            }
            reportCulling(receiptKey, "advanced (safe-zone degraded)");
            return advancedFrustum(directives, matrixSet, renderMultiplier);
        }
        if (state == ShadowCullState.DISTANCE) {
            return boxFrustum(directives, renderMultiplier, "box (pack)", receiptKey);
        }
        reportCulling(receiptKey, "advanced (pack)");
        return advancedFrustum(directives, matrixSet, renderMultiplier);
    }

    private Frustum boxFrustum(
            final PackShadowDirectives directives,
            final float renderMultiplier,
            final String description,
            final String receiptKey
    ) {
        double distance = shadowDistance(directives, renderMultiplier);
        double renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        if (distance <= 0.0 || distance > renderDistance) {
            // Upstream disables culling entirely when the pack's distance
            // exceeds the normal render distance.
            reportCulling(receiptKey, description + " -> none (distance)");
            return new NonCullingFrustum();
        }
        reportCulling(receiptKey, description);
        return new BoxCullingFrustum(new BoxCuller(distance));
    }

    private Frustum advancedFrustum(
            final PackShadowDirectives directives,
            final IrisMetalUniformValues.ShadowMatrixSet matrixSet,
            final float renderMultiplier
    ) {
        double distance = shadowDistance(directives, renderMultiplier);
        double renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        BoxCuller boxCuller = distance > 0.0 && distance < renderDistance
                ? new BoxCuller(distance)
                : null;
        CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        Matrix4f projView = MetalIrisDepthConvention.packProjection(state.getGbufferProjection())
                .mul(state.getGbufferModelView(), new Matrix4f());
        Vector4f lightWorld = new CelestialUniforms(this.pipeline.getSunPathRotation())
                .getShadowLightPositionInWorldSpace();
        Vector3f light = new Vector3f(lightWorld.x, lightWorld.y, lightWorld.z).normalize();
        return new AdvancedShadowCullingFrustum(projView, matrixSet.packProjection(), light, boxCuller);
    }

    /**
     * Pack distance scaled by the frustum's render multiplier; a negative
     * multiplier (user shadow distance) wins over the pack distance.
     */
    private static double shadowDistance(
            final PackShadowDirectives directives,
            final float renderMultiplier
    ) {
        if (renderMultiplier < 0.0F) {
            return IrisVideoSettings.shadowDistance * 16.0;
        }
        return directives.getDistance() * renderMultiplier;
    }

    private void reportCulling(final String key, final String description) {
        if (!description.equals(this.reportedCulling.get(key))) {
            this.reportedCulling.put(key, description);
            this.pipeline.receipts().recordEvent(key + "=" + description);
        }
    }

    private void reportEntityFrustum(final String mode) {
        if (!mode.equals(this.reportedEntityFrustum)) {
            this.reportedEntityFrustum = mode;
            this.pipeline.receipts().recordEvent("shadow.entityFrustum=" + mode);
        }
    }

    private void reportDepthMode(final String mode) {
        if (!mode.equals(this.reportedDepthMode)) {
            this.reportedDepthMode = mode;
            this.pipeline.receipts().recordEvent("shadow.depthFix=" + mode);
        }
    }

    /**
     * Terrain culling mode from the last caster pass ({@code advanced},
     * {@code box}, {@code none}) or {@code n/a} before the first pass, for the
     * M6.4 shadow-status probe line. The full receipt description stays in the
     * {@code shadow.culling=...} event.
     */
    String shadowCullingMode() {
        String description = this.reportedCulling.get("shadow.culling");
        return description == null ? "n/a" : description.split(" ", 2)[0];
    }

    /** {@code shared}, {@code separate} or {@code n/a} before the first caster pass. */
    String shadowEntityFrustumMode() {
        return this.reportedEntityFrustum == null ? "n/a" : this.reportedEntityFrustum;
    }
}
