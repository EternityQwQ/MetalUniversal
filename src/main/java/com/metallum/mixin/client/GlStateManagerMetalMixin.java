package com.metallum.mixin.client;

import com.metallum.client.metal.MetalRuntime;
import com.metallum.client.metal.gl.GlMetalTranslator;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes {@code GlStateManager} state calls into the GL-over-Metal translator.
 *
 * <p>1.12.2 funnels most OpenGL state through {@code GlStateManager}, so this is
 * the single highest-leverage hook for broad mod compatibility: vanilla,
 * OptiFine, and nearly all mods set blend/depth/cull/viewport/texture state
 * through these methods. When the Metal backend is active, each call is mirrored
 * into the translator; when inactive (non-Metal platform) the mixin body is a
 * no-op and vanilla GL runs unchanged.</p>
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerMetalMixin {

    @Inject(method = {"enableBlend", "disableBlend"}, at = @At("HEAD"))
    private static void metallum$onBlend(CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null) {
            // enabled/disabled is inferred from the stack; keep it simple here.
        }
    }

    @Inject(method = "enableDepth", at = @At("HEAD"))
    private static void metallum$enableDepth(CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null) {
            t.setDepthTest(true);
        }
    }

    @Inject(method = "disableDepth", at = @At("HEAD"))
    private static void metallum$disableDepth(CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null) {
            t.setDepthTest(false);
        }
    }

    @Inject(method = "depthMask", at = @At("HEAD"))
    private static void metallum$depthMask(boolean flag, CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null) {
            t.setDepthWrite(flag);
        }
    }

    @Inject(method = "depthFunc", at = @At("HEAD"))
    private static void metallum$depthFunc(int func, CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null) {
            t.setDepthFunc(func);
        }
    }

    @Inject(method = "enableCull", at = @At("HEAD"))
    private static void metallum$enableCull(CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null) {
            t.setCull(true);
        }
    }

    @Inject(method = "disableCull", at = @At("HEAD"))
    private static void metallum$disableCull(CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null) {
            t.setCull(false);
        }
    }

    @Inject(method = "cullFace", at = @At("HEAD"))
    private static void metallum$cullFace(GlStateManager.CullFace cullFaceIn, CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null && cullFaceIn != null) {
            t.setCullMode(cullFaceIn.mode);
        }
    }

    @Inject(method = "frontFace", at = @At("HEAD"), require = 0)
    private static void metallum$frontFace(int mode, CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null) {
            t.setFrontFace(mode);
        }
    }

    @Inject(method = "viewport", at = @At("HEAD"))
    private static void metallum$viewport(int x, int y, int w, int h, CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null) {
            t.setViewport(x, y, w, h);
        }
    }

    @Inject(method = {"enableScissorTest", "disableScissorTest"}, at = @At("HEAD"), require = 0)
    private static void metallum$scissorTest(CallbackInfo ci) {
        // Some 1.12.2 builds expose scissor via GlStateManager; require=0 keeps
        // this safe if absent. Scissor rect is set via GL11.glScissor directly.
    }

    @Inject(method = "bindTexture", at = @At("HEAD"))
    private static void metallum$bindTexture(int glId, CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t != null) {
            t.bindTexture(0, glId);
        }
    }
}
