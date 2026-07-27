package com.metallum.client.metal.fx;

import com.metallum.Metallum;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * User-tunable configuration for the MetalFX upscaler and frame
 * interpolator. Settings persist to {@code config/metallum_fx.properties}
 * next to the game directory so they survive restarts and can be edited
 * out-of-band (e.g. on iOS where the in-game UI may not be reachable if
 * a renderer crashes).
 *
 * <p>The settings are read lazily on first access and reloaded when
 * {@link #reload()} is called (e.g. after the video-settings screen applies
 * a change). All getters are thread-safe via a single volatile holder.
 *
 * <p>Defaults are conservative: spatial upscaling {@code OFF}, frame
 * interpolation {@code OFF}. This preserves the original render path on
 * devices where MetalFX is disabled or unavailable, so the mod never
 * regresses baseline behaviour unless the user opts in.
 */
@Environment(EnvType.CLIENT)
public final class MetalFxConfig {
    /**
     * Spatial upscaling mode. Maps 1:1 to the on-screen cycling control:
     * <ul>
     *   <li>{@link #OFF} — render at native drawable resolution, no MetalFX</li>
     *   <li>{@link #QUALITY} — render at 77% then upscale (sharpest, most cost)</li>
     *   <li>{@link #BALANCED} — render at 67% then upscale (default DLSS-style)</li>
     *   <li>{@link #PERFORMANCE} — render at 56% then upscale (max FPS)</li>
     *   <li>{@link #ULTRA_PERFORMANCE} — render at 33% then upscale (max FPS, blurry)</li>
     * </ul>
     * The render-scale percentages follow the conventional DLSS naming so
     * users familiar with Nvidia's presets can reason about quality.
     */
    public enum SpatialMode {
        OFF(1.0f),
        QUALITY(0.77f),
        BALANCED(0.67f),
        PERFORMANCE(0.56f),
        ULTRA_PERFORMANCE(0.33f);

        public final float renderScale;

        SpatialMode(float renderScale) {
            this.renderScale = renderScale;
        }

        public boolean isEnabled() {
            return this != OFF;
        }
    }

    /**
     * Frame interpolation mode. {@link #OFF} disables the interpolator;
     * {@link #AUTO} uses {@code MTLFXFrameInterpolator} when the device
     * supports it (M3+ / A17 Pro+). {@link #FORCE_BLEND} is retained for
     * config-file backwards compatibility only — it resolves to the same
     * hardware path as {@code AUTO}; the legacy 50/50 blend fallback was
     * removed (unacceptable ghosting on fast-moving first-person content).
     */
    public enum FrameInterpolationMode {
        OFF,
        AUTO,
        FORCE_BLEND
    }

    /**
     * Temporal upscaling mode. When active, replaces the spatial scaler
     * with {@code MTLFXTemporalScaler}, which uses temporal history +
     * motion vectors to produce a higher-quality upscale than the
     * single-frame spatial path.
     *
     * <p>Requires macOS 13.0+ / iOS 16.0+ (Apple Silicon M1+ / A14+).
     * When the device does not support the temporal scaler, the option
     * silently falls back to the spatial scaler selected by
     * {@link #spatialMode}.
     *
     * <p>The motion-vector texture is currently zero-filled (no per-entity
     * motion capture pipeline is wired in yet), so the temporal scaler
     * relies on its internal optical-flow estimate. This still produces
     * noticeably better temporal stability than the spatial scaler on
     * static and slowly-moving scenes, and never regresses baseline
     * behaviour because the spatial fallback remains available.
     */
    public enum TemporalUpscalingMode {
        OFF,
        AUTO
    }

    private static final String CONFIG_FILE_NAME = "metallum_fx.properties";
    private static final String KEY_SPATIAL = "spatialUpscaling";
    private static final String KEY_INTERP = "frameInterpolation";
    private static final String KEY_TEMPORAL = "temporalUpscaling";
    private static final String KEY_ACKNOWLEDGED = "acknowledged";

    private static volatile MetalFxConfig INSTANCE = new MetalFxConfig();

    private volatile SpatialMode spatialMode = SpatialMode.OFF;
    private volatile FrameInterpolationMode interpolationMode = FrameInterpolationMode.OFF;
    private volatile TemporalUpscalingMode temporalMode = TemporalUpscalingMode.OFF;
    /**
     * Whether the user has acknowledged the MetalFX warning dialog at least
     * once. Persisted so the warning only shows the first time the user
     * opens the MetalFX settings. Reset by deleting the config file.
     */
    private volatile boolean acknowledged = false;
    private volatile boolean deviceCapabilitiesQueried = false;
    private volatile boolean spatialSupported = false;
    private volatile boolean temporalSupported = false;
    private volatile boolean interpolationSupported = false;
    private volatile boolean blendSupported = false;
    private volatile String deviceName = "<unknown>";

    private MetalFxConfig() {
    }

    public static MetalFxConfig get() {
        return INSTANCE;
    }

    public SpatialMode spatialMode() {
        return spatialMode;
    }

    public FrameInterpolationMode interpolationMode() {
        return interpolationMode;
    }

    public TemporalUpscalingMode temporalMode() {
        return temporalMode;
    }

    public boolean acknowledged() {
        return acknowledged;
    }

    public void setAcknowledged(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }

    public void setSpatialMode(SpatialMode mode) {
        this.spatialMode = mode;
    }

    public void setInterpolationMode(FrameInterpolationMode mode) {
        this.interpolationMode = mode;
    }

    public void setTemporalMode(TemporalUpscalingMode mode) {
        this.temporalMode = mode;
    }

    /**
     * @return {@code true} if the active spatial mode is enabled <em>and</em>
     * the current device supports {@code MTLFXSpatialScaler}.
     */
    public boolean isSpatialUpscalingActive() {
        return spatialMode.isEnabled() && spatialSupported;
    }

    /**
     * @return {@code true} if temporal upscaling is enabled <em>and</em> the
     * device supports {@code MTLFXTemporalScaler} <em>and</em> a spatial
     * mode is active (temporal upscaling replaces — does not stack on —
     * spatial upscaling, so it needs the same render-scale shrink to be
     * meaningful). When this returns {@code false}, the present path uses
     * the spatial scaler (if active) or the source texture directly.
     */
    public boolean isTemporalUpscalingActive() {
        return temporalMode == TemporalUpscalingMode.AUTO
                && temporalSupported
                && spatialMode.isEnabled();
    }

    /**
     * @return {@code true} if frame interpolation is enabled <em>and</em>
     * the device supports the hardware {@code MTLFXFrameInterpolator}
     * path (M3+ / A17 Pro+).
     *
     * <p>The legacy 50/50 blend fallback was removed because it produced
     * unacceptable ghosting on fast-moving first-person content. Both
     * {@link FrameInterpolationMode#AUTO} and
     * {@link FrameInterpolationMode#FORCE_BLEND} now resolve to "use the
     * hardware path if available, otherwise off"; {@code FORCE_BLEND} is
     * retained only for config-file backwards compatibility (a user with an
     * old {@code frameInterpolation=FORCE_BLEND} line in their properties
     * file still gets hardware interpolation rather than a parse failure).
     */
    public boolean isFrameInterpolationActive() {
        if (interpolationMode == FrameInterpolationMode.OFF) {
            return false;
        }
        return interpolationSupported;
    }

    /**
     * Whether the path that runs {@code MTLFXFrameInterpolator} should be
     * taken. Kept as a distinct hook from {@link #isFrameInterpolationActive()}
     * so future non-hardware fallbacks can plug in here without touching
     * every call site. Currently equivalent to
     * {@code isFrameInterpolationActive()}.
     */
    public boolean usesMtlFxInterpolator() {
        return isFrameInterpolationActive();
    }

    public boolean spatialSupported() {
        return spatialSupported;
    }

    public boolean temporalSupported() {
        return temporalSupported;
    }

    public boolean interpolationSupported() {
        return interpolationSupported;
    }

    public boolean blendSupported() {
        return blendSupported;
    }

    public String deviceName() {
        return deviceName;
    }

    /**
     * Queries the Metal device for MetalFX capability and caches the result.
     * Called once after the Metal device is created in {@code MetalBackend}.
     * Subsequent calls are no-ops. Safe to call from any thread.
     *
     * @param deviceHandle the raw {@code MTLDevice} pointer, obtained from
     *                     {@code MetalDevice.metalDeviceHandle()}. Passed as
     *                     an opaque {@link MemorySegment} so this class does
     *                     not need to reference the package-private
     *                     {@code MetalDevice} type.
     */
    public void queryDeviceCapabilities(java.lang.foreign.MemorySegment deviceHandle) {
        if (deviceCapabilitiesQueried) {
            return;
        }
        synchronized (this) {
            if (deviceCapabilitiesQueried) {
                return;
            }
            try {
                deviceName = com.metallum.client.metal.render.bridge.MetalNativeBridge.metallum_copy_device_name(deviceHandle);
                spatialSupported = com.metallum.client.metal.render.bridge.MetalNativeBridge.metallum_fx_supports_spatial_scaler(deviceHandle);
                temporalSupported = com.metallum.client.metal.render.bridge.MetalNativeBridge.metallum_fx_supports_temporal_scaler(deviceHandle);
                interpolationSupported = com.metallum.client.metal.render.bridge.MetalNativeBridge.metallum_fx_supports_frame_interpolation(deviceHandle);
                // The frame-blend fallback is a pure Metal compute path —
                // available on every Metal device we support. The only
                // failure mode is shader compilation, which is rare.
                blendSupported = true;
                Metallum.LOGGER.info(
                        "[MetalFX] device='{}' spatial={} temporal={} interpolation={} blend={}",
                        deviceName, spatialSupported, temporalSupported, interpolationSupported, blendSupported);
            } catch (Throwable t) {
                Metallum.LOGGER.warn("[MetalFX] Failed to query device capabilities; MetalFX disabled", t);
                spatialSupported = false;
                temporalSupported = false;
                interpolationSupported = false;
                blendSupported = false;
            } finally {
                deviceCapabilitiesQueried = true;
            }
        }
    }

    /**
     * Loads the configuration from disk, replacing in-memory values. Called
     * at startup and after the video-settings screen applies a change.
     */
    public static synchronized void reload() {
        MetalFxConfig cfg = new MetalFxConfig();
        Path configPath = configPath();
        if (Files.exists(configPath)) {
            Properties props = new Properties();
            try (var in = Files.newInputStream(configPath)) {
                props.load(in);
            } catch (IOException e) {
                Metallum.LOGGER.warn("[MetalFX] Failed to load config from {}: {}", configPath, e.getMessage());
            }
            cfg.spatialMode = parseEnum(props.getProperty(KEY_SPATIAL), SpatialMode.OFF, SpatialMode.class);
            cfg.interpolationMode = parseEnum(props.getProperty(KEY_INTERP), FrameInterpolationMode.OFF, FrameInterpolationMode.class);
            cfg.temporalMode = parseEnum(props.getProperty(KEY_TEMPORAL), TemporalUpscalingMode.OFF, TemporalUpscalingMode.class);
            cfg.acknowledged = Boolean.parseBoolean(props.getProperty(KEY_ACKNOWLEDGED, "false"));
        }
        // Preserve previously-queried device capabilities across reloads so
        // changing a setting in-game doesn't force a re-query (the Metal
        // device doesn't change while the game is running).
        MetalFxConfig prev = INSTANCE;
        cfg.deviceCapabilitiesQueried = prev.deviceCapabilitiesQueried;
        cfg.spatialSupported = prev.spatialSupported;
        cfg.temporalSupported = prev.temporalSupported;
        cfg.interpolationSupported = prev.interpolationSupported;
        cfg.blendSupported = prev.blendSupported;
        cfg.deviceName = prev.deviceName;
        cfg.acknowledged = prev.acknowledged || cfg.acknowledged;
        INSTANCE = cfg;
    }

    /**
     * Persists the current in-memory configuration to disk. Called when the
     * video-settings screen applies a change.
     */
    public static synchronized void save() {
        MetalFxConfig cfg = INSTANCE;
        Properties props = new Properties();
        props.setProperty(KEY_SPATIAL, cfg.spatialMode.name());
        props.setProperty(KEY_INTERP, cfg.interpolationMode.name());
        props.setProperty(KEY_TEMPORAL, cfg.temporalMode.name());
        props.setProperty(KEY_ACKNOWLEDGED, Boolean.toString(cfg.acknowledged));
        Path configPath = configPath();
        try {
            Files.createDirectories(configPath.getParent());
            try (var out = Files.newOutputStream(configPath)) {
                props.store(out, "MetalUniversal MetalFX configuration");
            }
        } catch (IOException e) {
            Metallum.LOGGER.warn("[MetalFX] Failed to save config to {}: {}", configPath, e.getMessage());
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getGameDir().resolve("config").resolve(CONFIG_FILE_NAME);
    }

    private static <T extends Enum<T>> T parseEnum(String value, T defaultValue, Class<T> enumClass) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }
}
