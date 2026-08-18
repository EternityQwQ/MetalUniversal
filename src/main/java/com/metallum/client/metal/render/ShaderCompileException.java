package com.metallum.client.metal.render;

/**
 * 1.21.11 无 blaze3d.vulkan.glsl.ShaderCompileException（26.2 的 MC 类），
 * 自建等价异常承载 shader 编译错误。
 */
public class ShaderCompileException extends Exception {
    public ShaderCompileException(final String message) {
        super(message);
    }

    public ShaderCompileException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
