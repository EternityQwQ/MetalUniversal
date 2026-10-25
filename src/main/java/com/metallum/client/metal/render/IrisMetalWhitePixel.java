package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;

/**
 * Metal-owned shared 1&times;1 white pixel, mirroring upstream Iris'
 * {@code whitePixel}. It backs the samplers the engine did not bind for a
 * given render type: a white {@code iris_overlay} yields
 * {@code entityColor = vec4(1,1,1,0)} (Iris' entity workaround then zeroes the
 * rgb, so no hurt tint) and a white {@code lightmap} yields fullbright.
 *
 * <p>Owned by {@link IrisMetalWorldResources} so its lifetime is exactly one
 * world generation, like the noise texture.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalWhitePixel implements AutoCloseable {
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_DST;
    private static final int WHITE_RGBA8 = 0xFFFFFFFF;

    private final MetalGpuTexture texture;
    private final MetalGpuTextureView view;
    private final MetalGpuSampler sampler;
    private boolean closed;

    IrisMetalWhitePixel(final MetalDevice device) {
        this.texture = (MetalGpuTexture) device.createTexture(
                "metallum:iris_white_pixel",
                USAGE,
                GpuFormat.RGBA8_UNORM,
                1,
                1,
                1,
                1
        );
        this.view = (MetalGpuTextureView) device.createTextureView(this.texture);
        this.sampler = new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.of(0.0)
        );

        ByteBuffer pixel = ByteBuffer.allocateDirect(Integer.BYTES);
        pixel.putInt(0, WHITE_RGBA8);
        device.createCommandEncoder().writeToTexture(this.texture, pixel, 0, 0, 0, 0, 1, 1);
    }

    MetalRenderPass.TextureViewAndSampler binding() {
        ensureOpen();
        return new MetalRenderPass.TextureViewAndSampler(this.view, this.sampler);
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris white pixel is closed");
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.view.close();
        this.texture.close();
        this.sampler.close();
    }
}
