package com.metallum.client.metal.render.mtl;

/**
 * Metal {@code MTLVertexFormat} values. Ported from the original enum; the
 * {@code value} field is the raw Metal constant. The {@code from(GlMetalFormat)}
 * mapping handles the formats the 1.12.2 GL translation layer emits.
 */
public enum MTLVertexFormat {
    Invalid(0L),
    UChar2(1L), UChar3(2L), UChar4(3L),
    Char2(4L), Char3(5L), Char4(6L),
    UChar2Normalized(7L), UChar3Normalized(8L), UChar4Normalized(9L),
    Char2Normalized(10L), Char3Normalized(11L), Char4Normalized(12L),
    UShort2(13L), UShort3(14L), UShort4(15L),
    Short2(16L), Short3(17L), Short4(18L),
    UShort2Normalized(19L), UShort3Normalized(20L), UShort4Normalized(21L),
    Short2Normalized(22L), Short3Normalized(23L), Short4Normalized(24L),
    Half2(25L), Half3(26L), Half4(27L),
    Float(28L), Float2(29L), Float3(30L), Float4(31L),
    Int(32L), Int2(33L), Int3(34L), Int4(35L),
    UInt(36L), UInt2(37L), UInt3(38L), UInt4(39L),
    Int1010102Normalized(40L), UInt1010102Normalized(41L),
    UChar4Normalized_bgra(42L),
    UChar(45L), Char(46L), UCharNormalized(47L), CharNormalized(48L),
    UShort(49L), Short(50L), UShortNormalized(51L), ShortNormalized(52L),
    Half(53L), FloatRG11B10(54L), FloatRGB9E5(55L);

    public final long value;

    MTLVertexFormat(long value) {
        this.value = value;
    }

    public static MTLVertexFormat from(GlMetalFormat format) {
        if (format == null) {
            return Invalid;
        }
        switch (format) {
            case R32_FLOAT: return Float;
            case RG32_FLOAT: return Float2;
            case RGBA32_FLOAT: return Float4;
            case RGBA8_UNORM: return UChar4Normalized;
            case RGBA8_UINT: return UChar4;
            case RG16_UINT: return UShort2;
            case RG16_UNORM: return UShort2Normalized;
            case RG16_SINT: return Short2;
            case RG16_SNORM: return Short2Normalized;
            case RGBA16_UINT: return UShort4;
            case RGBA16_SINT: return Short4;
            case RGBA16_UNORM: return UShort4Normalized;
            case RGBA16_SNORM: return Short4Normalized;
            case R32_UINT: return UInt;
            case RG32_UINT: return UInt2;
            case RGBA32_UINT: return UInt4;
            case R32_SINT: return Int;
            case RG32_SINT: return Int2;
            case RGBA32_SINT: return Int4;
            case R16_FLOAT: return Half;
            case R16_UINT: return UShort;
            case R16_SINT: return Short;
            case R16_UNORM: return UShortNormalized;
            case R16_SNORM: return ShortNormalized;
            case R8_UINT: return UChar;
            case R8_SINT: return Char;
            case R8_UNORM: return UCharNormalized;
            case R8_SNORM: return CharNormalized;
            case RG16_FLOAT: return Half2;
            case RGBA16_FLOAT: return Half4;
            case RGBA8_SNORM: return Char4Normalized;
            case RGBA8_SINT: return Char4;
            default: return Invalid;
        }
    }
}
