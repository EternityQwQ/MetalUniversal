package com.metallum.mixin.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalDebugSwitches;
import com.metallum.client.metal.render.MetalProbeReport;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hard cloud kill for the bisection probes: MC 26.2 renders clouds through its
 * own frame-graph pass ({@code LevelRenderer.addCloudsPass}) into a dedicated
 * clouds target that the pack's final pass never touches, so the pack-level
 * {@code CloudSetting} override cannot suppress them. Cancelling the pass
 * registration itself is the only reliable way to drop the engine clouds.
 * Inactive by default.
 */
@Mixin(LevelRenderer.class)
abstract class NoVanillaCloudsMixin {
    @Unique
    private static boolean metallum$reported;

    @Inject(method = "addCloudsPass", at = @At("HEAD"), cancellable = true)
    private void metallum$skipCloudsPass(
            final FrameGraphBuilder builder,
            final CloudStatus cloudStatus,
            final Vec3 vec3,
            final long l,
            final float f,
            final int i,
            final float g,
            final int j,
            final CallbackInfo ci
    ) {
        boolean cancelling = MetalDebugSwitches.NO_CLOUDS_HARD
                || MetalDebugSwitches.NO_VANILLA_CLOUDS
                || MetalDebugSwitches.NO_VANILLA_SKY;
        if (!metallum$reported) {
            metallum$reported = true;
            Metallum.LOGGER.warn("[metallum-iris][debug] addCloudsPass seen; cancelling={}", cancelling);
            MetalProbeReport.record("addCloudsPass seen; cancelling=" + cancelling);
        }
        if (cancelling) {
            ci.cancel();
        }
    }
}
