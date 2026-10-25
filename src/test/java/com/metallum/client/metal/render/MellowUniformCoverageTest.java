package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the Mellow v3.4.1a Voxy-integration prewarm crash
 * {@code Iris uniform 'vxProjInv' (mat4) has no Metal or Iris value source}.
 *
 * <p>Mellow ships a Voxy integration even though the mod is absent: its normal
 * gbuffers/deferred/composite programs declare the {@code vx*} family under
 * {@code #ifndef VOXY_TERRAIN} (which is true for every port build), and the
 * following {@code #ifdef VOXY / #else} block declares {@code dh*} because
 * {@code VOXY} is never defined. Upstream loads these programs without Voxy
 * too (the Dh/Voxy programs need the mod's integration hooks, which the port
 * never invokes), so the fix is the GLSL default: a statically unused
 * {@code vx*} uniform is inactive in GL, and an unassigned {@code dh*} reads
 * zero. The port hoists every loose uniform into one explicit block, so it
 * must write that zero itself.
 *
 * <p>The audit mirrors {@code prepareWorldUniforms} (six sodium keys + every
 * {@link IrisMetalWorldBridge#WORLD_OVERRIDE_KEYS} entry + the graph programs)
 * for all three dimension program sets under the shipped and FANCY option
 * sets, then pins the new writer cases by value.
 */
final class MellowUniformCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "Mellow_3.4.1a.zip");
    /** {@code shaders.properties} option sets. */
    private static final Map<String, Map<String, String>> CONFIGS = new LinkedHashMap<>();
    /** Program sets the port can create; the family is per-dimension. */
    private static final Map<String, NamespacedId> DIMENSIONS = new LinkedHashMap<>();
    /** Every vx/dh member the audit must find in linked layouts. */
    private static final Set<String> EXPECTED_MEMBERS = Set.of(
            "vxProjInv", "vxProj", "vxProjPrev", "vxModelView", "vxModelViewInv",
            "vxModelViewPrev", "vxRenderDistance",
            "dhProjectionInverse", "dhProjection", "dhPreviousProjection", "dhRenderDistance"
    );

    static {
        CONFIGS.put("default", Map.of());
        CONFIGS.put("FANCY", Map.of("PROFILE", "FANCY"));
        DIMENSIONS.put("overworld", new NamespacedId("minecraft", "overworld"));
        DIMENSIONS.put("nether", new NamespacedId("minecraft", "the_nether"));
        DIMENSIONS.put("end", new NamespacedId("minecraft", "the_end"));
    }

    @Test
    void mellowWorldUniformBlocksHaveNoUnsupportedMembers() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        Map<String, UniformCoverageHarness.Audit> audits = UniformCoverageHarness.auditAll(
                PACK, DIMENSIONS, CONFIGS,
                holder -> {
                    // CommonUniforms.generalCommonUniforms cannot run headless (its
                    // `client.gui.hud::isHidden` method reference dereferences
                    // Minecraft.getInstance()); IrisExclusiveUniforms dereferences
                    // Minecraft.getInstance().level while registering. Register the
                    // names Mellow's layouts take from those blocks; production
                    // builds the full graph in MetalWorldRenderingPipeline.
                    holder.uniform2i(UniformUpdateFrequency.PER_FRAME, "eyeBrightness", () -> new Vector2i());
                    holder.uniform1i(UniformUpdateFrequency.PER_FRAME, "isEyeInWater", () -> 0);
                    holder.uniform1f(UniformUpdateFrequency.PER_TICK, "rainStrength", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_TICK, "wetness", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "thunderStrength", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_TICK, "endFlashIntensity", () -> 0.0f);
                    holder.uniform1i(UniformUpdateFrequency.PER_FRAME, "dhRenderDistance", () -> 0);
                    holder.uniform1b(UniformUpdateFrequency.PER_FRAME, "is_invisible", () -> false);
                    holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "blindness", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "darknessLightFactor", () -> 0.0f);
                    holder.uniform3d(UniformUpdateFrequency.PER_FRAME, "playerLookVector", () -> new Vector3d());
                }
        );

        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            System.out.println("[mellow-uniform-coverage] config=" + entry.getKey()
                    + " linkedPrograms=" + entry.getValue().linkedPrograms
                    + " declared=" + entry.getValue().declared.size()
                    + " graphSupplied=" + entry.getValue().graphSupplied.size()
                    + " rejected=" + entry.getValue().rejected);
        }

        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            UniformCoverageHarness.Audit audit = entry.getValue();
            assertTrue(audit.linkedPrograms > 0,
                    "Mellow '" + entry.getKey() + "': no world program linked at all");
            assertTrue(audit.rejected.isEmpty(),
                    "Mellow '" + entry.getKey() + "' world uniforms have neither a writer case"
                            + " nor an Iris graph source: " + audit.rejected
                            + " (graph-supplied declared names: " + audit.graphSupplied + ")");
            assertFalse(
                    audit.unsupported.contains("vxProjInv"),
                    "vxProjInv must have a writer value source in '" + entry.getKey() + "'"
            );
        }

        // The vx/dh family is declared across the shipped programs; the writer
        // cases must answer every member in every configuration.
        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            for (String name : EXPECTED_MEMBERS) {
                assertTrue(entry.getValue().declared.contains(name),
                        "Mellow '" + entry.getKey() + "' layouts must declare " + name
                                + "; declared=" + entry.getValue().declared);
                assertFalse(entry.getValue().unsupported.contains(name),
                        name + " must have a writer value source in '" + entry.getKey() + "'");
            }
        }

        // Pin the GLSL-default values with synthetic members (layout-independent):
        // mat4 -> all-zero matrix, int -> 0. The graph is consulted first, so
        // these cases only answer names no supplier owns.
        UniformCoverageHarness.Audit overworld = audits.get("overworld/default");
        for (String name : new String[]{
                "vxProjInv", "vxProj", "vxProjPrev", "vxModelView", "vxModelViewInv",
                "vxModelViewPrev"
        }) {
            ByteBuffer bytes = overworld.relaxed.writeUniformForGate(
                    new IrisMetalGlslLinker.UniformMember("mat4", name, 0, 0, 64)
            );
            assertFalse(overworld.relaxed.unsupportedNames().contains(name),
                    name + " must be answered by the writer's explicit zero-matrix case");
            for (int index = 0; index < 16; index++) {
                assertEquals(0.0f, bytes.getFloat(index * Float.BYTES), 0.0f,
                        name + " column " + index + " must default to zero");
            }
        }
        ByteBuffer distance = overworld.relaxed.writeUniformForGate(
                new IrisMetalGlslLinker.UniformMember("int", "vxRenderDistance", 0, 0, 4)
        );
        assertFalse(overworld.relaxed.unsupportedNames().contains("vxRenderDistance"),
                "vxRenderDistance must be answered by the writer's explicit int case");
        assertEquals(0, distance.getInt(0), "vxRenderDistance must default to 0");

        // The dh* family comes from pinned Iris itself: MatrixUniforms
        // (20e226b lines 41-45) registers dhProjection/dhProjectionInverse/
        // dhPreviousProjection and CommonUniforms:185 registers
        // dhRenderDistance, so the writer must not need fallbacks for them.
        for (String name : new String[]{
                "dhProjectionInverse", "dhProjection", "dhPreviousProjection", "dhRenderDistance"
        }) {
            assertTrue(overworld.graph.hasVariable(name),
                    name + " must stay graph-supplied by pinned Iris, not a writer default");
        }
    }
}
