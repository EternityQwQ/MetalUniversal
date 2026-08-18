package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalBackend;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 退出崩溃修复（hs_err 铁证）：MC 退出调 GLFW.glfwTerminate → Amethyst 的
 * pojavTerminate → MobileGlues gl_terminate 在"从未 make current"环境下
 * （Metal 后端 redirect 了 MC 的 GL 初始化，GL 上下文从未建立）经
 * libGLESv2 GetCurrentValidContextTLSIndex 读取空 TLS → SIGSEGV。
 * Metal 主机上跳过 glfwTerminate（GL 资源由进程退出回收，Amethyst 每次
 * 启动 MC 前都会重新初始化 EGL）。
 */
@Mixin(GLFW.class)
public class GLFWTerminateMixin {
    @Inject(method = "glfwTerminate", remap = false, at = @At("HEAD"), cancellable = true)
    private static void metallum$skipTerminate(final CallbackInfo ci) {
        if (MetalBackend.isMetalHost()) {
            ci.cancel();
        }
    }
}
