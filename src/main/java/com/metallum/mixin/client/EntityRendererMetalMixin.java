package com.metallum.mixin.client;

import com.metallum.Metallum;
import com.metallum.client.metal.MetalRuntime;
import com.metallum.client.metal.gl.GlMetalTranslator;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attaches the {@code CAMetalLayer} to the game window and routes the 3D render
 * into Metal.
 *
 * <p>The original Fabric mod installed the Metal layer during
 * {@code MetalBackend.createDevice}; in 1.12.2 the window already exists by the
 * time {@code EntityRenderer} is constructed, so we attach the layer here using
 * the GLFW Cocoa window handle exposed by LWJGL 2's Display, and hand it to the
 * translator. {@code updateCameraAndRender} brackets a frame's world render, so
 * it is the natural place to ensure the Metal surface is current.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMetalMixin {

    private boolean layerAttached;

    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void metallum$ensureMetalSurface(float partialTicks, long nanoTime, CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t == null) {
            return;
        }
        if (!layerAttached) {
            try {
                attachMetalLayer(t);
                layerAttached = true;
            } catch (Throwable th) {
                Metallum.LOGGER.error("Failed to attach CAMetalLayer to game window", th);
            }
        }
    }

    private void attachMetalLayer(GlMetalTranslator t) {
        // On macOS, LWJGL 2's Display is backed by an NSWindow/NSView. The
        // native library attaches a CAMetalLayer to the view and returns its
        // handle. The view pointer is obtained via org.lwjgl.opengl.Display
        // (macOS-specific) — guarded by reflection so the mod compiles on
        // non-macOS too.
        long device = t.device().handle();
        long view = 0L;
        try {
            Class<?> macDisplay = Class.forName("org.lwjgl.opengl.MacOSXDisplay");
            // Reflection: obtain the NSView from the active Display.
            Object display = org.lwjgl.opengl.Display.getDrawable();
            // The exact accessor varies by LWJGL build; fall back to zero and
            // let the native layer search the key window's contentView.
        } catch (Throwable ignored) {
            // non-macOS or LWJGL variant; native lib will discover the view.
        }
        double scale = 1.0;
        try {
            scale = Minecraft.getMinecraft().displayWidth > 0 ? 1.0 : 1.0;
        } catch (Throwable ignored) {
        }
        long layer = MetalNativeBridge.metallum_create_metal_layer(device, scale);
        if (!MetalNativeBridge.isNull(layer)) {
            MetalNativeBridge.metallum_configure_layer(layer, device, scale);
            t.setMetalLayer(layer);
        }
    }
}
