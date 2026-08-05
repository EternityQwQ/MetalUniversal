package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLBlendFactor {
    Zero(0L),
    One(1L),
    SourceColor(2L),
    OneMinusSourceColor(3L),
    SourceAlpha(4L),
    OneMinusSourceAlpha(5L),
    DestinationColor(6L),
    OneMinusDestinationColor(7L),
    DestinationAlpha(8L),
    OneMinusDestinationAlpha(9L),
    SourceAlphaSaturated(10L),
    BlendColor(11L),
    OneMinusBlendColor(12L),
    BlendAlpha(13L),
    OneMinusBlendAlpha(14L),
    Source1Color(15L),
    OneMinusSource1Color(16L),
    Source1Alpha(17L),
    OneMinusSource1Alpha(18L),
    Unspecialized(19);

    public final long value;

    MTLBlendFactor(final long value) {
        this.value = value;
    }

    public static MTLBlendFactor from(final com.mojang.blaze3d.platform.SourceFactor factor) {
        return switch (factor) {
            case ZERO -> Zero;
            case ONE -> One;
            case SRC_COLOR -> SourceColor;
            case ONE_MINUS_SRC_COLOR -> OneMinusSourceColor;
            case SRC_ALPHA -> SourceAlpha;
            case ONE_MINUS_SRC_ALPHA -> OneMinusSourceAlpha;
            case DST_COLOR -> DestinationColor;
            case ONE_MINUS_DST_COLOR -> OneMinusDestinationColor;
            case DST_ALPHA -> DestinationAlpha;
            case ONE_MINUS_DST_ALPHA -> OneMinusDestinationAlpha;
            case SRC_ALPHA_SATURATE -> SourceAlphaSaturated;
            case CONSTANT_COLOR -> BlendColor;
            case ONE_MINUS_CONSTANT_COLOR -> OneMinusBlendColor;
            case CONSTANT_ALPHA -> BlendAlpha;
            case ONE_MINUS_CONSTANT_ALPHA -> OneMinusBlendAlpha;
        };
    }

    public static MTLBlendFactor from(final com.mojang.blaze3d.platform.DestFactor factor) {
        return switch (factor) {
            case ZERO -> Zero;
            case ONE -> One;
            case SRC_COLOR -> SourceColor;
            case ONE_MINUS_SRC_COLOR -> OneMinusSourceColor;
            case SRC_ALPHA -> SourceAlpha;
            case ONE_MINUS_SRC_ALPHA -> OneMinusSourceAlpha;
            case DST_COLOR -> DestinationColor;
            case ONE_MINUS_DST_COLOR -> OneMinusDestinationColor;
            case DST_ALPHA -> DestinationAlpha;
            case ONE_MINUS_DST_ALPHA -> OneMinusDestinationAlpha;
            case CONSTANT_COLOR -> BlendColor;
            case ONE_MINUS_CONSTANT_COLOR -> OneMinusBlendColor;
            case CONSTANT_ALPHA -> BlendAlpha;
            case ONE_MINUS_CONSTANT_ALPHA -> OneMinusBlendAlpha;
        };
    }
}
