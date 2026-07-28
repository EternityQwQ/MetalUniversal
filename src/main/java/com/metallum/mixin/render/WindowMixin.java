package com.metallum.mixin.render;

import com.metallum.client.metal.fx.MetalFxConfig;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shrinks the framebuffer dimensions reported by {@link Window} when MetalFX
 * spatial upscaling is active, so Minecraft allocates its main render target
 * at a reduced resolution and the MetalFX spatial scaler actually has work
 * to do on the present path.
 *
 * <p><b>Why this is needed.</b> MetalFX spatial upscaling only produces a
 * visible benefit when the game renders at a lower internal resolution than
 * the display. {@code MetalSurface} keeps the
 * {@code CAMetalLayer.drawableSize} at full display resolution (so the
 * compositor shows the upscaled frame 1:1), but the size of the game's main
 * render target — and the GUI scale calculation — are both driven by
 * {@link Window#getWidth()} / {@link Window#getHeight()} (which read the
 * private {@code framebufferWidth} / {@code framebufferHeight} fields set
 * from the GLFW framebuffer size). Without intercepting these getters, the
 * game renders at 100% resolution, the source texture passed to
 * {@code MetalFxPipeline.maybeEncode} is already full-size, the
 * {@code sourceWidth != outputWidth} guard is never satisfied, and the
 * spatial scaler is a permanent no-op.
 *
 * <p><b>Why getters and not {@code refreshFramebufferSize}.</b> Vanilla
 * {@code refreshFramebufferSize()} writes {@code framebufferWidth/Height}
 * and then calls {@code eventHandler.resizeDisplay()}, which recomputes
 * {@code guiScale} / {@code guiScaledWidth} / {@code guiScaledHeight} from
 * those fields. Injecting at the tail of {@code refreshFramebufferSize}
 * would run <i>after</i> {@code resizeDisplay} has already consumed the
 * un-shrunk values, so the GUI scale would be wrong. Intercepting the
 * getters ensures <em>every</em> consumer — mainRenderTarget allocation,
 * GUI scale calculation, scissor rects, input coordinates — sees the
 * shrunk dimensions consistently.
 *
 * <p><b>GUI impact.</b> {@code guiScaledWidth} / {@code guiScaledHeight}
 * are derived from the (shrunk) framebuffer dimensions, so the GUI also
 * renders at the reduced resolution and is upscaled together with the 3D
 * scene. This is the standard "render scaling" behaviour (identical to
 * how resolution scaling works with FSR/DLSS) and is the desired result:
 * the whole frame, HUD included, is reconstructed by MetalFX.
 *
 * <p><b>Metal-only &amp; immediate.</b> The shrink is only applied when the
 * active GPU backend is Metal and spatial upscaling is enabled. Because
 * {@link MetalFxConfig#spatialMode()} reads a {@code volatile} field,
 * changes take effect on the very next getter call — no need to trigger a
 * framebuffer rebuild when the user toggles the mode in the options screen.
 *
 * <p><b>Present path.</b> {@code MetalSurface.outputWidth()/outputHeight()}
 * deliberately bypass these getters and return the full display size, so
 * the MetalFX output target (and the {@code CAMetalLayer.drawableSize})
 * remain at full resolution. The present path therefore sees
 * {@code sourceWidth < outputWidth} and runs the spatial scaler.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    // Inject at RETURN so the original method body runs first and
    // cir.getReturnValue() holds the real framebufferWidth/Height. We then
    // overwrite it with the scaled value. HEAD would leave getReturnValue()
    // unset (the original body hasn't executed).
    @Inject(method = "getWidth", at = @At("RETURN"), cancellable = true)
    private void metallum$scaleFramebufferWidth(CallbackInfoReturnable<Integer> cir) {
        int original = cir.getReturnValue();
        Integer scaled = metallum$scale(original);
        if (scaled != null) {
            cir.setReturnValue(scaled);
        }
    }

    @Inject(method = "getHeight", at = @At("RETURN"), cancellable = true)
    private void metallum$scaleFramebufferHeight(CallbackInfoReturnable<Integer> cir) {
        int original = cir.getReturnValue();
        Integer scaled = metallum$scale(original);
        if (scaled != null) {
            cir.setReturnValue(scaled);
        }
    }

    // guiScaledWidth / guiScaledHeight are cached fields computed in
    // resizeDisplay() from the (un-shrunk) framebufferWidth/Height. Because
    // we intercept getWidth/getHeight "immediately" (without triggering
    // resizeDisplay), these cached GUI dimensions stay at the old full-size
    // values. If left un-intercepted, Screen.width / Screen.height would
    // report e.g. 960 (from 1920) while the render target is only 634 — so
    // all GUI widgets (buttons, etc.) would be laid out in the 960-wide
    // coordinate space and land outside the 634-wide render target,
    // vanishing from the screen. Scaling these getters keeps the GUI
    // coordinate space consistent with the shrunk render target.
    @Inject(method = "getGuiScaledWidth", at = @At("RETURN"), cancellable = true)
    private void metallum$scaleGuiScaledWidth(CallbackInfoReturnable<Integer> cir) {
        int original = cir.getReturnValue();
        Integer scaled = metallum$scale(original);
        if (scaled != null) {
            cir.setReturnValue(scaled);
        }
    }

    @Inject(method = "getGuiScaledHeight", at = @At("RETURN"), cancellable = true)
    private void metallum$scaleGuiScaledHeight(CallbackInfoReturnable<Integer> cir) {
        int original = cir.getReturnValue();
        Integer scaled = metallum$scale(original);
        if (scaled != null) {
            cir.setReturnValue(scaled);
        }
    }

    /**
     * @return the dimension multiplied by the active spatial render scale, or
     *         {@code null} to leave the original value unchanged (non-Metal
     *         backend, spatial upscaling off, or scale >= 1.0).
     */
    private static Integer metallum$scale(int original) {
        if (!metallum$isMetalBackend()) {
            return null;
        }
        MetalFxConfig cfg = MetalFxConfig.get();
        if (!cfg.isSpatialUpscalingActive()) {
            return null;
        }
        float scale = cfg.spatialMode().renderScale;
        if (scale >= 1.0f) {
            return null;
        }
        return Math.max(1, Math.round(original * scale));
    }

    private static boolean metallum$isMetalBackend() {
        try {
            var device = RenderSystem.getDevice();
            if (device == null) {
                return false;
            }
            return "Metal".equals(device.getDeviceInfo().backendName());
        } catch (Throwable t) {
            return false;
        }
    }
}
