package com.metallum.client.metal.render.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public enum MTLColorWriteMask {
    None(0L),
    Alpha(1L),
    Blue(2L),
    Green(4L),
    Red(8L),
    All(15L);

    public final long value;

    MTLColorWriteMask(final long value) {
        this.value = value;
    }

    // 1.21.11 无 ColorTargetState.WriteMask：RenderPipeline 仅暴露 writeColor/writeAlpha
    // 布尔，Metal 着色器输出按分量写入（RGBA 全部或按布尔拆）
    public static long from(final boolean writeColor, final boolean writeAlpha) {
        long mask = 0L;
        if (writeColor) mask |= Red.value | Green.value | Blue.value;
        if (writeAlpha) mask |= Alpha.value;
        return mask;
    }
}
