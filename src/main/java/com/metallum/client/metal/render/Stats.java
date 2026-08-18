package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuBuffer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.metallum.Metallum;

public class Stats {
    private static final AtomicLong CREATED_BUFFERS = new AtomicLong();

    private static final ConcurrentHashMap<Integer, UsageStats> USAGE_STATS = new ConcurrentHashMap<>();

    private static final class UsageStats {
        final AtomicLong count = new AtomicLong();
        final AtomicLong requestedBytes = new AtomicLong();
        final AtomicLong allocatedBytes = new AtomicLong();
    }

    public static void recordUsage(int usage, long requestedSize, long allocatedSize) {
        UsageStats stats = USAGE_STATS.computeIfAbsent(usage, k -> new UsageStats());

        stats.count.incrementAndGet();
        stats.requestedBytes.addAndGet(requestedSize);
        stats.allocatedBytes.addAndGet(allocatedSize);

        CREATED_BUFFERS.incrementAndGet();
    }

    /** Render hot-path metrics. Only active when -Dmetallum.renderStats=true is set, so the
     *  default hot path carries no branch/allocation cost at these call sites. */
    private static final boolean RENDER_STATS = Boolean.getBoolean("metallum.renderStats");
    private static final AtomicLong INDEXED_DRAWS = new AtomicLong();
    private static final AtomicLong BATCHED_MULTI_DRAWS = new AtomicLong();
    private static final AtomicLong TEXEL_VIEW_ALLOCATIONS = new AtomicLong();
    private static final AtomicLong FRAME = new AtomicLong();

    public static void recordIndexedDraw() {
        if (RENDER_STATS) INDEXED_DRAWS.incrementAndGet();
    }

    public static void recordBatchedMultiDraw() {
        if (RENDER_STATS) BATCHED_MULTI_DRAWS.incrementAndGet();
    }

    public static void recordTexelViewAllocation() {
        if (RENDER_STATS) TEXEL_VIEW_ALLOCATIONS.incrementAndGet();
    }

    /** Called once per frame; logs a rolling summary when render stats are enabled. */
    public static void tickRenderStats() {
        if (!RENDER_STATS) return;
        long frame = FRAME.incrementAndGet();
        if (frame % 120L == 0L) {
            Metallum.LOGGER.info(
                    "[metallum:renderStats] frame={} indexedDraws={} batchedMultiDraws={} texelViewAlloc={}",
                    frame, INDEXED_DRAWS.get(), BATCHED_MULTI_DRAWS.get(), TEXEL_VIEW_ALLOCATIONS.get());
        }
    }
}
