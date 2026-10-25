package com.metallum.client.metal.render;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native-free multi-frame ping-pong simulation over the execution graph's
 * planned passes. Mirrors {@code beginFrame}'s history canonicalization,
 * {@code executeStage}'s read-snapshot / opposite-side write / flip-state
 * advance, {@code executeFinal}'s FINAL-stage rule (reads with its snapshot,
 * writes no colortex) and the end-of-frame canonicalization. Test-only.
 *
 * <p>The world bridge's own writes before the graph passes (gbuffer/translucent
 * draws landing on the current read side) are declared by the caller through
 * {@link WorldWrite} so the simulation stays pack-agnostic.</p>
 */
final class FrameFlowSimulator {
    /** A world-bridge write landing on the target's current read side before a pass. */
    record WorldWrite(String beforePass, int target, String label) {
    }

    /** How the frame reaches its last boundary. */
    enum EndMode {
        /** {@code executeFinal} ran and canonicalized its read sides into main. */
        COMPLETE,
        /**
         * Stage-strip/debug early exit: {@code executeFinal} returns before the
         * final pass and before canonicalization. The snapshot survives for
         * the next {@code beginFrame}.
         */
        EARLY_EXIT
    }

    private static final int DEFAULT_FRAMES = 2;

    private final Map<String, String> reads = new HashMap<>();
    private final Map<String, String> writes = new HashMap<>();
    private final Map<Integer, BitSet> endStates = new HashMap<>();
    private final Map<Integer, Integer> beginHistoryCopies = new HashMap<>();
    private final Map<Integer, Integer> finalHistoryCopies = new HashMap<>();
    private final Map<String, Integer> historyCopiesByTarget = new HashMap<>();

    private FrameFlowSimulator() {
    }

    /** Runs two complete frames and returns the queryable observations. */
    static FrameFlowSimulator simulate(
            final List<IrisMetalExecutionGraph.PlannedPass> passes,
            final int targetCount,
            final List<WorldWrite> worldWrites
    ) {
        return simulate(passes, targetCount, worldWrites, DEFAULT_FRAMES, EndMode.COMPLETE);
    }

    /** Runs the requested number of frames with the same end mode each frame. */
    static FrameFlowSimulator simulate(
            final List<IrisMetalExecutionGraph.PlannedPass> passes,
            final int targetCount,
            final List<WorldWrite> worldWrites,
            final int frames,
            final EndMode endMode
    ) {
        FrameFlowSimulator flow = new FrameFlowSimulator();
        String[][] side = new String[targetCount][2];
        BitSet flipped = new BitSet();
        for (int frame = 1; frame <= frames; frame++) {
            // beginFrame: canonicalize only when the previous frame did not
            // consume its history snapshot (early exit). A normal frame's
            // executeFinal already copied the read sides into main.
            int beginCopies = 0;
            for (int t = flipped.nextSetBit(0); t >= 0; t = flipped.nextSetBit(t + 1)) {
                side[t][0] = side[t][1];
                beginCopies++;
                flow.countHistoryCopy(frame, t);
            }
            flow.beginHistoryCopies.put(frame, beginCopies);
            flipped.clear();
            if (frame == 1) {
                for (int t = 0; t < targetCount; t++) {
                    side[t][0] = "clear";
                    side[t][1] = "clear";
                }
            }

            for (IrisMetalExecutionGraph.PlannedPass pass : passes) {
                if (endMode == EndMode.EARLY_EXIT && pass.stage().equals("FINAL")) {
                    continue;
                }
                int[] buffers = pass.drawBuffers();
                BitSet readsFromAlt = pass.readsFromAlt();
                for (WorldWrite worldWrite : worldWrites) {
                    if (worldWrite.beforePass().equals(pass.name())) {
                        int target = worldWrite.target();
                        side[target][flipped.get(target) ? 1 : 0] =
                                "f" + frame + ":" + worldWrite.label();
                    }
                }
                // Every pass samples through its snapshot, including FINAL.
                for (int t = 0; t < targetCount; t++) {
                    flow.reads.put(key(frame, pass.name(), t), side[t][readsFromAlt.get(t) ? 1 : 0]);
                }
                if (!pass.stage().equals("FINAL")) {
                    for (int t : buffers) {
                        int writeSide = readsFromAlt.get(t) ? 0 : 1;
                        String marker = "f" + frame + ":" + pass.name();
                        side[t][writeSide] = marker;
                        flow.writes.put(key(frame, pass.name(), t), marker);
                    }
                }
                flipped.clear();
                flipped.or(pass.stateAfter());
            }

            int finalCopies = 0;
            if (endMode == EndMode.COMPLETE) {
                // executeFinal: canonicalize every flipped target's read side
                // into main, then consume the snapshot so beginFrame does not
                // repeat the same blit.
                for (int t = flipped.nextSetBit(0); t >= 0; t = flipped.nextSetBit(t + 1)) {
                    side[t][0] = side[t][1];
                    finalCopies++;
                    flow.countHistoryCopy(frame, t);
                }
                flow.endStates.put(frame, (BitSet) flipped.clone());
                if (IrisMetalExecutionGraph.consumesHistorySnapshot(
                        false, MetalDebugSwitches.FRAME_HISTORY
                )) {
                    // The next beginFrame must not copy these targets again.
                    flipped.clear();
                }
            } else {
                // Early exit: the final pass did not run, so the snapshot must
                // survive into the next frame's beginFrame canonicalization.
                flow.endStates.put(frame, (BitSet) flipped.clone());
            }
            flow.finalHistoryCopies.put(frame, finalCopies);
        }
        return flow;
    }

    /** Side label read by {@code pass} in {@code frame} for {@code target}, or null. */
    String read(final int frame, final String pass, final int target) {
        return reads.get(key(frame, pass, target));
    }

    /** Marker written by {@code pass} in {@code frame} for {@code target}, or null. */
    String write(final int frame, final String pass, final int target) {
        return writes.get(key(frame, pass, target));
    }

    /** History copies performed by {@code beginFrame} for {@code frame}. */
    int beginHistoryCopies(final int frame) {
        return beginHistoryCopies.getOrDefault(frame, 0);
    }

    /** History copies performed by {@code executeFinal} for {@code frame}. */
    int finalHistoryCopies(final int frame) {
        return finalHistoryCopies.getOrDefault(frame, 0);
    }

    /** Total history copies for one frame (frame boundary + final pass). */
    int historyCopies(final int frame) {
        return beginHistoryCopies(frame) + finalHistoryCopies(frame);
    }

    /** History copies performed for one target in one frame. */
    int historyCopies(final int frame, final int target) {
        return historyCopiesByTarget.getOrDefault(targetKey(frame, target), 0);
    }

    private void countHistoryCopy(final int frame, final int target) {
        String key = targetKey(frame, target);
        historyCopiesByTarget.merge(key, 1, Integer::sum);
    }

    /** Copy of the flip state after the frame's end-of-frame canonicalization. */
    BitSet endState(final int frame) {
        BitSet state = endStates.get(frame);
        return state == null ? new BitSet() : (BitSet) state.clone();
    }

    private static String key(final int frame, final String pass, final int target) {
        return frame + "|" + pass + "|" + target;
    }

    private static String targetKey(final int frame, final int target) {
        return frame + "|" + target;
    }
}
