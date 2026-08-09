package com.metallum.client.metal;

import com.metallum.Metallum;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * Forge Configuration-backed settings for the Metal backend.
 *
 * <p>The 1.12.2 port intentionally does not expose MetalFX options: MetalFX
 * (spatial/temporal upscaling and frame generation) is a macOS 13+ quality
 * enhancement that is unavailable on iOS and is not part of the core "make the
 * game render through Metal" path. The native bridge still declares the
 * MetalFX entry points so a future macOS-only enhancement layer can use them,
 * but nothing in the runtime calls them.</p>
 */
public final class MetalConfig {

    private static Configuration config;

    public static boolean metalHud = false;
    public static boolean optiFineCompat = true;

    public static void load(File file) {
        config = new Configuration(file);
        try {
            config.load();
            metalHud = config.getBoolean("metalHud", "metal", false,
                    "Show Apple's Metal performance overlay. Restart the game after changing this option.");
            optiFineCompat = config.getBoolean("optiFineCompat", "compat", true,
                    "Enable OptiFine compatibility handling (fake GL capability probes so OptiFine's shader preprocessor loads on the Metal backend).");
        } catch (Exception e) {
            Metallum.LOGGER.error("Failed to load Metallum config, using defaults", e);
        } finally {
            if (config.hasChanged()) {
                config.save();
            }
        }
    }
}
