package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLCommandQueue {
    private MemorySegment handle;

    private MTLCommandQueue(final MemorySegment handle) {
        this.handle = handle;
    }

    public static MTLCommandQueue create(final MemorySegment device) {
        MemorySegment handle = MetalNativeBridge.MTLDevice_makeCommandQueue(device);
        if (MetalNativeBridge.isNullHandle(handle)) {
            throw new IllegalStateException("Failed to create Metal command queue");
        }
        return new MTLCommandQueue(handle);
    }

    public MTLCommandBuffer makeCommandBuffer(@Nullable final String label) {
        MemorySegment commandBuffer = MetalNativeBridge.MTLCommandQueue_makeCommandBuffer(handle, label);
        if (MetalNativeBridge.isNullHandle(commandBuffer)) {
            throw new IllegalStateException("Failed to create MTLCommandBuffer");
        }
        return new MTLCommandBuffer(commandBuffer);
    }

    public void close() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return;
        }
        MetalNativeBridge.metallum_release_object(handle);
        handle = MemorySegment.NULL;
    }
}
