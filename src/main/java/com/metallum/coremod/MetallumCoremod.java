package com.metallum.coremod;

import com.metallum.Metallum;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

/**
 * Forge 1.12.2 coremod entry point.
 *
 * <p>Forge 1.12.2 does not ship the SpongePowered Mixin runtime. The Mixin +
 * ASM jars are bundled inside this mod jar (see {@code build.gradle} embed
 * scope) and loaded onto the classpath by this coremod. The Mixin environment
 * is started once here, and the {@code metallum.mixins.json} config is added so
 * the {@code GlStateManager}/{@code EntityRenderer}/etc. mixins that route
 * rendering into the Metal translation layer are applied at class-load time.</p>
 *
 * <p>This mirrors the MixinBootstrap pattern used by many 1.12.2 mods. The
 * coremod must be declared via {@code FMLCorePlugin} in the jar manifest and
 * via {@code coremods/metallum_coremod.json} so Forge's
 * {@code CoreModManager} discovers it before mod classes load.</p>
 */
public final class MetallumCoremod implements net.minecraftforge.fml.relauncher.IFMLLoadingPlugin {
    private static boolean mixinStarted = false;

    @Override
    public String[] getASMTransformerClass() {
        startMixin();
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // no-op; nothing additional needed from Forge here
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    /**
     * Starts Mixin exactly once. Called from {@link #getASMTransformerClass()}
     * because Forge invokes that method during coremod setup, before any mod
     * class is loaded, which is the earliest safe point to initialise the
     * Mixin environment.
     */
    public static synchronized void startMixin() {
        if (mixinStarted) {
            return;
        }
        mixinStarted = true;
        try {
            MixinBootstrap.init();
            Mixins.addConfiguration("metallum.mixins.json");
            Metallum.LOGGER.info("Metallum Mixin environment started.");
        } catch (Throwable t) {
            Metallum.LOGGER.error("Failed to start Metallum Mixin environment; Metal backend disabled.", t);
        }
    }
}
