package com.metallum.client.metal.render;

import com.mojang.blaze3d.systems.GpuQueryPool;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.util.OptionalLong;

@Environment(EnvType.CLIENT)
final class MetalGpuQueryPool implements GpuQueryPool {
    private final OptionalLong[] values;

    MetalGpuQueryPool(final int size) {
        this.values = new OptionalLong[size];

        for (int i = 0; i < size; i++) {
            this.values[i] = OptionalLong.empty();
        }
    }

    void setValue(final int index, final long value) {
        checkIndex("write", index, 1);
        this.values[index] = OptionalLong.of(value);
    }

    @Override
    public int size() {
        return this.values.length;
    }

    @Override
    public @NonNull OptionalLong getValue(final int index) {
        checkIndex("read", index, 1);
        return this.values[index];
    }

    @Override
    public OptionalLong @NonNull [] getValues(final int index, final int count) {
        checkIndex("read", index, count);
        OptionalLong[] result = new OptionalLong[count];
        System.arraycopy(this.values, index, result, 0, count);
        return result;
    }

    /**
     * Enforces the pool's {@link #size()} contract on a single query slot before
     * it is used to index the fixed-length backing array. Without this gate the
     * getters would surface an opaque {@link ArrayIndexOutOfBoundsException}
     * thrown by either {@link #getValue} or {@link System#arraycopy}, hiding the
     * actual cause (an invalid query index from the caller).
     */
    private void checkIndex(final String op, final int index, final int count) {
        if (index < 0 || count < 0 || index > this.values.length - count) {
            throw new IndexOutOfBoundsException(
                    "Query pool " + op + " out of range: index=" + index
                            + ", count=" + count + ", size=" + this.values.length);
        }
    }

    @Override
    public void close() {
        for (int i = 0; i < this.values.length; i++) {
            this.values[i] = OptionalLong.empty();
        }
    }
}
