package com.metallum.mixin.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalDebugSwitches;
import com.metallum.client.metal.render.MetalProbeReport;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Second hard cloud kill for the bisection probes, backing up
 * {@link NoVanillaCloudsMixin}: cancels the cloud renderer's own
 * {@code render} entry point so nothing reaches the GPU even if some other
 * path (e.g. the frame-graph pass being registered without
 * {@code addCloudsPass}) still submits cloud work. Inactive by default.
 */
@Mixin(CloudRenderer.class)
abstract class NoVanillaCloudRendererMixin {
    @Unique
    private static boolean metallum$reported;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void metallum$skipCloudRender(
            final int i,
            final CloudStatus cloudStatus,
            final float f,
            final int j,
            final Vec3 vec3,
            final long l,
            final float g,
            final CallbackInfo ci
    ) {
        boolean cancelling = MetalDebugSwitches.NO_CLOUDS_HARD
                || MetalDebugSwitches.NO_VANILLA_CLOUDS
                || MetalDebugSwitches.NO_VANILLA_SKY;
        if (!metallum$reported) {
            metallum$reported = true;
            Metallum.LOGGER.warn("[metallum-iris][debug] cloudRenderer.render seen; cancelling={}", cancelling);
            MetalProbeReport.record("cloudRenderer.render seen; cancelling=" + cancelling);
        }
        if (cancelling) {
            ci.cancel();
        }
    }
}
