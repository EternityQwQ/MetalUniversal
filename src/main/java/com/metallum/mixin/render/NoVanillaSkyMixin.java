package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalDebugSwitches;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code metallum.iris.debug.noVanillaSky} probe: cancels every public render
 * method of the engine sky renderer so the on-device artifact can be bisected
 * against the port's own sky handling. Inactive by default; the debug switch
 * is read at injection time, so a vanilla session is completely unaffected.
 */
@Mixin(SkyRenderer.class)
abstract class NoVanillaSkyMixin {
    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void metallum$skipSkyDisc(final int color, final CallbackInfo ci) {
        cancelWhenActive(ci);
    }

    @Inject(method = "renderDarkDisc", at = @At("HEAD"), cancellable = true)
    private void metallum$skipDarkDisc(final CallbackInfo ci) {
        cancelWhenActive(ci);
    }

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void metallum$skipSunMoonAndStars(
            final PoseStack poseStack,
            final float f,
            final float g,
            final float h,
            final MoonPhase moonPhase,
            final float i,
            final float j,
            final CallbackInfo ci
    ) {
        cancelWhenActive(ci);
    }

    @Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
    private void metallum$skipSunriseAndSunset(
            final PoseStack poseStack,
            final float f,
            final int i,
            final CallbackInfo ci
    ) {
        cancelWhenActive(ci);
    }

    @Inject(method = "renderEndSky", at = @At("HEAD"), cancellable = true)
    private void metallum$skipEndSky(final CallbackInfo ci) {
        cancelWhenActive(ci);
    }

    @Inject(method = "renderEndFlash", at = @At("HEAD"), cancellable = true)
    private void metallum$skipEndFlash(
            final PoseStack poseStack,
            final float f,
            final float g,
            final float h,
            final CallbackInfo ci
    ) {
        cancelWhenActive(ci);
    }

    private static void cancelWhenActive(final CallbackInfo ci) {
        if (MetalDebugSwitches.NO_VANILLA_SKY) {
            ci.cancel();
        }
    }
}
