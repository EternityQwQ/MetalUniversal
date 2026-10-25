package com.metallum.mixin.iris;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Metal-side Iris render-dispatch mixin.
 *
 * <p>M1: the {@code setPipeline} takeover that previously lived here moved to
 * {@code IrisMetalWorldBridge.installPipeline}, dispatched from
 * {@code IrisRenderPassMixin} at {@code setPipeline} HEAD. This RETURN hook is
 * retained as a registered no-op so the mixin entry point stays stable for
 * future milestones.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass")
public class MetalIrisPipelineMixin {
    @Inject(method = "setPipeline", at = @At("RETURN"))
    private void metallum$irisSetupState(final RenderPipeline pipeline, final CallbackInfo ci) {
        // M1: world-program dispatch is owned by IrisMetalWorldBridge (see
        // IrisRenderPassMixin); nothing to do here.
    }
}
