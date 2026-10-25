package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import org.joml.Vector2i;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CR r5.9.3 fixture: inside the
 * {@code IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE} branch its
 * {@code shaders.properties} declares
 * {@code size.buffer.colortex1/7 = REFLECTION_RES REFLECTION_RES} with the
 * default {@code REFLECTION_RES 0.5}. This pins the real directive parse path
 * (macro default, 0.5 relative truncation) and the target table
 * {@link IrisMetalRenderTargetExtents} hands to the Metal ping-pong targets.
 */
final class ComplementaryReimaginedSizeBufferDirectiveTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "ComplementaryReimagined_r5.9.3.zip");
    private static final int BASE_WIDTH = 1920;
    private static final int BASE_HEIGHT = 1080;

    @Test
    void reflectionTargetsAreHalfSizeAndEveryOtherTargetStaysBase() throws Exception {
        assumeTrue(MetalDebugSwitches.SIZE_BUFFER, "sizeBuffer kill switch is off");
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

            assertHalfSize(directives.getTextureScaleOverride(1, BASE_WIDTH, BASE_HEIGHT), "colortex1");
            assertHalfSize(directives.getTextureScaleOverride(7, BASE_WIDTH, BASE_HEIGHT), "colortex7");
            // colortex1's legacy alias is gdepth; CR declares no other size.buffer entries.
            assertHalfSize(directives.getTextureScaleOverride(1, BASE_WIDTH, BASE_HEIGHT), "gdepth");

            int targetCount = IrisMetalRenderTargetFormats.from(directives).length;
            assertTrue(targetCount > 7, "CR needs at least 8 color targets, got " + targetCount);
            IrisMetalRenderTargetExtents.Extents extents =
                    IrisMetalRenderTargetExtents.from(directives, targetCount, BASE_WIDTH, BASE_HEIGHT);

            for (int index = 0; index < targetCount; index++) {
                if (index == 1 || index == 7) {
                    assertEquals(960, extents.width(index), "colortex" + index + " width");
                    assertEquals(540, extents.height(index), "colortex" + index + " height");
                    assertFalse(extents.isBase(index), "colortex" + index + " should be scaled");
                } else {
                    assertEquals(BASE_WIDTH, extents.width(index), "colortex" + index + " width");
                    assertEquals(BASE_HEIGHT, extents.height(index), "colortex" + index + " height");
                    assertTrue(extents.isBase(index), "colortex" + index + " should stay base");
                }
            }
            assertTrue(extents.nonBaseSummary().contains("colortex1=960x540"));
            assertTrue(extents.nonBaseSummary().contains("colortex7=960x540"));
        }
    }

    private static void assertHalfSize(final Vector2i extent, final String name) {
        assertEquals(960, extent.x(), name + " width");
        assertEquals(540, extent.y(), name + " height");
    }
}
