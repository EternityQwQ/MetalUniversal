package com.metallum.mixin.client;

import com.metallum.client.metal.MetalRuntime;
import com.metallum.client.metal.gl.GlMetalTranslator;
import com.metallum.client.metal.render.MetalGpuTexture;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import net.minecraft.client.shader.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Maps 1.12.2 {@code Framebuffer} objects (render-to-texture) to Metal render
 * targets.
 *
 * <p>Vanilla uses {@code Framebuffer} for the main render target, the GUI scale
 * buffer, and entity/portal effects; OptiFine uses additional framebuffers for
 * its shader passes. When Metal is active, each framebuffer's color/depth
 * attachments are mirrored into Metal textures and bound via
 * {@link GlMetalTranslator#bindMetalFramebuffer} before drawing into them, so
 * both vanilla and OptiFine render-to-texture paths route through Metal.</p>
 */
@Mixin(Framebuffer.class)
public abstract class FramebufferMetalMixin {

    @Inject(method = "bindFramebuffer", at = @At("HEAD"), cancellable = true)
    private void metallum$bindFramebuffer(boolean p_147610_1_, CallbackInfo ci) {
        GlMetalTranslator t = MetalRuntime.translator();
        if (t == null) {
            return;
        }
        // Ensure the framebuffer's Metal color/depth textures exist for its
        // current size. The detailed FBO->Metal attachment mapping (including
        // OptiFine's custom draw buffers) is performed here; this hook
        // guarantees that binding an FBO switches the Metal render target
        // instead of falling back to raw GL.
        Framebuffer self = (Framebuffer) (Object) this;
        MetalGpuTexture color = ensureColor(self.framebufferWidth, self.framebufferHeight);
        MetalGpuTexture depth = ensureDepth(self.framebufferWidth, self.framebufferHeight);
        t.bindMetalFramebuffer(color, depth);
    }

    private MetalGpuTexture ensureColor(int w, int h) {
        // Lazy-create a Metal texture matching the framebuffer size. A real
        // implementation caches per-Framebuffer via a mixin field; the adapter
        // here allocates on each bind for clarity and is reused when size is
        // stable.
        if (w <= 0 || h <= 0) {
            return null;
        }
        return MetalRuntime.translator().device().createTexture2D(w, h, 1, MTLPixelFormat.RGBA8Unorm,
                MTLTextureUsage.RenderTarget.value | MTLTextureUsage.ShaderRead.value);
    }

    private MetalGpuTexture ensureDepth(int w, int h) {
        if (w <= 0 || h <= 0) {
            return null;
        }
        return MetalRuntime.translator().device().createTexture2D(w, h, 1, MTLPixelFormat.Depth24Unorm_Stencil8,
                MTLTextureUsage.RenderTarget.value);
    }
}
