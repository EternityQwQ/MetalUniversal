package com.metallum.client.metal.render;

import com.metallum.Metallum;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import org.joml.Vector2i;
import org.jspecify.annotations.Nullable;

/**
 * Pure-logic per-logical-target render extents for Iris colortex targets.
 *
 * <p>Derives each target's pixel size from the pack's {@code size.buffer.*}
 * directives exactly like upstream Iris: {@code directives.getTextureScaleOverride}
 * resolves legacy aliases first ({@code gcolor}, {@code gdepth}, ...) and then
 * {@code colortexN}, applies relative values (a value containing {@code "."}) as
 * {@code (int)(base * value)} with a truncating cast, and treats integral
 * values as absolute pixels ({@code "1"} is one pixel, {@code "1.0"} is the
 * base size). Unlike upstream, a zero/negative result is clamped to 1 with a
 * one-time warning instead of throwing.</p>
 *
 * <p>Lives outside {@link IrisMetalRenderTargets} so the mapping can be unit
 * tested on Linux without a Metal device, and so the
 * {@code metallum.iris.sizeBuffer=off} kill switch can force every target back
 * to the base extent.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalRenderTargetExtents {
    /** Dedupes the clamp warning across every target and generation in the process. */
    private static boolean warnedClamp;

    private IrisMetalRenderTargetExtents() {
    }

    /** Immutable table of per-target extents plus the base extent they derive from. */
    record Extents(int baseWidth, int baseHeight, int[] widths, int[] heights) {
        Extents {
            if (baseWidth <= 0 || baseHeight <= 0) {
                throw new IllegalArgumentException(
                        "Base target extent must be positive: " + baseWidth + "x" + baseHeight
                );
            }
            if (widths.length != heights.length || widths.length == 0) {
                throw new IllegalArgumentException("Target extent arrays must be non-empty and equally sized");
            }
            for (int index = 0; index < widths.length; index++) {
                if (widths[index] <= 0 || heights[index] <= 0) {
                    throw new IllegalArgumentException(
                            "Target " + index + " extent must be positive: "
                                    + widths[index] + "x" + heights[index]
                    );
                }
            }
            widths = widths.clone();
            heights = heights.clone();
        }

        int count() {
            return widths.length;
        }

        int width(final int index) {
            checkIndex(index);
            return widths[index];
        }

        int height(final int index) {
            checkIndex(index);
            return heights[index];
        }

        boolean isBase(final int index) {
            return width(index) == baseWidth && height(index) == baseHeight;
        }

        /** True when at least one target differs from the base extent. */
        boolean anyNonBase() {
            for (int index = 0; index < widths.length; index++) {
                if (!isBase(index)) {
                    return true;
                }
            }
            return false;
        }

        /** Human-readable {@code colortexN=WxH} list of the non-base targets. */
        String nonBaseSummary() {
            StringBuilder summary = new StringBuilder();
            for (int index = 0; index < widths.length; index++) {
                if (isBase(index)) {
                    continue;
                }
                if (summary.length() > 0) {
                    summary.append(' ');
                }
                summary.append("colortex").append(index).append('=')
                        .append(widths[index]).append('x').append(heights[index]);
            }
            return summary.toString();
        }

        private void checkIndex(final int index) {
            if (index < 0 || index >= widths.length) {
                throw new IllegalArgumentException("Logical target index out of range: " + index);
            }
        }
    }

    /**
     * Resolves every target extent for one base size. A {@code null} directives
     * table (the test/uniform path) or a disabled
     * {@code metallum.iris.sizeBuffer} switch yields the base extent for every
     * target.
     */
    static Extents from(
            final @Nullable PackDirectives directives,
            final int targetCount,
            final int baseWidth,
            final int baseHeight
    ) {
        if (targetCount <= 0) {
            throw new IllegalArgumentException("At least one logical target is required");
        }
        if (baseWidth <= 0 || baseHeight <= 0) {
            throw new IllegalArgumentException(
                    "Base target extent must be positive: " + baseWidth + "x" + baseHeight
            );
        }
        int[] widths = new int[targetCount];
        int[] heights = new int[targetCount];
        for (int index = 0; index < targetCount; index++) {
            int width = baseWidth;
            int height = baseHeight;
            if (MetalDebugSwitches.SIZE_BUFFER && directives != null) {
                Vector2i override = directives.getTextureScaleOverride(index, baseWidth, baseHeight);
                if (override != null) {
                    width = override.x();
                    height = override.y();
                }
            }
            if (width < 1 || height < 1) {
                warnClamp(width, height);
                width = Math.max(1, width);
                height = Math.max(1, height);
            }
            widths[index] = width;
            heights[index] = height;
        }
        return new Extents(baseWidth, baseHeight, widths, heights);
    }

    private static void warnClamp(final int width, final int height) {
        if (!warnedClamp) {
            warnedClamp = true;
            Metallum.LOGGER.warn(
                    "[metallum-iris] size.buffer produced a non-positive target extent {}x{}; clamping to 1x1",
                    width, height
            );
        }
    }
}
