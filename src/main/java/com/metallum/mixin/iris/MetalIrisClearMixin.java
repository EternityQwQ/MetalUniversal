package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalWorldBridge;
import com.metallum.client.metal.render.MetalActive;
import com.metallum.client.metal.render.MetalIrisProgram;
import com.metallum.client.metal.render.MetalIrisProgramsToClear;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Metal-side Iris render-dispatch clear mixin: the counterpart of
 * {@link MetalIrisPipelineMixin}. Mirrors iris-ref's
 * {@code MixinGlCommandEncoder} {@code submitRenderPass HEAD} injector, which
 * calls {@code iris$clearState} on every program that was set up during the
 * pass and then clears the pending list.
 *
 * <p>When a render pass is submitted, every {@link MetalIrisProgram} that had
 * {@code iris$setupState} called on it during the pass is reset (its
 * {@code isSetUp} flag cleared) so the next pass re-installs the shaderpack
 * pipeline. The pending list is the process-wide
 * {@link MetalIrisProgramsToClear#PROGRAMS} static holder, shared with
 * {@link MetalIrisPipelineMixin} to avoid cross-mixin field access.
 *
 * <p>Also ends any active {@link IrisMetalWorldBridge} world pass at pass
 * submit, so sampler fallback for the next pass starts from a clean slate.
 *
 * <p><b>Non-Metal no-op.</b> Gated by {@link MetalActive#isMetalActive()}.
 *
 * <p><b>Target visibility.</b> {@code MetalCommandEncoder} is package-private,
 * so this mixin targets it by fully-qualified name string
 * ({@code targets = "..."}) rather than class literal.
 */
@Environment(EnvType.CLIENT)
@Mixin(targets = "com.metallum.client.metal.render.MetalCommandEncoder")
public class MetalIrisClearMixin {
    @Inject(method = "submitRenderPass", at = @At("HEAD"))
    private void metallum$irisClearState(final CallbackInfo ci) {
        if (!MetalActive.isMetalActive()) {
            return;
        }
        IrisMetalWorldBridge.endPass();
        if (!MetalIrisProgramsToClear.PROGRAMS.isEmpty()) {
            MetalIrisProgramsToClear.PROGRAMS.forEach(MetalIrisProgram::iris$clearState);
            MetalIrisProgramsToClear.PROGRAMS.clear();
        }
    }
}
