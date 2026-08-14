package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
final class MetalGpuTextureView extends GpuTextureView {
    private boolean closed;
    @Nullable
    private MemorySegment nativeHandle;

    MetalGpuTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        ((MetalGpuTexture) texture).addView();
    }

    MemorySegment nativeHandle() {
        if (this.closed) {
            throw new IllegalStateException("Texture view is closed");
        }

        MetalGpuTexture texture = (MetalGpuTexture) this.texture();
        if (this.baseMipLevel() == 0 && this.mipLevels() >= texture.getMipLevels()) {
            return texture.nativeHandle();
        }
        if (this.nativeHandle == null) {
            MemorySegment viewHandle = MetalNativeBridge.metallum_create_texture_view(
                    texture.nativeHandle(),
                    this.baseMipLevel(),
                    this.mipLevels()
            );
            if (MetalNativeBridge.isNullHandle(viewHandle)) {
                throw new IllegalStateException(
                        "Failed to create Metal texture view for mip range " + this.baseMipLevel() + "+" + this.mipLevels()
                );
            }
            this.nativeHandle = viewHandle;
        }
        return this.nativeHandle;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        if (this.nativeHandle != null) {
            MemorySegment handle = this.nativeHandle;
            this.nativeHandle = null;
            ((MetalGpuTexture) this.texture()).queueNativeRelease(handle);
        }
        this.closed = true;
        ((MetalGpuTexture) this.texture()).removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }
}
