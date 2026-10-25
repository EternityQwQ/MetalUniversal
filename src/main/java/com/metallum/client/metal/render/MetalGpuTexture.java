package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;

@Environment(EnvType.CLIENT)
final class MetalGpuTexture extends GpuTexture {
    private static final AtomicInteger IRIS_SYNTHETIC_IDS = new AtomicInteger(1);

    /**
     * Backend-private usage bit for textures written through a Metal compute
     * shader. Blaze3D 26.2 does not expose a storage-texture usage flag.
     */
    static final int USAGE_SHADER_WRITE = 1 << 5;

    private final MetalDevice device;
    private final MTLPixelFormat mtlPixelFormat;
    private final boolean texture3D;
    private boolean closed;
    @Nullable
    private Vector4fc materializedColorClear;
    @Nullable
    private Double materializedDepthClear;
    private int views = 1;
    private int irisSyntheticId;
    @Nullable
    private MemorySegment nativeHandle;

    MetalGpuTexture(
            final MetalDevice device,
            @GpuTexture.Usage final int usage,
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        this(device, usage, label, format, width, height, depthOrLayers, mipLevels, false);
    }

    MetalGpuTexture(
            final MetalDevice device,
            @GpuTexture.Usage final int usage,
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels,
            final boolean texture3D
    ) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        this.texture3D = texture3D;
        this.mtlPixelFormat = MTLPixelFormat.from(format);

        this.nativeHandle = texture3D
                ? MetalNativeBridge.metallum_create_texture_3d(
                        device.metalDeviceHandle(),
                        this.mtlPixelFormat,
                        width,
                        height,
                        depthOrLayers,
                        mipLevels,
                        toMtlTextureUsage(usage),
                        MTLStorageMode.Private,
                        label
                )
                : MetalNativeBridge.metallum_create_texture_2d(
                        device.metalDeviceHandle(),
                        this.mtlPixelFormat,
                        width,
                        height,
                        depthOrLayers,
                        mipLevels,
                        (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? 1L : 0L,
                        toMtlTextureUsage(usage),
                        MTLStorageMode.Private,
                        label
                );
        if (MetalNativeBridge.isNullHandle(this.nativeHandle)) {
            throw new IllegalStateException(
                    "Failed to create Metal " + (texture3D ? "3D" : "2D") + " texture '" + label + "'"
            );
        }
    }

    int pixelSize() {
        return this.getFormat().blockSize();
    }

    boolean isTexture3D() {
        return this.texture3D;
    }

    void recordMaterializedClear(@Nullable final Vector4fc color, @Nullable final Double depth) {
        if (color != null) {
            this.materializedColorClear = color;
        }
        if (depth != null) {
            this.materializedDepthClear = depth;
        }
    }

    boolean clearIsRedundant(@Nullable final Vector4fc color, @Nullable final Double depth) {
        return (color == null || color.equals(this.materializedColorClear))
                && (depth == null || depth.equals(this.materializedDepthClear));
    }

    void markContentsDirty() {
        this.materializedColorClear = null;
        this.materializedDepthClear = null;
    }

    MemorySegment nativeHandle() {
        if (this.nativeHandle == null) {
            throw new IllegalStateException("Native Metal texture is closed");
        }
        return this.nativeHandle;
    }

    void queueNativeRelease(final MemorySegment handle) {
        this.device.queueResourceRelease(handle);
    }

    void addView() {
        this.views++;
    }

    void removeView() {
        this.views--;
        if (this.views < 0) {
            throw new IllegalStateException("Too many views removed from texture");
        }
        if (this.closed && this.views == 0 && this.nativeHandle != null) {
            MemorySegment handle = this.nativeHandle;
            this.nativeHandle = null;
            this.device.queueResourceRelease(handle);
        }
    }

    MTLPixelFormat mtlPixelFormat() {
        return this.mtlPixelFormat;
    }

    MTLPixelFormat mtlDepthPixelFormat() {
        return this.mtlPixelFormat == MTLPixelFormat.Stencil8
                ? MTLPixelFormat.Invalid
                : this.mtlPixelFormat;
    }

    MTLPixelFormat mtlStencilPixelFormat() {
        return this.mtlPixelFormat.hasStencil() ? this.mtlPixelFormat : MTLPixelFormat.Invalid;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    /**
     * Iris tracks every Mojang texture by an integer GL id. Metal textures do
     * not have one, so its mixin default deliberately throws. A stable,
     * process-local identity preserves the tracking contract without exposing
     * or fabricating an OpenGL object.
     */
    public int iris$getGlId() {
        if (this.irisSyntheticId == 0) {
            this.irisSyntheticId = IRIS_SYNTHETIC_IDS.getAndIncrement();
        }
        return this.irisSyntheticId;
    }

    /**
     * Iris marks GL textures whose PBR format requires non-linear mip filtering;
     * in 1.11.2 the flag is only recorded on {@code GlTexture} and never read,
     * so this no-op preserves behaviour while avoiding the mixin default
     * ({@code AssertionError: Why.}) that a Metal texture would otherwise hit
     * via {@code TextureFormat.setupTextureParameters}.
     */
    public void iris$markMipmapNonLinear() {
    }

    private static long toMtlTextureUsage(@GpuTexture.Usage final int usage) {
        long result = 0L;
        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 || (usage & GpuTexture.USAGE_COPY_DST) != 0 || (usage & GpuTexture.USAGE_COPY_SRC) != 0) {
            result |= MTLTextureUsage.ShaderRead.value;
        }
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            result |= MTLTextureUsage.RenderTarget.value;
            result |= MTLTextureUsage.ShaderRead.value;
        }
        if ((usage & USAGE_SHADER_WRITE) != 0) {
            result |= MTLTextureUsage.ShaderWrite.value;
        }
        return result == 0L ? MTLTextureUsage.ShaderRead.value : result;
    }
}
