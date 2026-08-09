package com.metallum.mixin;

import com.metallum.Metallum;
import com.metallum.client.metal.MetalRuntime;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for Metallum.
 *
 * <p>Controls which mixins apply based on the runtime environment:
 * <ul>
 *   <li>The Metal backend mixins only apply when a Metal device is available
 *       (macOS / Apple Silicon). On non-Metal platforms every mixin is refused
 *       so the mod is a no-op and the vanilla OpenGL path is untouched.</li>
 *   <li>The OptiFine compatibility mixin only applies when OptiFine is detected
 *       on the classpath, so it does not alter vanilla behaviour.</li>
 * </ul>
 */
public final class MetallumMixinConfigPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
        // no-op
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Metal-routing mixins require a Metal device; otherwise skip entirely.
        if (!MetalRuntime.isMetalPlatform()) {
            if (mixinClassName.contains("OptiFineCompatMixin")) {
                return false;
            }
            return false;
        }
        if (mixinClassName.contains("OptiFineCompatMixin")) {
            return MetalRuntime.isOptiFinePresent();
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // no-op
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClassNode, String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClassNode, String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }
}
