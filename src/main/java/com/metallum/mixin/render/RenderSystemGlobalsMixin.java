package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalBackend;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MC 1.21.11 的 Globals UBO（CameraBlockPos/CameraOffset/ScreenSize/...）不走
 * RenderPass.setUniform（字节码实证：全 jar 无 "Globals" 字符串绑定），而是经
 * RenderSystem.setGlobalSettingsUniform(GpuBuffer) 独立传入（GlobalSettingsUniform.update
 * 每帧重写内容）。
 *
 * <p>terrain.vsh 的顶点变换 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset
 * 依赖 Globals——Metal 后端若未绑定 buffer(0)，CameraBlockPos/CameraOffset 读 0 →
 * pos 变绝对世界坐标 → 透视 far 平面外全裁剪 → 方块不可见（readbackDepth 恒 1.0）。
 * 本 mixin 捕获该 buffer 供 MetalRenderPass 按管线 Globals binding 每帧绑定。
 */
@Mixin(RenderSystem.class)
public class RenderSystemGlobalsMixin {
    @Inject(method = "setGlobalSettingsUniform", remap = false, at = @At("HEAD"))
    private static void metallum$captureGlobalSettings(final GpuBuffer buffer, final CallbackInfo ci) {
        MetalBackend.setGlobalSettingsBuffer(buffer);
    }
}
