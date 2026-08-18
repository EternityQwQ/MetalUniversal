package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalBackend;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.11 的 RenderSystem.flipFrame 在 present 之外仍直接调 GLFW.glfwSwapBuffers
 * （GL 交换）。Metal 后端下画面已由 CommandEncoder.presentTexture 提交，GL 交换会
 * 命中 Amethyst 的 mobileglues gl_swap_buffers（SIGSEGV），故全局跳过。
 *
 * <p>选择 mixin GLFW 类而非 @Redirect flipFrame：mixin 0.8.7 的 @Redirect handler
 * 参数必须覆盖目标方法全部参数，而 flipFrame 的参数类型（fyk/fwf）无 mojmap 映射
 * 无法写出签名；GLFW.glfwSwapBuffers(long) 签名已知且 GLFW 类由 KnotClassLoader
 * 在窗口创建时才首次加载（mixin 系统 preLaunch 即就绪，变换来得及）。
 */
@Mixin(GLFW.class)
public class GLFWSwapBuffersMixin {
    @Inject(method = "glfwSwapBuffers", remap = false, at = @At("HEAD"), cancellable = true)
    private static void metallum$skipSwapBuffers(final long window, final CallbackInfo ci) {
        if (MetalBackend.isMetalHost()) {
            ci.cancel();
        }
    }
}
