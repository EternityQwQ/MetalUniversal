package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalBackend;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 1.21.11 无 GpuBackend 体系（26.2 的多后端抽象）：GpuDevice 由
 * RenderSystem.initRenderer 内直接 new GlDevice(...) 创建并存入静态 DEVICE 字段。
 * 本 mixin 将 GlDevice 构造器调用重定向到 MetalBackend.createDevice，
 * 使 DEVICE 持有 MetalDevice；原方法的其余初始化（dynamicUniforms 等）原样保留。
 */
@Mixin(RenderSystem.class)
public class RenderSystemDeviceMixin {
    @Redirect(
            method = "initRenderer",
            // blaze3d 类无 intermediary 映射（loom 1.14 的 remap 链不含 blaze3d）：
            // 与 26.2 的 Sodium mixin 同用 remap=false + mojmap 名（dev/生产运行时均为 mojmap）
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/opengl/GlDevice;<init>(JIZLcom/mojang/blaze3d/shaders/ShaderSource;Z)V"
            )
    )
    private static GpuDevice metallum$createMetalDevice(
            final GlDevice instance,
            final long window,
            final int rendererType,
            final boolean debug,
            final ShaderSource shaderSource,
            final boolean isIntegrated
    ) {
        return MetalBackend.createDevice(window, shaderSource);
    }
}
