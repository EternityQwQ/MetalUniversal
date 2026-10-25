package com.metallum.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MetallumMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String PREFERRED_GRAPHICS_API_MIXIN = "com.metallum.mixin.render.PreferredGraphicsApiMixin";
    private static final String PREFERRED_GRAPHICS_BACKEND_OPTION = "preferredGraphicsBackend";
    private static final String DEFAULT_GRAPHICS_BACKEND = "\"default\"";

    /**
     * Bisection probe / opt-in performance mixins that must apply whenever the
     * mod runs on macOS, regardless of the {@code preferredGraphicsBackend}
     * value in options.txt. They are fully inert at runtime while
     * {@code MetalDebugSwitches} is unset, so applying them unconditionally
     * changes no behavior.
     */
    private static final Set<String> ALWAYS_APPLY_RENDER_PROBES = Set.of(
            "com.metallum.mixin.render.NoVanillaSkyMixin",
            "com.metallum.mixin.render.NoVanillaCloudsMixin",
            "com.metallum.mixin.render.NoVanillaCloudRendererMixin",
            "com.metallum.mixin.render.ForceCloudStatusOffMixin",
            "com.metallum.mixin.render.MetalProbeReportMixin",
            "com.metallum.mixin.render.MetalRenderScaleMixin"
    );

    private boolean isMacOs;
    private boolean isDefaultGraphicsApi;

    @Override
    public void onLoad(String mixinPackage) {
        String osName = System.getProperty("os.name", "");
        this.isMacOs = osName.toLowerCase(Locale.ROOT).contains("mac");
        this.isDefaultGraphicsApi = isDefaultGraphicsApiSelected();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!this.isMacOs) {
            return false;
        }
        if (ALWAYS_APPLY_RENDER_PROBES.contains(mixinClassName)) {
            return true;
        }
        if (mixinClassName.contains(".mixin.sodium.")) {
            return FabricLoader.getInstance().isModLoaded("sodium");
        }
        if (mixinClassName.contains(".mixin.iris.")) {
            // Iris integration only makes sense when Iris is present. The
            // isMacOs gate above covers the macOS dev environment; iOS targets
            // are packaged separately and the actual Metal-active gating is
            // enforced at runtime by MetalActive (RenderSystem device backend
            // name), so the mixins are no-ops whenever Metal is not in use.
            return FabricLoader.getInstance().isModLoaded("iris");
        }
        return PREFERRED_GRAPHICS_API_MIXIN.equals(mixinClassName) || this.isDefaultGraphicsApi;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean isDefaultGraphicsApiSelected() {
        Path optionsFile = FabricLoader.getInstance().getGameDir().resolve("options.txt");
        try {
            for (String line : Files.readAllLines(optionsFile)) {
                int separator = line.indexOf(':');
                if (separator <= 0) {
                    continue;
                }
                if (PREFERRED_GRAPHICS_BACKEND_OPTION.equals(line.substring(0, separator))) {
                    String value = line.substring(separator + 1).toLowerCase(Locale.ROOT);
                    return DEFAULT_GRAPHICS_BACKEND.equals(value);
                }
            }
        } catch (IOException ignored) {
        }

        return true;
    }
}
