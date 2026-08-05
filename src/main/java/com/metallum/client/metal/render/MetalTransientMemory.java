package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 瞬态内存池（CPU 块 + GPU 块），submit 轮转复用。
 * 1.21.11 无 26.2 的 TransientMemory 接口与 GpuBufferSlice.MappedView，
 * 本类改为纯内部工具类（由 MetalCommandEncoder 持有）。
 */
@Environment(EnvType.CLIENT)
final class MetalTransientMemory {
    private static final long BLOCK_SIZE = 524288L;
    private static final long MAX_CPU_ALIGNMENT = 16L;
    private static final long MAX_GPU_ALIGNMENT = 256L;
    private static final int BLOCK_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE;

    record MappedView(GpuBufferSlice slice, ByteBuffer data, Runnable closeAction) implements AutoCloseable {
        @Override
        public void close() {
            closeAction.run();
        }
    }

    private final MetalDevice device;
    private final MetalCommandEncoder encoder;
    private final TransientBlockAllocator<Long> cpuBlockAllocator = new TransientBlockAllocator<>(
            BLOCK_SIZE, MAX_CPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(MemoryUtil::nmemAlloc, MemoryUtil::nmemFree)
    );
    private final TransientBlockAllocator<MetalGpuBuffer> gpuBlockAllocator;
    private long submitIndex = 0L;

    MetalTransientMemory(final MetalDevice device, final MetalCommandEncoder encoder) {
        this.device = device;
        this.encoder = encoder;
        this.gpuBlockAllocator = new TransientBlockAllocator<>(
                BLOCK_SIZE, MAX_GPU_ALIGNMENT, TransientBlockAllocator.Allocator.create(this::allocateGpuBlock, this::freeGpuBlock)
        );
    }

    void rotate() {
        cpuBlockAllocator.rotate().run();
        encoder.queueForDestroy(gpuBlockAllocator.rotate());
        submitIndex++;
    }

    void close() {
        cpuBlockAllocator.close();
        gpuBlockAllocator.close();
    }

    private MetalGpuBuffer allocateGpuBlock(final long size) {
        return new MetalGpuBuffer(device, BLOCK_USAGE, size);
    }

    private void freeGpuBlock(final MetalGpuBuffer block) {
        block.close();
    }

    ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<Long> alloc = cpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        return MemoryUtil.memByteBuffer(alloc.block() + alloc.offset(), (int) alloc.size());
    }

    MappedView allocateStaging(final long size, final long alignment, final int usage, final long minimumAllocation, final long elementSize) {
        return allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
    }

    GpuBufferSlice allocateGpu(final long size, final long alignment, final int usage, final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<MetalGpuBuffer> alloc = gpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        return new GpuBufferSlice(wrap(alloc.block(), usage), alloc.offset(), alloc.size());
    }

    MappedView allocateGpuMapped(final long size, final long alignment, final int usage, final long minimumAllocation, final long elementSize) {
        return allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
    }

    private MappedView allocateMapped(final long size, final long alignment, final int usage, final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<MetalGpuBuffer> alloc = gpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        GpuBufferSlice slice = new GpuBufferSlice(wrap(alloc.block(), usage), alloc.offset(), alloc.size());
        ByteBuffer hostView = alloc.block().sliceStorage(alloc.offset(), alloc.size());
        return new MappedView(slice, hostView, () -> {
        });
    }

    private MetalGpuBuffer wrap(final MetalGpuBuffer block, final int usage) {
        return new TransientGpuBuffer(device, block.nativeHandle(), usage, block.size(), this, submitIndex);
    }

    GpuBufferSlice uploadStaging(final ByteBuffer data, final long alignment, final int usage, final long minimumAllocation, final long elementSize) {
        return upload(List.of(data), alignment, usage, minimumAllocation, elementSize);
    }

    GpuBufferSlice uploadStaging(final List<ByteBuffer> data, final long alignment, final int usage, final long minimumAllocation, final long elementSize) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    GpuBufferSlice uploadGpu(final List<ByteBuffer> data, final long alignment, final int usage, final long minimumAllocation, final long elementSize) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    private GpuBufferSlice upload(final List<ByteBuffer> data, final long alignment, final int usage, final long minimumAllocation, final long elementSize) {
        long totalSize = 0L;
        for (ByteBuffer buffer : data) {
            totalSize += buffer.remaining();
            totalSize = roundToward(totalSize, alignment);
        }

        GpuBufferSlice result;
        try (MappedView mapped = allocateMapped(totalSize, alignment, usage, minimumAllocation, elementSize)) {
            long mappedPtr = MemoryUtil.memAddress(mapped.data());
            long offset = 0L;
            for (ByteBuffer buffer : data) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), mappedPtr + offset, Math.min(mapped.slice().length() - offset, buffer.remaining()));
                offset += buffer.remaining();
                offset = roundToward(offset, alignment);
                if (offset >= mapped.slice().length()) {
                    break;
                }
            }
            result = mapped.slice();
        }
        return result;
    }

    List<GpuBufferSlice> multiUploadStaging(final List<ByteBuffer> data, final long alignment, final int usage) {
        return multiUpload(data, alignment, usage);
    }

    List<GpuBufferSlice> multiUploadGpu(final List<ByteBuffer> data, final long alignment, final int usage) {
        return multiUpload(data, alignment, usage);
    }

    private List<GpuBufferSlice> multiUpload(final List<ByteBuffer> data, final long alignment, final int usage) {
        ArrayList<GpuBufferSlice> uploaded = new ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            uploaded.add(null);
        }
        List<Integer> sortedIndices = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            sortedIndices.add(i);
        }
        sortedIndices.sort((a, b) -> Integer.compare(data.get(a).remaining(), data.get(b).remaining()));

        while (!sortedIndices.isEmpty()) {
            boolean allocatedAnything = false;

            for (int i = sortedIndices.size() - 1; i >= 0; i--) {
                int bufferIndex = sortedIndices.get(i);
                ByteBuffer currentBuffer = data.get(bufferIndex);
                if (gpuBlockAllocator.canAllocateInCurrentBlock(currentBuffer.remaining(), alignment)) {
                    sortedIndices.remove(i);
                    try (MappedView view = allocateGpuMapped(currentBuffer.remaining(), alignment, usage, 0L, 1L)) {
                        MemoryUtil.memCopy(currentBuffer, view.data());
                        uploaded.set(bufferIndex, view.slice());
                    }
                    allocatedAnything = true;
                    break;
                }
            }

            if (!allocatedAnything) {
                int bufferIndex = sortedIndices.remove(sortedIndices.size() - 1);
                ByteBuffer currentBuffer = data.get(bufferIndex);
                try (MappedView view = allocateGpuMapped(currentBuffer.remaining(), alignment, usage, 0L, 1L)) {
                    MemoryUtil.memCopy(currentBuffer, view.data());
                    uploaded.set(bufferIndex, view.slice());
                }
            }
        }

        return uploaded;
    }

    private static long roundToward(final long value, final long alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    private static final class TransientGpuBuffer extends MetalGpuBuffer {
        private final MetalTransientMemory owner;
        private final long bufferSubmitIndex;
        private boolean closed;

        TransientGpuBuffer(
                final MetalDevice device,
                final java.lang.foreign.MemorySegment handle,
                final int usage,
                final long size,
                final MetalTransientMemory owner,
                final long submitIndex
        ) {
            super(device, usage, size, handle);
            this.owner = owner;
            this.bufferSubmitIndex = submitIndex;
        }

        @Override
        public boolean isClosed() {
            if (closed) {
                return true;
            }
            closed = bufferSubmitIndex < owner.submitIndex;
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }

    }
}
