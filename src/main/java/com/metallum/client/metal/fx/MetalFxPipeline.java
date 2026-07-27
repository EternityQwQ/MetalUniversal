package com.metallum.client.metal.fx;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * Owns the native MetalFX scaler / interpolator handles and the intermediate
 * textures used by the present path. One instance lives per
 * {@code MetalDevice} and is recreated lazily when the source or drawable
 * resolution changes.
 *
 * <p>The pipeline runs in two stages during {@code MetalSurface.present()}:
 * <ol>
 *   <li><b>Spatial upscale</b> (optional): if the user enabled a spatial mode
 *       and the device supports {@code MTLFXSpatialScaler}, the source color
 *       texture is upscaled from the internal render resolution to the
 *       drawable's resolution into {@link #upscaledColorTexture}.</li>
 *   <li><b>Frame interpolation</b> (optional): if frame interpolation is on
 *       and the device supports {@code MTLFXFrameInterpolator} (M3+ /
 *       A17 Pro+), a synthetic intermediate frame is generated between the
 *       previous and current frame using the hardware interpolator.</li>
 * </ol>
 *
 * <p>When no MetalFX feature is active, {@link #maybeEncode} is a no-op and
 * the caller presents the source texture directly to the drawable —
 * preserving the legacy render path.
 *
 * <p><b>Why this is now actually useful.</b> Previously the spatial scaler
 * was a no-op because {@code MetalSurface} reported the full display
 * resolution as the internal resolution, so {@code sourceWidth == outputWidth}
 * and the scaler was skipped. {@code MetalSurface} now shrinks the reported
 * internal resolution when spatial upscaling is active, so the game renders
 * at e.g. 77% resolution and MetalFX upscales to 100% — yielding real FPS
 * gains on fragment-bound scenes.
 *
 * <p>The 50/50 blend fallback for frame interpolation has been removed: it
 * produced severe ghosting on fast-moving first-person content (the exact
 * use case where users want interpolation). Frame interpolation now
 * requires the hardware {@code MTLFXFrameInterpolator} path, which is only
 * available on M3+ / A17 Pro+ devices. On older devices the option
 * silently does nothing (the config screen reports "not supported").
 */
@Environment(EnvType.CLIENT)
public final class MetalFxPipeline {
    private final MemorySegment deviceHandle;

    @Nullable
    private MemorySegment spatialScaler = null;
    @Nullable
    private MemorySegment temporalScaler = null;
    @Nullable
    private MemorySegment frameInterpolator = null;

    // Intermediate textures. Recreated when the resolution changes. Each
    // carries ShaderRead|ShaderWrite so MetalFX can encode into it and the
    // next pass can sample it.
    @Nullable
    private MemorySegment upscaledColorTexture = null;
    @Nullable
    private MemorySegment previousColorTexture = null;
    @Nullable
    private MemorySegment interpolationOutputTexture = null;
    @Nullable
    private MemorySegment motionVectorTexture = null;
    // Separate motion texture for the temporal scaler, which requires
    // source-resolution motion vectors (inputWidth x inputHeight). The
    // frame interpolator above uses output-resolution motion vectors
    // (outputWidth x outputHeight). Sharing one texture between the two
    // would force a recreate every frame when both features are active,
    // so each path owns its own. Both are zero-filled — no per-entity
    // motion is wired in yet — so MetalFX falls back to its internal
    // optical-flow estimate.
    @Nullable
    private MemorySegment temporalMotionTexture = null;

    // Tracked dimensions so we only recreate textures/handles when they change.
    private int cachedInputWidth = -1;
    private int cachedInputHeight = -1;
    private int cachedOutputWidth = -1;
    private int cachedOutputHeight = -1;
    // upscaledColorTexture has its own cache because ensureSpatialScaler
    // (called just before ensureUpscaledTexture) updates cachedOutputWidth,
    // which would otherwise make ensureUpscaledTexture skip rebuilding when
    // only the output resolution changed.
    private int cachedUpscaledWidth = -1;
    private int cachedUpscaledHeight = -1;
    private boolean previousFrameValid = false;
    private boolean loggedSpatialActive = false;
    private boolean loggedTemporalActive = false;
    private boolean loggedInterpActive = false;
    // Motion-vector texture has its own dimension cache because it is shared
    // between the temporal scaler (which wants source-resolution motion) and
    // the frame interpolator (which wants output-resolution motion). When
    // both are active we keep the larger of the two so neither path reads
    // out-of-bounds. Tracked separately from cachedInputWidth/Height and
    // cachedOutputWidth/Height so resizing one path does not spuriously
    // invalidate the other.
    private int cachedMotionWidth = -1;
    private int cachedMotionHeight = -1;
    // Temporal motion texture dimensions (source resolution, distinct from
    // the interpolator's output-resolution motion texture above).
    private int cachedTemporalMotionWidth = -1;
    private int cachedTemporalMotionHeight = -1;
    // Tracks whether the temporal motion texture has been zeroed. Independent
    // from motionVectorCleared (which tracks the interpolator's texture)
    // because the two textures have different lifetimes.
    private boolean temporalMotionCleared = false;
    // Tracks whether the motion-vector texture has been zeroed. Private-storage
    // textures start with undefined contents; feeding garbage RG32Float motion
    // vectors into MTLFXFrameInterpolator crashes the encoder on stricter
    // drivers (e.g. M5 Max). Cleared once after creation.
    private boolean motionVectorCleared = false;
    // True until the first successful interpolator encode completes. The first
    // encode must set shouldResetHistory=true so the interpolator initialises
    // its internal history from the provided color+previous pair rather than
    // reading stale/uninitialised history.
    private boolean firstInterpFrame = true;
    // True until the first successful temporal scaler encode completes. The
    // first encode must pass reset=1 so the scaler seeds its internal history
    // from the provided color frame instead of reading uninitialised history.
    private boolean firstTemporalFrame = true;
    // Halton jitter phase counter for the temporal scaler. Advances each
    // frame to provide sub-pixel sampling diversity that the temporal
    // reconstruction uses to recover detail beyond the render resolution.
    private int temporalJitterPhase = 0;

    // BGRA8Unorm is the format CAMetalLayer drawables use on Apple platforms;
    // the present pipeline in MetallumNative.swift is hardwired to it.
    private static final long COLOR_FORMAT = MTLPixelFormat.BGRA8Unorm.value;
    // Motion vectors are 2-component floats (x, y) per texel — this matches
    // MTLFXFrameInterpolator's required motion vector format.
    private static final MTLPixelFormat MOTION_FORMAT_ENUM = MTLPixelFormat.RG32Float;
    private static final long USAGE_SHADER_RW =
            MTLTextureUsage.ShaderRead.value | MTLTextureUsage.ShaderWrite.value;

    /**
     * @param deviceHandle the raw {@code MTLDevice} pointer. Passed as an
     *                     opaque {@link MemorySegment} so this class does not
     *                     need to reference the package-private
     *                     {@code MetalDevice} type.
     */
    public MetalFxPipeline(MemorySegment deviceHandle) {
        this.deviceHandle = deviceHandle;
    }

    private MemorySegment deviceHandle() {
        return deviceHandle;
    }

    /**
     * Encodes the MetalFX passes (if any) and returns the texture that should
     * be presented to the drawable. If neither spatial upscaling nor frame
     * interpolation is active, returns {@code sourceTexture} unchanged.
     *
     * @param commandBuffer the active MTLCommandBuffer
     * @param sourceTexture  the frame the game just rendered (at internal res)
     * @param sourceWidth    width of {@code sourceTexture} in texels
     * @param sourceHeight   height of {@code sourceTexture} in texels
     * @param outputWidth    drawable width (target resolution)
     * @param outputHeight   drawable height (target resolution)
     * @return the texture to present, or {@code sourceTexture} if MetalFX is off
     */
    public MemorySegment maybeEncode(
            MemorySegment commandBuffer,
            MemorySegment sourceTexture,
            int sourceWidth, int sourceHeight,
            int outputWidth, int outputHeight
    ) {
        MetalFxConfig cfg = MetalFxConfig.get();
        MemorySegment currentFrame = sourceTexture;

        // ---- Stage 1: upscaling (temporal OR spatial) --------------------
        // Temporal upscaling replaces spatial upscaling when enabled: it
        // produces a higher-quality result by integrating information across
        // frames. When temporal is off or unsupported, we fall back to the
        // spatial scaler. Both paths require sourceWidth != outputWidth
        // (guaranteed by MetalSurface shrinking the internal resolution).
        boolean needsUpscale = (cfg.isSpatialUpscalingActive() || cfg.isTemporalUpscalingActive())
                && sourceWidth > 0 && sourceHeight > 0
                && outputWidth > 0 && outputHeight > 0
                && (sourceWidth != outputWidth || sourceHeight != outputHeight);

        if (needsUpscale) {
            ensureUpscaledTexture(outputWidth, outputHeight);
            if (cfg.isTemporalUpscalingActive()) {
                // Temporal path: uses motion vectors + Halton jitter + history.
                // Falls back to spatial on any failure.
                ensureTemporalScaler(sourceWidth, sourceHeight, outputWidth, outputHeight);
                ensureTemporalMotionTexture(sourceWidth, sourceHeight);
                if (temporalScaler != null && upscaledColorTexture != null) {
                    try {
                        if (!temporalMotionCleared && temporalMotionTexture != null) {
                            // metallum_fx_clear_texture returns false if the
                            // render-command-encoder creation failed (e.g.
                            // texture lacks RenderTarget usage). On failure we
                            // leave temporalMotionCleared=false so the next
                            // frame retries; we do NOT abort the temporal
                            // encode, because the scaler tolerates a zero
                            // motion texture on its first frame (reset=1).
                            try {
                                if (MetalNativeBridge.metallum_fx_clear_texture(
                                        commandBuffer, temporalMotionTexture)) {
                                    temporalMotionCleared = true;
                                } else {
                                    Metallum.LOGGER.warn(
                                            "[MetalFX] temporal motion clear returned 0; will retry next frame");
                                }
                            } catch (Throwable clearErr) {
                                Metallum.LOGGER.warn(
                                        "[MetalFX] failed to clear temporal motion texture", clearErr);
                            }
                        }
                        // Halton jitter in render-resolution pixels. The
                        // temporal scaler integrates this sub-pixel offset
                        // across frames to recover detail beyond the render
                        // resolution. Phase advances every frame.
                        float phaseCount = 8.0f; // conservative default
                        org.joml.Vector2f jitter = com.metallum.client.metal.render.MetalFxMath
                                .pixelJitter(temporalJitterPhase, (int) phaseCount);
                        org.joml.Vector2f mvScale = com.metallum.client.metal.render.MetalFxMath
                                .motionVectorScale(sourceWidth, sourceHeight);
                        int reset = firstTemporalFrame ? 1 : 0;
                        MetalNativeBridge.metallum_fx_temporal_scaler_encode(
                                temporalScaler,
                                commandBuffer,
                                sourceTexture,
                                MemorySegment.NULL, // prevColor: scaler manages history internally
                                temporalMotionTexture != null ? temporalMotionTexture : MemorySegment.NULL,
                                MemorySegment.NULL, // depth: not wired in yet (optional)
                                upscaledColorTexture,
                                jitter.x(), jitter.y(),
                                mvScale.x(), mvScale.y(),
                                reset
                        );
                        currentFrame = upscaledColorTexture;
                        firstTemporalFrame = false;
                        temporalJitterPhase++;
                        if (!loggedTemporalActive) {
                            Metallum.LOGGER.info(
                                    "[MetalFX] temporal scaler RUNNING: {}x{} -> {}x{} (mode={})",
                                    sourceWidth, sourceHeight, outputWidth, outputHeight, cfg.spatialMode());
                            loggedTemporalActive = true;
                        }
                    } catch (Throwable t) {
                        Metallum.LOGGER.warn("[MetalFX] temporal scaler encode failed; falling back to spatial", t);
                        currentFrame = encodeSpatialFallback(commandBuffer, sourceTexture,
                                sourceWidth, sourceHeight, outputWidth, outputHeight, cfg);
                    }
                } else {
                    currentFrame = encodeSpatialFallback(commandBuffer, sourceTexture,
                            sourceWidth, sourceHeight, outputWidth, outputHeight, cfg);
                }
            } else {
                // Spatial path (default when temporal is off).
                currentFrame = encodeSpatialFallback(commandBuffer, sourceTexture,
                        sourceWidth, sourceHeight, outputWidth, outputHeight, cfg);
            }
        } else {
            if (loggedSpatialActive && !cfg.isSpatialUpscalingActive()) {
                loggedSpatialActive = false;
            }
            if (loggedTemporalActive && !cfg.isTemporalUpscalingActive()) {
                loggedTemporalActive = false;
                firstTemporalFrame = true;
                temporalJitterPhase = 0;
                // Force re-clear of the temporal motion texture on next use,
                // since it may have been sitting idle with stale contents.
                temporalMotionCleared = false;
            }
        }

        // ---- Stage 2: hardware frame interpolation ------------------------
        // Only runs on M3+ / A17 Pro+ (MTLFXFrameInterpolator support).
        // The 50/50 blend fallback was removed due to unacceptable
        // ghosting on fast-moving content. On unsupported devices
        // isFrameInterpolationActive() returns false, so this entire
        // block is skipped and the user sees no interpolation.
        if (cfg.isFrameInterpolationActive() && cfg.usesMtlFxInterpolator()
                && outputWidth > 0 && outputHeight > 0) {
            ensureInterpolationOutput(outputWidth, outputHeight);
            ensurePreviousTexture(outputWidth, outputHeight);
            ensureMotionVectorTexture(outputWidth, outputHeight);
            if (interpolationOutputTexture != null && previousColorTexture != null) {
                if (frameInterpolator == null) {
                    frameInterpolator = MetalNativeBridge.metallum_fx_create_frame_interpolator(
                            deviceHandle(), outputWidth, outputHeight, COLOR_FORMAT
                    );
                }
                if (frameInterpolator != null) {
                    // Zero the motion-vector texture once after creation. The
                    // texture uses Private storage, which has undefined initial
                    // contents — feeding garbage RG32Float motion vectors into
                    // MTLFXFrameInterpolator crashes the encoder on stricter
                    // drivers (e.g. M5 Max). The texture is only ever read by
                    // the interpolator (we never write new motion vectors), so
                    // a single clear keeps it zero for its lifetime.
                    if (!motionVectorCleared && motionVectorTexture != null) {
                        // Match the temporal path: check the return value so a
                        // failed clear (e.g. encoder creation failure) leaves
                        // motionVectorCleared=false and retries next frame.
                        // Marking it cleared on failure would feed garbage
                        // RG32Float motion vectors to the interpolator on the
                        // next encode — the exact crash this clear exists to
                        // prevent on stricter drivers (M5 Max).
                        try {
                            if (MetalNativeBridge.metallum_fx_clear_texture(
                                    commandBuffer, motionVectorTexture)) {
                                motionVectorCleared = true;
                            } else {
                                Metallum.LOGGER.warn(
                                        "[MetalFX] motion vector clear returned 0; will retry next frame");
                            }
                        } catch (Throwable t) {
                            Metallum.LOGGER.warn("[MetalFX] failed to clear motion vector texture", t);
                        }
                    }
                    boolean encoded = false;
                    try {
                        if (previousFrameValid) {
                            // Encode the hardware interpolator. We pass a
                            // zero-filled motion vector texture (no engine
                            // motion vectors available) — MTLFXFrameInterpolator
                            // falls back to its internal optical-flow estimate
                            // when motion vectors are zero, which is still
                            // far better than a naive 50/50 blend.
                            //
                            // The reset argument maps to the interpolator's
                            // shouldResetHistory. It must be 1 on the first
                            // encode after the previous-frame seed so the
                            // interpolator initialises its internal history
                            // from the provided color+previous pair instead of
                            // reading uninitialised history (crash on M5 Max).
                            int reset = firstInterpFrame ? 1 : 0;
                            MetalNativeBridge.metallum_fx_frame_interpolator_encode(
                                    frameInterpolator,
                                    commandBuffer,
                                    currentFrame,
                                    previousColorTexture,
                                    motionVectorTexture != null ? motionVectorTexture : MemorySegment.NULL,
                                    interpolationOutputTexture,
                                    1.0f, 1.0f,
                                    reset
                            );
                            encoded = true;
                            firstInterpFrame = false;
                        }
                        if (encoded) {
                            // Save the pre-interpolation frame (source or
                            // upscaled) before reassigning currentFrame.
                            MemorySegment preInterpFrame = currentFrame;
                            currentFrame = interpolationOutputTexture;
                            // Blit the pre-interpolation frame into
                            // previousColorTexture for use as "previous" on
                            // the next iteration. We can't hold the source or
                            // upscaled texture across frames (they may be
                            // overwritten next frame), so we copy into our
                            // own previousColorTexture.
                            //
                            // The previous swap-based approach was buggy: after
                            // the swap, previousColorTexture and
                            // interpolationOutputTexture aliased the same
                            // texture, causing the interpolator to read from
                            // and write to the same texture on subsequent
                            // frames — undefined behavior that crashed Metal
                            // validation and made the window disappear.
                            MetalNativeBridge.metallum_fx_encode_frame_blend(
                                    deviceHandle(), commandBuffer,
                                    preInterpFrame, preInterpFrame, previousColorTexture
                            );
                            if (!loggedInterpActive) {
                                Metallum.LOGGER.info(
                                        "[MetalFX] frame interpolation RUNNING (hardware): {}x{}",
                                        outputWidth, outputHeight);
                                loggedInterpActive = true;
                            }
                        }
                    } catch (Throwable t) {
                        Metallum.LOGGER.warn("[MetalFX] frame interpolator encode threw; falling back to source", t);
                    }
                    if (!previousFrameValid) {
                        // First frame: no previous to interpolate from.
                        // Blit current into previous to seed next frame.
                        MetalNativeBridge.metallum_fx_encode_frame_blend(
                                deviceHandle(), commandBuffer,
                                currentFrame, currentFrame, previousColorTexture
                        );
                        previousFrameValid = true;
                    }
                }
            }
        } else if (!cfg.isFrameInterpolationActive()) {
            previousFrameValid = false;
            loggedInterpActive = false;
            // Reset so the next time interpolation is re-enabled, the motion
            // texture is re-cleared and the first encode signals reset.
            motionVectorCleared = false;
            firstInterpFrame = true;
        }

        return currentFrame;
    }

    /**
     * Encodes the spatial scaler as a fallback when temporal upscaling is
     * disabled or failed. Extracted from the original inline path so the
     * temporal branch can reuse it without duplicating the
     * ensureSpatialScaler + encode + logging logic.
     */
    private MemorySegment encodeSpatialFallback(
            final MemorySegment commandBuffer,
            final MemorySegment sourceTexture,
            final int sourceWidth, final int sourceHeight,
            final int outputWidth, final int outputHeight,
            final MetalFxConfig cfg
    ) {
        ensureSpatialScaler(sourceWidth, sourceHeight, outputWidth, outputHeight);
        if (spatialScaler != null && upscaledColorTexture != null) {
            try {
                MetalNativeBridge.metallum_fx_spatial_scaler_encode(
                        spatialScaler,
                        commandBuffer,
                        sourceTexture,
                        upscaledColorTexture,
                        0.0f, 0.0f, 1.0f
                );
                if (!loggedSpatialActive) {
                    Metallum.LOGGER.info(
                            "[MetalFX] spatial scaler RUNNING: {}x{} -> {}x{} (mode={})",
                            sourceWidth, sourceHeight, outputWidth, outputHeight, cfg.spatialMode());
                    loggedSpatialActive = true;
                }
                return upscaledColorTexture;
            } catch (Throwable t) {
                Metallum.LOGGER.warn("[MetalFX] spatial scaler encode failed; falling back to source", t);
            }
        }
        return sourceTexture;
    }

    private void ensureTemporalScaler(int inW, int inH, int outW, int outH) {
        if (temporalScaler != null
                && cachedInputWidth == inW && cachedInputHeight == inH
                && cachedOutputWidth == outW && cachedOutputHeight == outH) {
            return;
        }
        if (temporalScaler != null) {
            MetalNativeBridge.metallum_release_object(temporalScaler);
            temporalScaler = null;
        }
        // Dimensions changed: the spatial scaler (if cached) is also stale.
        // Release it so the next ensureSpatialScaler rebuilds at the new size
        // instead of passing the cached-dimension check with stale data.
        if (spatialScaler != null
                && (cachedInputWidth != inW || cachedInputHeight != inH
                || cachedOutputWidth != outW || cachedOutputHeight != outH)) {
            MetalNativeBridge.metallum_release_object(spatialScaler);
            spatialScaler = null;
        }
        temporalScaler = MetalNativeBridge.metallum_fx_create_temporal_scaler(
                deviceHandle(),
                inW, inH, outW, outH,
                COLOR_FORMAT, COLOR_FORMAT,
                MOTION_FORMAT_ENUM.value,
                MTLPixelFormat.Depth32Float.value
        );
        cachedInputWidth = inW;
        cachedInputHeight = inH;
        cachedOutputWidth = outW;
        cachedOutputHeight = outH;
        // New scaler instance: must signal reset on first encode.
        firstTemporalFrame = true;
        // Temporal motion texture dimensions may have changed alongside the
        // scaler (source-resolution motion, distinct from the interpolator's
        // output-resolution motion). Reset the temporal clear flag — NOT
        // motionVectorCleared, which tracks the interpolator's texture and
        // has a different lifetime. Resetting the wrong flag here would leave
        // the temporal motion texture with stale contents on a scaler rebuild,
        // or spuriously force the interpolator to re-clear its own texture.
        temporalMotionCleared = false;
    }

    private void ensureSpatialScaler(int inW, int inH, int outW, int outH) {
        if (spatialScaler != null
                && cachedInputWidth == inW && cachedInputHeight == inH
                && cachedOutputWidth == outW && cachedOutputHeight == outH) {
            return;
        }
        if (spatialScaler != null) {
            MetalNativeBridge.metallum_release_object(spatialScaler);
            spatialScaler = null;
        }
        // Dimensions changed: the temporal scaler (if cached) is also stale.
        // Release it so the next ensureTemporalScaler rebuilds at the new size.
        if (temporalScaler != null
                && (cachedInputWidth != inW || cachedInputHeight != inH
                || cachedOutputWidth != outW || cachedOutputHeight != outH)) {
            MetalNativeBridge.metallum_release_object(temporalScaler);
            temporalScaler = null;
            firstTemporalFrame = true;
            motionVectorCleared = false;
        }
        spatialScaler = MetalNativeBridge.metallum_fx_create_spatial_scaler(
                deviceHandle(),
                inW, inH, outW, outH,
                COLOR_FORMAT, COLOR_FORMAT
        );
        cachedInputWidth = inW;
        cachedInputHeight = inH;
        cachedOutputWidth = outW;
        cachedOutputHeight = outH;
    }

    private void ensureUpscaledTexture(int width, int height) {
        if (upscaledColorTexture != null
                && cachedUpscaledWidth == width && cachedUpscaledHeight == height) {
            return;
        }
        if (upscaledColorTexture != null) {
            MetalNativeBridge.metallum_release_object(upscaledColorTexture);
            upscaledColorTexture = null;
        }
        upscaledColorTexture = MetalNativeBridge.metallum_create_texture_2d(
                deviceHandle(),
                MTLPixelFormat.BGRA8Unorm,
                width, height,
                1L, 1L, 0L,
                USAGE_SHADER_RW,
                MTLStorageMode.Private,
                "metallum-fx-upscaled"
        );
        cachedUpscaledWidth = width;
        cachedUpscaledHeight = height;
    }

    private void ensurePreviousTexture(int width, int height) {
        if (previousColorTexture != null
                && cachedOutputWidth == width && cachedOutputHeight == height) {
            return;
        }
        if (previousColorTexture != null) {
            MetalNativeBridge.metallum_release_object(previousColorTexture);
            previousColorTexture = null;
        }
        previousColorTexture = MetalNativeBridge.metallum_create_texture_2d(
                deviceHandle(),
                MTLPixelFormat.BGRA8Unorm,
                width, height,
                1L, 1L, 0L,
                USAGE_SHADER_RW | MTLTextureUsage.RenderTarget.value,
                MTLStorageMode.Private,
                "metallum-fx-previous"
        );
    }

    private void ensureInterpolationOutput(int width, int height) {
        if (interpolationOutputTexture != null
                && cachedOutputWidth == width && cachedOutputHeight == height) {
            return;
        }
        if (interpolationOutputTexture != null) {
            MetalNativeBridge.metallum_release_object(interpolationOutputTexture);
            interpolationOutputTexture = null;
        }
        interpolationOutputTexture = MetalNativeBridge.metallum_create_texture_2d(
                deviceHandle(),
                MTLPixelFormat.BGRA8Unorm,
                width, height,
                1L, 1L, 0L,
                USAGE_SHADER_RW,
                MTLStorageMode.Private,
                "metallum-fx-interp-output"
        );
    }

    private void ensureMotionVectorTexture(int width, int height) {
        if (motionVectorTexture != null
                && cachedMotionWidth == width && cachedMotionHeight == height) {
            return;
        }
        if (motionVectorTexture != null) {
            MetalNativeBridge.metallum_release_object(motionVectorTexture);
            motionVectorTexture = null;
        }
        // RenderTarget is required so metallum_fx_clear_texture can attach the
        // texture to a render pass (loadAction = .clear). ShaderRead|ShaderWrite
        // alone is not sufficient for the clear pass.
        motionVectorTexture = MetalNativeBridge.metallum_create_texture_2d(
                deviceHandle(),
                MOTION_FORMAT_ENUM,
                width, height,
                1L, 1L, 0L,
                USAGE_SHADER_RW | MTLTextureUsage.RenderTarget.value,
                MTLStorageMode.Private,
                "metallum-fx-motion"
        );
        cachedMotionWidth = width;
        cachedMotionHeight = height;
        // New texture: needs a clear before first interpolator use.
        motionVectorCleared = false;
    }

    /**
     * Ensures the temporal scaler's motion texture exists at the given
     * (source-resolution) dimensions. Independent from
     * {@link #ensureMotionVectorTexture} because the temporal scaler needs
     * source-resolution motion while the frame interpolator needs
     * output-resolution motion — sharing one texture would force a recreate
     * every frame when both features are active.
     */
    private void ensureTemporalMotionTexture(int width, int height) {
        if (temporalMotionTexture != null
                && cachedTemporalMotionWidth == width && cachedTemporalMotionHeight == height) {
            return;
        }
        if (temporalMotionTexture != null) {
            MetalNativeBridge.metallum_release_object(temporalMotionTexture);
            temporalMotionTexture = null;
        }
        temporalMotionTexture = MetalNativeBridge.metallum_create_texture_2d(
                deviceHandle(),
                MOTION_FORMAT_ENUM,
                width, height,
                1L, 1L, 0L,
                USAGE_SHADER_RW | MTLTextureUsage.RenderTarget.value,
                MTLStorageMode.Private,
                "metallum-fx-temporal-motion"
        );
        cachedTemporalMotionWidth = width;
        cachedTemporalMotionHeight = height;
        temporalMotionCleared = false;
    }

    /**
     * Releases all native resources. Called when the MetalDevice is closed.
     */
    public void close() {
        if (spatialScaler != null) {
            MetalNativeBridge.metallum_release_object(spatialScaler);
            spatialScaler = null;
        }
        if (temporalScaler != null) {
            MetalNativeBridge.metallum_release_object(temporalScaler);
            temporalScaler = null;
        }
        if (frameInterpolator != null) {
            MetalNativeBridge.metallum_release_object(frameInterpolator);
            frameInterpolator = null;
        }
        if (upscaledColorTexture != null) {
            MetalNativeBridge.metallum_release_object(upscaledColorTexture);
            upscaledColorTexture = null;
        }
        if (previousColorTexture != null) {
            MetalNativeBridge.metallum_release_object(previousColorTexture);
            previousColorTexture = null;
        }
        if (interpolationOutputTexture != null) {
            MetalNativeBridge.metallum_release_object(interpolationOutputTexture);
            interpolationOutputTexture = null;
        }
        if (motionVectorTexture != null) {
            MetalNativeBridge.metallum_release_object(motionVectorTexture);
            motionVectorTexture = null;
        }
        if (temporalMotionTexture != null) {
            MetalNativeBridge.metallum_release_object(temporalMotionTexture);
            temporalMotionTexture = null;
        }
        previousFrameValid = false;
        loggedSpatialActive = false;
        loggedTemporalActive = false;
        loggedInterpActive = false;
        motionVectorCleared = false;
        temporalMotionCleared = false;
        firstInterpFrame = true;
        firstTemporalFrame = true;
        temporalJitterPhase = 0;
        cachedInputWidth = cachedInputHeight = cachedOutputWidth = cachedOutputHeight = -1;
        cachedUpscaledWidth = cachedUpscaledHeight = -1;
        cachedMotionWidth = cachedMotionHeight = -1;
        cachedTemporalMotionWidth = cachedTemporalMotionHeight = -1;
    }
}
