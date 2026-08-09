package com.metallum.client.metal.render;

import com.metallum.Metallum;

import java.util.ArrayList;
import java.util.List;

/**
 * Rotating deferred-destruction queue for Metal resources. Ported from
 * {@code MetalDestructionQueue}. Resources cannot be released while a command
 * buffer referencing them is in flight, so destruction is deferred by a few
 * frames.
 */
public final class MetalDestructionQueue {
    private final List<Runnable>[] queues;
    private int current;

    @SuppressWarnings("unchecked")
    public MetalDestructionQueue(int queueCount) {
        this.queues = (List<Runnable>[]) new List<?>[queueCount];
        for (int i = 0; i < queueCount; i++) {
            this.queues[i] = new ArrayList<Runnable>();
        }
    }

    public void add(Runnable destroyAction) {
        if (destroyAction == null) {
            return;
        }
        this.queues[this.current].add(destroyAction);
    }

    public void rotate() {
        this.current = (this.current + 1) % this.queues.length;
        List<Runnable> toDestroy = this.queues[this.current];
        this.queues[this.current] = new ArrayList<Runnable>();
        for (Runnable r : toDestroy) {
            try {
                r.run();
            } catch (Exception e) {
                Metallum.LOGGER.error("[metallum] Destroy action threw; resource may have leaked", e);
            }
        }
    }

    public void close() {
        for (int i = 0; i < this.queues.length; i++) {
            this.rotate();
        }
    }
}
