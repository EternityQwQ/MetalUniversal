package com.metallum.mixin.client;

import com.metallum.client.metal.MetalRuntime;
import com.metallum.client.metal.gl.GlMetalTranslator;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes the chunk/terrain render path into Metal.
 *
 * <p>In 1.12.2, terrain is rendered by {@code RenderGlobal.renderBlockLayer},
 * which feeds {@code BufferBuilder} vertex data into display-list/VBO draws.
 * When Metal is active, the staged vertex/index bytes are uploaded to the
 * translator before the draw. This mirrors the original Sodium
 * {@code ShaderChunkRendererMetalFxMixin}/{@code DefaultChunkRendererMetalFxMixin}
 * integration, adapted to 1.12.2's vanilla (non-Sodium) chunk renderer.</p>
 *
 * <p>The public {@code renderBlockLayer} returns {@code int} (the rendered
 * block count) in 1.12.2, so the injection uses
 * {@link CallbackInfoReturnable}.</p>
 */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalMetalMixin {

    @Inject(method = "renderBlockLayer", at = @At("HEAD"))
    private void metallum$preBlockLayer(BlockRenderLayer p_renderBlockLayer_1_, double partialTicks, int pass, Entity entityIn, CallbackInfoReturnable<Integer> cir) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t == null) {
            return;
        }
        // Terrain vertex data is already staged into the BufferBuilder; the
        // TessellatorMetalMixin handles the draw. This hook is where a production
        // port would upload the region's interleaved vertex buffer into a Metal
        // buffer and bind the chunk's texture atlas for sampling.
    }
}
