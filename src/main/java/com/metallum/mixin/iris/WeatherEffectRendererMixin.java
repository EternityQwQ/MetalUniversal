package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalWorldBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * M3 weather arming: 26.2's {@code WeatherEffectRenderer.render} builds its
 * own pass (against {@code OutputTarget.WEATHER_TARGET}) and calls
 * {@code setPipeline}({@code WEATHER_DEPTH_WRITE}/{@code WEATHER_NO_DEPTH_WRITE})
 * directly, so the world override's pending key was never armed. Injecting
 * before the pass creation arms {@link ShaderKey#WEATHER}; both engine
 * pipelines map to that key in {@code MetalIrisPipelines}.
 */
@Environment(EnvType.CLIENT)
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
    @Inject(
            method = "render(Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass("
                            + "Ljava/util/function/Supplier;"
                            + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                            + "Ljava/util/Optional;"
                            + "Lcom/mojang/blaze3d/textures/GpuTextureView;"
                            + "Ljava/util/OptionalDouble;)"
                            + "Lcom/mojang/blaze3d/systems/RenderPass;",
                    shift = At.Shift.BEFORE
            )
    )
    private void metallum$armWeatherOverride(
            final Vec3 cameraPos,
            final WeatherRenderState renderState,
            final CallbackInfo ci
    ) {
        IrisMetalWorldBridge.armForDraw(ShaderKey.WEATHER);
    }
}
