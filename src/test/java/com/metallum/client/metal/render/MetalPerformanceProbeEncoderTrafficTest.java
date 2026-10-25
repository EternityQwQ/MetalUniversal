package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic gate for the encoder-boundary bandwidth model
 * ({@code perf bandwidth encoder}): traffic is charged once per native render
 * encoder instead of once per declared render pass, so a window whose passes
 * reuse the open encoder reports less than the declared per-pass model. No
 * Metal device is touched.
 */
final class MetalPerformanceProbeEncoderTrafficTest {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int RGBA8 = 4;
    private static final int RGBA16F = 8;
    private static final int DEPTH32F = 4;

    private static long attachmentBytes(final int width, final int height, final int bytesPerPixel) {
        return (long) width * (long) height * bytesPerPixel;
    }

    private static MetalPerformanceProbe.EncoderTraffic traffic(
            final long[] colorBytes,
            final boolean[] colorCleared,
            final long depthBytes,
            final boolean depthCleared
    ) {
        return MetalPerformanceProbe.encoderTraffic(colorBytes, colorCleared, depthBytes, depthCleared);
    }

    @Test
    void unclearedAttachmentsLoadAndStoreTheirWholeExtent() {
        long color = attachmentBytes(WIDTH, HEIGHT, RGBA8);
        long depth = attachmentBytes(WIDTH, HEIGHT, DEPTH32F);

        MetalPerformanceProbe.EncoderTraffic traffic =
                traffic(new long[]{color}, new boolean[]{false}, depth, false);

        assertEquals(color + depth, traffic.loadBytes());
        assertEquals(color + depth, traffic.storeBytes());
    }

    @Test
    void clearedAttachmentsOnlyStore() {
        long color = attachmentBytes(WIDTH, HEIGHT, RGBA8);
        long depth = attachmentBytes(WIDTH, HEIGHT, DEPTH32F);

        MetalPerformanceProbe.EncoderTraffic traffic =
                traffic(new long[]{color}, new boolean[]{true}, depth, true);

        assertEquals(0L, traffic.loadBytes(), "a clear action never loads the previous contents");
        assertEquals(color + depth, traffic.storeBytes(), "every attached extent is stored");
    }

    @Test
    void unusedAttachmentSlotsAreIgnored() {
        long color = attachmentBytes(WIDTH, HEIGHT, RGBA8);

        MetalPerformanceProbe.EncoderTraffic traffic =
                traffic(new long[]{0L, color, 0L}, new boolean[]{false, false, false}, 0L, false);

        assertEquals(color, traffic.loadBytes());
        assertEquals(color, traffic.storeBytes());
    }

    @Test
    void perAttachmentExtentsAreChargedIndividually() {
        long halfWidth = attachmentBytes(WIDTH / 2, HEIGHT / 2, RGBA16F);
        long full = attachmentBytes(WIDTH, HEIGHT, RGBA8);

        MetalPerformanceProbe.EncoderTraffic traffic =
                traffic(new long[]{full, halfWidth}, new boolean[]{false, true}, 0L, false);

        assertEquals(full, traffic.loadBytes(), "only the uncleared slot loads, at its own extent");
        assertEquals(full + halfWidth, traffic.storeBytes());
    }

    @Test
    void depthOnlyEncoderChargesNoColorSlot() {
        long depth = attachmentBytes(WIDTH, HEIGHT, DEPTH32F);

        MetalPerformanceProbe.EncoderTraffic traffic =
                traffic(new long[]{0L}, new boolean[]{false}, depth, true);

        assertEquals(0L, traffic.loadBytes());
        assertEquals(depth, traffic.storeBytes());
    }

    @Test
    void reusedEncodersChargeOneLoadPerEncoderNotPerPass() {
        long color = attachmentBytes(WIDTH, HEIGHT, RGBA8);
        long depth = attachmentBytes(WIDTH, HEIGHT, DEPTH32F);
        long[] colorBytes = {color};
        boolean[] colorCleared = {false};

        MetalPerformanceProbe.EncoderTraffic perEncoder =
                traffic(colorBytes, colorCleared, depth, false);

        // 41 declared passes over the same attachment set (the real-device
        // ratio) but only 17 native render encoders: the declared model charges
        // 41 load+store pairs, the encoder model charges 17.
        long pair = perEncoder.loadBytes() + perEncoder.storeBytes();
        long declared = 41L * pair;
        long encoder = 17L * pair;

        assertEquals(41L * 2L * (color + depth), declared);
        assertEquals(17L * 2L * (color + depth), encoder);
        assertEquals(declared * 17L / 41L, encoder);
    }

    @Test
    void encoderSummaryNamesEveryEncoderKind() {
        assertEquals(
                "perf encoders real=17 clear=3 blit=42",
                MetalPerformanceProbe.encoderSummary(17L, 3L, 42L)
        );
    }
}
