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
        return fromImpl(type, count, true);
    }

    /**
     * 整型语义 attribute（shader 声明 ivec/uvec，如 1.21.11 的 rendertype_text.vsh 中
     * `in ivec2 UV2`）：descriptor 必须用非 normalized 格式，否则 Metal 报
     * "Cannot convert attribute from MTLAttributeFormat*Normalized to int2 or uint2"
     * （normalized 格式只允许转 float；GL 后端无此限制，Metal 严格匹配）。
     */
    public static MTLVertexFormat fromInteger(final com.mojang.blaze3d.vertex.VertexFormatElement.Type type, final int count) {
        return fromImpl(type, count, false);
    }

    private static MTLVertexFormat fromImpl(final com.mojang.blaze3d.vertex.VertexFormatElement.Type type, final int count, final boolean normalized) {
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
                case 1 -> normalized ? UCharNormalized : UChar;
                case 2 -> normalized ? UChar2Normalized : UChar2;
                case 3 -> normalized ? UChar3Normalized : UChar3;
                case 4 -> normalized ? UChar4Normalized : UChar4;
                default -> Invalid;
            };
            case BYTE -> switch (count) {
                case 1 -> normalized ? CharNormalized : Char;
                case 2 -> normalized ? Char2Normalized : Char2;
                case 3 -> normalized ? Char3Normalized : Char3;
                case 4 -> normalized ? Char4Normalized : Char4;
                default -> Invalid;
            };
            case USHORT -> switch (count) {
                case 1 -> normalized ? UShortNormalized : UShort;
                case 2 -> normalized ? UShort2Normalized : UShort2;
                case 3 -> normalized ? UShort3Normalized : UShort3;
                case 4 -> normalized ? UShort4Normalized : UShort4;
                default -> Invalid;
            };
            case SHORT -> switch (count) {
                case 1 -> normalized ? ShortNormalized : Short;
                case 2 -> normalized ? Short2Normalized : Short2;
                case 3 -> normalized ? Short3Normalized : Short3;
                case 4 -> normalized ? Short4Normalized : Short4;
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
