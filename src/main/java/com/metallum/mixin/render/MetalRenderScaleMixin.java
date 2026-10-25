package com.metallum.mixin.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalDebugSwitches;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps vanilla's per-frame "main target must match the window extent" check
 * from undoing the opt-in render scale ({@code -Dmetallum.iris.renderScale}).
 *
 * <p>{@code GameRenderer.render} compares the full window extent in
 * {@code WindowRenderState} with the main target and calls {@code resize} on
 * every mismatch; without this redirect a resized (smaller) target would be
 * rebuilt at full size at the start of each frame and then rebuilt again at the
 * scaled size by {@code MetalWorldRenderingPipeline}, i.e. two full attachment
 * rebuilds and a {@code LevelRenderer.resize} (occlusion-graph invalidation)
 * per frame. The redirect substitutes the scaled extent and skips the call when
 * the target already matches it, so the steady state stays free.
 *
 * <p>At the default 1.0 the handler forwards the original arguments unchanged;
 * {@code WindowRenderState} itself is untouched, so GUI layout/projection keep
 * their normal dimensions and the smaller target simply renders the same NDC
 * image at fewer pixels. A failed resize latches
 * {@link MetalDebugSwitches#failRenderScale()} (shared with the pipeline's
 * fallback) and restores the full window extent instead of crashing.
 */
@Mixin(GameRenderer.class)
abstract class MetalRenderScaleMixin {
    @Redirect(
            method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;resize(II)V"
            )
    )
    private void metallum$resizeMainTargetForRenderScale(
            final GameRenderer renderer,
            final int windowWidth,
            final int windowHeight
    ) {
        float scale = MetalDebugSwitches.effectiveRenderScale();
        if (scale == 1.0f || windowWidth <= 0 || windowHeight <= 0) {
            renderer.resize(windowWidth, windowHeight);
            return;
        }
        int targetWidth = MetalDebugSwitches.scaledExtent(windowWidth, scale);
        int targetHeight = MetalDebugSwitches.scaledExtent(windowHeight, scale);
        RenderTarget mainTarget = renderer.mainRenderTarget();
        if (mainTarget != null && mainTarget.width == targetWidth && mainTarget.height == targetHeight) {
            // Already at the scaled extent: do not clear the cross-frame pool or
            // invalidate the section occlusion graph every frame.
            return;
        }
        try {
            renderer.resize(targetWidth, targetHeight);
        } catch (RuntimeException failure) {
            if (MetalDebugSwitches.failRenderScale()) {
                Metallum.LOGGER.warn(
                        "[metallum-iris] renderScale {} could not resize the main target to {}x{};"
                                + " falling back to 1.0",
                        scale, targetWidth, targetHeight, failure
                );
            }
            try {
                renderer.resize(windowWidth, windowHeight);
            } catch (RuntimeException restoreFailure) {
                Metallum.LOGGER.warn(
                        "[metallum-iris] could not restore the main target to {}x{} after render scaling",
                        windowWidth, windowHeight, restoreFailure
                );
            }
        }
    }
}
