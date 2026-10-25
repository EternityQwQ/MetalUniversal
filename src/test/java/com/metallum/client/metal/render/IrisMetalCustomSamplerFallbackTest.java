package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linux-runnable regression coverage for the water "flat surface + player-centred
 * reflection blob" bug (CR WATER_STYLE=3).
 *
 * <p>CR declares {@code texture.gbuffers.gaux4=lib/textures/cloud-water.png}.
 * Terrain water draws resolve samplers through
 * {@link IrisMetalTerrainBridge#fallbackSampler}; before the fix that method
 * never consulted {@link IrisMetalCustomTextures}, so {@code gaux4} fell through
 * to {@link PackRenderTargetDirectives#LEGACY_RENDER_TARGETS} index 7
 * (colortex7, the reflection buffer). The first test pins the pack data that
 * makes the override mandatory; the second pins the precedence helper the two
 * fallbacks now route through (custom hit &rarr; custom; miss &rarr; original
 * fallback called exactly once, or {@code null}).
 */
final class IrisMetalCustomSamplerFallbackTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "ComplementaryReimagined_r5.9.3.zip");

    @Test
    void crGbuffersStageDefinesWaterNormalSamplerAsCloudWaterPng() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programSet = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            Map<TextureStage, ? extends Map<String, CustomTextureData>> customTextures =
                    programSet.getPack().getCustomTextureDataMap();
            Map<String, CustomTextureData> gbuffers = customTextures.get(TextureStage.GBUFFERS_AND_SHADOW);

            assertTrue(gbuffers != null && gbuffers.containsKey("gaux4"),
                    "CR must define texture.gbuffers.gaux4; stages=" + customTextures.keySet());
            CustomTextureData.PngData waterNormals = assertInstanceOf(
                    CustomTextureData.PngData.class,
                    gbuffers.get("gaux4"),
                    "gaux4 must be PNG-backed so IrisMetalCustomTextures can materialize it"
            );
            assertArrayEquals(
                    Files.readAllBytes(fileSystem.getPath("/shaders/lib/textures/cloud-water.png")),
                    waterNormals.getContent(),
                    "gaux4 must be the cloud-water.png water normal map"
            );

            // Documents the legacy name-to-target mapping the terrain shortcut
            // used before the custom-texture check was added: gaux4 -> colortex7.
            assertEquals(7, PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.indexOf("gaux4"),
                    "gaux4 no longer resolves to colortex7; revisit the terrain fallback shortcut");
        }
    }

    @Test
    void customOverrideWinsAndMissDefersToOriginalFallback() {
        MetalRenderPass.TextureViewAndSampler custom =
                new MetalRenderPass.TextureViewAndSampler(null, null);
        MetalRenderPass.TextureViewAndSampler standard =
                new MetalRenderPass.TextureViewAndSampler(null, null);

        AtomicBoolean fallbackCalled = new AtomicBoolean();
        assertSame(
                custom,
                IrisMetalCustomTextures.overrideFirst(custom, () -> {
                    fallbackCalled.set(true);
                    return standard;
                }),
                "a custom-texture hit must win without consulting the standard fallback"
        );
        assertFalse(fallbackCalled.get(), "standard fallback must not run on a custom hit");

        assertSame(
                standard,
                IrisMetalCustomTextures.overrideFirst(null, () -> standard),
                "a custom-texture miss must return the original fallback binding"
        );

        AtomicReference<MetalRenderPass.TextureViewAndSampler> miss = new AtomicReference<>();
        AtomicBoolean nullFallbackCalled = new AtomicBoolean();
        assertNull(
                IrisMetalCustomTextures.overrideFirst(null, () -> {
                    nullFallbackCalled.set(true);
                    return null;
                }),
                "a miss with no standard binding must stay null, not throw"
        );
        assertTrue(nullFallbackCalled.get(), "the miss path must run the original fallback exactly once");
    }

    /**
     * Structural guard: both name-based fallbacks must expose the custom-first
     * route. The heavy paths need a live Metal pipeline context, so this only
     * checks that the private standard-sampler split still exists; the CR data
     * test above plus the helper test pin the actual behavior.
     */
    @Test
    void bothBridgesSplitCustomLookupFromStandardResolution() {
        for (Class<?> bridge : new Class<?>[]{IrisMetalTerrainBridge.class, IrisMetalWorldBridge.class}) {
            assertTrue(
                    Arrays.stream(bridge.getDeclaredMethods())
                            .anyMatch(method -> method.getName().equals("standardSampler")),
                    bridge.getSimpleName() + " must keep the extracted standardSampler fallback"
            );
        }
    }
}
