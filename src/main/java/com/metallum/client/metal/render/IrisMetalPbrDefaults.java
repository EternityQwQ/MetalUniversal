package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metal-owned neutral PBR defaults mirroring upstream Iris'
 * {@code PBRTextureManager.defaultNormalTexture}/{@code defaultSpecularTexture}.
 *
 * <p>{@code PBRType.NORMAL} defaults to {@code 0x7F7FFFFF} (RGBA
 * 127/127/255/255, a flat surface normal) and {@code PBRType.SPECULAR} to
 * {@code 0x00000000} (black, no specular). Upstream binds these when a pack
 * samples {@code normals}/{@code specular} but no PBR resource pack supplied a
 * {@code _n}/{@code _s} texture; without the fallback the raster bridges
 * return null and the pass aborts with "Missing sampler". Pack-provided PBR
 * atlases are out of scope; those resolve through the custom-texture layer
 * first.
 */
@Environment(EnvType.CLIENT)
final class IrisMetalPbrDefaults implements AutoCloseable {
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_DST;
    /** Upstream {@code PBRType.NORMAL.getDefaultValue()}: RGBA 127/127/255/255, flat. */
    static final int NORMAL_DEFAULT_RGBA = 0x7F7FFFFF;
    /** Upstream {@code PBRType.SPECULAR.getDefaultValue()}: RGBA 0/0/0/0, none. */
    static final int SPECULAR_DEFAULT_RGBA = 0x00000000;

    /** Dedupes the one-line probe for packs that run without PBR resources. */
    private static final Set<String> REPORTED_FALLBACKS = ConcurrentHashMap.newKeySet();

    private final SingleColor normal;
    private final SingleColor specular;
    private boolean closed;

    IrisMetalPbrDefaults(final MetalDevice device) {
        this.normal = new SingleColor(
                device, "metallum:iris_pbr_normal_default", rgba8(NORMAL_DEFAULT_RGBA)
        );
        this.specular = new SingleColor(
                device, "metallum:iris_pbr_specular_default", rgba8(SPECULAR_DEFAULT_RGBA)
        );
    }

    /** Splits an upstream RGBA-packed int into RGBA byte order. */
    private static byte[] rgba8(final int rgba) {
        return new byte[]{
                (byte) (rgba >>> 24), (byte) (rgba >>> 16), (byte) (rgba >>> 8), (byte) rgba
        };
    }

    /** Whether {@code name} is one of upstream's PBR level-sampler names. */
    static boolean isPbrSampler(final String name) {
        return "normals".equals(name) || "specular".equals(name);
    }

    MetalRenderPass.TextureViewAndSampler binding(final String name) {
        ensureOpen();
        SingleColor texture = switch (name) {
            case "normals" -> this.normal;
            case "specular" -> this.specular;
            default -> throw new IllegalArgumentException("Not a PBR sampler: " + name);
        };
        if (REPORTED_FALLBACKS.add(name)) {
            MetalProbeReport.record("pbr default sampler name=" + name
                    + " texture=" + texture.label);
        }
        return texture.binding();
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris PBR defaults are closed");
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.normal.close();
        this.specular.close();
    }

    /** One 1x1 RGBA8 texture plus its sampler and view. */
    private static final class SingleColor implements AutoCloseable {
        private final String label;
        private final MetalGpuTexture texture;
        private final MetalGpuTextureView view;
        private final MetalGpuSampler sampler;

        SingleColor(final MetalDevice device, final String label, final byte[] rgba8) {
            this.label = label;
            this.texture = (MetalGpuTexture) device.createTexture(
                    label,
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

            ByteBuffer pixel = ByteBuffer.allocateDirect(rgba8.length);
            pixel.put(rgba8);
            pixel.flip();
            device.createCommandEncoder().writeToTexture(this.texture, pixel, 0, 0, 0, 0, 1, 1);
        }

        MetalRenderPass.TextureViewAndSampler binding() {
            return new MetalRenderPass.TextureViewAndSampler(this.view, this.sampler);
        }

        @Override
        public void close() {
            this.view.close();
            this.texture.close();
            this.sampler.close();
        }
    }
}
