package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLVertexFormat {
    Invalid(0L),
    UChar2(1L),
    UChar3(2L),
    UChar4(3L),
    Char2(4L),
    Char3(5L),
    Char4(6L),
    UChar2Normalized(7L),
    UChar3Normalized(8L),
    UChar4Normalized(9L),
    Char2Normalized(10L),
    Char3Normalized(11L),
    Char4Normalized(12L),
    UShort2(13L),
    UShort3(14L),
    UShort4(15L),
    Short2(16L),
    Short3(17L),
    Short4(18L),
    UShort2Normalized(19L),
    UShort3Normalized(20L),
    UShort4Normalized(21L),
    Short2Normalized(22L),
    Short3Normalized(23L),
    Short4Normalized(24L),
    Half2(25L),
    Half3(26L),
    Half4(27L),
    Float(28L),
    Float2(29L),
    Float3(30L),
    Float4(31L),
    Int(32L),
    Int2(33L),
    Int3(34L),
    Int4(35L),
    UInt(36L),
    UInt2(37L),
    UInt3(38L),
    UInt4(39L),
    Int1010102Normalized(40L),
    UInt1010102Normalized(41L),
    UChar4Normalized_bgra(42L),
    UChar(45L),
    Char(46L),
    UCharNormalized(47L),
    CharNormalized(48L),
    UShort(49L),
    Short(50L),
    UShortNormalized(51L),
    ShortNormalized(52L),
    Half(53L),
    FloatRG11B10(54L),
    FloatRGB9E5(55L);

    public final long value;

    MTLVertexFormat(final long value) {
        this.value = value;
    }

    public static MTLVertexFormat from(final com.mojang.blaze3d.vertex.VertexFormatElement.Type type, final int count) {
        // 1.21.11 无 GpuFormat：顶点格式由 VertexFormatElement.Type + 分量数推导
        return switch (type) {
            case FLOAT -> switch (count) {
                case 1 -> Float;
                case 2 -> Float2;
                case 3 -> Float3;
                case 4 -> Float4;
                default -> Invalid;
            };
            case UBYTE -> switch (count) {
                case 1 -> UCharNormalized;
                case 2 -> UChar2Normalized;
                case 3 -> UChar3Normalized;
                case 4 -> UChar4Normalized;
                default -> Invalid;
            };
            case BYTE -> switch (count) {
                case 1 -> CharNormalized;
                case 2 -> Char2Normalized;
                case 3 -> Char3Normalized;
                case 4 -> Char4Normalized;
                default -> Invalid;
            };
            case USHORT -> switch (count) {
                case 1 -> UShortNormalized;
                case 2 -> UShort2Normalized;
                case 3 -> UShort3Normalized;
                case 4 -> UShort4Normalized;
                default -> Invalid;
            };
            case SHORT -> switch (count) {
                case 1 -> ShortNormalized;
                case 2 -> Short2Normalized;
                case 3 -> Short3Normalized;
                case 4 -> Short4Normalized;
                default -> Invalid;
            };
            case UINT -> switch (count) {
                case 1 -> UInt;
                case 2 -> UInt2;
                case 3 -> UInt3;
                case 4 -> UInt4;
                default -> Invalid;
            };
            case INT -> switch (count) {
                case 1 -> Int;
                case 2 -> Int2;
                case 3 -> Int3;
                case 4 -> Int4;
                default -> Invalid;
            };
        };
    }
}
