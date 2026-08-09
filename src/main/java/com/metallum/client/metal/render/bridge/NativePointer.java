package com.metallum.client.metal.render.bridge;

/**
 * An opaque handle to a native Metal object (MTLDevice, MTLCommandQueue,
 * MTLCommandBuffer, encoder, texture, buffer, sampler, pipeline state, etc.).
 *
 * <p>In the original Java 25 implementation these were
 * {@code java.lang.foreign.MemorySegment} values passed through the Foreign
 * Function &amp; Memory API. Java 8 has no FFM API, so the 1.12.2 port represents
 * every native pointer as a 64-bit {@code long} and reaches the dylib through
 * JNI. Zero means null.</p>
 */
public final class NativePointer {
    public static final long NULL = 0L;

    private final long address;

    public NativePointer(long address) {
        this.address = address;
    }

    public long address() {
        return address;
    }

    public boolean isNull() {
        return address == NULL;
    }

    public static boolean isNull(long address) {
        return address == NULL;
    }

    public static NativePointer of(long address) {
        return new NativePointer(address);
    }

    @Override
    public String toString() {
        return "NativePointer{0x" + Long.toHexString(address) + '}';
    }
}
