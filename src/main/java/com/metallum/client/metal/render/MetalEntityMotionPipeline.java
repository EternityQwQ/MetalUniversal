package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/** Builds motion-only MRT variants of the Minecraft pipelines that can be replayed. */
@Environment(EnvType.CLIENT)
final class MetalEntityMotionPipeline {
    /**
     * A group of Minecraft pipelines whose clip position one reduced motion
     * shader can reproduce.
     *
     * <p>Family membership is decided by the clip transform, not by what the
     * geometry represents. Two pipelines belong together exactly when the same
     * reduced vertex shader rebuilds their raster clip position from the same
     * attributes and uniforms; anything else needs its own family, because a
     * shader that reconstructs the wrong clip position produces motion vectors
     * that look plausible and are wrong.</p>
     */
    enum Family {
        /**
         * {@code core/entity} and {@code core/item}: {@code DefaultVertexFormat.ENTITY}
         * with clip position {@code ProjMat * ModelViewMat * Position}. Entity
         * models, dropped items, item frames and held items.
         */
        ENTITY("core/entity_motion", "entity_motion/"),
        /**
         * {@code core/block}: {@code DefaultVertexFormat.BLOCK} with clip position
         * {@code ProjMat * ModelViewMat * (Position + ModelOffset)}. Falling
         * blocks and block entities reach the interpolator only through this
         * family; before it existed they arrived with no object motion at all.
         */
        BLOCK("core/block_motion", "block_motion/");

        private final Identifier shader;
        private final String locationPrefix;

        Family(final String shaderPath, final String locationPrefix) {
            this.shader = Identifier.fromNamespaceAndPath("metallum", shaderPath);
            this.locationPrefix = locationPrefix;
        }

        Identifier shader() {
            return shader;
        }

        String locationPrefix() {
            return locationPrefix;
        }
    }

    private static final BindGroupLayout RESOURCES = BindGroupLayout.builder()
            .withUniform("MetallumMotion", UniformType.UNIFORM_BUFFER)
            .build();
    private static final ColorTargetState MOTION_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.RG16_FLOAT, ColorTargetState.WRITE_COLOR);
    private static final ColorTargetState VALIDITY_TARGET =
            new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_RED);
    private static final Map<RenderPipeline, RenderPipeline> CACHE = new IdentityHashMap<>();

    private MetalEntityMotionPipeline() {
    }

    /**
     * The family that can replay {@code source}, or null if none can.
     *
     * <p>Keyed on the Minecraft vertex shader path, which is what identifies the
     * clip transform. A pipeline whose shader is not listed here is left alone
     * rather than replayed by the closest-looking family.</p>
     */
    static @Nullable Family familyOf(final RenderPipeline source) {
        if (source == null) {
            return null;
        }
        return switch (source.getVertexShader().getPath()) {
            case "core/entity", "core/item" -> Family.ENTITY;
            case "core/block" -> Family.BLOCK;
            default -> null;
        };
    }

    static boolean isSplittableVertexShader(final RenderPipeline source) {
        return familyOf(source) != null;
    }

    static boolean supports(final RenderPipeline source) {
        if (!isSplittableVertexShader(source)) {
            return false;
        }
        ColorTargetState sourceTarget = source.getColorTargetState();
        return sourceTarget != null
                && sourceTarget.blendFunction().isEmpty()
                && !source.getShaderDefines().flags().contains("DISSOLVE");
    }

    static RenderPipeline forSource(final RenderPipeline source) {
        return CACHE.computeIfAbsent(source, MetalEntityMotionPipeline::build);
    }

    static void clear() {
        CACHE.clear();
    }

    private static RenderPipeline build(final RenderPipeline source) {
        Family family = familyOf(source);
        if (family == null) {
            throw new IllegalArgumentException(
                    "No motion family replays " + source.getLocation() + " (" + source.getVertexShader() + ")");
        }
        String sourceName = source.getLocation().toString()
                .replace(':', '/')
                .replaceAll("[^a-zA-Z0-9_./-]", "_");
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metallum", family.locationPrefix() + sourceName))
                .withVertexShader(family.shader())
                .withFragmentShader(family.shader())
                .withCull(source.isCull())
                .withPolygonMode(source.getPolygonMode())
                .withPrimitiveTopology(source.getPrimitiveTopology())
                .withColorTargetState(0, MOTION_TARGET)
                .withColorTargetState(1, VALIDITY_TARGET);

        source.getBindGroupLayouts().forEach(builder::withBindGroupLayout);
        builder.withBindGroupLayout(RESOURCES);
        for (int slot = 0; slot < source.getVertexFormatBindings().length; slot++) {
            if (source.getVertexFormatBinding(slot) != null) {
                builder.withVertexBinding(slot, source.getVertexFormatBinding(slot));
            }
        }
        source.getShaderDefines().flags().forEach(builder::withShaderDefine);
        source.getShaderDefines().values().forEach((name, value) -> {
            try {
                builder.withShaderDefine(name, Integer.parseInt(value));
            } catch (NumberFormatException integerFailure) {
                try {
                    builder.withShaderDefine(name, Float.parseFloat(value));
                } catch (NumberFormatException floatFailure) {
                    // Both families' shader values currently consist of numeric
                    // ALPHA_CUTOUT thresholds. Unknown textual defines are not
                    // safe to reinterpret and therefore make this variant
                    // fail closed at shader compilation.
                    throw new IllegalArgumentException(
                            "Unsupported motion shader define " + name + "=" + value,
                            floatFailure
                    );
                }
            }
        });

        DepthStencilState sourceDepth = source.getDepthStencilState();
        if (sourceDepth != null) {
            builder.withDepthStencilState(new DepthStencilState(
                    sourceDepth.depthTest(),
                    false,
                    sourceDepth.depthBiasScaleFactor(),
                    sourceDepth.depthBiasConstant()
            ));
        }
        return builder.build();
    }
}
