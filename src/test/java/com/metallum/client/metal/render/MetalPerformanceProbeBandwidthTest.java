package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic gate for the {@code -Dmetallum.perf} per-pass attachment traffic
 * accounting: {@link MetalPerformanceProbe#passTraffic} must mirror what the
 * native encoder does (always {@code .store}; {@code .load} only when the
 * descriptor supplied no clear value) and must only call a pass
 * {@code fullCoverage} when the render area covers every whole attachment.
 * No Metal device is touched.
 */
final class MetalPerformanceProbeBandwidthTest {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final long PIXELS = (long) WIDTH * HEIGHT;
    private static final int RGBA8 = 4;
    private static final int RGBA16F = 8;
    private static final int DEPTH32F = 4;

    private static MetalPerformanceProbe.PassTraffic traffic(
            final int areaX,
            final int areaY,
            final int areaWidth,
            final int areaHeight,
            final boolean allViewsFull,
            final int[] colorBpp,
            final boolean[] colorCleared,
            final int depthBpp,
            final boolean depthCleared
    ) {
        return MetalPerformanceProbe.passTraffic(
                areaX, areaY, areaWidth, areaHeight,
                WIDTH, HEIGHT, allViewsFull,
                colorBpp, colorCleared, depthBpp, depthCleared
        );
    }

    @Test
    void fullScreenWithoutClearLoadsAndStoresEveryAttachment() {
        MetalPerformanceProbe.PassTraffic traffic = traffic(
                0, 0, WIDTH, HEIGHT, true,
                new int[]{RGBA8}, new boolean[]{false}, DEPTH32F, false
        );

        assertEquals(PIXELS * RGBA8 + PIXELS * DEPTH32F, traffic.loadBytes());
        assertEquals(PIXELS * RGBA8 + PIXELS * DEPTH32F, traffic.storeBytes());
        assertEquals(1, traffic.colorAttachments());
        assertTrue(traffic.depthAttachment());
        assertTrue(traffic.fullCoverage());
    }

    @Test
    void colorClearDropsTheLoadButKeepsTheStore() {
        MetalPerformanceProbe.PassTraffic traffic = traffic(
                0, 0, WIDTH, HEIGHT, true,
                new int[]{RGBA8}, new boolean[]{true}, 0, false
        );

        assertEquals(0L, traffic.loadBytes(), "a cleared attachment never loads old contents");
        assertEquals(PIXELS * RGBA8, traffic.storeBytes(), "native always stores");
        assertFalse(traffic.depthAttachment());
        assertEquals(1, traffic.colorAttachments());
    }

    @Test
    void depthClearDropsOnlyTheDepthLoad() {
        MetalPerformanceProbe.PassTraffic traffic = traffic(
                0, 0, WIDTH, HEIGHT, true,
                new int[]{RGBA8}, new boolean[]{false}, DEPTH32F, true
        );

        assertEquals(PIXELS * RGBA8, traffic.loadBytes());
        assertEquals(PIXELS * RGBA8 + PIXELS * DEPTH32F, traffic.storeBytes());
        assertTrue(traffic.depthAttachment());
    }

    @Test
    void partialRenderAreaScalesBytesAndIsNotFullCoverage() {
        int areaWidth = WIDTH / 2;
        int areaHeight = HEIGHT / 2;
        MetalPerformanceProbe.PassTraffic traffic = traffic(
                0, 0, areaWidth, areaHeight, true,
                new int[]{RGBA8}, new boolean[]{false}, DEPTH32F, false
        );

        long pixels = (long) areaWidth * areaHeight;
        assertEquals(pixels * (RGBA8 + DEPTH32F), traffic.loadBytes());
        assertEquals(pixels * (RGBA8 + DEPTH32F), traffic.storeBytes());
        assertFalse(traffic.fullCoverage(), "a smaller render area cannot become dontCare");
    }

    @Test
    void nonzeroAreaOriginIsNotFullCoverage() {
        MetalPerformanceProbe.PassTraffic traffic = traffic(
                8, 8, WIDTH, HEIGHT, true,
                new int[]{RGBA8}, new boolean[]{true}, 0, false
        );

        assertFalse(traffic.fullCoverage());
    }

    @Test
    void subViewAttachmentIsNotFullCoverage() {
        MetalPerformanceProbe.PassTraffic traffic = traffic(
                0, 0, WIDTH, HEIGHT, false,
                new int[]{RGBA8}, new boolean[]{true}, 0, false
        );

        assertFalse(traffic.fullCoverage(), "a mip/partial view still reads only its own extent");
    }

    @Test
    void mixedClearFlagsCountPerColorSlot() {
        MetalPerformanceProbe.PassTraffic traffic = traffic(
                0, 0, WIDTH, HEIGHT, true,
                new int[]{RGBA8, RGBA16F}, new boolean[]{true, false}, 0, false
        );

        assertEquals(PIXELS * RGBA16F, traffic.loadBytes(), "only the uncleared slot loads");
        assertEquals(PIXELS * (RGBA8 + RGBA16F), traffic.storeBytes());
        assertEquals(2, traffic.colorAttachments());
        assertTrue(traffic.fullCoverage());
    }

    @Test
    void unusedColorSlotsAreIgnored() {
        MetalPerformanceProbe.PassTraffic traffic = traffic(
                0, 0, WIDTH, HEIGHT, true,
                new int[]{0, RGBA8, 0}, new boolean[]{false, true, false}, 0, false
        );

        assertEquals(0L, traffic.loadBytes());
        assertEquals(PIXELS * RGBA8, traffic.storeBytes());
        assertEquals(1, traffic.colorAttachments());
    }

    @Test
    void depthOnlyPassCountsNoColorAttachment() {
        MetalPerformanceProbe.PassTraffic traffic = traffic(
                0, 0, WIDTH, HEIGHT, true,
                new int[]{0}, new boolean[]{false}, DEPTH32F, false
        );

        assertEquals(0, traffic.colorAttachments());
        assertEquals(PIXELS * DEPTH32F, traffic.loadBytes());
        assertEquals(PIXELS * DEPTH32F, traffic.storeBytes());
        assertTrue(traffic.fullCoverage());
    }
}
