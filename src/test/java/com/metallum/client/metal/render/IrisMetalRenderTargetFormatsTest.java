package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class IrisMetalRenderTargetFormatsTest {
    @Test
    void preservesComponentWidthAndNumericClass() {
        Map<String, GpuFormat> expected = Map.ofEntries(
                Map.entry("R8", GpuFormat.R8_UNORM),
                Map.entry("RG16", GpuFormat.RG16_UNORM),
                Map.entry("RGBA16", GpuFormat.RGBA16_UNORM),
                Map.entry("R16F", GpuFormat.R16_FLOAT),
                Map.entry("RG32F", GpuFormat.RG32_FLOAT),
                Map.entry("RGBA32F", GpuFormat.RGBA32_FLOAT),
                Map.entry("R8I", GpuFormat.R8_SINT),
                Map.entry("RG16I", GpuFormat.RG16_SINT),
                Map.entry("RGBA32I", GpuFormat.RGBA32_SINT),
                Map.entry("R8UI", GpuFormat.R8_UINT),
                Map.entry("RG16UI", GpuFormat.RG16_UINT),
                Map.entry("RGBA32UI", GpuFormat.RGBA32_UINT),
                Map.entry("RGB10_A2", GpuFormat.RGB10A2_UNORM),
                Map.entry("RGB10_A2UI", GpuFormat.RGB10A2_UINT),
                Map.entry("R11F_G11F_B10F", GpuFormat.RG11B10_FLOAT)
        );
        expected.forEach((name, format) ->
                assertEquals(format, IrisMetalRenderTargetFormats.fromInternalName(name), name));
    }

    @Test
    void promotesUnsupportedThreeChannelAttachmentsWithoutLosingPrecision() {
        assertEquals(
                GpuFormat.RGBA8_UNORM,
                IrisMetalRenderTargetFormats.fromInternalName("RGB8")
        );
        assertEquals(
                GpuFormat.RGBA16_FLOAT,
                IrisMetalRenderTargetFormats.fromInternalName("RGB16F")
        );
        assertEquals(
                GpuFormat.RGBA32_SINT,
                IrisMetalRenderTargetFormats.fromInternalName("RGB32I")
        );
        assertEquals(
                GpuFormat.RGBA16_UINT,
                IrisMetalRenderTargetFormats.fromInternalName("RGB16UI")
        );
    }

    @Test
    void unknownFormatsFallBackToTheDefaultInsteadOfThrowing() {
        assertEquals(
                IrisMetalTextureFormats.DEFAULT_FORMAT,
                IrisMetalRenderTargetFormats.fromInternalName("PACK_SPECIFIC_MAGIC")
        );
        assertNotNull(IrisMetalRenderTargetFormats.fromInternalName(null));
    }

    /**
     * Every format the pinned Iris enumerates must be lowered explicitly, not
     * silently caught by the unknown-format fallback. This is the regression
     * net for CR 5.9.3, whose colortex1/colortex4 request SNORM formats.
     */
    @Test
    void mapsEveryUpstreamIrisFormat() {
        Map<String, GpuFormat> expected = Map.ofEntries(
                Map.entry("RGBA", GpuFormat.RGBA8_UNORM),
                Map.entry("R8", GpuFormat.R8_UNORM),
                Map.entry("RG8", GpuFormat.RG8_UNORM),
                Map.entry("RGB8", GpuFormat.RGBA8_UNORM),
                Map.entry("RGBA8", GpuFormat.RGBA8_UNORM),
                Map.entry("R8_SNORM", GpuFormat.R8_SNORM),
                Map.entry("RG8_SNORM", GpuFormat.RG8_SNORM),
                Map.entry("RGB8_SNORM", GpuFormat.RGBA8_SNORM),
                Map.entry("RGBA8_SNORM", GpuFormat.RGBA8_SNORM),
                Map.entry("R16", GpuFormat.R16_UNORM),
                Map.entry("RG16", GpuFormat.RG16_UNORM),
                Map.entry("RGB16", GpuFormat.RGBA16_UNORM),
                Map.entry("RGBA16", GpuFormat.RGBA16_UNORM),
                Map.entry("R16_SNORM", GpuFormat.R16_SNORM),
                Map.entry("RG16_SNORM", GpuFormat.RG16_SNORM),
                Map.entry("RGB16_SNORM", GpuFormat.RGBA16_SNORM),
                Map.entry("RGBA16_SNORM", GpuFormat.RGBA16_SNORM),
                Map.entry("R16F", GpuFormat.R16_FLOAT),
                Map.entry("RG16F", GpuFormat.RG16_FLOAT),
                Map.entry("RGB16F", GpuFormat.RGBA16_FLOAT),
                Map.entry("RGBA16F", GpuFormat.RGBA16_FLOAT),
                Map.entry("R32F", GpuFormat.R32_FLOAT),
                Map.entry("RG32F", GpuFormat.RG32_FLOAT),
                Map.entry("RGB32F", GpuFormat.RGBA32_FLOAT),
                Map.entry("RGBA32F", GpuFormat.RGBA32_FLOAT),
                Map.entry("R8I", GpuFormat.R8_SINT),
                Map.entry("RG8I", GpuFormat.RG8_SINT),
                Map.entry("RGB8I", GpuFormat.RGBA8_SINT),
                Map.entry("RGBA8I", GpuFormat.RGBA8_SINT),
                Map.entry("R8UI", GpuFormat.R8_UINT),
                Map.entry("RG8UI", GpuFormat.RG8_UINT),
                Map.entry("RGB8UI", GpuFormat.RGBA8_UINT),
                Map.entry("RGBA8UI", GpuFormat.RGBA8_UINT),
                Map.entry("R16I", GpuFormat.R16_SINT),
                Map.entry("RG16I", GpuFormat.RG16_SINT),
                Map.entry("RGB16I", GpuFormat.RGBA16_SINT),
                Map.entry("RGBA16I", GpuFormat.RGBA16_SINT),
                Map.entry("R16UI", GpuFormat.R16_UINT),
                Map.entry("RG16UI", GpuFormat.RG16_UINT),
                Map.entry("RGB16UI", GpuFormat.RGBA16_UINT),
                Map.entry("RGBA16UI", GpuFormat.RGBA16_UINT),
                Map.entry("R32I", GpuFormat.R32_SINT),
                Map.entry("RG32I", GpuFormat.RG32_SINT),
                Map.entry("RGB32I", GpuFormat.RGBA32_SINT),
                Map.entry("RGBA32I", GpuFormat.RGBA32_SINT),
                Map.entry("R32UI", GpuFormat.R32_UINT),
                Map.entry("RG32UI", GpuFormat.RG32_UINT),
                Map.entry("RGB32UI", GpuFormat.RGBA32_UINT),
                Map.entry("RGBA32UI", GpuFormat.RGBA32_UINT),
                Map.entry("RGBA2", GpuFormat.RGBA8_UNORM),
                Map.entry("RGBA4", GpuFormat.RGBA8_UNORM),
                Map.entry("R3_G3_B2", GpuFormat.RGBA8_UNORM),
                Map.entry("RGB5_A1", GpuFormat.RGBA8_UNORM),
                Map.entry("RGB565", GpuFormat.RGBA8_UNORM),
                Map.entry("RGB10_A2", GpuFormat.RGB10A2_UNORM),
                Map.entry("RGB10_A2UI", GpuFormat.RGB10A2_UINT),
                Map.entry("R11F_G11F_B10F", GpuFormat.RG11B10_FLOAT),
                Map.entry("RGB9_E5", GpuFormat.RGBA16_FLOAT)
        );

        for (InternalTextureFormat format : InternalTextureFormat.values()) {
            String name = format.name();
            assertNotNull(expected.get(name), "unpinned upstream format " + name);
            assertEquals(expected.get(name), IrisMetalRenderTargetFormats.fromInternalName(name), name);
        }
    }

    @Test
    void computeImagesUseTheSharedFormatTable() {
        assertEquals(
                GpuFormat.RGBA8_SNORM,
                IrisMetalComputeResources.imageFormat(InternalTextureFormat.RGBA8_SNORM)
        );
        assertEquals(
                GpuFormat.RGBA8_UNORM,
                IrisMetalComputeResources.imageFormat(InternalTextureFormat.RGB8)
        );
        assertEquals(
                GpuFormat.RGBA16_FLOAT,
                IrisMetalComputeResources.imageFormat(InternalTextureFormat.RGB9_E5)
        );
    }
}
