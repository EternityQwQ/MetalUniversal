package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCompareFunction;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLResourceOptions;

import java.nio.ByteBuffer;

/**
 * Owns a Metal {@code MTLDevice} and its command queue, and allocates Metal
 * GPU resources (buffers, textures, samplers, pipeline states).
 *
 * <p>Adapted from the original {@code MetalDevice}, which implemented
 * {@code com.mojang.blaze3d.systems.GpuDeviceBackend}. In 1.12.2 there is no
 * such abstraction, so this class stands alone: the {@link GlMetalTranslator}
 * drives it directly, allocating resources as the GL translation layer needs
 * them.</p>
 */
public final class MetalDevice {
    private final long deviceHandle;
    private final long metalLayer;
    private final long cocoaView;
    private final long commandQueue;
    private final String deviceName;
    private final MetalDestructionQueue destructionQueue = new MetalDestructionQueue(3);

    public MetalDevice(long deviceHandle, long metalLayer, long cocoaView, String deviceName) {
        this.deviceHandle = deviceHandle;
        this.metalLayer = metalLayer;
        this.cocoaView = cocoaView;
        this.deviceName = deviceName;
        this.commandQueue = MetalNativeBridge.metallum_MTLDevice_makeCommandQueue(deviceHandle);
        if (MetalNativeBridge.isNull(commandQueue)) {
            throw new IllegalStateException("Failed to create MTLCommandQueue");
        }
        try {
            MetalNativeBridge.metallum_init_pipelines(deviceHandle);
        } catch (Throwable t) {
            Metallum.LOGGER.warn("metallum_init_pipelines failed (continuing): {}", t.toString());
        }
    }

    public long handle() {
        return deviceHandle;
    }

    public long commandQueue() {
        return commandQueue;
    }

    public long metalLayer() {
        return metalLayer;
    }

    public long cocoaView() {
        return cocoaView;
    }

    public String deviceName() {
        return deviceName;
    }

    public long maxMemoryAllocationSize() {
        return MetalNativeBridge.metallum_MTLDevice_maxMemoryAllocationSize(deviceHandle);
    }

    public MetalGpuBuffer createBuffer(long byteSize) {
        long buf = MetalNativeBridge.metallum_create_buffer(deviceHandle, byteSize, (int) MTLResourceOptions.ResourceStorageModeShared.value);
        if (MetalNativeBridge.isNull(buf)) {
            throw new IllegalStateException("Failed to allocate Metal buffer of " + byteSize + " bytes");
        }
        Stats.recordUsage(0, byteSize, byteSize);
        return new MetalGpuBuffer(buf, byteSize);
    }

    public MetalGpuTexture createTexture2D(int width, int height, int mipLevels, MTLPixelFormat format, long usage) {
        long tex = MetalNativeBridge.metallum_create_texture_2d(deviceHandle, width, height, mipLevels, format.value, (int) usage);
        if (MetalNativeBridge.isNull(tex)) {
            throw new IllegalStateException("Failed to allocate Metal texture " + width + "x" + height + " " + format);
        }
        return new MetalGpuTexture(tex, width, height, 1, mipLevels, 1, format);
    }

    public MetalGpuSampler createSampler(MTLCompareFunction compare, int maxAnisotropy, float lodMaxClamp) {
        long s = MetalNativeBridge.metallum_create_sampler_v2(
                deviceHandle,
                0, 0, 0, 0, 0,
                maxAnisotropy, lodMaxClamp,
                compare.value
        );
        if (MetalNativeBridge.isNull(s)) {
            throw new IllegalStateException("Failed to create Metal sampler");
        }
        return new MetalGpuSampler(s);
    }

    public long makeDepthStencilState(MTLCompareFunction compare, boolean depthWriteEnabled) {
        return MetalNativeBridge.metallum_MTLDevice_makeDepthStencilState(deviceHandle, (int) compare.value, depthWriteEnabled);
    }

    public long makeRenderPipelineState(long descriptor) {
        return MetalNativeBridge.metallum_MTLDevice_makeRenderPipelineState(deviceHandle, descriptor);
    }

    public long makeComputePipelineState(long function) {
        return MetalNativeBridge.metallum_MTLDevice_makeComputePipelineState(deviceHandle, function);
    }

    public long createFence() {
        return MetalNativeBridge.metallum_create_fence(deviceHandle);
    }

    public ByteBuffer mapBuffer(MetalGpuBuffer buffer) {
        long ptr = MetalNativeBridge.metallum_get_buffer_contents(buffer.handle());
        if (MetalNativeBridge.isNull(ptr)) {
            return null;
        }
        return longToByteBuffer(ptr, buffer.size());
    }

    public void releaseOnNextFrame(long handle) {
        if (!MetalNativeBridge.isNull(handle)) {
            destructionQueue.add(new Runnable() {
                @Override
                public void run() {
                    MetalNativeBridge.metallum_release_object(handle);
                }
            });
        }
    }

    public void rotateDestructionQueue() {
        destructionQueue.rotate();
    }

    public void close() {
        destructionQueue.close();
        if (!MetalNativeBridge.isNull(commandQueue)) {
            MetalNativeBridge.metallum_release_object(commandQueue);
        }
    }

    private static ByteBuffer longToByteBuffer(long address, long size) {
        // Java 8 has no MemorySegment; use the LWJGL 2 / direct ByteBuffer trick
        // via sun.misc.Unsafe-free reflection on java.nio.Buffer address.
        ByteBuffer buf = ByteBuffer.allocateDirect(0);
        // Allocate a fresh direct buffer of the right size, then overwrite its
        // address pointer to point at the native memory. This avoids pulling in
        // LWJGL's PointerBuffer and works on the vanilla 1.12.2 LWJGL 2 classpath.
        try {
            ByteBuffer sized = ByteBuffer.allocateDirect((int) Math.min(size, Integer.MAX_VALUE));
            long addressFieldOffset = addressOffset();
            sun.misc.Unsafe unsafe = unsafe();
            unsafe.putLong(sized, addressFieldOffset, address);
            // capacity/limit already set by allocateDirect
            return sized;
        } catch (Throwable t) {
            Metallum.LOGGER.error("Failed to map native buffer to ByteBuffer", t);
            return null;
        }
    }

    private static volatile long cachedAddressOffset = -1L;
    private static volatile sun.misc.Unsafe cachedUnsafe;

    private static sun.misc.Unsafe unsafe() throws Exception {
        if (cachedUnsafe != null) {
            return cachedUnsafe;
        }
        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        cachedUnsafe = (sun.misc.Unsafe) f.get(null);
        return cachedUnsafe;
    }

    private static long addressOffset() throws Exception {
        if (cachedAddressOffset >= 0L) {
            return cachedAddressOffset;
        }
        sun.misc.Unsafe u = unsafe();
        // The 'address' field offset is stable across java.nio.Buffer subclasses.
        ByteBuffer probe = ByteBuffer.allocateDirect(8);
        cachedAddressOffset = u.objectFieldOffset(java.nio.Buffer.class.getDeclaredField("address"));
        return cachedAddressOffset;
    }
}
