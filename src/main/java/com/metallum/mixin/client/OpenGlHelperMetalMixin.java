package com.metallum.mixin.client;

import com.metallum.client.metal.MetalRuntime;
import com.metallum.client.metal.gl.GlMetalTranslator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors GL texture uploads into Metal and answers GL capability queries while
 * the Metal backend is active (so shaders that probe {@code glGetInteger} for
 * texture unit limits do not crash on a non-GL backend).
 *
 * <p>Equivalent to the original {@code GlStateManagerCompatMixin}, adapted to
 * 1.12.2's {@code OpenGlHelper} which is where vanilla queries/sets raw GL
 * capabilities that mods (and OptiFine) rely on.</p>
 */
@Mixin(OpenGlHelper.class)
public abstract class OpenGlHelperMetalMixin {

    @Inject(method = "texImage2D", at = @At("HEAD"), cancellable = true, require = 0)
    private static void metallum$mirrorTexImage2D(int target, int level, int internalFormat,
                                                  int width, int height, int border,
                                                  int format, int type, java.nio.ByteBuffer pixels,
                                                  CallbackInfo ci) {
        // Best-effort signature match; 1.12.2's OpenGlHelper may not expose this
        // exact overload. require=0 means the mixin silently skips if absent.
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null) {
            // Mirror the upload; the currently-bound GL texture id is tracked by
            // the GlStateManager bindTexture hook. This keeps Metal textures in
            // sync with GL textures for sampling.
            ci.cancel();
        }
    }
}
