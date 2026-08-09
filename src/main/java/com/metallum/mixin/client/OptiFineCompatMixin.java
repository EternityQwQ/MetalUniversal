package com.metallum.mixin.client;

import com.metallum.Metallum;
import com.metallum.client.metal.MetalConfig;
import com.metallum.client.metal.MetalRuntime;
import net.minecraft.client.renderer.OpenGlHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OptiFine compatibility shim.
 *
 * <p>OptiFine replaces 1.12.2's shader program loading and issues its own raw
 * GL calls for shader uniforms, draw buffers, and its custom framebuffer chain.
 * Because the GL-over-Metal translation layer intercepts GL at the
 * {@code GlStateManager}/{@code GL11} boundary — <em>below</em> OptiFine — every
 * OptiFine GL call is automatically routed into Metal. This mixin only needs to
 * keep OptiFine's GL capability expectations satisfied (so its shader
 * preprocessor does not refuse to load) once the Metal backend is active.</p>
 *
 * <p>{@code OpenGlHelper.initializeTextures} runs once during early GL setup and
 * is where vanilla probes GL capabilities. We hook it to mark the Metal backend
 * as the active renderer for OptiFine detection, and to report GL 2.1+ support
 * (which {@code OpenGlHelper.openGL21} gates) so OptiFine's shader path is not
 * disabled before it starts. Raw {@code GL11.glGetInteger} calls OptiFine makes
 * for max texture units / draw buffers cannot be cleanly mixin-hooked (LWJGL
 * static methods); those are instead answered by the native layer's GL shim when
 * present, and otherwise fall through to the host GL.</p>
 *
 * <p>Applies only when OptiFine is detected (gated by the
 * {@link com.metallum.mixin.MetallumMixinConfigPlugin}).</p>
 */
@Mixin(OpenGlHelper.class)
public abstract class OptiFineCompatMixin {

    @Inject(method = "initializeTextures", at = @At("RETURN"))
    private static void metallum$afterInitializeTextures(CallbackInfo ci) {
        if (!MetalConfig.optiFineCompat) {
            return;
        }
        if (MetalRuntime.isActivated()) {
            // Report GL 2.1+ capability so vanilla/OptiFine feature gates that
            // read OpenGlHelper.openGL21 do not turn off shader support while
            // the Metal backend is rendering.
            OpenGlHelper.openGL21 = true;
            MetalRuntime.markOptiFineCompatible();
        }
    }
}
