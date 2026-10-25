package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the Sildur's Vibrant 2.02 prewarm crash
 * {@code Iris uniform 'isNether' (bool) has no Metal or Iris value source}.
 *
 * <p>{@code deferred.fsh} declares {@code uniform bool isNether;} (and the
 * per-dimension variants do too) but neither the pack nor upstream Iris
 * registers a supplier for it. OptiFine/Iris semantics for a declared uniform
 * with no supplier are the GLSL default; the port instead supplies the
 * faithful dimension answer (current world is the Nether), because Sildur's
 * uses the flag to switch its nether-specific math.
 *
 * <p>The audit itself (world + graph programs, all three dimension program
 * sets, shipped and {@code nMap=1} option sets, real custom-uniform graph)
 * lives in {@link UniformCoverageHarness}; this test keeps the pack-specific
 * assertions: no rejected member anywhere and {@code isNether} pinned as
 * writer-handled with the neutral-frame value 0.
 */
final class SildursUniformCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "Sildurs.zip");
    /** {@code shaders.properties} option in parentheses. */
    private static final Map<String, Map<String, String>> CONFIGS = new LinkedHashMap<>();
    /** Program sets the port can create; the crash family is per-dimension. */
    private static final Map<String, NamespacedId> DIMENSIONS = new LinkedHashMap<>();

    static {
        CONFIGS.put("default", Map.of());
        CONFIGS.put("nMap=1", Map.of("nMap", "1"));
        DIMENSIONS.put("overworld", new NamespacedId("minecraft", "overworld"));
        DIMENSIONS.put("nether", new NamespacedId("minecraft", "the_nether"));
        DIMENSIONS.put("end", new NamespacedId("minecraft", "the_end"));
    }

    @Test
    void sildursWorldUniformBlocksHaveNoUnsupportedMembers() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        Map<String, UniformCoverageHarness.Audit> audits = UniformCoverageHarness.auditAll(
                PACK, DIMENSIONS, CONFIGS,
                holder -> {
                    // CommonUniforms.generalCommonUniforms cannot run headless (its
                    // `client.gui.hud::isHidden` method reference dereferences
                    // Minecraft.getInstance()). Register the names Sildur's world
                    // layouts take from it; production builds the full graph in
                    // MetalWorldRenderingPipeline.
                    holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "blindness", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "darknessLightFactor", () -> 0.0f);
                }
        );

        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            System.out.println("[sildurs-uniform-coverage] config=" + entry.getKey()
                    + " linkedPrograms=" + entry.getValue().linkedPrograms
                    + " declared=" + entry.getValue().declared.size()
                    + " graphSupplied=" + entry.getValue().graphSupplied
                    + " rejected=" + entry.getValue().rejected);
        }

        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            UniformCoverageHarness.Audit audit = entry.getValue();
            assertTrue(audit.linkedPrograms > 0,
                    "Sildur's '" + entry.getKey() + "': no world program linked at all");
            assertTrue(audit.rejected.isEmpty(),
                    "Sildur's '" + entry.getKey() + "' world uniforms have neither a writer case"
                            + " nor an Iris graph source: " + audit.rejected
                            + " (graph-supplied declared names: " + audit.graphSupplied + ")");
        }

        // The crash trigger must stay declared and writer-handled in every
        // configuration, so removing the case cannot regress silently.
        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            assertTrue(entry.getValue().declared.contains("isNether"),
                    "Sildur's '" + entry.getKey() + "' layouts must declare isNether: "
                            + entry.getValue().declared);
            assertFalse(entry.getValue().unsupported.contains("isNether"),
                    "isNether must have a writer value source");
        }

        // Pin the exact writer path with a synthetic member (layout-independent):
        // the neutral frame is not the Nether, so the bool must write 0.
        UniformCoverageHarness.Audit overworld = audits.get("overworld/default");
        ByteBuffer bytes = overworld.relaxed.writeUniformForGate(
                new IrisMetalGlslLinker.UniformMember("bool", "isNether", 0, 0, 4)
        );
        assertFalse(overworld.relaxed.unsupportedNames().contains("isNether"),
                "isNether must be answered by the writer's explicit case");
        assertEquals(0, bytes.getInt(0), "neutral frame must write isNether=false");
    }
}
