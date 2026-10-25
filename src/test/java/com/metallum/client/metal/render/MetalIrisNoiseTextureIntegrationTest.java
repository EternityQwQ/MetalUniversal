package com.metallum.client.metal.render;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
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
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** GPU readback coverage for Iris's real custom/default {@code noisetex}. */
@EnabledOnOs(OS.MAC)
final class MetalIrisNoiseTextureIntegrationTest {
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        device = MetalGpuTestSupport.createSystemDefaultDevice(
                (identifier, type) -> null,
                "Iris noise texture integration device"
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
    void customPngPreservesPixelsAndFiltering() throws IOException {
        byte[] png = twoPixelPng();
        CustomTextureData.PngData data = new CustomTextureData.PngData(
                new TextureFilteringData(false, true),
                png
        );
        try (IrisMetalNoiseTexture noise = new IrisMetalNoiseTexture(device, 64, data)) {
            ByteBuffer pixels = MetalGpuTestSupport.readback(
                    device, encoder, noise.texture(), "iris noisetex readback"
            );
            MetalGpuTestSupport.assertPixel(pixels, 0, 255, 0, 0, 255);
            MetalGpuTestSupport.assertPixel(pixels, 1, 0, 128, 255, 64);
            assertEquals("pack-noise-png", noise.source());
            assertEquals(AddressMode.CLAMP_TO_EDGE, noise.binding().sampler().getAddressModeU());
            assertEquals(FilterMode.NEAREST, noise.binding().sampler().getMinFilter());
        }
    }

    @Test
    void defaultNoiseMatchesIrisFixedSeedAndSampling() {
        int size = 4;
        try (IrisMetalNoiseTexture noise = new IrisMetalNoiseTexture(device, size, null)) {
            ByteBuffer pixels = MetalGpuTestSupport.readback(
                    device, encoder, noise.texture(), "iris noisetex readback"
            );
            int[] expected = irisNoiseArgb(size);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int argb = expected[x * size + y];
                    int offset = (x + y * size) * 4;
                    MetalGpuTestSupport.assertPixel(
                            pixels,
                            offset / 4,
                            (argb >>> 16) & 0xFF,
                            (argb >>> 8) & 0xFF,
                            argb & 0xFF,
                            0xFF
                    );
                }
            }
            assertEquals("iris-default-noise", noise.source());
            assertEquals(AddressMode.REPEAT, noise.binding().sampler().getAddressModeU());
            assertEquals(FilterMode.LINEAR, noise.binding().sampler().getMinFilter());
        }
    }

    @Test
    void unsupportedCustomNoiseFailsClosed() {
        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> new IrisMetalNoiseTexture(device, 16, new CustomTextureData.LightmapMarker())
        );
        assertTrue(failure.getMessage().contains("LightmapMarker"));
    }

    private static byte[] twoPixelPng() throws IOException {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);
        image.setRGB(1, 0, 0x400080FF);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static int[] irisNoiseArgb(final int size) {
        Random random = new Random(0);
        int[] pixels = new int[size * size];
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                pixels[x * size + y] = random.nextInt() | 0xFF000000;
            }
        }
        return pixels;
    }
}
