package com.metallum.client.metal.render.mtl;

public enum MTLIndexType {
    UInt16(0L),
    UInt32(1L);

    public final long value;

    MTLIndexType(long value) {
        this.value = value;
    }

    public static MTLIndexType fromIndexByteSize(int bytes) {
        if (bytes == 2) {
            return UInt16;
        }
        if (bytes == 4) {
            return UInt32;
        }
        throw new IllegalArgumentException("Unsupported index element size: " + bytes);
    }
}
