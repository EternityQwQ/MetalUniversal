package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.fx.MetalFxConfig;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Metal-backed {@link GpuSurfaceBackend}. Owns the {@code CAMetalLayer} used
 * as the present target and bridges Blaze3D's surface configuration to
 * Metal's drawable sizing.
 *
 * <p><b>MetalFX spatial upscaling integration.</b> When the user enables a
 * spatial upscaling mode (Quality / Balanced / Performance / Ultra), the
 * surface reports a <i>reduced</i> {@code width()/height()} to Minecraft
 * (so the game allocates a smaller main render target and shades fewer
 * fragments), while keeping the {@code CAMetalLayer.drawableSize} at the
 * full display resolution. The present path then runs
 * {@link MetalFxPipeline#maybeEncode} to upscale the low-res source texture
 * back to display resolution before blitting to the drawable.
 *
 * <p>This is what makes spatial upscaling actually useful: the GPU renders
 * at e.g. 77% resolution (proportional FPS gain on fragment-bound scenes),
 * and MetalFX reconstructs the missing detail for free on dedicated
 * upscaling hardware. Without this step the source and output resolutions
 * would always be equal, and {@code MTLFXSpatialScaler} would be a no-op.
 *
 * <p>The drawable size is intentionally kept at full resolution: Core
 * Animation composites the {@code CAMetalLayer} at its frame size, and a
 * smaller drawable would be stretched by the compositor (introducing
 * blurry bilinear upscaling on top of MetalFX). Keeping the drawable full
 * size means the MetalFX-upscaled texture is presented 1:1 to the
 * drawable, and Core Animation just displays it as-is.
 *
 * <p>When MetalFX is off (or the device doesn't support it), the surface
 * behaves identically to the legacy path: configuration dimensions are
 * passed through unchanged, drawable size == config size, and the present
 * path is a plain 1:1 blit.
 */
@Environment(EnvType.CLIENT)
final class MetalSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> SUPPORTED_PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.MAILBOX);
    private final MetalDevice device;
    private final MemorySegment metalLayer;
    /**
     * The GLFW window handle. Used to read the <i>real</i> framebuffer size
     * via {@code glfwGetFramebufferSize} in {@link #configure}, because the
     * {@link GpuSurface.Configuration} passed to {@code configure} may have
     * already been shrunk by {@code WindowMixin} when spatial upscaling is
     * active. The drawable must always be sized to the true display
     * resolution so Core Animation presents the MetalFX-upscaled frame 1:1.
     */
    private final long windowHandle;
    private GpuSurface.Configuration configuration;
    /**
     * Full display resolution (the dimensions Minecraft's window actually
     * has on screen). Used as the {@code CAMetalLayer.drawableSize} and as
     * the MetalFX output target. Tracked separately from
     * {@link #configuration} because the configuration may be deliberately
     * shrunk to {@link #internalWidth}/{@link #internalHeight} when
     * spatial upscaling is active.
     */
    private int displayWidth = 0;
    private int displayHeight = 0;
    /**
     * Internal render resolution reported back to Minecraft via the
     * configuration. Equal to {@link #displayWidth}/{@link #displayHeight}
     * when MetalFX spatial upscaling is off; shrunk by
     * {@link MetalFxConfig.SpatialMode#renderScale} when it's on.
     */
    private int internalWidth = 0;
    private int internalHeight = 0;
    /**
     * The spatial mode used to compute {@link #internalWidth}/
     * {@link #internalHeight} on the last {@link #configure} call. Tracked
     * so we can detect when the user changes the mode in the MetalFX
     * options screen and re-shrink the configuration on the next
     * {@link #acquireNextTexture}.
     */
    private MetalFxConfig.SpatialMode appliedSpatialMode = MetalFxConfig.SpatialMode.OFF;
    private MetalCommandEncoder pendingPresentEncoder;

    MetalSurface(final MetalDevice device, final MemorySegment metalLayer, final long windowHandle) {
        this.device = device;
        this.metalLayer = metalLayer;
        this.windowHandle = windowHandle;
    }

    @Override
    public void configure(final GpuSurface.Configuration config) throws SurfaceException {
        if (config.width() <= 0 || config.height() <= 0) {
            throw new SurfaceException("Metal surface configuration must be positive, got " + config.width() + "x" + config.height());
        }

        // Read the TRUE display resolution directly from GLFW. We can't trust
        // config.width()/height() here because WindowMixin shrinks those
        // getters when spatial upscaling is active — the configuration
        // passed to us carries the shrunk (internal) dimensions, but the
        // CAMetalLayer.drawableSize must be the full display resolution so
        // Core Animation presents the MetalFX-upscaled frame 1:1.
        int realWidth;
        int realHeight;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pW = stack.mallocInt(1);
            var pH = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(windowHandle, pW, pH);
            realWidth = pW.get(0);
            realHeight = pH.get(0);
        }
        // GLFW can return 0 for the framebuffer size when the window is
        // minimized, not yet realized, or in the middle of a DPI change.
        // Propagating 0 into displayWidth/displayHeight would later cause
        // MetalFX to create zero-sized textures -> Metal validation abort
        // (the 4:3 / window-not-ready crash). Fall back to the configuration
        // dimensions, which were already validated > 0 above.
        if (realWidth <= 0 || realHeight <= 0) {
            realWidth = config.width();
            realHeight = config.height();
            Metallum.LOGGER.warn(
                    "[MetalFX] glfwGetFramebufferSize returned {}x{}; falling back to config {}x{}",
                    realWidth, realHeight, config.width(), config.height());
        }
        this.displayWidth = realWidth;
        this.displayHeight = realHeight;
        applyInternalResolution(config);

        // The CAMetalLayer.drawableSize is ALWAYS the full display
        // resolution. We do NOT shrink it to the internal resolution,
        // because Core Animation would then stretch the smaller drawable
        // to the layer's frame, undoing MetalFX's work with a blurry
        // bilinear pass. Instead, the present path blits the
        // MetalFX-upscaled (full-res) texture directly into the
        // full-res drawable.
        MetalNativeBridge.metallum_configure_layer(
                this.metalLayer,
                this.displayWidth,
                this.displayHeight,
                config.presentMode() == GpuSurface.PresentMode.MAILBOX ? 1 : 0
        );
    }

    /**
     * Computes {@link #internalWidth}/{@link #internalHeight} from the
     * display dimensions and the current MetalFX spatial mode, then
     * installs a (possibly shrunk) {@link GpuSurface.Configuration} that
     * Minecraft will use to size its main render target.
     *
     * <p>When spatial upscaling is off, the configuration is passed
     * through unchanged. When it's on, a new configuration with the
     * shrunk dimensions is constructed and stored in
     * {@link #configuration}; Minecraft reads these dimensions when
     * creating its main render target texture, so the game shades at the
     * lower internal resolution.
     */
    private void applyInternalResolution(final GpuSurface.Configuration config) {
        MetalFxConfig fxConfig = MetalFxConfig.get();
        MetalFxConfig.SpatialMode mode = fxConfig.spatialMode();
        this.appliedSpatialMode = mode;

        int targetWidth = this.displayWidth;
        int targetHeight = this.displayHeight;
        // Temporal upscaling reuses the spatial mode's render scale to shrink
        // the internal resolution — it produces a higher-quality result than
        // spatial but still needs sourceWidth != outputWidth to be useful.
        boolean upscalingActive = fxConfig.isSpatialUpscalingActive()
                || fxConfig.isTemporalUpscalingActive();
        if (mode != MetalFxConfig.SpatialMode.OFF && upscalingActive) {
            float scale = mode.renderScale;
            targetWidth = Math.max(1, Math.round(this.displayWidth * scale));
            targetHeight = Math.max(1, Math.round(this.displayHeight * scale));

            // MTLFXSpatialScaler / MTLFXTemporalScaler require output <= 3 * input
            // (per axis). When renderScale is too low (e.g. ULTRA_PERFORMANCE at
            // 0.33 -> 3.03x upscale), the scaler's makeSpatialScaler/makeTemporalScaler
            // throws and returns nil, which previously froze the frame because the
            // Java side misread MemorySegment.NULL as a valid handle. Clamp the
            // internal resolution to at least 1/3 of the display so the upscale
            // ratio is exactly 3.0x — the limit MetalFX accepts. The FPS cost is
            // negligible (internal resolution grows by ~1% at the 33% tier).
            int minInputWidth = (int) Math.ceil(this.displayWidth / 3.0);
            int minInputHeight = (int) Math.ceil(this.displayHeight / 3.0);
            if (targetWidth < minInputWidth || targetHeight < minInputHeight) {
                int clampedW = Math.max(targetWidth, minInputWidth);
                int clampedH = Math.max(targetHeight, minInputHeight);
                Metallum.LOGGER.info(
                        "[MetalFX] clamped internal resolution {}x{} -> {}x{} (min 1/3 of display {}x{}, mode={})",
                        targetWidth, targetHeight, clampedW, clampedH, this.displayWidth, this.displayHeight, mode);
                targetWidth = clampedW;
                targetHeight = clampedH;
            }

            String modeLabel = fxConfig.isTemporalUpscalingActive() ? "temporal" : "spatial";
            Metallum.LOGGER.info(
                    "[MetalFX] {} upscaling active: spatialMode={} renderScale={} internal={}x{} display={}x{}",
                    modeLabel, mode, scale, targetWidth, targetHeight, this.displayWidth, this.displayHeight);
        }

        this.internalWidth = targetWidth;
        this.internalHeight = targetHeight;

        if (targetWidth == config.width() && targetHeight == config.height()) {
            // No shrink needed — keep the original Configuration object.
            this.configuration = config;
        } else {
            // Construct a shrunk Configuration so Minecraft creates a
            // smaller main render target. GpuSurface.Configuration is a
            // record (width, height, presentMode) in Blaze3D 26.2.
            this.configuration = new GpuSurface.Configuration(
                    targetWidth, targetHeight, config.presentMode()
            );
        }
    }

    /**
     * Re-applies the internal resolution if the user has changed the
     * MetalFX spatial mode since the last {@link #configure} call.
     * Minecraft only calls {@code configure} on window resize / video
     * settings change, so without this hook the render target wouldn't
     * shrink until the user resized the window.
     *
     * <p>Called from {@link #acquireNextTexture} (once per frame, on the
     * render thread). If the spatial mode hasn't changed, this is a cheap
     * reference-equality check. If it has, we recompute the internal
     * resolution and update {@link #configuration} in place — the
     * drawable size is left untouched because the display resolution
     * hasn't changed.
     */
    private void reconfigureIfSpatialModeChanged() {
        if (this.displayWidth <= 0 || this.displayHeight <= 0) {
            return;
        }
        MetalFxConfig.SpatialMode current = MetalFxConfig.get().spatialMode();
        if (current == this.appliedSpatialMode) {
            return;
        }
        // Mode changed. Rebuild a synthetic Configuration from the stored
        // display dimensions + present mode and re-apply the internal
        // resolution math. We can't reuse the original Configuration
        // object because it may have been the un-shrunk one.
        GpuSurface.PresentMode mode = this.configuration != null
                ? this.configuration.presentMode()
                : GpuSurface.PresentMode.FIFO;
        GpuSurface.Configuration synthetic = new GpuSurface.Configuration(
                this.displayWidth, this.displayHeight, mode
        );
        applyInternalResolution(synthetic);
        Metallum.LOGGER.info(
                "[MetalFX] reconfigured surface for new spatial mode: internal={}x{} display={}x{}",
                this.internalWidth, this.internalHeight, this.displayWidth, this.displayHeight);
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() {
        // Per-frame hook: pick up MetalFX config changes without waiting
        // for Minecraft to call configure() (which only happens on
        // window resize / video settings change).
        reconfigureIfSpatialModeChanged();
    }

    @Override
    public void blitFromTexture(final @NonNull CommandEncoderBackend commandEncoder, final @NonNull GpuTextureView textureView) {
        if (!(commandEncoder instanceof MetalCommandEncoder metalEncoder)) {
            throw new IllegalArgumentException("Metal surface requires MetalCommandEncoder");
        }

        // outputWidth/Height = display resolution (full size). This is
        // what MetalFX will upscale TO. The source texture (textureView)
        // is at internalWidth x internalHeight when spatial upscaling is
        // active, so source != output and MetalFX actually runs.
        metalEncoder.presentTextureToDrawable(metalLayer, textureView, outputWidth(), outputHeight());
        this.pendingPresentEncoder = metalEncoder;
    }

    @Override
    public void present() {
        pendingPresentEncoder.submit();
    }

    @Override
    public void close() {
    }

    @Override
    public @NonNull Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return SUPPORTED_PRESENT_MODES;
    }

    /**
     * Returns the <b>display</b> (full) width — the resolution MetalFX
     * upscales to and the size of the CAMetalLayer drawable. This is
     * deliberately NOT {@code configuration.width()} when spatial
     * upscaling is active, because the configuration has been shrunk to
     * the internal resolution to make Minecraft render at lower res.
     */
    private int outputWidth() {
        return displayWidth;
    }

    private int outputHeight() {
        return displayHeight;
    }
}
