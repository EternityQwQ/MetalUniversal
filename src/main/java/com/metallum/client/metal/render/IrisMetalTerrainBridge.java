package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

/** Atomically pairs one Sodium terrain draw with its Iris PSO and attachments. */
public final class IrisMetalTerrainBridge {
    private static final ThreadLocal<TerrainContext> ACTIVE_TERRAIN = new ThreadLocal<>();
    private static final Set<String> REPORTED_CUSTOM_SAMPLERS = new HashSet<>();

    private IrisMetalTerrainBridge() {
    }

    public static void begin(final TerrainRenderPass pass) {
        MetalWorldRenderingPipeline pipeline = activePipeline();
        if (pipeline == null) {
            ACTIVE_TERRAIN.remove();
            return;
        }

        ShaderKey key = shaderKey(pass);
        Optional<IrisMetalGlslLinker.LinkedRasterProgram> linked =
                pipeline.programs().sodium(key.getProgram(), key.getAlphaTest());
        if (linked.isEmpty()) {
            ACTIVE_TERRAIN.remove();
            return;
        }
        int[] drawBuffers = linked.orElseThrow().program().drawBuffers();
        if (drawBuffers.length == 0) {
            drawBuffers = new int[]{0};
        }
        ACTIVE_TERRAIN.set(new TerrainContext(pipeline, key, drawBuffers));
    }

    public static void end() {
        ACTIVE_TERRAIN.remove();
    }

    public static @Nullable RenderPass createRenderPass(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        TerrainContext context = currentContext();
        if (context == null) {
            return null;
        }
        IrisMetalShadowTargets shadowTargets = context.pipeline().resources().shadowTargets();
        if (ShadowRenderingState.areShadowsCurrentlyBeingRendered() && shadowTargets != null) {
            // B (M6.3): caster passes never clear. The vanilla terrain draw's
            // clears must not be forwarded to the shadow targets; clears run
            // once per frame before the caster pass.
            IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor =
                    shadowTargets.createShadowGbufferDescriptor(
                            label.get(),
                            context.drawBuffers(),
                            null,
                            null
                    );
            return encoder.createRenderPass(descriptor.descriptor());
        }
        RenderPassDescriptor descriptor = context.pipeline().resources().renderTargets()
                .createTerrainWriteDescriptor(
                        label.get(),
                        context.drawBuffers(),
                        sceneColor,
                        clearColor.orElse(null),
                        sceneDepth,
                        clearDepth.isPresent() ? clearDepth.getAsDouble() : null
                );
        return encoder.createRenderPass(descriptor);
    }

    static @Nullable MetalCompiledRenderPipeline compiledPipeline(
            final MetalDevice device,
            final RenderPipeline source
    ) {
        TerrainContext context = currentContext();
        if (context == null) {
            return null;
        }
        if (!source.getLocation().getNamespace().contains("sodium")) {
            throw new IllegalArgumentException(
                    "Iris Metal terrain received a non-Sodium pipeline " + source.getLocation()
            );
        }
        if (!context.pipeline().compiledPrograms().isOwnedBy(device)) {
            throw new IllegalStateException("Iris Metal terrain PSO crossed Metal device ownership");
        }

        var state = IrisMetalCompiledPrograms.RasterState.from(
                source, source.getVertexFormatBinding(0)
        );
        return context.pipeline().compiledPrograms()
                .sodium(context.key().getProgram(), context.key().getAlphaTest(), state)
                .orElseThrow(() -> new IllegalStateException(
                        "Iris Metal terrain program disappeared during draw: " + context.key()
                ));
    }

    /**
     * Installs the generation-owned Sodium pipeline without going through
     * RenderPass's vanilla format/count validation. Iris DRAWBUFFERS changes
     * both the attachment count and formats, so that validation must happen
     * against the linked Iris program instead of the original Sodium pipeline.
     */
    public static boolean installPipeline(
            final RenderPassBackend backend,
            final RenderPipeline source
    ) {
        if (!(backend instanceof MetalRenderPass metalPass)) {
            return false;
        }
        TerrainContext context = currentContext();
        if (context == null || !source.getLocation().getNamespace().contains("sodium")) {
            return false;
        }
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            throw new IllegalStateException("Iris Metal terrain has no active Metal device");
        }
        MetalCompiledRenderPipeline compiled = compiledPipeline(device, source);
        metalPass.setCompiledPipeline(compiled);
        if (compiled.resource(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME) != null) {
            metalPass.setUniform(
                    IrisMetalGlslLinker.UNIFORM_BLOCK_NAME,
                    context.pipeline().uniformSlice(context.key())
            );
        }
        return true;
    }

    static MetalRenderPass.@Nullable TextureViewAndSampler fallbackSampler(
            final String name,
            final Map<String, MetalRenderPass.TextureViewAndSampler> bound
    ) {
        TerrainContext context = currentContext();
        if (context == null) {
            return null;
        }
        // Terrain and shadow-caster draws share TextureStage.GBUFFERS_AND_SHADOW
        // (texture.gbuffers.*). Stage custom textures take precedence over every
        // name-based default, exactly like upstream's
        // ProgramSamplers.CustomTextureSamplerInterceptor. Before this check,
        // CR's texture.gbuffers.gaux4 (cloud-water.png, the water normal map)
        // fell through to LEGACY_RENDER_TARGETS index 7 -> colortex7, the
        // reflection buffer, so water sampled reflections instead of waves.
        MetalRenderPass.TextureViewAndSampler custom = context.pipeline().resources()
                .customTextures()
                .resolve(TextureStage.GBUFFERS_AND_SHADOW, name);
        if (custom != null) {
            recordCustomSamplerOverride("terrain", name);
        }
        return IrisMetalCustomTextures.overrideFirst(
                custom,
                () -> standardSampler(context, name, bound)
        );
    }

    private static MetalRenderPass.@Nullable TextureViewAndSampler standardSampler(
            final TerrainContext context,
            final String name,
            final Map<String, MetalRenderPass.TextureViewAndSampler> bound
    ) {
        MetalRenderPass.TextureViewAndSampler alias = switch (name) {
            case "gtexture", "texture", "tex" -> bound.get("u_BlockTex");
            case "lightmap" -> bound.get("u_LightTex");
            default -> null;
        };
        if (alias != null) {
            return alias;
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
                throw new IllegalStateException(
                        "Sampler " + name + " resolves to colortex" + colorIndex
                                + " but this generation owns only "
                                + renderTargets.colorTargets().targetCount() + " targets"
                );
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
        int depthIndex = IrisMetalExecutionGraph.legacyShadowDepth(name, false);
        if (depthIndex >= 0) {
            boolean comparison = !name.endsWith("HW");
            return new MetalRenderPass.TextureViewAndSampler(
                    depthIndex == 0
                            ? shadowTargets.shadowDepthView()
                            : shadowTargets.shadowDepthNoTranslucentsView(),
                    shadowTargets.depthSampler(depthIndex, comparison)
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

    private static @Nullable TerrainContext currentContext() {
        TerrainContext context = ACTIVE_TERRAIN.get();
        if (context == null) {
            return null;
        }
        if (activePipeline() != context.pipeline()) {
            ACTIVE_TERRAIN.remove();
            throw new IllegalStateException("Iris Metal terrain context crossed world generations");
        }
        return context;
    }

    private static @Nullable MetalWorldRenderingPipeline activePipeline() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        return pipeline instanceof MetalWorldRenderingPipeline metal ? metal : null;
    }

    private static ShaderKey shaderKey(final TerrainRenderPass pass) {
        boolean shadow = ShadowRenderingState.areShadowsCurrentlyBeingRendered();
        if (pass.isTranslucent()) {
            return shadow
                    ? ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT
                    : ShaderKey.SODIUM_TERRAIN_TRANSLUCENT;
        }
        if (pass.supportsFragmentDiscard()) {
            return shadow
                    ? ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT
                    : ShaderKey.SODIUM_TERRAIN_CUTOUT;
        }
        return shadow
                ? ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID
                : ShaderKey.SODIUM_TERRAIN_SOLID;
    }

    private record TerrainContext(
            MetalWorldRenderingPipeline pipeline,
            ShaderKey key,
            int[] drawBuffers
    ) {
        private TerrainContext {
            drawBuffers = Arrays.copyOf(drawBuffers, drawBuffers.length);
        }

        @Override
        public int[] drawBuffers() {
            return Arrays.copyOf(this.drawBuffers, this.drawBuffers.length);
        }
    }
}
