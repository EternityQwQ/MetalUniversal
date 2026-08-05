package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalBackend;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.SamplerCache;
import net.minecraft.client.renderer.DynamicUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.11 无 GpuBackend 体系（26.2 的多后端抽象）：GpuDevice 由
 * RenderSystem.initRenderer 内直接 new GlDevice(...) 创建并存入静态 DEVICE 字段。
 * 本 mixin 在方法头部取消原实现（避免创建 GlDevice/初始化 GL），改用
 * MetalBackend.createDevice 创建 MetalDevice，并补全原方法被跳过的副作用
 * （apiDescription / dynamicUniforms / samplerCache 初始化，字节码实证）。
 *
 * <p>Mixin 不支持 @Redirect 构造器（InvalidInjectionException: Illegal @Redirect
 * of constructor），故采用 HEAD + cancellable 方案。
 *
 * <p>flipFrame 的 glfwSwapBuffers 拦截已移至 GLFWSwapBuffersMixin（mixin 0.8.7 的
 * @Redirect handler 参数必须覆盖目标方法全部参数，此处无法写出 fyk/fwf 类型）。
 */
@Mixin(RenderSystem.class)
public class RenderSystemDeviceMixin {
    @Shadow(remap = false)
    private static GpuDevice DEVICE;
    @Shadow(remap = false)
    private static String apiDescription;
    @Shadow(remap = false)
    private static DynamicUniforms dynamicUniforms;
    @Shadow(remap = false)
    private static SamplerCache samplerCache;

    @Inject(method = "initRenderer", remap = false, at = @At("HEAD"), cancellable = true)
    private static void metallum$createMetalDevice(
            final long window,
            final int rendererType,
            final boolean debug,
            final ShaderSource shaderSource,
            final boolean isIntegrated,
            final CallbackInfo ci
    ) {
        if (!MetalBackend.isMetalHost()) {
            return;
        }
        DEVICE = MetalBackend.createDevice(window, shaderSource);
        apiDescription = DEVICE.getImplementationInformation();
        dynamicUniforms = new DynamicUniforms();
        samplerCache.initialize();
        ci.cancel();
    }
}
