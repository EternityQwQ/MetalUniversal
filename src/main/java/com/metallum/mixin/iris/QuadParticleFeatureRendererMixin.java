package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalWorldBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * M3 particle arming: 26.2's {@code QuadParticleFeatureRenderer.executeGroup}
 * builds its render pass directly
 * ({@code CommandEncoder.createRenderPass(...)} then per-layer
 * {@code setPipeline}/{@code drawIndexed}), so it never reaches
 * {@code PreparedRenderType.drawFromBuffer} and the world override's pending
 * key was never armed. Injecting immediately before the pass creation (the
 * encoder was already created a few instructions earlier) arms the key that
 * matches the group's translucency; the consume in
 * {@code MetalCommandEncoder.createRenderPass} then rewrites exactly this
 * pass.
 */
@Environment(EnvType.CLIENT)
@Mixin(QuadParticleFeatureRenderer.class)
public abstract class QuadParticleFeatureRendererMixin {
    @Inject(
            method = "executeGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;"
                    + "ILjava/util/List;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass("
                            + "Ljava/util/function/Supplier;"
                            + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                            + "Ljava/util/Optional;"
                            + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                            + "Ljava/util/OptionalDouble;)"
                            + "Lcom/mojang/blaze3d/systems/RenderPass;",
                    shift = At.Shift.BEFORE
            )
    )
    private void metallum$armParticleOverride(
            final FeatureFrameContext context,
            final int groupIndex,
            final List<QuadParticleFeatureRenderer.Submit> submits,
            final boolean strictlyOrdered,
            final CallbackInfo ci
    ) {
        if (submits.isEmpty()) {
            return;
        }
        // The group was prepared per translucency, so every submit carries the
        // same flag; it selects the engine pipeline (OPAQUE_PARTICLE vs
        // TRANSLUCENT_PARTICLE) drawn inside this pass.
        IrisMetalWorldBridge.armForDraw(submits.getFirst().translucent()
                ? ShaderKey.PARTICLES_TRANS
                : ShaderKey.PARTICLES);
    }
}
