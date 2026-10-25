package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java guard for the lightning "Missing sampler tex" crash (iPad M3,
 * thundering weather, shadow-caster pass).
 *
 * <p>26.2's {@code RenderPipelines.LIGHTNING} uses
 * {@code DefaultVertexFormat.POSITION_COLOR}, so the engine binds no
 * {@code Sampler0}; Complementary Reimagined's {@code shadow} /
 * {@code gbuffers_lightning} programs nevertheless declare and sample
 * {@code tex}. Upstream {@code IrisSamplers.addLevelSamplers} covers that by
 * binding the shared white pixel to the no-texture alias group when
 * {@code hasTexture == false};
 * {@link IrisMetalWorldBridge#isAlbedoAlias} pins the subset the world
 * fallback white-pixels when {@code Sampler0} is unbound.
 *
 * <p>{@code gcolor}/{@code colortex0} are also in upstream's white-pixel
 * branch, but the port's {@code standardSampler} resolves those names to live
 * render targets before any missing-sampler throw can occur, so they are
 * deliberately not rerouted here.
 */
final class IrisMetalWorldSamplerFallbackTest {
    @Test
    void albedoAliasesAreExactlyTheUpstreamNoTextureSet() {
        Set<String> albedoAliases = Set.of("gtexture", "texture", "tex", "u_MainSampler");
        for (String name : albedoAliases) {
            assertTrue(
                    IrisMetalWorldBridge.isAlbedoAlias(name),
                    name + " must fall back to the white pixel when no Sampler0 is bound"
            );
        }
    }

    @Test
    void nonAlbedoSamplersKeepTheirExistingResolutionPath() {
        for (String name : new String[]{
                "iris_overlay", "lightmap", "noisetex", "colortex0", "gcolor",
                "depthtex0", "depthtex1", "shadowtex0", "shadowcolor0",
                "Sampler0", "u_BlockTex", "u_LightTex", ""
        }) {
            assertFalse(
                    IrisMetalWorldBridge.isAlbedoAlias(name),
                    name + " must not be rerouted through the white-pixel albedo fallback"
            );
        }
    }
}
