package com.metallum.client.metal;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.gl.GlMetalTranslator;

/**
 * Top-level runtime that owns the Metal device and the GL-over-Metal
 * translation layer for the 1.12.2 port.
 *
 * <p>On macOS / Apple Silicon this extracts and loads the bundled
 * {@code libmetallum.dylib}, creates a Metal device, and activates a
 * {@link GlMetalTranslator} that captures OpenGL state from
 * {@code GlStateManager}/{@code GL11} and records Metal command buffers. On
 * every other platform {@link #bootstrap()} is a no-op and the game keeps its
 * vanilla OpenGL backend, so the mod is safe to ship universally.</p>
 */
public final class MetalRuntime {
    private static volatile boolean activated = false;
    private static volatile boolean metalPlatform = false;
    private static volatile boolean optiFinePresent = false;
    private static volatile boolean optiFineCompatible = false;
    private static GlMetalTranslator translator;

    private MetalRuntime() {
    }

    public static void bootstrap() {
        metalPlatform = detectMetalPlatform();
        optiFinePresent = detectOptiFine();

        if (!metalPlatform) {
            Metallum.LOGGER.info("Non-Metal platform detected ({}); Metallum is inactive.",
                    System.getProperty("os.name"));
            return;
        }
        if (metalPlatform && !optiFinePresent) {
            Metallum.LOGGER.info("OptiFine not detected; using direct Metal translation.");
        } else if (optiFinePresent) {
            Metallum.LOGGER.info("OptiFine detected; GL-over-Metal translation will route OptiFine's GL calls too.");
        }

        try {
            MetalNativeBridge.load();
            if (!MetalNativeBridge.isLoaded()) {
                Metallum.LOGGER.error("libmetallum.dylib could not be loaded; Metal backend inactive.");
                return;
            }
            translator = new GlMetalTranslator();
            boolean ok = translator.init();
            if (!ok) {
                Metallum.LOGGER.error("Metal device/context creation failed; falling back to OpenGL.");
                translator = null;
                return;
            }
            activated = true;
            Metallum.LOGGER.info("Metal backend activated on device: {}", translator.deviceName());
        } catch (Throwable t) {
            Metallum.LOGGER.error("Metal backend activation failed; falling back to OpenGL.", t);
            translator = null;
            activated = false;
        }
    }

    public static boolean isActivated() {
        return activated;
    }

    public static boolean isMetalPlatform() {
        return metalPlatform;
    }

    public static boolean isOptiFinePresent() {
        return optiFinePresent;
    }

    public static void markOptiFineCompatible() {
        optiFineCompatible = true;
    }

    public static boolean isOptiFineCompatible() {
        return optiFineCompatible;
    }

    public static GlMetalTranslator translator() {
        return translator;
    }

    private static boolean detectMetalPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return true;
        }
        // PojavLauncher / Amethyst on iOS reports as Mac/Darwin aarch64.
        String tmp = System.getProperty("java.io.tmpdir", "");
        return tmp.contains("/var/mobile/") || tmp.contains("/var/containers/");
    }

    private static boolean detectOptiFine() {
        try {
            Class.forName("optifine.OptiFineTweaker");
            return true;
        } catch (ClassNotFoundException e1) {
            try {
                Class.forName("optifine.Installer");
                return true;
            } catch (ClassNotFoundException e2) {
                try {
                    Class.forName("net.optifine.OptiFineTweaker");
                    return true;
                } catch (ClassNotFoundException e3) {
                    return false;
                }
            }
        }
    }
}
