package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalWorldBridge;
import com.metallum.client.metal.render.MetalProbeReport;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.pathways.HorizonRenderer;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * M4.1 horizon arming: {@code MetalWorldRenderingPipeline.onBeginClear} now
 * calls Iris's {@link HorizonRenderer#renderHorizon} (upstream parity) to fill
 * the lower half of the sky. Like 26.2's {@code SkyRenderer}, HorizonRenderer
 * builds its pass directly ({@code CommandEncoder.createRenderPass(...)} then
 * setPipeline/draw), so it never reaches {@code PreparedRenderType.drawFromBuffer}
 * and the world override's pending key would never be armed. This injection
 * fires immediately before that method's single pass creation and arms
 * {@link ShaderKey#SKY_BASIC}; the consume in
 * {@code MetalCommandEncoder.createRenderPass} then rewrites exactly this
 * pass. With the world pass disabled, {@code armForDraw} returns {@code false}
 * and the cone stays on the vanilla SKY pipeline.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = HorizonRenderer.class, remap = false)
public abstract class HorizonRendererMixin {
    private static final String CREATE_RENDER_PASS =
            "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass("
                    + "Ljava/util/function/Supplier;"
                    + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                    + "Ljava/util/Optional;"
                    + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                    + "Ljava/util/OptionalDouble;)"
                    + "Lcom/mojang/blaze3d/systems/RenderPass;";

    /** One-shot probe so the horizon arming is distinguishable without flooding the report. */
    @Unique
    private static boolean metallum$horizonArmReported;

    @Inject(
            method = "renderHorizon(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;Lorg/joml/Vector4f;)V",
            at = @At(value = "INVOKE", target = CREATE_RENDER_PASS, shift = At.Shift.BEFORE)
    )
    private void metallum$armHorizon(
            final Matrix4fc modelView,
            final Matrix4fc projection,
            final Vector4f fogColor,
            final CallbackInfo ci
    ) {
        if (IrisMetalWorldBridge.armForDraw(ShaderKey.SKY_BASIC) && !metallum$horizonArmReported) {
            metallum$horizonArmReported = true;
            MetalProbeReport.record("world override arm horizon");
        }
    }
}
