package com.metallum.client.metal.render.mtl;

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
    Unspecialized(19L);

    public final long value;

    MTLBlendFactor(long value) {
        this.value = value;
    }
}
