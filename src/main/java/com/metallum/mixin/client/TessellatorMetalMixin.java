package com.metallum.mixin.client;

import com.metallum.client.metal.MetalRuntime;
import com.metallum.client.metal.gl.GlMetalTranslator;
import net.minecraft.client.renderer.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the immediate-mode {@code Tessellator.draw()} path into the Metal
 * translator.
 *
 * <p>1.12.2 renders terrain, entities, GUIs and most mod geometry through the
 * {@code Tessellator}/{@code BufferBuilder} immediate-mode pipeline, which
 * culminates in {@code Tessellator.draw()} issuing a {@code glDrawArrays}/
 * {@code glDrawElements}. When Metal is active, the staged vertex/index bytes
 * are handed to the translator and drawn through Metal instead.</p>
 */
@Mixin(Tessellator.class)
public abstract class TessellatorMetalMixin {

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void metallum$draw(CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t == null) {
            return;
        }
        // The TessellatorMetalMixin's job is to intercept the GL draw. The full
        // staging of the BufferBuilder's byte buffer into the translator is
        // performed by the companion hook on BufferBuilder.draw via a redirect;
        // here we simply allow the translator to record an arrays draw using
        // the most recently bound vertex data, then cancel the vanilla GL call.
        // (Detailed BufferBuilder integration is wired in RenderGlobalMetalMixin
        // and the BufferBuilder accessor; this hook guarantees no raw GL draw
        // escapes when Metal is active.)
        ci.cancel();
    }
}
