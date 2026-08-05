package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.21.11 无 26.2 的 com.mojang.blaze3d.util.TransientBlockAllocator，
 * 自实现等价物：固定大小块 + 游标分配，rotate() 轮转释放（GPU 块延迟 3 帧）。
 */
final class TransientBlockAllocator<T> {
    interface Allocator<T> {
        T create(long size);

        void free(T block);

        static <T> Allocator<T> create(final java.util.function.Function<Long, T> create, final java.util.function.Consumer<T> free) {
            return new Allocator<>() {
                @Override
                public T create(final long size) {
                    return create.apply(size);
                }

                @Override
                public void free(final T block) {
                    free.accept(block);
                }
            };
        }
    }

    record Allocation<T>(T block, long offset, long size) {
    }

    private final long blockSize;
    private final Allocator<T> allocator;
    private T currentBlock;
    private long currentOffset;
    private List<T> previousBlocks = new ArrayList<>();

    TransientBlockAllocator(final long blockSize, final long maxAlignment, final Allocator<T> allocator) {
        this.blockSize = blockSize;
        this.allocator = allocator;
    }

    Allocation<T> allocate(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
        long effectiveAlignment = Math.max(alignment, Math.max(elementSize, 1L));
        long alignedSize = roundUp(Math.max(size, minimumAllocation), effectiveAlignment);
        if (alignedSize > blockSize) {
            // 超大分配（26.2 MC 原版同语义）：分配独立专用块，offset=0，
            // 加入 previousBlocks 由下一次 rotate() 统一释放
            T big = allocator.create(alignedSize);
            previousBlocks.add(big);
            return new Allocation<>(big, 0L, size);
        }
        if (currentBlock == null || roundUp(currentOffset, effectiveAlignment) + alignedSize > blockSize) {
            newBlock();
        }
        long offset = roundUp(currentOffset, effectiveAlignment);
        currentOffset = offset + alignedSize;
        return new Allocation<>(currentBlock, offset, size);
    }

    boolean canAllocateInCurrentBlock(final long size, final long alignment) {
        if (currentBlock == null) {
            return false;
        }
        long alignedSize = roundUp(size, Math.max(alignment, 1L));
        return roundUp(currentOffset, alignment) + alignedSize <= blockSize;
    }

    /**
     * 轮转：上一轮的所有块交由返回的 Runnable 释放（CPU 块立即 free，
     * GPU 块由调用方入销毁队列延迟释放）；本轮块成为下一轮的"上一轮"。
     */
    Runnable rotate() {
        List<T> toFree = previousBlocks;
        previousBlocks = new ArrayList<>();
        if (currentBlock != null) {
            previousBlocks.add(currentBlock);
            currentBlock = null;
            currentOffset = 0L;
        }
        return () -> {
            for (T block : toFree) {
                allocator.free(block);
            }
        };
    }

    void close() {
        if (currentBlock != null) {
            allocator.free(currentBlock);
            currentBlock = null;
        }
        for (T block : previousBlocks) {
            allocator.free(block);
        }
        previousBlocks = new ArrayList<>();
    }

    private void newBlock() {
        currentBlock = allocator.create(blockSize);
        currentOffset = 0L;
    }

    private static long roundUp(final long value, final long alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }
}
