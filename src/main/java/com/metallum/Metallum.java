package com.metallum;

import com.metallum.client.metal.MetalRuntime;
import com.metallum.client.metal.MetalConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MetalUniversal for Minecraft 1.12.2 Forge.
 *
 * <p>This is a port of the MetalUniversal Fabric mod (which targets modern
 * Minecraft 26.2 with a pluggable GpuBackend abstraction). 1.12.2 has no such
 * abstraction: OpenGL is hardcoded throughout {@code GlStateManager},
 * {@code Tessellator}, {@code EntityRenderer} and every mod's render path.
 *
 * <p>Instead of reimplementing every renderer, Metallum installs a
 * <em>GL-over-Metal translation layer</em>: OpenGL state and draw calls captured
 * at the {@code GlStateManager}/{@code GL11} boundary are translated into Metal
 * command encoders and submitted to a Metal device. Because OptiFine and the
 * vast majority of mods ultimately issue OpenGL calls through the same path,
 * translating at that boundary gives broad compatibility without requiring each
 * mod to opt in.</p>
 *
 * <p>The native Metal bindings (the {@code mtl} and {@code bridge} packages)
 * are ported from the original Java 25 Foreign Function &amp; Memory API
 * implementation to Java 8 JNI. The Swift native library is reused verbatim:
 * it speaks the Apple Metal API, which is version-independent of Minecraft.</p>
 */
@Mod(modid = Metallum.MOD_ID, name = "MetalUniversal", version = "${mod_version}", clientSideOnly = true, acceptedMinecraftVersions = "[1.12.2]")
public final class Metallum {
    public static final String MOD_ID = "metallum";
    public static final String NAME = "MetalUniversal";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    @Mod.Instance(MOD_ID)
    public static Metallum instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MetalConfig.load(event.getSuggestedConfigurationFile());
        MetalRuntime.bootstrap();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        Metallum.LOGGER.info("MetalUniversal 1.12.2 port initialized. Backend: {}",
                MetalRuntime.isActivated() ? "Metal (GL-over-Metal translation)" : "OpenGL (Metal unavailable, passthrough)");
    }
}
