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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the Complementary Unbound r5.9.3 prewarm crash family:
 * its {@code shaders.properties:269-275} biome flags
 * ({@code inNetherWastes}, {@code inCrimsonForest}, {@code inWarpedForest},
 * {@code inBasaltDeltas}, {@code inSoulValley}, {@code inPaleGarden},
 * {@code inSulfurCaves}) are defined as
 * {@code smooth(..., if(in(biome, BIOME_*), 1, 0), ...)}, but no upstream Iris
 * build (pinned 20e226b included) supplies the {@code BIOME_*} constants.
 * Stareval therefore drops the variables, desktop GL leaves the declared
 * uniforms at the GLSL default 0, and the port's strict prewarm used to throw
 * {@code has no Metal or Iris value source}. The writer answers them with the
 * faithful 0; if upstream ever adds the constants the custom-uniform graph
 * resolves first and the fallback becomes inert.
 *
 * <p>The audit itself (world + graph programs, all three dimension program
 * sets, shipped and {@code RP_MODE=3} option sets, real custom-uniform graph)
 * lives in {@link UniformCoverageHarness}; this test keeps the pack-specific
 * assertions: no rejected member anywhere, every crashed flag declared and
 * writer-handled, and the neutral-frame writer value pinned to 0.
 */
final class ComplementaryUnboundUniformCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "ComplementaryUnbound.zip");
    /** The seven biome smooth-flags the crash family covers. */
    private static final Set<String> BIOME_FLAGS = Set.of(
            "inNetherWastes", "inCrimsonForest", "inWarpedForest",
            "inBasaltDeltas", "inSoulValley", "inPaleGarden", "inSulfurCaves"
    );
    /** {@code shaders.properties} option in parentheses. */
    private static final Map<String, Map<String, String>> CONFIGS = new LinkedHashMap<>();
    /** Program sets the port can create; the crash family is per-dimension. */
    private static final Map<String, NamespacedId> DIMENSIONS = new LinkedHashMap<>();

    static {
        CONFIGS.put("default", Map.of());
        CONFIGS.put("RP_MODE=3", Map.of("RP_MODE", "3"));
        DIMENSIONS.put("overworld", new NamespacedId("minecraft", "overworld"));
        DIMENSIONS.put("nether", new NamespacedId("minecraft", "the_nether"));
        DIMENSIONS.put("end", new NamespacedId("minecraft", "the_end"));
    }

    @Test
    void unboundWorldUniformBlocksHaveNoUnsupportedMembers() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        Map<String, UniformCoverageHarness.Audit> audits = UniformCoverageHarness.auditAll(
                PACK, DIMENSIONS, CONFIGS,
                holder -> {
                    // CommonUniforms.generalCommonUniforms and
                    // IrisExclusiveUniforms.addIrisExclusiveUniforms cannot run
                    // headless (they dereference Minecraft.getInstance() while
                    // registering), so register the names Unbound's custom
                    // uniforms and world layouts take from them:
                    // eyeBrightness/rainStrength/isEyeInWater/is_invisible from
                    // generalCommonUniforms (isEyeInCave and the smoothed
                    // eyeBrightness variables resolve through them) and
                    // playerLookVector from IrisExclusiveUniforms.
                    holder.uniform2i(UniformUpdateFrequency.PER_FRAME, "eyeBrightness", () -> new Vector2i());
                    holder.uniform1i(UniformUpdateFrequency.PER_FRAME, "isEyeInWater", () -> 0);
                    holder.uniform1f(UniformUpdateFrequency.PER_TICK, "rainStrength", () -> 0.0f);
                    holder.uniform1b(UniformUpdateFrequency.PER_FRAME, "is_invisible", () -> false);
                    holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "blindness", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "darknessLightFactor", () -> 0.0f);
                    holder.uniform3d(UniformUpdateFrequency.PER_FRAME, "playerLookVector", () -> new Vector3d());
                }
        );

        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            System.out.println("[unbound-uniform-coverage] config=" + entry.getKey()
                    + " linkedPrograms=" + entry.getValue().linkedPrograms
                    + " declared=" + entry.getValue().declared.size()
                    + " graphSupplied=" + entry.getValue().graphSupplied
                    + " rejected=" + entry.getValue().rejected);
        }

        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            UniformCoverageHarness.Audit audit = entry.getValue();
            assertTrue(audit.linkedPrograms > 0,
                    "Unbound '" + entry.getKey() + "': no program linked at all");
            assertTrue(audit.rejected.isEmpty(),
                    "Unbound '" + entry.getKey() + "' uniforms have neither a writer case"
                            + " nor an Iris graph source: " + audit.rejected
                            + " (graph-supplied declared names: " + audit.graphSupplied + ")");
        }

        // Every crashed biome flag must stay declared (so this gate keeps
        // exercising them) and writer-handled wherever it appears.
        Set<String> seenFlags = new LinkedHashSet<>();
        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            for (String flag : BIOME_FLAGS) {
                if (entry.getValue().declared.contains(flag)) {
                    seenFlags.add(flag);
                    assertFalse(entry.getValue().unsupported.contains(flag),
                            flag + " must have a writer value source in '" + entry.getKey() + "'");
                }
            }
        }
        Set<String> missingFlags = new LinkedHashSet<>(BIOME_FLAGS);
        missingFlags.removeAll(seenFlags);
        assertEquals(BIOME_FLAGS, seenFlags,
                "the gate must exercise every crashed biome flag; missing=" + missingFlags);

        // Pin the exact writer path with synthetic members (layout-independent):
        // the neutral frame must answer each flag with the GLSL default 0.
        UniformCoverageHarness.Audit overworld = audits.get("overworld/default");
        for (String flag : BIOME_FLAGS) {
            ByteBuffer bytes = overworld.relaxed.writeUniformForGate(
                    new IrisMetalGlslLinker.UniformMember("float", flag, 0, 0, 4)
            );
            assertFalse(overworld.relaxed.unsupportedNames().contains(flag),
                    flag + " must be answered by the writer's explicit default");
            assertEquals(0.0f, bytes.getFloat(0), flag + " default must be 0");
        }
    }
}
