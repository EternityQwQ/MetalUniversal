package com.metallum.mixin.client;

import com.metallum.client.metal.MetalRuntime;
import com.metallum.client.metal.gl.GlMetalTranslator;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the Metal frame lifecycle around Minecraft's render loop.
 *
 * <p>{@code runGameLoop} is where 1.12.2 runs each client tick + render. We
 * begin a Metal frame at the top and present/submit at the end, so the entire
 * render pass is captured. This mirrors the original
 * {@code MinecraftMetalFxMixin} frame lifecycle, simplified for 1.12.2.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMetalMixin {

    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void metallum$beginFrame(CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null) {
            t.beginFrame();
        }
    }

    @Inject(method = "runGameLoop", at = @At("RETURN"))
    private void metallum$endFrame(CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null) {
            t.endFrame();
        }
    }
}
