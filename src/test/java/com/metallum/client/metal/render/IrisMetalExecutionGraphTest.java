package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalExecutionGraphTest {
    @Test
    void drawBuffersFlipBeforeExplicitTrueFlip() {
        BitSet before = new BitSet();
        IrisMetalExecutionGraph.FlipTransition transition = IrisMetalExecutionGraph.transition(
                before, new int[]{0}, Map.of(0, true), 2
        );

        assertEquals(new BitSet(), transition.readsFromAlt());
        assertEquals(new BitSet(), transition.stateAfter(), "the two toggles cancel");
    }

    @Test
    void explicitFalseSuppressesImplicitDrawBuffersFlip() {
        IrisMetalExecutionGraph.FlipTransition transition = IrisMetalExecutionGraph.transition(
                new BitSet(), new int[]{1}, Map.of(1, false), 2
        );

        assertEquals(new BitSet(), transition.stateAfter());
    }

    @Test
    void invalidAndRepeatedDrawBuffersFailClosed() {
        assertThrows(
                IllegalStateException.class,
                () -> IrisMetalExecutionGraph.validateDrawBuffers("bad", new int[]{2}, 2)
        );
        assertThrows(
                IllegalStateException.class,
                () -> IrisMetalExecutionGraph.validateDrawBuffers("duplicate", new int[]{0, 0}, 2)
        );
    }

    @Test
    void explicitFlipTargetRangeIsStrict() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalExecutionGraph.transition(
                        new BitSet(), new int[]{0}, Map.of(2, true), 2
                )
        );
    }

    @Test
    void rasterStorageBindingsKeepLogicalSsboIdentity() {
        String descriptor = MetalCrossShaderCompiler.storageBufferDescriptorName(7, "voxelData");
        assertEquals("iris_ssbo/7/voxelData", descriptor);
        assertEquals(7, MetalCrossShaderCompiler.storageBufferLogicalBinding(descriptor));
        assertEquals(-1, MetalCrossShaderCompiler.storageBufferLogicalBinding("voxelData"));
    }

    @Test
    void linkerDistinguishesStorageImagesFromSampledSamplers() {
        assertEquals(
                true,
                new IrisMetalGlslLinker.SamplerDecl("lightimg0", "image3D").storageImage()
        );
        assertEquals(
                false,
                new IrisMetalGlslLinker.SamplerDecl("voxeltex", "sampler3D").storageImage()
        );
    }


    /** Normal final passes consume the snapshot; stage-strip early exits must not. */
    @Test
    void finalConsumesHistorySnapshotOnlyWhenItCanonicalized() {
        assertTrue(IrisMetalExecutionGraph.consumesHistorySnapshot(false, true),
                "a completed final pass under frameHistory=on consumes the snapshot");
        assertFalse(IrisMetalExecutionGraph.consumesHistorySnapshot(true, true),
                "stage-strip must leave the snapshot for the next beginFrame");
        assertFalse(IrisMetalExecutionGraph.consumesHistorySnapshot(false, false),
                "frameHistory=off keeps the old behavior (no snapshot consumption)");
    }

    /**
     * The Q1 fast path: executeFinal canonicalizes once, and the following
     * beginFrame must not repeat it. Stage-strip frames never reach the final
     * copy, so their snapshot survives to the next frame boundary.
     */
    @Test
    void frameFlowCopiesHistoryOncePerFrameAcrossNormalAndEarlyExitFrames() {
        BitSet none = new BitSet();
        BitSet flipped = new BitSet();
        flipped.set(0);
        List<IrisMetalExecutionGraph.PlannedPass> passes = List.of(
                new IrisMetalExecutionGraph.PlannedPass(
                        "COMPOSITE", "composite", new int[]{0}, none, flipped
                ),
                new IrisMetalExecutionGraph.PlannedPass(
                        "FINAL", "final", new int[]{0}, flipped, flipped
                )
        );

        FrameFlowSimulator normal = FrameFlowSimulator.simulate(
                passes, 1, List.of(), 2, FrameFlowSimulator.EndMode.COMPLETE
        );
        assertEquals(1, normal.finalHistoryCopies(1), "executeFinal copies once");
        assertEquals(0, normal.beginHistoryCopies(2),
                "the next beginFrame must not repeat the canonicalization");
        assertEquals(1, normal.historyCopies(2, 0), "one history copy per normal frame");

        FrameFlowSimulator early = FrameFlowSimulator.simulate(
                passes, 1, List.of(), 2, FrameFlowSimulator.EndMode.EARLY_EXIT
        );
        assertEquals(0, early.finalHistoryCopies(1), "early exit skips the final copy");
        assertEquals(1, early.beginHistoryCopies(2),
                "the next beginFrame must canonicalize the preserved snapshot");
        assertEquals(1, early.historyCopies(2, 0),
                "the early-exit history is still copied exactly once");
    }

    /**
     * The {@code beginFrame} aliasing regression: {@code executeStage} assigns
     * {@code state = plan.stateAfter()} and the next frame's {@code beginFrame}
     * clears that graph field in place. With the record's default accessor the
     * clear also wiped the plan's own bookmark, so every later frame ran with
     * an empty flip state: MakeUp's exposure history was never canonicalized
     * back to {@code main} and its output stayed black. The accessors (and the
     * compact constructor) must copy.
     */
    @Test
    void rasterPlanAccessorsReturnIndependentSnapshots() {
        BitSet reads = new BitSet();
        reads.set(1);
        BitSet after = new BitSet();
        after.set(4);
        after.set(6);
        IrisMetalExecutionGraph.RasterPlan plan = new IrisMetalExecutionGraph.RasterPlan(
                IrisMetalExecutionGraph.Stage.COMPOSITE, 0, "composite", null,
                new int[]{0, 3}, reads, after, "token"
        );

        // Constructor copies: reusing/clearing the caller's BitSets is safe.
        reads.clear();
        after.clear();
        assertTrue(plan.readsFromAlt().get(1), "constructor must copy readsFromAlt");
        assertTrue(plan.stateAfter().get(6), "constructor must copy stateAfter");

        // The exact bug: the graph field aliases the accessor result, then
        // beginFrame() clears it in place.
        BitSet state = plan.stateAfter();
        state.clear();
        assertTrue(plan.stateAfter().get(4), "stateAfter must survive an in-place clear");
        assertTrue(plan.stateAfter().get(6), "stateAfter must survive an in-place clear");
        BitSet shadowState = plan.readsFromAlt();
        shadowState.clear();
        assertTrue(plan.readsFromAlt().get(1), "readsFromAlt must survive an in-place clear");

        // Callers cannot reach the plan's draw-buffer array either.
        int[] buffers = plan.drawBuffers();
        buffers[0] = 99;
        assertEquals(0, plan.drawBuffers()[0], "drawBuffers must return a copy");
    }
}
