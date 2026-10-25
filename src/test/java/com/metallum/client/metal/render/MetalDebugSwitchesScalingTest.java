package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic gate for the two opt-in performance overrides:
 * {@link MetalDebugSwitches#parseRenderScale(String)} /
 * {@link MetalDebugSwitches#scaledExtent(int, float)} for
 * {@code -Dmetallum.iris.renderScale}, and
 * {@link MetalDebugSwitches#parseShadowResolution(String)} /
 * {@link MetalDebugSwitches#resolveShadowResolution(int, int)} for
 * {@code -Dmetallum.iris.shadowResolution}. Both switches must be inert at
 * their defaults (1.0 / 0), so the parsers have to map every malformed or
 * out-of-range value back to the off state instead of throwing.
 */
final class MetalDebugSwitchesScalingTest {
    @Test
    void renderScaleClampsIntoHalfToOne() {
        assertEquals(0.5f, MetalDebugSwitches.parseRenderScale("0.49"), 0.0f, "below the floor clamps to 0.5");
        assertEquals(0.5f, MetalDebugSwitches.parseRenderScale("0.5"), 0.0f);
        assertEquals(0.75f, MetalDebugSwitches.parseRenderScale("0.75"), 0.0f);
        assertEquals(1.0f, MetalDebugSwitches.parseRenderScale("1.0"), 0.0f);
        assertEquals(1.0f, MetalDebugSwitches.parseRenderScale("1.5"), 0.0f, "above the ceiling clamps to 1.0");
        assertEquals(0.5f, MetalDebugSwitches.parseRenderScale("0.1"), 0.0f);
        assertEquals(0.5f, MetalDebugSwitches.parseRenderScale("-2"), 0.0f);
    }

    @Test
    void renderScaleMalformedFallsBackToDefault() {
        assertEquals(1.0f, MetalDebugSwitches.parseRenderScale(""), 0.0f);
        assertEquals(1.0f, MetalDebugSwitches.parseRenderScale("abc"), 0.0f);
        assertEquals(1.0f, MetalDebugSwitches.parseRenderScale("0.75x"), 0.0f);
        assertEquals(1.0f, MetalDebugSwitches.parseRenderScale("NaN"), 0.0f);
        assertEquals(1.0f, MetalDebugSwitches.parseRenderScale("Infinity"), 0.0f);
        assertEquals(1.0f, MetalDebugSwitches.parseRenderScale("-Infinity"), 0.0f);
    }

    @Test
    void scaledExtentRoundsAndNeverDropsBelowOne() {
        assertEquals(1440, MetalDebugSwitches.scaledExtent(1920, 0.75f));
        assertEquals(1080, MetalDebugSwitches.scaledExtent(1920, 0.5625f));
        assertEquals(1439, MetalDebugSwitches.scaledExtent(1919, 0.75f), "odd extent rounds to nearest");
        assertEquals(961, MetalDebugSwitches.scaledExtent(1921, 0.5f), "half extent rounds up");
        assertEquals(2, MetalDebugSwitches.scaledExtent(3, 0.5f));
        assertEquals(1, MetalDebugSwitches.scaledExtent(1, 0.5f), "minimum extent stays 1");
        assertEquals(1, MetalDebugSwitches.scaledExtent(0, 0.75f), "malformed zero stays 1");
        assertEquals(1920, MetalDebugSwitches.scaledExtent(1920, 1.0f), "default scale is identity");
    }

    @Test
    void shadowResolutionOffKeepsThePackValue() {
        assertEquals(0, MetalDebugSwitches.parseShadowResolution("0"));
        assertEquals(0, MetalDebugSwitches.parseShadowResolution(""), "unset/malformed is off");
        assertEquals(0, MetalDebugSwitches.parseShadowResolution("abc"));
        assertEquals(2048, MetalDebugSwitches.resolveShadowResolution(2048, 0), "override 0 keeps the pack value");
        assertEquals(1024, MetalDebugSwitches.resolveShadowResolution(2048, 1024));
    }

    @Test
    void shadowResolutionRoundsDownToEvenAndRejectsOutOfRange() {
        assertEquals(1022, MetalDebugSwitches.parseShadowResolution("1023"), "odd requests round down");
        assertEquals(1024, MetalDebugSwitches.parseShadowResolution("1024"));
        assertEquals(256, MetalDebugSwitches.parseShadowResolution("256"), "lower bound accepted");
        assertEquals(0, MetalDebugSwitches.parseShadowResolution("255"), "below the range is off");
        assertEquals(4096, MetalDebugSwitches.parseShadowResolution("4096"), "upper bound accepted");
        assertEquals(0, MetalDebugSwitches.parseShadowResolution("4097"), "above the range is off");
        assertEquals(0, MetalDebugSwitches.parseShadowResolution("5000"));
        assertEquals(0, MetalDebugSwitches.parseShadowResolution("-1024"));
        assertEquals(0, MetalDebugSwitches.parseShadowResolution("1"));
    }
}
