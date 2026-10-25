package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.BufferBlendInformation;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives.RenderTargetSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CR r5.9.3 smoke test: {@code shaders/shaders.properties} carries
 * unconditional {@code blend.gbuffers_*.colortexN=off} overrides, which Iris
 * rejects unless {@code supportsBufferBlending()} reports true. This pins the
 * property parsing plus the program&rarr;colortex mapping the Metal PSO
 * consumes; the mixin that flips the capability bit is exercised on device
 * (the headless stub already returns true, so a unit test cannot regress it).
 */
final class ComplementaryReimaginedBlendDirectiveTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "ComplementaryReimagined_r5.9.3.zip");

    @Test
    void unconditionalColorTargetBlendOverridesParse() throws Exception {
        assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;
        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));

            assertBlendOff(programs.get(ProgramId.Textured).orElseThrow(), 4);
            ProgramSource water = programs.get(ProgramId.Water).orElseThrow();
            assertBlendOff(water, 4);
            assertBlendOff(water, 8);
        }
    }

    /**
     * CR 5.9.3's {@code lib/pipelineSettings.glsl} requests
     * {@code colortex1Format = RGB8_SNORM} and {@code colortex4Format = RGBA8_SNORM}
     * (inside a block comment that Iris' line-based const parser still reads).
     * RGBA8_SNORM used to abort {@code MetalWorldRenderingPipeline.<init>} before
     * the SNORM family was lowered. Every target format the pack asks for must
     * map, and the two SNORM names must actually be part of the request set so
     * this test cannot silently stop covering the crash.
     */
    @Test
    void everyRenderTargetFormatRequestedByThePackMapsToMetal() throws Exception {
        assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;
        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            PackDirectives directives = programs.getPackDirectives();

            Set<String> requested = new TreeSet<>();
            for (Map.Entry<Integer, RenderTargetSettings> entry
                    : directives.getRenderTargetDirectives().getRenderTargetSettings().entrySet()) {
                InternalTextureFormat format = entry.getValue().getInternalFormat();
                if (format == null) {
                    continue;
                }
                requested.add(format.name());
                assertNotNull(
                        IrisMetalRenderTargetFormats.fromInternalName(format.name()),
                        "colortex" + entry.getKey() + " format " + format
                );
            }

            assertTrue(requested.contains("RGB8_SNORM"), "requested formats: " + requested);
            assertTrue(requested.contains("RGBA8_SNORM"), "requested formats: " + requested);

            GpuFormat[] lowered = IrisMetalRenderTargetFormats.from(directives);
            assertEquals(GpuFormat.RGBA8_SNORM, lowered[1]);
            assertEquals(GpuFormat.RGBA8_SNORM, lowered[4]);
        }
    }

    private static void assertBlendOff(final ProgramSource source, final int colortex) {
        List<BufferBlendInformation> overrides = source.getDirectives().getBufferBlendOverrides();
        assertTrue(
                overrides.stream().anyMatch(override -> override.index() == colortex && override.blendMode() == null),
                source.getName() + " is missing blend off for colortex" + colortex + ": " + overrides
        );
    }
}
