package com.metallum.client.metal.fx;

import com.metallum.Metallum;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 启动时的系统环境检测。在 {@link MetalFxConfig#queryDeviceCapabilities}
完成后调用，汇总打印操作系统版本、芯片型号、MetalFX 各项特性支持情况，
 * 并在不符合 Apple 官方要求时给出明确警告。
 *
 * <p>检测维度：
 * <ul>
 *   <li>操作系统 — macOS 13.0+ (Spatial/Temporal)、26.0+ (Frame Interpolator)</li>
 *   <li>芯片 — Apple Silicon M1/A14+ (GPU family 7+)、M3/A17 Pro+ (family 9+)</li>
 *   <li>MetalFX 能力 — 由 native bridge 查询的实际设备支持情况</li>
 * </ul>
 *
 * <p>本类只做检测和日志输出，不阻止游戏启动。即使不满足要求，
 * {@link MetalFxConfig} 的各 {@code is*Active()} 方法也会因为
 * {@code *Supported} 为 false 而自动跳过对应功能。
 */
@Environment(EnvType.CLIENT)
final class MetalFxSystemCheck {

    /**
     * macOS 版本下限。Spatial/Temporal Scaler 需要 13.0，
     * Frame Interpolator 需要 26.0（MTLFXFrameInterpolator 硬件路径
     * 在 macOS 26 / iOS 26 起稳定可用）。
     */
    private static final int MACOS_SPATIAL_MIN_MAJOR = 13;
    private static final int MACOS_INTERP_MIN_MAJOR = 26;

    private MetalFxSystemCheck() {
    }

    /**
     * 执行系统检测并打印汇总日志。仅在设备能力查询完成后调用一次。
     */
    static void run(MetalFxConfig cfg) {
        String osName = System.getProperty("os.name", "unknown");
        String osVersion = System.getProperty("os.version", "unknown");
        String osArch = System.getProperty("os.arch", "unknown");
        String deviceName = cfg.deviceName();

        boolean isMacOS = osName.toLowerCase().contains("mac");
        int macosMajor = parseMacOSMajor(osVersion);

        Metallum.LOGGER.info("[MetalFX] ===== 系统环境检测 =====");
        Metallum.LOGGER.info("[MetalFX] 操作系统: {} {}", osName, osVersion);
        Metallum.LOGGER.info("[MetalFX] 架构: {}", osArch);
        Metallum.LOGGER.info("[MetalFX] 芯片: {}", deviceName);
        Metallum.LOGGER.info("[MetalFX] MetalFX 能力: spatial={} temporal={} interpolation={} blend={}",
                cfg.spatialSupported(), cfg.temporalSupported(),
                cfg.interpolationSupported(), cfg.blendSupported());

        // --- 操作系统版本校验 ---
        if (isMacOS) {
            if (macosMajor > 0 && macosMajor < MACOS_SPATIAL_MIN_MAJOR) {
                Metallum.LOGGER.warn("[MetalFX] macOS {} 低于 MetalFX 最低要求 ({}.0+)，空间/时间超分不可用",
                        osVersion, MACOS_SPATIAL_MIN_MAJOR);
            } else if (macosMajor > 0 && macosMajor < MACOS_INTERP_MIN_MAJOR) {
                Metallum.LOGGER.warn("[MetalFX] macOS {} 低于帧插值最低要求 ({}.0+)，帧插值不可用",
                        osVersion, MACOS_INTERP_MIN_MAJOR);
            } else if (macosMajor > 0) {
                Metallum.LOGGER.info("[MetalFX] macOS 版本满足全部 MetalFX 要求");
            }
        } else {
            Metallum.LOGGER.warn("[MetalFX] 当前操作系统 ({}) 不是 macOS，MetalFX 仅支持 macOS/iOS", osName);
        }

        // --- 芯片架构校验（Apple Silicon） ---
        if (!"aarch64".equals(osArch) && !"arm64".equals(osArch)) {
            Metallum.LOGGER.warn("[MetalFX] 架构 {} 不是 Apple Silicon (aarch64)，MetalFX 需要 M1/A14 及以上",
                    osArch);
        }

        // --- MetalFX 能力与功能开关交叉校验 ---
        if (cfg.spatialMode().isEnabled() && !cfg.spatialSupported()) {
            Metallum.LOGGER.warn("[MetalFX] 空间超分已开启但设备不支持，将自动回退到原生渲染");
        }
        if (cfg.isTemporalUpscalingActive() && !cfg.temporalSupported()) {
            Metallum.LOGGER.warn("[MetalFX] 时间超分已开启但设备不支持，将自动回退到空间超分");
        }
        if (cfg.isFrameInterpolationActive() && !cfg.interpolationSupported()) {
            Metallum.LOGGER.warn("[MetalFX] 帧插值已开启但设备不支持 (需 M3+/A17 Pro+)，将自动关闭");
        }

        Metallum.LOGGER.info("[MetalFX] ===== 系统环境检测完成 =====");
    }

    /**
     * 从 macOS 版本字符串（如 "15.7.3"、"14.0"）解析主版本号。
     * 非 macOS 或无法解析时返回 -1。
     */
    private static int parseMacOSMajor(String osVersion) {
        if (osVersion == null || osVersion.isBlank()) {
            return -1;
        }
        String first = osVersion.split("\\.")[0];
        try {
            return Integer.parseInt(first);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
