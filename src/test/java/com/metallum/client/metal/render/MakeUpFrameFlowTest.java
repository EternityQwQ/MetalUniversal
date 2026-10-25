package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native-free frame-flow gate for the MakeUp-UltraFast black-screen report.
 *
 * <p>MakeUp is the first tested pack that (a) has a {@code prepare} program
 * writing {@code colortex1 + gaux4}, and (b) reads its {@code gaux3}
 * auto-exposure history in the <em>vertex</em> stage of {@code composite} and
 * {@code final}. The exposure loop is
 * {@code exposure = mix(f(scene), texture(gaux3, .5), exp(-frameTime*1.25))},
 * so if the previous frame's {@code gaux3} write is not visible to the next
 * frame's composite, exposure collapses to {@code (1-k)*f} (~2% of the scene)
 * and only saturated sky/sun pixels survive — the reported "nearly black
 * screen, sky flashing on camera movement" symptom.
 *
 * <p>This test runs the graph's real plans (no Metal device) through
 * {@link FrameFlowSimulator}, the shared two-frame ping-pong simulation, and
 * pins:
 * <ul>
 *   <li>{@code prepare} is planned with {@code DRAWBUFFERS:17} (colortex1 +
 *       gaux4);</li>
 *   <li>{@code composite} reads {@code gaux3} from the main side and writes
 *       the new exposure to the alt side (then canonicalized to main);</li>
 *   <li>frame N+1's composite sees frame N's exposure — not a cleared
 *       buffer;</li>
 *   <li>{@code final} reads the same-frame {@code composite} exposure and the
 *       same-frame {@code composite2} scene output.</li>
 * </ul>
 */
final class MakeUpFrameFlowTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "MakeUp-UltraFast-9.5f.zip");
    private static final Map<String, String> NO_EFFECTS = Map.of(
            "AA_TYPE", "0", "BLOOM", "false", "DOF", "false", "MOTION_BLUR", "false"
    );

    @Test
    void makeUpFrameFlowKeepsExposureHistoryAndSceneSides() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        // Default (bloom on): composite writes bloom + scene + exposure.
        assertFlow("default", Map.of(), new int[]{0, 1, 6});
        // no_effects profile approximation (AA/DOF/BLOOM/motion-blur off):
        // composite writes scene + exposure only.
        assertFlow("no_effects", NO_EFFECTS, new int[]{1, 6});
    }

    private static void assertFlow(
            final String label,
            final Map<String, String> config,
            final int[] expectedCompositeBuffers
    ) throws Exception {
        try (FileSystem fs = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fs.getPath("/shaders"), config,
                    StandardMacros.createStandardEnvironmentDefines(), false
            );
            ProgramSet programSet = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            int targetCount = IrisMetalRenderTargetFormats.from(programSet.getPackDirectives()).length;
            try (IrisMetalWorldPrograms programs = new IrisMetalWorldPrograms(1, programSet)) {
                IrisMetalExecutionGraph graph =
                        new IrisMetalExecutionGraph(1, programSet, programs, targetCount);
                List<IrisMetalExecutionGraph.PlannedPass> passes = graph.plannedPasses();

                IrisMetalExecutionGraph.PlannedPass prepare = pass(passes, "prepare");
                assertNotNull(prepare, label + ": MakeUp prepare was not planned");
                assertEquals("[1, 7]", Arrays.toString(prepare.drawBuffers()),
                        label + ": prepare must write colortex1 (main) + gaux4 (sky/fog)");

                IrisMetalExecutionGraph.PlannedPass composite = pass(passes, "composite");
                assertNotNull(composite, label + ": MakeUp composite was not planned");
                assertEquals(Arrays.toString(expectedCompositeBuffers),
                        Arrays.toString(composite.drawBuffers()),
                        label + ": composite draw buffers");

                // World draws land on the current read side of colortex1 before
                // the graph pass that follows them.
                List<FrameFlowSimulator.WorldWrite> worldWrites = List.of(
                        new FrameFlowSimulator.WorldWrite("deferred", 1, "gbuffers"),
                        new FrameFlowSimulator.WorldWrite("composite", 1, "translucent")
                );
                FrameFlowSimulator flow = FrameFlowSimulator.simulate(
                        passes, targetCount, worldWrites, 3, FrameFlowSimulator.EndMode.COMPLETE
                );

                String frame2CompositeRead = flow.read(2, "composite", 6);
                String frame2FinalExposure = flow.read(2, "final", 6);
                String frame2FinalScene = flow.read(2, "final", 1);

                assertEquals("clear", flow.read(1, "composite", 6),
                        label + ": frame 1 composite must read the cleared gaux3 history");
                assertEquals("f1:composite", flow.write(1, "composite", 6),
                        label + ": frame 1 composite must write the new exposure to gaux3");
                assertEquals("f1:composite", frame2CompositeRead,
                        label + ": frame 2 composite must read frame 1's exposure, not the cleared side"
                                + " (exposure would collapse to (1-k)*f and render black)");
                assertEquals("f2:composite", frame2FinalExposure,
                        label + ": final must read the same-frame composite exposure");
                assertEquals("f2:composite2", frame2FinalScene,
                        label + ": final must read the same-frame composite2 scene output");
                assertTrue(flow.endState(2).get(6),
                        label + ": the end-of-frame state must mark gaux3 so the history"
                                + " canonicalization covers it");
                assertEquals(0, flow.beginHistoryCopies(2),
                        label + ": a normal frame must not canonicalize its history twice;"
                                + " executeFinal already copied the read sides into main");
                assertEquals(0, flow.beginHistoryCopies(3),
                        label + ": the second normal frame must not repeat the history copy");
                assertEquals(1, flow.historyCopies(2, 6),
                        label + ": gaux3 must be copied from alt to main exactly once per frame");
                assertEquals(1, flow.historyCopies(3, 6),
                        label + ": gaux3 must stay at one history copy per normal frame");
                for (int target = 0; target < targetCount; target++) {
                    assertTrue(flow.historyCopies(2, target) <= 1,
                            label + ": target " + target + " was history-copied more than once");
                }
                assertEquals("f2:composite", flow.read(3, "composite", 6),
                        label + ": frame 3 composite must read frame 2's exposure");

                // Early exit (stage strip): executeFinal returns before its
                // canonicalization, so the next beginFrame must still copy the
                // surviving snapshot and keep the exposure history alive.
                FrameFlowSimulator early = FrameFlowSimulator.simulate(
                        passes, targetCount, worldWrites, 3, FrameFlowSimulator.EndMode.EARLY_EXIT
                );
                assertEquals(0, early.finalHistoryCopies(1),
                        label + ": an early-exit frame must not canonicalize in executeFinal");
                assertTrue(early.endState(1).get(6),
                        label + ": an early-exit frame must keep the gaux3 history snapshot");
                assertTrue(early.beginHistoryCopies(2) > 0,
                        label + ": the next beginFrame must canonicalize the early-exit snapshot");
                assertEquals(1, early.historyCopies(2, 6),
                        label + ": the early-exit gaux3 history must be copied exactly once at the"
                                + " next frame boundary");
                assertEquals("f1:composite", early.read(2, "composite", 6),
                        label + ": frame 2 composite must see frame 1's exposure after an early exit");
                assertEquals("f2:composite", early.read(3, "composite", 6),
                        label + ": exposure history must survive consecutive early-exit frames");
                System.out.println("[makeup-frame-flow] " + label
                        + " prepare=" + Arrays.toString(prepare.drawBuffers())
                        + " composite=" + Arrays.toString(composite.drawBuffers())
                        + " frame2Reads=" + frame2CompositeRead
                        + " finalExposure=" + frame2FinalExposure
                        + " finalScene=" + frame2FinalScene
                        + " frame2HistoryCopies=" + flow.historyCopies(2, 6)
                        + " earlyExitBeginCopies=" + early.beginHistoryCopies(2));
            }
        }
    }

    private static IrisMetalExecutionGraph.PlannedPass pass(
            final List<IrisMetalExecutionGraph.PlannedPass> passes,
            final String name
    ) {
        for (IrisMetalExecutionGraph.PlannedPass pass : passes) {
            if (pass.name().equals(name)) {
                return pass;
            }
        }
        return null;
    }
}
