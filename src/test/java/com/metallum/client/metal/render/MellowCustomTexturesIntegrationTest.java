package com.metallum.client.metal.render;

import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * macOS GPU coverage for the Mellow custom-texture kinds: a synthetic
 * {@code RawData3D} volume must create a real Metal 3D texture whose upload
 * reads back byte-identical, and a {@code ResourceData} entry must prewarm
 * lazily (no TextureManager access, no crash) when no client is running.
 *
 * <p>Skipped on Linux; the pack-level shape gate is
 * {@code MellowCustomTextureDefinitionsTest}.
 */
@EnabledOnOs(OS.MAC)
final class MellowCustomTexturesIntegrationTest {
    private static final int SIZE = 2;

    private MetalDevice device;

    @BeforeEach
    void createDevice() {
        device = MetalGpuTestSupport.createSystemDefaultDevice(
                (identifier, type) -> null,
                "Mellow custom textures integration device"
        );
    }

    @AfterEach
    void closeDevice() {
        if (device != null) {
            device.close();
        }
    }

    @Test
    void rawData3DIsUploadedAndReadsBackAsA3DTexture() {
        byte[] content = new byte[SIZE * SIZE * SIZE];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 7 + 1);
        }
        CustomTextureData.RawData3D raw = new CustomTextureData.RawData3D(
                content,
                new TextureFilteringData(true, false),
                InternalTextureFormat.R8,
                PixelFormat.RED,
                PixelType.UNSIGNED_BYTE,
                SIZE,
                SIZE,
                SIZE
        );

        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device, Map.of(), Map.of("customtex0", raw)
        )) {
            textures.prewarmAll();
            MetalRenderPass.TextureViewAndSampler binding =
                    textures.resolve(TextureStage.GBUFFERS_AND_SHADOW, "customtex0");
            assertNotNull(binding, "the raw 3D volume must resolve from the global customtex map");
            assertTrue(textures.hasOverride(TextureStage.GBUFFERS_AND_SHADOW, "customtex0"));

            MetalGpuTexture texture = (MetalGpuTexture) binding.textureView().texture();
            assertTrue(texture.isTexture3D(), "the binding must be a 3D Metal texture");
            assertEquals(SIZE, texture.getDepthOrLayers(), "layer count");

            ByteBuffer readback = MetalGpuTestSupport.readback3D(
                    device, device.createCommandEncoder(), texture, "mellow raw3d readback"
            );
            assertEquals(content.length, readback.remaining(), "readback size");
            for (int i = 0; i < content.length; i++) {
                assertEquals(content[i], readback.get(i), "raw 3D byte " + i);
            }
        }
    }

    @Test
    void resourceDataPrewarmsLazilyWithoutAClient() {
        Assumptions.assumeTrue(Minecraft.getInstance() == null,
                "a live client owns the TextureManager this test simulates as not ready");
        CustomTextureData.ResourceData resource = new CustomTextureData.ResourceData(
                "minecraft", "textures/environment/clouds.png"
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device,
                Map.of(TextureStage.DEFERRED, Map.of("colortex3", resource))
        )) {
            // prewarmAll must tolerate a not-ready TextureManager instead of
            // throwing during world load.
            textures.prewarmAll();
            assertNull(textures.resolve(TextureStage.DEFERRED, "colortex3"),
                    "an unavailable TextureManager texture binds as null (caller keeps its standard sampler)");
            assertTrue(textures.hasOverride(TextureStage.DEFERRED, "colortex3"),
                    "the declaration must still register as an override");
        }
    }
}
