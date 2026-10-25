package com.metallum.client.metal.render;

import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the shared sampler-name -> logical colortex mapping used by both world
 * bridges and the composite/deferred/final execution graph.
 *
 * <p>MakeUp-UltraFast declares legacy render-target aliases ({@code gaux3},
 * {@code gaux1}...) in its graph programs. The graph resolved names through
 * {@code parseSuffix(name, "colortex")} only, so an active {@code gaux3} came
 * back null and {@code bindRaster} threw
 * {@code "Iris pass composite is missing required sampler 'gaux3'"}. The
 * legacy list's position is the colortex index, so the mapping is
 * position-based, not name-based.
 */
final class IrisMetalRenderTargetsTest {
    @Test
    void legacyAliasesMapToTheirListPosition() {
        // Pin the upstream table the mapping is derived from: index 3 is
        // `composite` and must resolve like any other alias.
        assertEquals(
                List.of("gcolor", "gdepth", "gnormal", "composite",
                        "gaux1", "gaux2", "gaux3", "gaux4"),
                PackRenderTargetDirectives.LEGACY_RENDER_TARGETS
        );

        assertEquals(0, IrisMetalRenderTargets.renderTargetIndex("gcolor"));
        assertEquals(1, IrisMetalRenderTargets.renderTargetIndex("gdepth"));
        assertEquals(2, IrisMetalRenderTargets.renderTargetIndex("gnormal"));
        assertEquals(3, IrisMetalRenderTargets.renderTargetIndex("composite"));
        assertEquals(4, IrisMetalRenderTargets.renderTargetIndex("gaux1"));
        assertEquals(5, IrisMetalRenderTargets.renderTargetIndex("gaux2"));
        assertEquals(6, IrisMetalRenderTargets.renderTargetIndex("gaux3"));
        assertEquals(7, IrisMetalRenderTargets.renderTargetIndex("gaux4"));
    }

    @Test
    void colortexNamesPassThroughUnchanged() {
        assertEquals(0, IrisMetalRenderTargets.renderTargetIndex("colortex0"));
        assertEquals(3, IrisMetalRenderTargets.renderTargetIndex("colortex3"));
        assertEquals(7, IrisMetalRenderTargets.renderTargetIndex("colortex7"));
        assertEquals(15, IrisMetalRenderTargets.renderTargetIndex("colortex15"));
        assertEquals(31, IrisMetalRenderTargets.renderTargetIndex("colortex31"));
    }

    @Test
    void unknownAndMalformedNamesAreNotTargets() {
        // `gaux0` is not an alias and `gcolor1` is neither a numbered colortex
        // nor a legacy name.
        assertEquals(-1, IrisMetalRenderTargets.renderTargetIndex("gaux0"));
        assertEquals(-1, IrisMetalRenderTargets.renderTargetIndex("gaux5"));
        assertEquals(-1, IrisMetalRenderTargets.renderTargetIndex("gcolor1"));
        // A `colortex` prefix with a non-numeric suffix is a miss, not index 0.
        assertEquals(-1, IrisMetalRenderTargets.renderTargetIndex("colortex"));
        assertEquals(-1, IrisMetalRenderTargets.renderTargetIndex("colortexx"));
        assertEquals(-1, IrisMetalRenderTargets.renderTargetIndex("colortex1x"));
        // Non-color-target sampler families stay unresolved here.
        assertEquals(-1, IrisMetalRenderTargets.renderTargetIndex("colorimg0"));
        assertEquals(-1, IrisMetalRenderTargets.renderTargetIndex("shadowtex0"));
        assertEquals(-1, IrisMetalRenderTargets.renderTargetIndex("noisetex"));
        assertEquals(-1, IrisMetalRenderTargets.renderTargetIndex(""));
    }

    /**
     * P1-1: the three former copies (world bridge, terrain bridge, execution
     * graph) of the shadowcolor parser now share this one. The explicit name
     * is target 0, {@code shadowcolorN} is N (callers bounds-check N against
     * the generation's target count), and {@code shadowcolorimg} / malformed
     * suffixes / other prefixes are misses.
     */
    @Test
    void shadowColorNamesResolveToTheirIndex() {
        assertEquals(0, IrisMetalRenderTargets.shadowColorIndex("shadowcolor"));
        assertEquals(1, IrisMetalRenderTargets.shadowColorIndex("shadowcolor1"));
        assertEquals(7, IrisMetalRenderTargets.shadowColorIndex("shadowcolor7"));
        // Out-of-range indices still parse; the caller rejects them against
        // the actual shadowcolor target count.
        assertEquals(99, IrisMetalRenderTargets.shadowColorIndex("shadowcolor99"));
        // The compute-style image name and malformed suffixes are misses.
        assertEquals(-1, IrisMetalRenderTargets.shadowColorIndex("shadowcolorimg"));
        assertEquals(-1, IrisMetalRenderTargets.shadowColorIndex("shadowcolorimg0"));
        assertEquals(-1, IrisMetalRenderTargets.shadowColorIndex("shadowcolorx"));
        assertEquals(-1, IrisMetalRenderTargets.shadowColorIndex("shadowcolor1x"));
        // Negative and spaced suffixes parse-fail to the same miss sentinel.
        assertEquals(-1, IrisMetalRenderTargets.shadowColorIndex("shadowcolor-1"));
        assertEquals(-1, IrisMetalRenderTargets.shadowColorIndex("shadowcolor "));
        assertEquals(-1, IrisMetalRenderTargets.shadowColorIndex("colortex1"));
        assertEquals(-1, IrisMetalRenderTargets.shadowColorIndex(""));
    }
}
