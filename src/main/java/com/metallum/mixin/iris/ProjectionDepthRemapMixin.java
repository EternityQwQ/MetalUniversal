package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalActive;
import com.metallum.client.metal.render.ProjectionDepthRemap;
import net.irisshaders.iris.Iris;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Metal has no OpenGL equivalent of {@code glDepthRange}: the rasterizer clips
 * clip-space z outside [0,1] outright. While an Iris pack is in use the engine
 * switches its projections to OpenGL-style [-1,1] depth (Iris's
 * {@code UndoReverseZOne/Four} path, which the Metal pipeline opts into via
 * {@code isPackInUseQuick}), so every fragment with negative clip z is dropped
 * by Metal instead of being mapped into [0,1] the way GL does.
 *
 * <p>This is what cut the GUI item atlas models in half (their front half has
 * negative clip z) and made flat 2D item icons disappear entirely (they sit at
 * z &le; 0). Applying {@link ProjectionDepthRemap} to the returned matrix
 * reproduces GL's depth range on Metal; combined with the backend's depth
 * compare flip and depth clear complement this forms the complete standard-z
 * convention Iris's {@code UndoReverseZThree/Five} implement for GL.
 */
@Mixin(Projection.class)
public abstract class ProjectionDepthRemapMixin {
    @Inject(method = "getMatrix", at = @At("RETURN"))
    private void metallum$remapDepthToZeroOne(final CallbackInfoReturnable<Matrix4f> cir) {
        if (!MetalActive.isMetalActive() || !Iris.isPackInUseQuick()) {
            return;
        }
        ProjectionDepthRemap.apply(cir.getReturnValue());
    }
}
