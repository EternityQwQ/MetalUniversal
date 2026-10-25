package com.metallum.client.metal.render;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the Mellow v3.4.1a custom-texture crash:
 * {@code UnsupportedOperationException: Unsupported Iris custom texture on
 * Metal: stage=GBUFFERS_AND_SHADOW, sampler=colortex7, type=ResourceData}.
 *
 * <p>Mellow declares three kinds of custom textures:
 * <ul>
 *   <li>{@code ResourceData} references to vanilla assets
 *       ({@code minecraft:textures/environment/clouds.png} on deferred/colortex3
 *       and gbuffers/colortex7) — stage-scoped map, the crash type;</li>
 *   <li>a {@code RawData3D} volume on colortex6
 *       ({@code /img/worley_perlin.bin TEXTURE_3D R8 64 64 64 RED UNSIGNED_BYTE}),
 *       which upstream parses into the global {@code customtexN} map and
 *       rewrites sampler references to;</li>
 *   <li>ordinary PNGs (noisetex, SMAA, LUTs).</li>
 * </ul>
 *
 * <p>Parses the real pack (no Metal device) and pins the data shapes plus the
 * renaming that {@link IrisMetalCustomTextures} must follow.
 */
final class MellowCustomTextureDefinitionsTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "Mellow_3.4.1a.zip");
    private static final String CLOUDS = "textures/environment/clouds.png";

    @Test
    void mellowDeclaresResourceDataAndRawData3DCustomTextures() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            Map<TextureStage, ? extends Map<String, CustomTextureData>> customTextures =
                    pack.getCustomTextureDataMap();

            Map<String, CustomTextureData> gbuffers = customTextures.get(TextureStage.GBUFFERS_AND_SHADOW);
            assertNotNull(gbuffers, "Mellow must declare gbuffers custom textures");
            Map<String, CustomTextureData> deferred = customTextures.get(TextureStage.DEFERRED);
            assertNotNull(deferred, "Mellow must declare deferred custom textures");

            // L54 / L53: `minecraft:` resource references -> ResourceData, and
            // they stay under their real sampler name in the stage map.
            assertResource(gbuffers.get("colortex7"), "gbuffers colortex7");
            assertResource(deferred.get("colortex3"), "deferred colortex3");

            // L55 / L56: 64^3 R8 volumes are raw directives. Upstream parses
            // them into the *global* customtexN map (Textureransformer renames
            // the sampler declarations), not the stage map.
            Object2ObjectMap<String, CustomTextureData> globals = pack.getIrisCustomTextureDataMap();
            assertEquals(2, globals.size(), "Mellow must declare exactly two raw custom textures");
            for (Map.Entry<String, CustomTextureData> entry : globals.entrySet()) {
                assertTrue(entry.getKey().startsWith("customtex"),
                        "raw custom texture must live under a customtexN name, was " + entry.getKey());
                assertRaw3D(entry.getValue(), entry.getKey());
            }
            assertNull(gbuffers.get("colortex6"),
                    "raw colortex6 must not stay in the stage map (it is renamed)");
            assertNull(deferred.get("colortex6"),
                    "raw colortex6 must not stay in the stage map (it is renamed)");

            // The renaming table pairs each raw declaration with its customtexN
            // name per stage; IrisMetalCustomTextures resolves from that value.
            PackDirectives directives =
                    pack.getProgramSet(new NamespacedId("minecraft", "overworld")).getPackDirectives();
            Map<String, String> renames = rawRenames(directives);
            assertEquals(java.util.Set.of("customtex0", "customtex1"),
                    new LinkedHashSet<>(renames.values()),
                    "renames=" + renames);
            assertNotNull(renames.get(TextureStage.GBUFFERS_AND_SHADOW + ":colortex6"),
                    "gbuffers colortex6 must be renamed; renames=" + renames);
            assertNotNull(renames.get(TextureStage.DEFERRED + ":colortex6"),
                    "deferred colortex6 must be renamed; renames=" + renames);

            // Sanity: everything else stays PNG-backed (noisetex, SMAA, LUTs).
            Map<String, CustomTextureData> composite = customTextures.get(TextureStage.COMPOSITE_AND_FINAL);
            assertNotNull(composite, "Mellow must declare composite custom textures");
            assertInstanceOf(CustomTextureData.PngData.class, gbuffers.get("noisetex"),
                    "gbuffers noisetex must stay PNG-backed");
            assertInstanceOf(CustomTextureData.PngData.class, composite.get("colortex7"),
                    "composite colortex7 (default LUT) must stay PNG-backed");
        }
    }

    private static Map<String, String> rawRenames(final PackDirectives directives) {
        Map<String, String> renames = new LinkedHashMap<>();
        for (Map.Entry<Tri<String, TextureType, TextureStage>, String> entry
                : directives.getTextureMap().entrySet()) {
            Tri<String, TextureType, TextureStage> key = entry.getKey();
            if (key.first().equals("colortex6")) {
                assertEquals(TextureType.TEXTURE_3D, key.second(), "colortex6 raw type");
                renames.put(key.third() + ":colortex6", entry.getValue());
            }
        }
        assertEquals(2, renames.size(), "both colortex6 declarations must be patched: " + renames);
        return renames;
    }

    private static void assertResource(final CustomTextureData data, final String label) {
        CustomTextureData.ResourceData resource = assertInstanceOf(
                CustomTextureData.ResourceData.class, data,
                label + " must be ResourceData (the crash type)"
        );
        assertEquals("minecraft", resource.getNamespace(), label + " namespace");
        assertEquals(CLOUDS, resource.getLocation(), label + " location");
    }

    private static void assertRaw3D(final CustomTextureData data, final String label) {
        CustomTextureData.RawData3D raw = assertInstanceOf(
                CustomTextureData.RawData3D.class, data,
                label + " must be RawData3D (the next unsupported type)"
        );
        assertEquals(64, raw.getSizeX(), label + " sizeX");
        assertEquals(64, raw.getSizeY(), label + " sizeY");
        assertEquals(64, raw.getSizeZ(), label + " sizeZ");
        assertEquals(InternalTextureFormat.R8, raw.getInternalFormat(), label + " internal format");
        assertEquals(PixelFormat.RED, raw.getPixelFormat(), label + " pixel format");
        assertEquals(PixelType.UNSIGNED_BYTE, raw.getPixelType(), label + " pixel type");
        assertEquals(64 * 64 * 64, raw.getContent().length, label + " content length");
        // worley_perlin.bin.mcmeta: {"blur": true, "clamp": false}
        assertTrue(raw.getFilteringData().shouldBlur(), label + " must blur (LINEAR) per mcmeta");
        assertFalse(raw.getFilteringData().shouldClamp(), label + " must repeat (not clamp) per mcmeta");
    }
}
