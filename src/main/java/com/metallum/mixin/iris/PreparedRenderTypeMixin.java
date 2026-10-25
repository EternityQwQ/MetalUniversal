package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalWorldBridge;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * M1 draw-time arming hook: 26.2 routes entity / block-entity draws by
 * {@link RenderPipeline} identity, not by {@code WorldRenderingPhase}, so the
 * phase gate in {@code IrisMetalWorldBridge.rewriteWorldDescriptor} never
 * opened for entities. {@code PreparedRenderType.drawFromBuffer} is the single
 * choke point for non-terrain world draws (its
 * {@code StagedVertexBuffer.ExecuteInfo} overload delegates here, matching
 * upstream Iris' {@code MixinPreparedRenderType}); arming at HEAD lets
 * {@code rewriteWorldDescriptor}, called synchronously by
 * {@code MetalCommandEncoder.createRenderPass} further down this method, take
 * the pass over exactly when the pipeline maps to an M1-whitelisted shader
 * key.
 *
 * <p>Non-whitelisted pipelines (hand, text, particles, GUI, ...) clear the
 * pending key, so their passes stay vanilla.
 */
@Environment(EnvType.CLIENT)
@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeMixin {
    @Shadow
    @Final
    private RenderPipeline pipeline;

    @Inject(
            method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;"
                    + "Lcom/mojang/blaze3d/buffers/GpuBuffer;"
                    + "Lcom/mojang/blaze3d/IndexType;III)V",
            at = @At("HEAD")
    )
    private void metallum$armWorldOverride(
            final GpuBuffer vertexBuffer,
            final GpuBuffer indexBuffer,
            final IndexType indexType,
            final int baseVertex,
            final int firstIndex,
            final int indexCount,
            final CallbackInfo ci
    ) {
        IrisMetalWorldBridge.armForDraw(this.pipeline);
    }
}
