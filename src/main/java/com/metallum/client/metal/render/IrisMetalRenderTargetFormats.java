package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives.RenderTargetSettings;

import java.util.Arrays;
import java.util.Map;

/**
 * Iris logical render-target formats lowered to renderable Metal formats.
 *
 * <p>Delegates the per-name lowering to {@link IrisMetalTextureFormats}, the
 * shared table used with compute custom images, so the two cannot drift.
 */
final class IrisMetalRenderTargetFormats {
    static final int MAX_LOGICAL_TARGETS = 32;
    static final GpuFormat DEFAULT_FORMAT = IrisMetalTextureFormats.DEFAULT_FORMAT;

    private IrisMetalRenderTargetFormats() {
    }

    static GpuFormat[] from(final PackDirectives directives) {
        Map<Integer, RenderTargetSettings> settings = directives
                .getRenderTargetDirectives()
                .getRenderTargetSettings();
        int highest = settings.keySet().stream()
                .filter(index -> index != null && index >= 0)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        if (highest >= MAX_LOGICAL_TARGETS) {
            throw new IllegalArgumentException(
                    "Iris render target colortex" + highest + " exceeds the supported 0.."
                            + (MAX_LOGICAL_TARGETS - 1) + " range"
            );
        }

        GpuFormat[] formats = new GpuFormat[highest + 1];
        Arrays.fill(formats, DEFAULT_FORMAT);
        for (Map.Entry<Integer, RenderTargetSettings> entry : settings.entrySet()) {
            int index = entry.getKey();
            if (index < 0 || index >= formats.length) {
                throw new IllegalArgumentException("Invalid Iris render-target index " + index);
            }
            RenderTargetSettings target = entry.getValue();
            if (target.getInternalFormat() != null) {
                formats[index] = fromInternalName(target.getInternalFormat().name());
            }
        }
        return formats;
    }

    /**
     * Lowers an Iris format name, falling back to {@link #DEFAULT_FORMAT} after
     * a one-time warning for names the shared table does not know. See
     * {@link IrisMetalTextureFormats#fromInternalName(String)}.
     */
    static GpuFormat fromInternalName(final String name) {
        return IrisMetalTextureFormats.fromInternalName(name);
    }
}
