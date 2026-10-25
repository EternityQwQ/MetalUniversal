package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Shared scaffolding for the {@code @EnabledOnOs(OS.MAC)} GPU integration
 * gates: system-default device creation with the debug options every gate
 * uses, texture/mip readback into a host {@link ByteBuffer}, and the RGBA
 * pixel assertions. Test-only and native-free at class-load time, so Linux
 * {@code compileTestJava} is unaffected; the tests that call it stay
 * macOS-gated.
 */
final class MetalGpuTestSupport {
    private MetalGpuTestSupport() {
    }

    /**
     * Creates the system-default Metal device. The caller keeps ownership and
     * closes it (the existing gates do so in {@code @AfterEach}).
     */
    static MetalDevice createSystemDefaultDevice(final ShaderSource source, final String label) {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(
                MetalNativeBridge.isNullHandle(nativeDevice),
                "MTLCreateSystemDefaultDevice returned null"
        );
        return new MetalDevice(
                source,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                label,
                MemorySegment.NULL
        );
    }

    /** Copies a texture's whole mip-0 extent into a host buffer. */
    static ByteBuffer readback(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final MetalGpuTexture texture,
            final String label
    ) {
        return readback(
                device, encoder, texture, label,
                0, texture.getWidth(0), texture.getHeight(0)
        );
    }

    /**
     * Copies a 3D texture's whole mip-0 volume into a host buffer. The blit
     * path copies every depth slice, so the staging buffer is sized with the
     * layer count.
     */
    static ByteBuffer readback3D(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final MetalGpuTexture texture,
            final String label
    ) {
        int size = texture.getWidth(0) * texture.getHeight(0)
                * texture.getDepthOrLayers() * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> label,
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                size
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer source = buffer.currentStorage().limit(size).slice().order(ByteOrder.nativeOrder());
            ByteBuffer copy = ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
            copy.put(source);
            copy.flip();
            return copy;
        }
    }

    /**
     * Copies one mip level of a texture into a host buffer. The caller submits
     * through {@code encoder}; this helper waits for the GPU work before
     * copying out of the mapped staging storage.
     */
    static ByteBuffer readback(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final MetalGpuTexture texture,
            final String label,
            final int mipLevel,
            final int width,
            final int height
    ) {
        int size = width * height * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> label,
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                size
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, mipLevel);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer source = buffer.currentStorage().limit(size).slice().order(ByteOrder.nativeOrder());
            ByteBuffer copy = ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
            copy.put(source);
            copy.flip();
            return copy;
        }
    }

    /** Asserts one RGBA8 pixel of a readback buffer against exact byte values. */
    static void assertPixel(
            final ByteBuffer pixels,
            final int index,
            final int red,
            final int green,
            final int blue,
            final int alpha
    ) {
        int offset = index * 4;
        assertEquals(red, Byte.toUnsignedInt(pixels.get(offset)), "red at pixel " + index);
        assertEquals(green, Byte.toUnsignedInt(pixels.get(offset + 1)), "green at pixel " + index);
        assertEquals(blue, Byte.toUnsignedInt(pixels.get(offset + 2)), "blue at pixel " + index);
        assertEquals(alpha, Byte.toUnsignedInt(pixels.get(offset + 3)), "alpha at pixel " + index);
    }
}
