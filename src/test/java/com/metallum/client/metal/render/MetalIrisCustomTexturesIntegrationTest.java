package com.metallum.client.metal.render;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** GPU and lifecycle coverage for stage-scoped Iris custom texture overrides. */
@EnabledOnOs(OS.MAC)
final class MetalIrisCustomTexturesIntegrationTest {
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        device = MetalGpuTestSupport.createSystemDefaultDevice(
                (identifier, type) -> null,
                "Iris custom textures integration device"
        );
        encoder = device.createCommandEncoder();
    }

    @AfterEach
    void closeDevice() {
        if (device != null) {
            device.close();
        }
    }

    @Test
    void pngOverridePreservesPixelsAndFiltering() throws IOException {
        EnumMap<TextureStage, Object2ObjectOpenHashMap<String, CustomTextureData>> definitions = definitions(
                TextureStage.COMPOSITE_AND_FINAL,
                "colortex7",
                png(false, true, 0xFFFF0000, 0x400080FF)
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(device, definitions)) {
            MetalRenderPass.TextureViewAndSampler binding =
                    textures.resolve(TextureStage.COMPOSITE_AND_FINAL, "colortex7");
            assertNotNull(binding);
            ByteBuffer pixels = MetalGpuTestSupport.readback(
                    device, encoder, (MetalGpuTexture) binding.textureView().texture(),
                    "iris custom texture readback"
            );
            MetalGpuTestSupport.assertPixel(pixels, 0, 255, 0, 0, 255);
            MetalGpuTestSupport.assertPixel(pixels, 1, 0, 128, 255, 64);
            assertEquals(AddressMode.CLAMP_TO_EDGE, binding.sampler().getAddressModeU());
            assertEquals(AddressMode.CLAMP_TO_EDGE, binding.sampler().getAddressModeV());
            assertEquals(FilterMode.NEAREST, binding.sampler().getMinFilter());
            assertEquals(FilterMode.NEAREST, binding.sampler().getMagFilter());
        }
    }

    @Test
    void stageIsolationAndAliasOrderPreserveOverridePrecedence() throws IOException {
        EnumMap<TextureStage, Object2ObjectOpenHashMap<String, CustomTextureData>> definitions = definitions(
                TextureStage.COMPOSITE_AND_FINAL,
                "colortex7",
                png(true, true, 0xFF00FF00)
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(device, definitions);
             IrisMetalCustomTextures standards = new IrisMetalCustomTextures(
                     device,
                     definitions(TextureStage.BEGIN, "standardSampler", png(false, false, 0xFFFFFFFF))
             )) {
            MetalRenderPass.TextureViewAndSampler standardBinding =
                    standards.resolve(TextureStage.BEGIN, "standardSampler");
            assertNotNull(standardBinding);

            assertSame(
                    standardBinding,
                    textures.overrideOrDefault(TextureStage.DEFERRED, standardBinding, "colortex7"),
                    "an override from another stage must not leak"
            );
            assertSame(
                    standardBinding,
                    textures.overrideOrDefault(
                            TextureStage.COMPOSITE_AND_FINAL,
                            standardBinding,
                            "missingAlias",
                            "alsoMissing"
                    )
            );

            MetalRenderPass.TextureViewAndSampler override = textures.overrideOrDefault(
                    TextureStage.COMPOSITE_AND_FINAL,
                    standardBinding,
                    "missingAlias",
                    "colortex7"
            );
            assertNotNull(override);
            assertNotSame(standardBinding, override, "same-stage custom sampler must override the standard binding");
            assertEquals(FilterMode.LINEAR, override.sampler().getMinFilter());
            assertTrue(textures.hasOverride(TextureStage.COMPOSITE_AND_FINAL, "colortex7"));
            assertFalse(textures.hasOverride(TextureStage.DEFERRED, "colortex7"));
        }
    }

    @Test
    void closeReleasesEveryMaterializedResourceAndIsIdempotent() throws IOException {
        IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device,
                definitions(TextureStage.BEGIN, "customSampler", png(false, false, 0xFFFFFFFF))
        );
        MetalRenderPass.TextureViewAndSampler binding = textures.resolve(TextureStage.BEGIN, "customSampler");
        assertNotNull(binding);
        MetalGpuTexture texture = (MetalGpuTexture) binding.textureView().texture();
        MetalGpuSampler sampler = (MetalGpuSampler) binding.sampler();

        textures.close();
        textures.close();

        assertTrue(binding.textureView().isClosed());
        assertTrue(texture.isClosed());
        assertTrue(sampler.isClosed());
        assertThrows(
                IllegalStateException.class,
                () -> textures.resolve(TextureStage.BEGIN, "customSampler")
        );
    }

    @Test
    void unsupportedKindsFailClosedOnlyWhenTheirStageSamplerIsRequested() {
        List<CustomTextureData> unsupported = List.of(
                new CustomTextureData.LightmapMarker(),
                new CustomTextureData.ResourceData("minecraft", "textures/block/dirt.png"),
                new CustomTextureData.RawData1D(
                        new byte[4], filtering(), InternalTextureFormat.RGBA8,
                        PixelFormat.RGBA, PixelType.UNSIGNED_BYTE, 1
                ),
                new CustomTextureData.RawData2D(
                        new byte[4], filtering(), InternalTextureFormat.RGBA8,
                        PixelFormat.RGBA, PixelType.UNSIGNED_BYTE, 1, 1
                ),
                new CustomTextureData.RawData3D(
                        new byte[4], filtering(), InternalTextureFormat.RGBA8,
                        PixelFormat.RGBA, PixelType.UNSIGNED_BYTE, 1, 1, 1
                ),
                new CustomTextureData.RawDataRect(
                        new byte[4], filtering(), InternalTextureFormat.RGBA8,
                        PixelFormat.RGBA, PixelType.UNSIGNED_BYTE, 1, 1
                )
        );

        for (CustomTextureData data : unsupported) {
            String type = data.getClass().getSimpleName();
            try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                    device,
                    definitions(TextureStage.SHADOWCOMP, "requiredInput", data)
            )) {
                assertNull(
                        textures.resolve(TextureStage.DEFERRED, "requiredInput"),
                        "unused stage-scoped unsupported data must not block pack load"
                );
                assertNull(
                        textures.resolve(TextureStage.SHADOWCOMP, "unreferencedInput"),
                        "unreferenced unsupported sampler must remain lazy"
                );

                UnsupportedOperationException failure = assertThrows(
                        UnsupportedOperationException.class,
                        () -> textures.resolve(TextureStage.SHADOWCOMP, "requiredInput")
                );
                assertTrue(failure.getMessage().contains("stage=SHADOWCOMP"));
                assertTrue(failure.getMessage().contains("sampler=requiredInput"));
                assertTrue(failure.getMessage().contains("type=" + type));
            }
        }
    }

    private static EnumMap<TextureStage, Object2ObjectOpenHashMap<String, CustomTextureData>> definitions(
            final TextureStage stage,
            final String sampler,
            final CustomTextureData data
    ) {
        EnumMap<TextureStage, Object2ObjectOpenHashMap<String, CustomTextureData>> definitions =
                new EnumMap<>(TextureStage.class);
        Object2ObjectOpenHashMap<String, CustomTextureData> stageDefinitions = new Object2ObjectOpenHashMap<>();
        stageDefinitions.put(sampler, data);
        definitions.put(stage, stageDefinitions);
        return definitions;
    }

    private static CustomTextureData.PngData png(
            final boolean blur,
            final boolean clamp,
            final int... argb
    ) throws IOException {
        BufferedImage image = new BufferedImage(argb.length, 1, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < argb.length; x++) {
            image.setRGB(x, 0, argb[x]);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return new CustomTextureData.PngData(new TextureFilteringData(blur, clamp), output.toByteArray());
    }

    private static TextureFilteringData filtering() {
        return new TextureFilteringData(false, false);
    }
}
