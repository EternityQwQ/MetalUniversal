package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalWorldBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * M4 sky arming: 26.2's {@link SkyRenderer} builds every sky pass directly
 * ({@code CommandEncoder.createRenderPass(...)} then setPipeline/draw), so
 * these draws never reach {@code PreparedRenderType.drawFromBuffer} and the
 * world override's pending key was never armed. Each injection fires
 * immediately before that method's single pass creation (the encoder is
 * created a few instructions earlier) and arms the key matching the sky
 * element; the consume in {@code MetalCommandEncoder.createRenderPass} then
 * rewrites exactly this pass. Vanilla sky rendering is redirected, not
 * cancelled, matching upstream Iris.
 */
@Environment(EnvType.CLIENT)
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    private static final String CREATE_RENDER_PASS =
            "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass("
                    + "Ljava/util/function/Supplier;"
                    + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                    + "Ljava/util/Optional;"
                    + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                    + "Ljava/util/OptionalDouble;)"
                    + "Lcom/mojang/blaze3d/systems/RenderPass;";

    @Inject(method = "renderSkyDisc(I)V", at = @At(value = "INVOKE", target = CREATE_RENDER_PASS, shift = At.Shift.BEFORE))
    private void metallum$armSkyDisc(final int color, final CallbackInfo ci) {
        IrisMetalWorldBridge.armForDraw(ShaderKey.SKY_BASIC);
    }

    @Inject(method = "renderDarkDisc()V", at = @At(value = "INVOKE", target = CREATE_RENDER_PASS, shift = At.Shift.BEFORE))
    private void metallum$armDarkDisc(final CallbackInfo ci) {
        IrisMetalWorldBridge.armForDraw(ShaderKey.SKY_BASIC);
    }

    @Inject(
            method = "renderStars(FLcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At(value = "INVOKE", target = CREATE_RENDER_PASS, shift = At.Shift.BEFORE)
    )
    private void metallum$armStars(final float partialTick, final PoseStack poseStack, final CallbackInfo ci) {
        IrisMetalWorldBridge.armForDraw(ShaderKey.SKY_BASIC);
    }

    @Inject(
            method = "renderSun(FLcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At(value = "INVOKE", target = CREATE_RENDER_PASS, shift = At.Shift.BEFORE)
    )
    private void metallum$armSun(final float partialTick, final PoseStack poseStack, final CallbackInfo ci) {
        IrisMetalWorldBridge.armForDraw(ShaderKey.SKY_TEXTURED);
    }

    @Inject(
            method = "renderMoon(Lnet/minecraft/world/level/MoonPhase;FLcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At(value = "INVOKE", target = CREATE_RENDER_PASS, shift = At.Shift.BEFORE)
    )
    private void metallum$armMoon(
            final MoonPhase moonPhase,
            final float partialTick,
            final PoseStack poseStack,
            final CallbackInfo ci
    ) {
        IrisMetalWorldBridge.armForDraw(ShaderKey.SKY_TEXTURED);
    }

    @Inject(
            method = "renderEndFlash(Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V",
            at = @At(value = "INVOKE", target = CREATE_RENDER_PASS, shift = At.Shift.BEFORE)
    )
    private void metallum$armEndFlash(
            final PoseStack poseStack,
            final float red,
            final float green,
            final float blue,
            final CallbackInfo ci
    ) {
        IrisMetalWorldBridge.armForDraw(ShaderKey.SKY_TEXTURED);
    }

    @Inject(method = "renderEndSky()V", at = @At(value = "INVOKE", target = CREATE_RENDER_PASS, shift = At.Shift.BEFORE))
    private void metallum$armEndSky(final CallbackInfo ci) {
        IrisMetalWorldBridge.armForDraw(ShaderKey.SKY_TEXTURED);
    }

    @Inject(
            method = "renderSunriseAndSunset(Lcom/mojang/blaze3d/vertex/PoseStack;FI)V",
            at = @At(value = "INVOKE", target = CREATE_RENDER_PASS, shift = At.Shift.BEFORE)
    )
    private void metallum$armSunriseAndSunset(
            final PoseStack poseStack,
            final float partialTick,
            final int color,
            final CallbackInfo ci
    ) {
        IrisMetalWorldBridge.armForDraw(ShaderKey.SKY_BASIC_COLOR);
    }
}
