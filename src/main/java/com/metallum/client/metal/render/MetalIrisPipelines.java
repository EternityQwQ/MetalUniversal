package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Port of upstream {@code IrisPipelines}' {@code RenderPipelines -> ShaderKey}
 * mapping for the Metal world pipeline (M1: non-terrain world programs).
 *
 * <p>Upstream is the source of truth. This class hardcodes the pinned
 * Iris 1.11.2 {@code assignToMain} table and then re-reads upstream's private
 * {@code coreShaderMap} reflectively during class init. Constant entries adopt
 * the reflected upstream value (drift protection, with a warning per drift);
 * selector entries (cutout/solid/translucent/text/text-intensity) are
 * evaluated by this port's own selectors, parameterized on
 * {@link MetalWorldRenderingPipeline}, so the Metal pipeline never depends on
 * {@code IrisRenderingPipeline}.</p>
 *
 * <p>The shadow table ({@code assignToShadow}) is intentionally not ported in
 * M1: while a shadow pass is being rendered this class returns {@code null}
 * and vanilla keeps drawing. Sodium terrain pipelines are also outside this
 * table's scope (see {@link IrisMetalTerrainBridge}).</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalIrisPipelines {
    private static final Function<MetalWorldRenderingPipeline, ShaderKey> NULL_MAPPING =
            pipeline -> null;
    private static final Map<RenderPipeline, Function<MetalWorldRenderingPipeline, ShaderKey>> CORE_SHADER_MAP =
            new HashMap<>();
    /**
     * M6.2: upstream {@code IrisPipelines.coreShaderMapShadow}. All entries are
     * constant, so the same table is used while {@code ShadowRenderer.ACTIVE}
     * is set; the port's terrain bridge keeps Sodium draws on its own shadow
     * keys.
     */
    private static final Map<RenderPipeline, Function<MetalWorldRenderingPipeline, ShaderKey>> CORE_SHADER_MAP_SHADOW =
            new HashMap<>();
    private static final Set<RenderPipeline> SELECTOR_PIPELINES = new HashSet<>();
    private static int upstreamDriftCount;
    private static int upstreamAdoptedCount;
    private static int upstreamMissingCount;

    static {
        assign(RenderPipelines.SOLID_BLOCK, pipeline -> ShaderKey.TERRAIN_SOLID);
        assign(RenderPipelines.CUTOUT_BLOCK, pipeline -> ShaderKey.TERRAIN_CUTOUT);
        assign(RenderPipelines.SOLID_TERRAIN, pipeline -> ShaderKey.TERRAIN_SOLID);
        assign(RenderPipelines.CUTOUT_TERRAIN, pipeline -> ShaderKey.TERRAIN_CUTOUT);
        assign(RenderPipelines.TRANSLUCENT_TERRAIN, pipeline -> ShaderKey.TERRAIN_TRANSLUCENT);
        assign(RenderPipelines.TRANSLUCENT_BLOCK, pipeline -> ShaderKey.MOVING_BLOCK);
        assign(RenderPipelines.WORLD_BORDER, pipeline -> ShaderKey.TEXTURED);
        assign(RenderPipelines.ENTITY_CUTOUT, MetalIrisPipelines::getCutout);
        assign(RenderPipelines.ENTITY_CUTOUT_CULL, MetalIrisPipelines::getCutout);
        assign(RenderPipelines.ENTITY_CUTOUT_DISSOLVE, MetalIrisPipelines::getCutout);
        assign(RenderPipelines.ENTITY_TRANSLUCENT_CULL, MetalIrisPipelines::getTranslucent);
        assign(RenderPipelines.ITEM_TRANSLUCENT, MetalIrisPipelines::getTranslucent);
        assign(RenderPipelines.ITEM_CUTOUT, MetalIrisPipelines::getCutout);
        assign(RenderPipelines.ENTITY_TRANSLUCENT, MetalIrisPipelines::getTranslucent);
        assign(RenderPipelines.ENTITY_SHADOW, MetalIrisPipelines::getTranslucent);
        assign(RenderPipelines.LINES, pipeline -> ShaderKey.LINES);
        assign(RenderPipelines.LINES_TRANSLUCENT, pipeline -> ShaderKey.LINES);
        assign(RenderPipelines.SECONDARY_BLOCK_OUTLINE, pipeline -> ShaderKey.LINES);
        assign(RenderPipelines.STARS, pipeline -> ShaderKey.SKY_BASIC);
        assign(RenderPipelines.SUNRISE_SUNSET, pipeline -> ShaderKey.SKY_BASIC_COLOR);
        assign(RenderPipelines.SKY, pipeline -> ShaderKey.SKY_BASIC);
        assign(RenderPipelines.CELESTIAL, pipeline -> ShaderKey.SKY_TEXTURED);
        assign(RenderPipelines.OPAQUE_PARTICLE, pipeline -> ShaderKey.PARTICLES);
        assign(RenderPipelines.TRANSLUCENT_PARTICLE, pipeline -> ShaderKey.PARTICLES_TRANS);
        assign(RenderPipelines.WATER_MASK, pipeline -> ShaderKey.BASIC);
        assign(RenderPipelines.GLINT, pipeline -> ShaderKey.GLINT);
        assign(RenderPipelines.ARMOR_CUTOUT_NO_CULL, MetalIrisPipelines::getCutout);
        assign(RenderPipelines.EYES, pipeline -> ShaderKey.ENTITIES_EYES);
        assign(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE, pipeline -> ShaderKey.ENTITIES_EYES_TRANS);
        assign(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, MetalIrisPipelines::getCutout);
        assign(RenderPipelines.ARMOR_TRANSLUCENT, MetalIrisPipelines::getTranslucent);
        assign(RenderPipelines.BREEZE_WIND, MetalIrisPipelines::getTranslucent);
        assign(RenderPipelines.ENTITY_SOLID, MetalIrisPipelines::getSolid);
        assign(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, MetalIrisPipelines::getSolid);
        assign(RenderPipelines.END_GATEWAY, pipeline -> ShaderKey.BLOCK_ENTITY);
        assign(RenderPipelines.ENERGY_SWIRL, pipeline -> ShaderKey.ENTITIES_CUTOUT);
        assign(RenderPipelines.END_CRYSTAL_BEAM, pipeline -> ShaderKey.ENTITIES_CUTOUT);
        assign(RenderPipelines.ENTITY_CUTOUT_Z_OFFSET, pipeline -> ShaderKey.ENTITIES_CUTOUT);
        assign(RenderPipelines.LIGHTNING, pipeline -> ShaderKey.LIGHTNING);
        assign(RenderPipelines.DRAGON_RAYS, pipeline -> ShaderKey.LIGHTNING);
        assign(RenderPipelines.BEACON_BEAM_OPAQUE, pipeline -> ShaderKey.BEACON);
        assign(RenderPipelines.BEACON_BEAM_TRANSLUCENT, pipeline -> ShaderKey.BEACON);
        assign(RenderPipelines.END_PORTAL, pipeline -> ShaderKey.BLOCK_ENTITY);
        assign(RenderPipelines.END_SKY, pipeline -> ShaderKey.SKY_TEXTURED);
        assign(RenderPipelines.WEATHER_DEPTH_WRITE, pipeline -> ShaderKey.WEATHER);
        assign(RenderPipelines.WEATHER_NO_DEPTH_WRITE, pipeline -> ShaderKey.WEATHER);
        assign(RenderPipelines.TEXT, MetalIrisPipelines::getText);
        assign(RenderPipelines.TEXT_POLYGON_OFFSET, MetalIrisPipelines::getText);
        assign(RenderPipelines.TEXT_SEE_THROUGH, MetalIrisPipelines::getText);
        assign(RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH, MetalIrisPipelines::getTextIntensity);
        assign(RenderPipelines.TEXT_BACKGROUND, pipeline -> ShaderKey.TEXT_BG);
        assign(RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH, pipeline -> ShaderKey.TEXT_BG);
        assign(RenderPipelines.TEXT_GRAYSCALE, MetalIrisPipelines::getTextIntensity);
        assign(RenderPipelines.CRUMBLING, pipeline -> ShaderKey.CRUMBLING);
        assign(RenderPipelines.LEASH, pipeline -> ShaderKey.LEASH);
        assign(RenderPipelines.CLOUDS, pipeline -> ShaderKey.CLOUDS);
        assign(RenderPipelines.FLAT_CLOUDS, pipeline -> ShaderKey.CLOUDS);
        assign(RenderPipelines.BANNER_PATTERN, MetalIrisPipelines::getTranslucent);

        // Upstream routes these through state-dependent selectors; the port
        // evaluates them with its own selector functions instead.
        SELECTOR_PIPELINES.add(RenderPipelines.ENTITY_CUTOUT);
        SELECTOR_PIPELINES.add(RenderPipelines.ENTITY_CUTOUT_CULL);
        SELECTOR_PIPELINES.add(RenderPipelines.ENTITY_CUTOUT_DISSOLVE);
        SELECTOR_PIPELINES.add(RenderPipelines.ENTITY_TRANSLUCENT_CULL);
        SELECTOR_PIPELINES.add(RenderPipelines.ITEM_TRANSLUCENT);
        SELECTOR_PIPELINES.add(RenderPipelines.ITEM_CUTOUT);
        SELECTOR_PIPELINES.add(RenderPipelines.ENTITY_TRANSLUCENT);
        SELECTOR_PIPELINES.add(RenderPipelines.ENTITY_SHADOW);
        SELECTOR_PIPELINES.add(RenderPipelines.ARMOR_CUTOUT_NO_CULL);
        SELECTOR_PIPELINES.add(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL);
        SELECTOR_PIPELINES.add(RenderPipelines.ARMOR_TRANSLUCENT);
        SELECTOR_PIPELINES.add(RenderPipelines.BREEZE_WIND);
        SELECTOR_PIPELINES.add(RenderPipelines.ENTITY_SOLID);
        SELECTOR_PIPELINES.add(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD);
        SELECTOR_PIPELINES.add(RenderPipelines.TEXT);
        SELECTOR_PIPELINES.add(RenderPipelines.TEXT_POLYGON_OFFSET);
        SELECTOR_PIPELINES.add(RenderPipelines.TEXT_SEE_THROUGH);
        SELECTOR_PIPELINES.add(RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH);
        SELECTOR_PIPELINES.add(RenderPipelines.TEXT_GRAYSCALE);
        SELECTOR_PIPELINES.add(RenderPipelines.BANNER_PATTERN);

        assignShadow(RenderPipelines.SOLID_BLOCK, ShaderKey.SHADOW_TERRAIN_CUTOUT);
        assignShadow(RenderPipelines.SOLID_TERRAIN, ShaderKey.SHADOW_TERRAIN_CUTOUT);
        assignShadow(RenderPipelines.CUTOUT_TERRAIN, ShaderKey.SHADOW_TERRAIN_CUTOUT);
        assignShadow(RenderPipelines.TRANSLUCENT_TERRAIN, ShaderKey.SHADOW_TRANSLUCENT);
        assignShadow(RenderPipelines.CUTOUT_BLOCK, ShaderKey.SHADOW_TERRAIN_CUTOUT);
        assignShadow(RenderPipelines.TRANSLUCENT_BLOCK, ShaderKey.SHADOW_TRANSLUCENT);
        assignShadow(RenderPipelines.ENTITY_CUTOUT, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.ARMOR_CUTOUT_NO_CULL, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.ENTITY_SOLID, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.CRUMBLING, ShaderKey.SHADOW_TEX);
        assignShadow(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.ENTITY_CUTOUT_CULL, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.ITEM_CUTOUT, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.ITEM_TRANSLUCENT, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.ENTITY_TRANSLUCENT, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.ENTITY_CUTOUT_DISSOLVE, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.ENTITY_TRANSLUCENT_CULL, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.END_CRYSTAL_BEAM, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.ENTITY_CUTOUT_Z_OFFSET, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.BREEZE_WIND, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.EYES, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.BANNER_PATTERN, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.ENERGY_SWIRL, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.GLINT, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.WEATHER_DEPTH_WRITE, ShaderKey.SHADOW_PARTICLES);
        assignShadow(RenderPipelines.WEATHER_NO_DEPTH_WRITE, ShaderKey.SHADOW_PARTICLES);
        assignShadow(RenderPipelines.OPAQUE_PARTICLE, ShaderKey.SHADOW_PARTICLES);
        assignShadow(RenderPipelines.TRANSLUCENT_PARTICLE, ShaderKey.SHADOW_PARTICLES);
        assignShadow(RenderPipelines.LINES, ShaderKey.SHADOW_LINES);
        assignShadow(RenderPipelines.LEASH, ShaderKey.SHADOW_LEASH);
        assignShadow(RenderPipelines.SECONDARY_BLOCK_OUTLINE, ShaderKey.SHADOW_LINES);
        assignShadow(RenderPipelines.TEXT, ShaderKey.SHADOW_TEXT);
        assignShadow(RenderPipelines.TEXT_POLYGON_OFFSET, ShaderKey.SHADOW_TEXT);
        assignShadow(RenderPipelines.TEXT_SEE_THROUGH, ShaderKey.SHADOW_TEXT);
        assignShadow(RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH, ShaderKey.SHADOW_TEXT_INTENSITY);
        assignShadow(RenderPipelines.TEXT_BACKGROUND, ShaderKey.SHADOW_TEXT_BG);
        assignShadow(RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH, ShaderKey.SHADOW_TEXT_BG);
        assignShadow(RenderPipelines.TEXT_GRAYSCALE, ShaderKey.SHADOW_TEXT_INTENSITY);
        assignShadow(RenderPipelines.WATER_MASK, ShaderKey.SHADOW_BASIC);
        assignShadow(RenderPipelines.BEACON_BEAM_OPAQUE, ShaderKey.SHADOW_BEACON_BEAM);
        assignShadow(RenderPipelines.BEACON_BEAM_TRANSLUCENT, ShaderKey.SHADOW_BEACON_BEAM);
        assignShadow(RenderPipelines.END_PORTAL, ShaderKey.SHADOW_BLOCK);
        assignShadow(RenderPipelines.END_GATEWAY, ShaderKey.SHADOW_BLOCK);
        assignShadow(RenderPipelines.ARMOR_TRANSLUCENT, ShaderKey.SHADOW_ENTITIES_CUTOUT);
        assignShadow(RenderPipelines.LIGHTNING, ShaderKey.SHADOW_LIGHTNING);
        assignShadow(RenderPipelines.DRAGON_RAYS, ShaderKey.SHADOW_LIGHTNING);

        applyUpstreamBaseline();
    }

    private static void assignShadow(final RenderPipeline pipeline, final ShaderKey key) {
        Function<MetalWorldRenderingPipeline, ShaderKey> current =
                CORE_SHADER_MAP_SHADOW.put(pipeline, pipeline1 -> key);
        if (current != null) {
            Iris.logger.warn(
                    "[MetalUniversal/Iris] RenderPipelines shadow mapping already assigned: "
                            + pipeline.getLocation()
            );
        }
    }

    private MetalIrisPipelines() {
    }

    /** Integration-layer entry point: maps a vanilla pipeline to its Iris shader key. */
    public static @Nullable ShaderKey getShaderKeyForPipeline(
            final @Nullable MetalWorldRenderingPipeline pipeline,
            final RenderPipeline source
    ) {
        Objects.requireNonNull(source, "source");
        Map<RenderPipeline, Function<MetalWorldRenderingPipeline, ShaderKey>> table =
                ShadowRenderingState.areShadowsCurrentlyBeingRendered()
                        ? CORE_SHADER_MAP_SHADOW
                        : CORE_SHADER_MAP;
        return table.getOrDefault(source, NULL_MAPPING).apply(pipeline);
    }

    /** Number of constant entries where the reflected upstream value replaced the pinned one. */
    static int upstreamDriftCount() {
        return upstreamDriftCount;
    }

    /** Number of constant entries present upstream but absent from the pinned table. */
    static int upstreamAdoptedCount() {
        return upstreamAdoptedCount;
    }

    /** Number of pinned entries that upstream no longer maps. */
    static int upstreamMissingCount() {
        return upstreamMissingCount;
    }

    private static ShaderKey getCutout(final @Nullable MetalWorldRenderingPipeline pipeline) {
        if (HandRenderer.INSTANCE.isActive()) {
            return HandRenderer.INSTANCE.isRenderingSolid()
                    ? ShaderKey.HAND_CUTOUT_DIFFUSE
                    : ShaderKey.HAND_WATER_DIFFUSE;
        } else if (isBlockEntities(pipeline)) {
            return ShaderKey.BLOCK_ENTITY_DIFFUSE;
        } else {
            return ShaderKey.ENTITIES_CUTOUT_DIFFUSE;
        }
    }

    private static ShaderKey getSolid(final @Nullable MetalWorldRenderingPipeline pipeline) {
        if (HandRenderer.INSTANCE.isActive()) {
            return HandRenderer.INSTANCE.isRenderingSolid()
                    ? ShaderKey.HAND_CUTOUT
                    : ShaderKey.HAND_TRANSLUCENT;
        } else if (isBlockEntities(pipeline)) {
            return ShaderKey.BLOCK_ENTITY;
        } else {
            return ShaderKey.ENTITIES_SOLID;
        }
    }

    private static ShaderKey getTranslucent(final @Nullable MetalWorldRenderingPipeline pipeline) {
        if (HandRenderer.INSTANCE.isActive()) {
            return HandRenderer.INSTANCE.isRenderingSolid()
                    ? ShaderKey.HAND_CUTOUT_DIFFUSE
                    : ShaderKey.HAND_WATER_DIFFUSE;
        } else if (isBlockEntities(pipeline)) {
            return ShaderKey.BE_TRANSLUCENT;
        } else {
            return ShaderKey.ENTITIES_TRANSLUCENT;
        }
    }

    private static ShaderKey getText(final @Nullable MetalWorldRenderingPipeline pipeline) {
        if (HandRenderer.INSTANCE.isActive()) {
            // In 1.21.11+, held map uses this.
            return HandRenderer.INSTANCE.isRenderingSolid()
                    ? ShaderKey.HAND_TEXT
                    : ShaderKey.HAND_TEXT_TRANSLUCENT;
        } else if (isBlockEntities(pipeline)) {
            return ShaderKey.TEXT_BE;
        } else {
            return ShaderKey.TEXT;
        }
    }

    private static ShaderKey getTextIntensity(final @Nullable MetalWorldRenderingPipeline pipeline) {
        if (isBlockEntities(pipeline)) {
            return ShaderKey.TEXT_INTENSITY_BE;
        } else {
            return ShaderKey.TEXT_INTENSITY;
        }
    }

    private static boolean isBlockEntities(final @Nullable MetalWorldRenderingPipeline pipeline) {
        return pipeline != null && pipeline.getPhase() == WorldRenderingPhase.BLOCK_ENTITIES;
    }

    private static void assign(
            final RenderPipeline pipeline,
            final Function<MetalWorldRenderingPipeline, ShaderKey> mapping
    ) {
        Function<MetalWorldRenderingPipeline, ShaderKey> current = CORE_SHADER_MAP.put(pipeline, mapping);
        if (current != null) {
            Iris.logger.warn(
                    "[MetalUniversal/Iris] RenderPipelines mapping already assigned: "
                            + pipeline.getLocation()
            );
        }
    }

    /**
     * Re-reads upstream's private {@code coreShaderMap}/{@code coreShaderMapShadow}
     * and adopts their constant entries so the pinned tables cannot silently
     * drift from the loaded Iris version. Selector entries are excluded from
     * the main map: they are state-dependent and evaluated by the port
     * selectors (the shadow map has no selector entries upstream).
     */
    private static void applyUpstreamBaseline() {
        Map<RenderPipeline, ShaderKey> main = upstreamConstantBaseline("coreShaderMap");
        if (main == null) {
            Iris.logger.warn(
                    "[MetalUniversal/Iris] Could not read upstream IrisPipelines.coreShaderMap; "
                            + "using the pinned Iris 1.11.2 RenderPipelines mapping table"
            );
        } else {
            applyMainBaseline(main);
        }
        Map<RenderPipeline, ShaderKey> shadow = upstreamConstantBaseline("coreShaderMapShadow");
        if (shadow == null) {
            Iris.logger.warn(
                    "[MetalUniversal/Iris] Could not read upstream IrisPipelines.coreShaderMapShadow; "
                            + "using the pinned Iris 1.11.2 shadow mapping table"
            );
        } else {
            applyBaseline(
                    CORE_SHADER_MAP_SHADOW, shadow, Set.of(), "shadow"
            );
        }
    }

    private static void applyMainBaseline(final Map<RenderPipeline, ShaderKey> baseline) {
        applyBaseline(CORE_SHADER_MAP, baseline, SELECTOR_PIPELINES, "main");
    }

    private static void applyBaseline(
            final Map<RenderPipeline, Function<MetalWorldRenderingPipeline, ShaderKey>> target,
            final Map<RenderPipeline, ShaderKey> baseline,
            final Set<RenderPipeline> selectors,
            final String table
    ) {
        for (Map.Entry<RenderPipeline, ShaderKey> entry : baseline.entrySet()) {
            if (selectors.contains(entry.getKey())) {
                continue;
            }
            Function<MetalWorldRenderingPipeline, ShaderKey> port = target.get(entry.getKey());
            if (port == null) {
                upstreamAdoptedCount++;
                target.put(entry.getKey(), pipeline -> entry.getValue());
                Iris.logger.warn(
                        "[MetalUniversal/Iris] Adopting upstream-only " + table
                                + " RenderPipelines mapping "
                                + entry.getKey().getLocation() + " -> " + entry.getValue()
                );
                continue;
            }
            ShaderKey pinned = port.apply(null);
            if (pinned != entry.getValue()) {
                upstreamDriftCount++;
                target.put(entry.getKey(), pipeline -> entry.getValue());
                Iris.logger.warn(
                        "[MetalUniversal/Iris] " + table + " RenderPipelines mapping drifted for "
                                + entry.getKey().getLocation() + ": pinned " + pinned
                                + " -> upstream " + entry.getValue()
                );
            }
        }
        for (Map.Entry<RenderPipeline, Function<MetalWorldRenderingPipeline, ShaderKey>> entry
                : target.entrySet()) {
            if (selectors.contains(entry.getKey())) {
                continue;
            }
            if (!baseline.containsKey(entry.getKey())) {
                upstreamMissingCount++;
                Iris.logger.warn(
                        "[MetalUniversal/Iris] " + table
                                + " RenderPipelines mapping no longer exists upstream: "
                                + entry.getKey().getLocation()
                );
            }
        }
    }

    private static @Nullable Map<RenderPipeline, ShaderKey> upstreamConstantBaseline(final String fieldName) {
        try {
            Class<?> upstream = Class.forName("net.irisshaders.iris.pipeline.IrisPipelines");
            Field field = upstream.getDeclaredField(fieldName);
            if (!field.trySetAccessible()) {
                throw new IllegalStateException(
                        "IrisPipelines." + fieldName + " is not accessible in this environment"
                );
            }
            Map<?, ?> raw = (Map<?, ?>) field.get(null);
            Map<RenderPipeline, ShaderKey> baseline = new HashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                RenderPipeline pipeline = (RenderPipeline) entry.getKey();
                try {
                    baseline.put(
                            pipeline,
                            (ShaderKey) ((it.unimi.dsi.fastutil.Function<?, ?>) entry.getValue()).apply(null)
                    );
                } catch (Throwable ignored) {
                    // Upstream selector entries are not null-safe; they are
                    // evaluated by the port selectors instead.
                }
            }
            return baseline;
        } catch (Throwable throwable) {
            return null;
        }
    }
}
