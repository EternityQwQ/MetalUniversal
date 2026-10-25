package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalDebugSwitches;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Forces {@code OptionsRenderState.cloudStatus} to {@link CloudStatus#OFF}
 * whenever the cloud probe switches are active. The render state is populated
 * by {@code GameRenderer.extractOptions()} from the user option, so the value
 * is rewritten at the single assignment site; the option itself is left
 * untouched. Inactive by default.
 */
@Mixin(GameRenderer.class)
abstract class ForceCloudStatusOffMixin {
    @Redirect(
            method = "extractOptions",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Options;getCloudStatus()Lnet/minecraft/client/CloudStatus;"
            )
    )
    private CloudStatus metallum$forceCloudsOff(final Options options) {
        if (MetalDebugSwitches.NO_CLOUDS_HARD
                || MetalDebugSwitches.NO_VANILLA_CLOUDS
                || MetalDebugSwitches.NO_VANILLA_SKY) {
            return CloudStatus.OFF;
        }
        return options.getCloudStatus();
    }
}
