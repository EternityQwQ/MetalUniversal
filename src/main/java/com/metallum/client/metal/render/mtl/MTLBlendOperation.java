package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLBlendOperation {
    Add(0L),
    Subtract(1L),
    ReverseSubtract(2L),
    Min(3L),
    Max(4L);

    public final long value;

    MTLBlendOperation(final long value) {
        this.value = value;
    }

    // 1.21.11 无平台级 BlendOp：BlendFunction 为 record（SourceFactor/DestFactor），
    // 混合操作固定为 Add（Metal 默认）
    public static MTLBlendOperation from() {
        return Add;
    }
}
