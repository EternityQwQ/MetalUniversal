package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalProbeReport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tick-loop entry point for the probe self-check. {@link MetalProbeReport#onClientTick}
 * returns immediately unless a debug switch is active, so an unset session pays
 * a single boolean check per tick and nothing else.
 */
@Mixin(Minecraft.class)
abstract class MetalProbeReportMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void metallum$probeReport(final boolean bl, final CallbackInfo ci) {
        MetalProbeReport.onClientTick((Minecraft) (Object) this);
    }
}
