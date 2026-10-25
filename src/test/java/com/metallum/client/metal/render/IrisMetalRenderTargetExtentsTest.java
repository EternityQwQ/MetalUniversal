package com.metallum.client.metal.render;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.texture.TextureScaleOverride;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Linux-only pure-logic coverage for {@code size.buffer} extents: the upstream
 * {@link TextureScaleOverride} quirks ({@code "0.5"} truncates, {@code "512"}
 * is absolute, {@code "1"} is one pixel while {@code "1.0"} is the base size)
 * plus {@link IrisMetalRenderTargetExtents} clamping/default behavior. No Metal
 * device is involved.
 */
final class IrisMetalRenderTargetExtentsTest {
    @TempDir
    Path tempDir;

    @Test
    void textureScaleOverrideMatchesUpstreamQuirks() {
        TextureScaleOverride half = new TextureScaleOverride("0.5", "0.5");
        assertEquals(960, half.getX(1920));
        assertEquals(540, half.getY(1080));

        // Relative values truncate toward zero.
        TextureScaleOverride odd = new TextureScaleOverride("0.75", "0.25");
        assertEquals(750, odd.getX(1001));
        assertEquals(25, odd.getY(101));

        // Integral values are absolute pixels.
        TextureScaleOverride absolute = new TextureScaleOverride("512", "256");
        assertEquals(512, absolute.getX(1920));
        assertEquals(256, absolute.getY(1080));

        // The OptiFine/Iris size.buffer quirk: "1" is one pixel, "1.0" is the base size.
        TextureScaleOverride onePixel = new TextureScaleOverride("1", "1");
        assertEquals(1, onePixel.getX(1920));
        assertEquals(1, onePixel.getY(1080));
        TextureScaleOverride base = new TextureScaleOverride("1.0", "1.0");
        assertEquals(1920, base.getX(1920));
        assertEquals(1080, base.getY(1080));
    }

    @Test
    void extentsReadPackDirectivesAndClampNonPositiveResults() {
        assumeTrue(MetalDebugSwitches.SIZE_BUFFER, "sizeBuffer kill switch is off");
        PackDirectives directives = directives(
                "size.buffer.colortex1 = 0.5 0.5\n"
                        + "size.buffer.colortex3 = 1 1\n"
                        + "size.buffer.colortex4 = 0.0001 0.0001\n"
                        + "size.buffer.colortex5 = 4096 2048\n"
        );

        IrisMetalRenderTargetExtents.Extents extents =
                IrisMetalRenderTargetExtents.from(directives, 6, 1920, 1080);

        assertEquals(1920, extents.width(0));
        assertEquals(1080, extents.height(0));
        assertTrue(extents.isBase(0));

        assertEquals(960, extents.width(1));
        assertEquals(540, extents.height(1));
        assertFalse(extents.isBase(1));

        // "1" is one absolute pixel, not a relative 1.0 multiplier.
        assertEquals(1, extents.width(3));
        assertEquals(1, extents.height(3));

        // 1920 * 0.0001 truncates to 0; upstream throws, the port clamps to 1.
        assertEquals(1, extents.width(4));
        assertEquals(1, extents.height(4));

        // Absolute values larger than the base are honored, not clamped down.
        assertEquals(4096, extents.width(5));
        assertEquals(2048, extents.height(5));

        assertTrue(extents.anyNonBase());
        assertTrue(extents.nonBaseSummary().contains("colortex1=960x540"));
    }

    @Test
    void extentsLegacyAliasOverridesColortexName() {
        assumeTrue(MetalDebugSwitches.SIZE_BUFFER, "sizeBuffer kill switch is off");
        PackDirectives directives = directives(
                "size.buffer.colortex1 = 0.5 0.5\nsize.buffer.gdepth = 0.25 0.25\n"
        );
        IrisMetalRenderTargetExtents.Extents extents =
                IrisMetalRenderTargetExtents.from(directives, 2, 1920, 1080);
        // Upstream resolves the legacy alias first when both are declared.
        assertEquals(480, extents.width(1));
        assertEquals(270, extents.height(1));
    }

    @Test
    void extentsWithoutDirectivesAreUniformBase() {
        IrisMetalRenderTargetExtents.Extents extents =
                IrisMetalRenderTargetExtents.from(null, 3, 1920, 1080);
        assertEquals(3, extents.count());
        for (int index = 0; index < extents.count(); index++) {
            assertEquals(1920, extents.width(index));
            assertEquals(1080, extents.height(index));
            assertTrue(extents.isBase(index));
        }
        assertFalse(extents.anyNonBase());
        assertEquals("", extents.nonBaseSummary());

        assertThrows(
                IllegalArgumentException.class,
                () -> extents.width(3)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalRenderTargetExtents.from(null, 0, 1920, 1080)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalRenderTargetExtents.from(null, 1, 0, 1080)
        );
    }

    private PackDirectives directives(final String contents) {
        Iris.testing = true;
        IncludeGraph graph = new IncludeGraph(tempDir, ImmutableList.of(), false);
        ShaderPackOptions options = new ShaderPackOptions(graph, Map.of());
        ShaderProperties properties = new ShaderProperties(contents, options, List.of());
        return new PackDirectives(Set.of(0, 1, 2, 3, 4, 5), properties);
    }
}
