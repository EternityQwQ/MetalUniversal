package com.metallum.client.metal.render;

import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native-free planning logic for the shaderpack per-stage compact sampler
 * index remap.
 *
 * <p>Metal limits {@code [[sampler(N)]]} to {@code 0..15} per shader stage,
 * while a shaderpack program may declare dozens of combined image samplers
 * (Complementary Reimagined's {@code deferred1} declares 23). This class owns
 * the pure index-assignment policy so it can be unit-tested on hosts without
 * the Metal native libraries; {@link MetalCrossShaderCompiler} applies the
 * plan to SPIRV-Cross decorations and to the runtime
 * {@link MetalCompiledRenderPipeline.ResourceBinding} table.
 */
@Environment(EnvType.CLIENT)
final class ShaderpackSamplerIndexPlan {
    /**
     * Metal's per-stage hardware limit for sampler slots: MSL rejects
     * {@code [[sampler(N)]]} for any {@code N > 15}.
     */
    static final int MAX_METAL_SAMPLERS_PER_STAGE = 16;

    private ShaderpackSamplerIndexPlan() {
    }

    /**
     * Assigns compact per-stage Metal sampler/texture indices to the active
     * sampled images of one shader stage.
     *
     * <p>Walks the stage's declared images in declaration order and assigns
     * {@code 0..n-1} to the subset that SPIRV-Cross reported active. The same
     * index is used for the MSL {@code [[texture(N)]]} and
     * {@code [[sampler(N)]]} attributes (SPIRV-Cross emits both from the same
     * decoration binding), and the render-time binding path looks the index up
     * in the identical per-stage table.
     *
     * @param declaredImages sampled image names in stage declaration order
     *                       (combined {@code SAMPLED_IMAGE} and separate
     *                       {@code SEPARATE_IMAGE} resources, de-duplicated).
     * @param activeNames    names SPIRV-Cross reported as active for the stage.
     * @param label          human-readable stage label used in the error message.
     * @return an immutable, declaration-ordered map of active image name to
     *         compact Metal index.
     * @throws ShaderCompileException if more than
     *                               {@link #MAX_METAL_SAMPLERS_PER_STAGE}
     *                               declared images are active.
     */
    static Map<String, Integer> assignSampledImageIndices(
            final List<String> declaredImages,
            final Set<String> activeNames,
            final String label
    ) throws ShaderCompileException {
        final List<String> activeImages = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (final String name : declaredImages) {
            if (activeNames.contains(name) && seen.add(name)) {
                activeImages.add(name);
            }
        }
        if (activeImages.size() > MAX_METAL_SAMPLERS_PER_STAGE) {
            final String overflow = activeImages.get(MAX_METAL_SAMPLERS_PER_STAGE);
            throw new ShaderCompileException(
                    "Stage " + label + " has " + activeImages.size() + " active sampled images, but Metal allows at most "
                            + MAX_METAL_SAMPLERS_PER_STAGE + " sampler slots per stage: resource '" + overflow
                            + "' would need [[sampler(" + MAX_METAL_SAMPLERS_PER_STAGE + ")]]. "
                            + "Active sampled images in declaration order: " + activeImages
            );
        }
        final Map<String, Integer> indices = new LinkedHashMap<>();
        for (int index = 0; index < activeImages.size(); index++) {
            indices.put(activeImages.get(index), index);
        }
        return Collections.unmodifiableMap(indices);
    }
}
