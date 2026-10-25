package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic gate for the two probe additions that explain encoder reuse:
 * the {@code perf encoders reuse=…} attribution of one
 * {@code renderCommandEncoder} call and the {@code perf order frame=N a | b}
 * dump of the first frames' pass labels. No Metal device, no Iris runtime and
 * no render target is touched.
 */
final class MetalPerformanceProbeEncoderDecisionTest {
    private static MetalPerformanceProbe.EncoderDecision decide(
            final boolean hasOpenEncoder,
            final boolean openIsRenderEncoder,
            final int openColorSlots,
            final int newColorSlots,
            final boolean sameSlotHandles,
            final boolean sameDepthHandle,
            final boolean clearPending
    ) {
        return MetalPerformanceProbe.encoderDecision(
                hasOpenEncoder, openIsRenderEncoder, openColorSlots, newColorSlots,
                sameSlotHandles, sameDepthHandle, clearPending
        );
    }

    /** Every argument is the "identical attachments, no clear" state. */
    private static MetalPerformanceProbe.EncoderDecision identical(final boolean clearPending) {
        return decide(true, true, 1, 1, true, true, clearPending);
    }

    @Test
    void noOpenEncoderIsAttributedToNoOpen() {
        assertEquals(
                MetalPerformanceProbe.EncoderDecision.NO_OPEN,
                decide(false, false, 0, 1, false, false, false)
        );
        assertEquals(
                MetalPerformanceProbe.EncoderDecision.NO_OPEN,
                // Even with a clear pending: nothing can be reused, and the
                // missing encoder is the branch that fires.
                decide(false, false, 0, 0, true, true, true)
        );
    }

    @Test
    void blitOrComputeEncoderIsAttributedToKind() {
        assertEquals(
                MetalPerformanceProbe.EncoderDecision.KIND,
                decide(true, false, 0, 1, false, false, false),
                "a blit/compute encoder has no attachment set to compare"
        );
    }

    @Test
    void colorSlotCountMismatchIsColorCount() {
        assertEquals(
                MetalPerformanceProbe.EncoderDecision.COLOR_COUNT,
                decide(true, true, 3, 1, false, false, false)
        );
        assertEquals(
                MetalPerformanceProbe.EncoderDecision.COLOR_COUNT,
                decide(true, true, 0, 1, true, true, false),
                "the transient clear encoder leaves no recorded color slots"
        );
    }

    @Test
    void sameSlotCountWithDifferentHandleIsSlotHandle() {
        assertEquals(
                MetalPerformanceProbe.EncoderDecision.SLOT_HANDLE,
                decide(true, true, 2, 2, false, true, false)
        );
    }

    @Test
    void colorSetIdenticalButDepthHandleDifferentIsDepth() {
        assertEquals(
                MetalPerformanceProbe.EncoderDecision.DEPTH,
                decide(true, true, 1, 1, true, false, false)
        );
    }

    @Test
    void identicalAttachmentsWithPendingClearIsClear() {
        assertEquals(MetalPerformanceProbe.EncoderDecision.CLEAR, identical(true));
        assertEquals(
                MetalPerformanceProbe.EncoderDecision.CLEAR,
                identical(true),
                "a clear on either color or depth blocks the merge"
        );
    }

    @Test
    void identicalAttachmentsWithoutClearIsReuse() {
        assertEquals(MetalPerformanceProbe.EncoderDecision.REUSE, identical(false));
    }

    @Test
    void attachmentDifferenceOutranksTheClearAttribution() {
        // Both a clear and a different attachment set block reuse; the
        // attachment identity is the physical cause, so it wins.
        assertEquals(
                MetalPerformanceProbe.EncoderDecision.SLOT_HANDLE,
                decide(true, true, 1, 1, false, true, true)
        );
        assertEquals(
                MetalPerformanceProbe.EncoderDecision.DEPTH,
                decide(true, true, 1, 1, true, false, true)
        );
    }

    @Test
    void everyDecisionHasADistinctProbeToken() {
        String[] expected = {"reuse", "noOpen", "kind", "colorCount", "slotHandle", "depth", "clear", "other"};
        MetalPerformanceProbe.EncoderDecision[] decisions = MetalPerformanceProbe.EncoderDecision.values();
        assertEquals(expected.length, decisions.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], decisions[index].token);
            assertEquals(index, decisions[index].ordinal());
        }
    }

    @Test
    void decisionSummaryLineMatchesTheDocumentedFormat() {
        long[] counts = {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L};
        assertEquals(
                "perf encoders reuse=1 noOpen=2 kind=3 colorCount=4 slotHandle=5 depth=6 clear=7 other=8",
                MetalPerformanceProbe.encoderDecisionSummary(counts)
        );
    }

    @Test
    void decisionSummaryToleratesAMissingWindow() {
        assertEquals(
                "perf encoders reuse=0 noOpen=0 kind=0 colorCount=0 slotHandle=0 depth=0 clear=0 other=0",
                MetalPerformanceProbe.encoderDecisionSummary(null)
        );
    }

    @Test
    void orderEntryAppendsTheFlipSideSuffix() {
        assertEquals("Terrain(main)", MetalPerformanceProbe.orderEntry("Terrain", "main"));
        assertEquals("Terrain(alt)", MetalPerformanceProbe.orderEntry("Terrain", "alt"));
        assertEquals("Terrain", MetalPerformanceProbe.orderEntry("Terrain", null));
        assertEquals("unnamed", MetalPerformanceProbe.orderEntry(null, null));
        assertEquals("unnamed(alt)", MetalPerformanceProbe.orderEntry("", "alt"));
    }

    @Test
    void orderEntryKeepsTheLineParseable() {
        assertEquals(
                "Iris_composite:_composite(main)",
                MetalPerformanceProbe.orderEntry("Iris_composite:_composite", "main")
        );
        assertFalse(
                MetalPerformanceProbe.orderEntry("bad | label", null).contains("|"),
                "the dump separator must not appear inside a label"
        );
    }

    @Test
    void orderLineJoinsLabelsInCreationOrder() {
        String[] labels = {"Terrain", "Immediate_draw_with_minecraft:pipeline/entity_cutout", "Iris_composite:_composite"};
        assertEquals(
                "perf order frame=2 Terrain | Immediate_draw_with_minecraft:pipeline/entity_cutout"
                        + " | Iris_composite:_composite",
                MetalPerformanceProbe.passOrderLine(2, labels, 3, false)
        );
    }

    @Test
    void orderLineIsJustTheHeaderWhenNothingWasRecorded() {
        assertEquals("perf order frame=3", MetalPerformanceProbe.passOrderLine(3, new String[0], 0, false));
    }

    @Test
    void orderCaptureStopsAtEightyEntriesAndMarksTheOverflow() {
        String[] labels = new String[MetalPerformanceProbe.ORDER_ENTRY_LIMIT];
        int count = 0;
        for (int index = 0; index < 85; index++) {
            count = MetalPerformanceProbe.appendOrderEntry(labels, count, "pass" + index);
        }
        assertEquals(80, count);
        assertFalse(java.util.Arrays.asList(labels).contains("pass80"));

        String line = MetalPerformanceProbe.passOrderLine(1, labels, count, true);
        assertTrue(line.startsWith("perf order frame=1 pass0 | pass1"));
        assertTrue(line.endsWith(" | pass79 | …"), line);
        // 79 inner separators + the one before the ellipsis; no 81st pass.
        assertEquals(80, line.split(" \\| ", -1).length - 1);
    }

    @Test
    void orderLineWithoutOverflowHasNoEllipsis() {
        String[] labels = new String[MetalPerformanceProbe.ORDER_ENTRY_LIMIT];
        int count = MetalPerformanceProbe.appendOrderEntry(labels, 0, "Terrain");
        String line = MetalPerformanceProbe.passOrderLine(1, labels, count, false);
        assertEquals("perf order frame=1 Terrain", line);
        assertFalse(line.contains("…"));
    }
}
