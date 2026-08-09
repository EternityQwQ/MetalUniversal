package com.metallum.client.metal.render;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Allocation statistics. Ported from {@code Stats}. */
public final class Stats {
    private static final AtomicLong CREATED_BUFFERS = new AtomicLong();
    private static final ConcurrentHashMap<Integer, UsageStats> USAGE_STATS = new ConcurrentHashMap<Integer, UsageStats>();

    private Stats() {
    }

    private static final class UsageStats {
        final AtomicLong count = new AtomicLong();
        final AtomicLong requestedBytes = new AtomicLong();
        final AtomicLong allocatedBytes = new AtomicLong();
    }

    public static void recordUsage(int usage, long requestedSize, long allocatedSize) {
        UsageStats s = USAGE_STATS.computeIfAbsent(Integer.valueOf(usage), new java.util.function.Function<Integer, UsageStats>() {
            @Override
            public UsageStats apply(Integer k) {
                return new UsageStats();
            }
        });
        s.count.incrementAndGet();
        s.requestedBytes.addAndGet(requestedSize);
        s.allocatedBytes.addAndGet(allocatedSize);
        CREATED_BUFFERS.incrementAndGet();
    }
}
