package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalActive;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;

/**
 * Layer 2：Iris {@link IrisRenderSystem} 构造期 GL 调用守卫。
 *
 * <p>覆盖 {@code ExtendedShader} 构造期间会经 Iris {@link IrisRenderSystem}
 * 触发的两类 GL 入口（渲染期调用如 {@code uniformMatrix4fv}/{@code bindTextureToUnit}
 * 等不在本层，由后续 Task 4 的 adapter 负责）：
 * <ul>
 *   <li>{@code detachShader(int program, int shader)} —— 由 {@code PartialShader.getFinally}
 *       → {@code detachIfValid} 在 {@code ShaderCreator.create} 求值
 *       {@code new ExtendedShader(id.getFinally(), ...)} 时调用，最终走
 *       {@code GL32C.glDetachShader}。哨兵 {@code PartialShader(0,0,0,-1,-1,-1)}
 *       使 {@code vertexS==0}/{@code fragS==0}（{@code >=0} 不跳过），故
 *       {@code detachShader(0,0)} 会被调，无 GL 上下文即崩。</li>
 *   <li>{@code getActiveUniform(int, int, int, IntBuffer, IntBuffer)} —— 由
 *       {@code ProgramUniforms.buildUniforms} 遍历 active uniforms 时调用，
 *       最终走 {@code GL32C.glGetActiveUniform}。{@code program==0} 时崩。</li>
 * </ul>
 *
 * <p><b>守卫。</b>当 {@link MetalActive#isMetalActive()} 且对应句柄 {@code <= 0}
 * 时短路：{@code detachShader} 直接 {@code cancel}（no-op），{@code getActiveUniform}
 * 返回空串 {@code ""}（与 Iris 对"无更多信息"的处理一致，见
 * {@code ProgramUniforms.buildUniforms} 的 {@code name.isEmpty()} 分支）。
 *
 * <p><b>Buffer blending 已连接。</b>{@link #metallum$bufferBlendingViaPso}
 * 让 {@code supportsBufferBlending()} 在 Metal 上返回 {@code true}：per-attachment
 * blend state 由 PSO 承载（{@code IrisMetalCompiledPrograms.colorTargets}
 * 将 {@code blend.*} override 按 DRAWBUFFERS slot 映射进每个 color attachment，
 * 最终落到 {@code MetalCompiledRenderPipeline.createPipeline}），不经过 GL 的
 * {@code glEnablei}/{@code glBlendFuncSeparateiARB}。故
 * {@code disableBufferBlend}/{@code enableBufferBlend}/{@code blendFuncSeparatei}
 * 在 Metal 上 no-op。其余四个能力位（SSBO / image load-store / compute /
 * tessellation）仍未连接，保持 fail-closed。
 *
 * <p><b>Metal 激活条件。</b>{@link MetalActive#isMetalActive()}。
 * <b>非 Metal 路径完全 no-op</b>：Metal 未激活时立即 return，Iris 原始 GL
 * 调用不受影响。{@code program/shader <= 0} 在合法 GL 路径下本就不会被合法
 * 查询（0 是哨兵），故守卫对非 Metal 零副作用。
 */
@Environment(EnvType.CLIENT)
@Mixin(IrisRenderSystem.class)
public class IrisRenderSystemMixin {
    @Inject(method = "initRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$skipGlCapabilityProbe(final CallbackInfo ci) {
        if (MetalActive.isMetalActive()) {
            ci.cancel();
        }
    }

    @Inject(
            method = {
                    "supportsSSBO",
                    "supportsImageLoadStore",
                    "supportsCompute",
                    "supportsTesselation"
            },
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void metallum$unconnectedIrisFeaturesFailClosed(
            final CallbackInfoReturnable<Boolean> cir
    ) {
        if (MetalActive.isMetalActive()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "supportsBufferBlending", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$bufferBlendingViaPso(final CallbackInfoReturnable<Boolean> cir) {
        if (MetalActive.isMetalActive()) {
            cir.setReturnValue(true); // Metal: per-attachment blend 由 PSO 承载
        }
    }

    @Inject(method = {"disableBufferBlend", "enableBufferBlend"}, at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$skipGlBufferBlendOnMetal(final int buffer, final CallbackInfo ci) {
        if (MetalActive.isMetalActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "blendFuncSeparatei", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$skipGlBlendFuncSeparateiOnMetal(
            final int buffer,
            final int srcRGB,
            final int dstRGB,
            final int srcAlpha,
            final int dstAlpha,
            final CallbackInfo ci
    ) {
        if (MetalActive.isMetalActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "getStringi", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$skipGlExtensionQuery(
            final int name,
            final int index,
            final CallbackInfoReturnable<String> cir
    ) {
        if (MetalActive.isMetalActive()) {
            cir.setReturnValue("");
        }
    }

    @Inject(method = "detachShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$skipDetachShaderOnMetal(
            int program,
            int shader,
            CallbackInfo cir
    ) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        if (program <= 0 || shader <= 0) {
            cir.cancel();
        }
    }

    @Inject(method = "getActiveUniform", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metallum$emptyActiveUniformOnMetal(
            int program,
            int index,
            int size,
            IntBuffer type,
            IntBuffer name,
            CallbackInfoReturnable<String> cir
    ) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        if (program <= 0) {
            cir.setReturnValue("");
        }
    }
}
