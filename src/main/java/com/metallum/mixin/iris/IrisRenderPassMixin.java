package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalTerrainBridge;
import com.metallum.client.metal.render.IrisMetalWorldBridge;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets Iris DRAWBUFFERS own the attachment contract on Metal, both for the
 * Sodium terrain draws ({@link IrisMetalTerrainBridge}) and for the M1
 * non-terrain world draws ({@link IrisMetalWorldBridge}).
 */
@Mixin(RenderPass.class)
public abstract class IrisRenderPassMixin {
    @Shadow
    private RenderPassBackend backend;

    @Inject(method = "setPipeline", at = @At("HEAD"), cancellable = true)
    private void metallum$installIrisPipeline(
            final RenderPipeline pipeline,
            final CallbackInfo ci
    ) {
        if (IrisMetalWorldBridge.installPipeline(this.backend, pipeline)
                || IrisMetalTerrainBridge.installPipeline(this.backend, pipeline)) {
            ci.cancel();
        }
    }
}
