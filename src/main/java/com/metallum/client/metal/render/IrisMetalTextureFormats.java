package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.metallum.Metallum;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared lowering of Iris logical texture formats to Metal {@link GpuFormat}s,
 * used by both render targets and compute custom images.
 *
 * <p>Metal has no three-channel pixel formats, so every {@code RGB*} request is
 * widened to the matching {@code RGBA*} format. Packed legacy formats without a
 * Metal equivalent ({@code RGBA2}, {@code RGBA4}, {@code RGB5_A1},
 * {@code R3_G3_B2}, {@code RGB565}) widen to {@code RGBA8_UNORM}, and
 * {@code RGB9_E5} widens to the higher-precision {@code RGBA16_FLOAT}. Unknown
 * names fall back to {@link #DEFAULT_FORMAT} after a single warning instead of
 * aborting pack loading.
 */
final class IrisMetalTextureFormats {
    static final GpuFormat DEFAULT_FORMAT = GpuFormat.RGBA8_UNORM;

    private static final Set<String> REPORTED_UNKNOWN = ConcurrentHashMap.newKeySet();

    private IrisMetalTextureFormats() {
    }

    static GpuFormat fromInternalName(final String name) {
        GpuFormat format = lookup(name);
        if (format != null) {
            return format;
        }

        if (REPORTED_UNKNOWN.add(String.valueOf(name))) {
            Metallum.LOGGER.warn(
                    "[metallum-iris] Unsupported Iris texture format '{}'; using {} instead",
                    name,
                    DEFAULT_FORMAT
            );
        }
        return DEFAULT_FORMAT;
    }

    /**
     * @return the matching Metal format, or {@code null} when the name is unknown
     */
    private static GpuFormat lookup(final String name) {
        if (name == null) {
            return null;
        }

        return switch (name) {
            // 8-bit normalized
            case "R8" -> GpuFormat.R8_UNORM;
            case "RG8" -> GpuFormat.RG8_UNORM;
            case "RGB8" -> GpuFormat.RGBA8_UNORM;
            case "RGBA", "RGBA8" -> GpuFormat.RGBA8_UNORM;
            // 8-bit signed normalized
            case "R8_SNORM" -> GpuFormat.R8_SNORM;
            case "RG8_SNORM" -> GpuFormat.RG8_SNORM;
            case "RGB8_SNORM" -> GpuFormat.RGBA8_SNORM;
            case "RGBA8_SNORM" -> GpuFormat.RGBA8_SNORM;
            // 16-bit normalized
            case "R16" -> GpuFormat.R16_UNORM;
            case "RG16" -> GpuFormat.RG16_UNORM;
            case "RGB16" -> GpuFormat.RGBA16_UNORM;
            case "RGBA16" -> GpuFormat.RGBA16_UNORM;
            // 16-bit signed normalized
            case "R16_SNORM" -> GpuFormat.R16_SNORM;
            case "RG16_SNORM" -> GpuFormat.RG16_SNORM;
            case "RGB16_SNORM" -> GpuFormat.RGBA16_SNORM;
            case "RGBA16_SNORM" -> GpuFormat.RGBA16_SNORM;
            // 16-bit float
            case "R16F" -> GpuFormat.R16_FLOAT;
            case "RG16F" -> GpuFormat.RG16_FLOAT;
            case "RGB16F" -> GpuFormat.RGBA16_FLOAT;
            case "RGBA16F" -> GpuFormat.RGBA16_FLOAT;
            // 32-bit float
            case "R32F" -> GpuFormat.R32_FLOAT;
            case "RG32F" -> GpuFormat.RG32_FLOAT;
            case "RGB32F" -> GpuFormat.RGBA32_FLOAT;
            case "RGBA32F" -> GpuFormat.RGBA32_FLOAT;
            // 8-bit integer
            case "R8I" -> GpuFormat.R8_SINT;
            case "RG8I" -> GpuFormat.RG8_SINT;
            case "RGB8I" -> GpuFormat.RGBA8_SINT;
            case "RGBA8I" -> GpuFormat.RGBA8_SINT;
            // 8-bit unsigned integer
            case "R8UI" -> GpuFormat.R8_UINT;
            case "RG8UI" -> GpuFormat.RG8_UINT;
            case "RGB8UI" -> GpuFormat.RGBA8_UINT;
            case "RGBA8UI" -> GpuFormat.RGBA8_UINT;
            // 16-bit integer
            case "R16I" -> GpuFormat.R16_SINT;
            case "RG16I" -> GpuFormat.RG16_SINT;
            case "RGB16I" -> GpuFormat.RGBA16_SINT;
            case "RGBA16I" -> GpuFormat.RGBA16_SINT;
            // 16-bit unsigned integer
            case "R16UI" -> GpuFormat.R16_UINT;
            case "RG16UI" -> GpuFormat.RG16_UINT;
            case "RGB16UI" -> GpuFormat.RGBA16_UINT;
            case "RGBA16UI" -> GpuFormat.RGBA16_UINT;
            // 32-bit integer
            case "R32I" -> GpuFormat.R32_SINT;
            case "RG32I" -> GpuFormat.RG32_SINT;
            case "RGB32I" -> GpuFormat.RGBA32_SINT;
            case "RGBA32I" -> GpuFormat.RGBA32_SINT;
            // 32-bit unsigned integer
            case "R32UI" -> GpuFormat.R32_UINT;
            case "RG32UI" -> GpuFormat.RG32_UINT;
            case "RGB32UI" -> GpuFormat.RGBA32_UINT;
            case "RGBA32UI" -> GpuFormat.RGBA32_UINT;
            // Packed legacy formats widened to the next supported Metal format
            case "RGBA2", "RGBA4", "R3_G3_B2", "RGB5_A1", "RGB565" -> GpuFormat.RGBA8_UNORM;
            case "RGB10_A2" -> GpuFormat.RGB10A2_UNORM;
            case "RGB10_A2UI" -> GpuFormat.RGB10A2_UINT;
            case "R11F_G11F_B10F" -> GpuFormat.RG11B10_FLOAT;
            case "RGB9_E5" -> GpuFormat.RGBA16_FLOAT;
            default -> null;
        };
    }
}
