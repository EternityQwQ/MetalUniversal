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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uniform/sampler coverage gate for Solas Shader V3.7b.
 *
 * <p>The pack uses OptiFine biome flags whose modern {@code BIOME_*} constants
 * are not defined by pinned Iris (20e226b), so stareval drops those custom
 * uniforms and desktop GL leaves the GLSL default 0. {@code isSnowy},
 * {@code isCherryGrove}, {@code isLushCaves}, {@code isDeepDark} and
 * {@code isPaleGarden} therefore need explicit writer defaults; flags whose
 * constants do exist (isDesert/isSwamp/...) keep resolving through the graph.
 *
 * <p>Voxel (VX) custom images and the shadowcomp compute live behind the
 * pack's own {@code VX_SUPPORT} gate ({@code lib/common.glsl:842}). The
 * per-slot {@code world0/shadowcomp.csh} compute <b>is</b> enabled by default
 * (it appears in {@code ProgramSet#getCompute(ShadowComposite)}, not in
 * {@code getShadowCompute()} — the earlier scan checked the wrong array) and
 * is covered by {@link SolasShadowCompComputeTest}; this gate only audits the
 * raster world/graph uniform block.
 */
final class SolasUniformCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "Solas Shader V3.7b.zip");
    private static final Map<String, Map<String, String>> CONFIGS = new LinkedHashMap<>();
    private static final Map<String, NamespacedId> DIMENSIONS = new LinkedHashMap<>();
    private static final String[] BIOME_FLAGS = {
            "isSnowy", "isCherryGrove", "isLushCaves", "isDeepDark", "isPaleGarden"
    };

    static {
        CONFIGS.put("default", Map.of());
        CONFIGS.put("profile=HIGH", Map.of("profile", "HIGH"));
        DIMENSIONS.put("overworld", new NamespacedId("minecraft", "overworld"));
        DIMENSIONS.put("nether", new NamespacedId("minecraft", "the_nether"));
        DIMENSIONS.put("end", new NamespacedId("minecraft", "the_end"));
    }

    @Test
    void solasWorldUniformBlocksHaveNoUnsupportedMembers() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        Map<String, UniformCoverageHarness.Audit> audits = UniformCoverageHarness.auditAll(
                PACK, DIMENSIONS, CONFIGS,
                holder -> {
                    holder.uniform2i(UniformUpdateFrequency.PER_FRAME, "eyeBrightness", () -> new Vector2i());
                    holder.uniform1i(UniformUpdateFrequency.PER_FRAME, "isEyeInWater", () -> 0);
                    holder.uniform1f(UniformUpdateFrequency.PER_TICK, "rainStrength", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_TICK, "wetness", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "thunderStrength", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "blindness", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "darknessLightFactor", () -> 0.0f);
                    // CommonUniforms.java:185; production supplies it headlessly too.
                    holder.uniform1i(UniformUpdateFrequency.PER_FRAME, "dhRenderDistance", () -> 0);
                    // IrisExclusiveUniforms.java:69; the End-flash supplier.
                    holder.uniform1f(UniformUpdateFrequency.PER_TICK, "endFlashIntensity", () -> 0.0f);
                    holder.uniform1b(UniformUpdateFrequency.PER_FRAME, "is_invisible", () -> false);
                    holder.uniform3d(UniformUpdateFrequency.PER_FRAME, "playerLookVector", () -> new Vector3d());
                }
        );

        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            System.out.println("[solas-uniform-coverage] config=" + entry.getKey()
                    + " linkedPrograms=" + entry.getValue().linkedPrograms
                    + " declared=" + entry.getValue().declared.size()
                    + " rejected=" + entry.getValue().rejected);
        }
        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            assertTrue(entry.getValue().linkedPrograms > 0,
                    "Solas '" + entry.getKey() + "': no world program linked");
            assertTrue(entry.getValue().rejected.isEmpty(),
                    "Solas '" + entry.getKey() + "' uniforms without a value source: "
                            + entry.getValue().rejected
                            + " (graph-supplied: " + entry.getValue().graphSupplied + ")");
        }

        // isSnowy and friends only appear in the overworld atmosphere set;
        // isPaleGarden is an MC>=1.21.4 sky flag declared by overworld and
        // nether (not the end) variants. Whatever a layout declares must be
        // writer-handled, and every configured layout must reject nothing.
        for (UniformCoverageHarness.Audit audit : audits.values()) {
            for (String flag : BIOME_FLAGS) {
                if (audit.declared.contains(flag)) {
                    assertFalse(audit.unsupported.contains(flag),
                            flag + " must have a writer value source");
                }
            }
        }
        assertTrue(audits.get("overworld/default").declared.contains("isSnowy"),
                "overworld layouts must declare isSnowy");
        assertTrue(audits.get("overworld/default").declared.contains("isPaleGarden"),
                "overworld layouts must declare isPaleGarden");
        assertTrue(audits.get("nether/default").declared.contains("isPaleGarden"),
                "nether layouts must declare isPaleGarden");

        UniformCoverageHarness.Audit overworld = audits.get("overworld/default");
        for (String flag : BIOME_FLAGS) {
            ByteBuffer bytes = overworld.relaxed.writeUniformForGate(
                    new IrisMetalGlslLinker.UniformMember("float", flag, 0, 0, 4)
            );
            assertFalse(overworld.relaxed.unsupportedNames().contains(flag),
                    flag + " must be answered by the writer's explicit default");
            assertEquals(0.0f, bytes.getFloat(0), 0.0f,
                    flag + " must default to the GLSL zero");
        }

        // Solas has no begin/prepare programs; the sweep stays empty.
        UniformCoverageHarness.Audit prelude = UniformCoverageHarness.auditPrelude(
                PACK, new NamespacedId("minecraft", "overworld"), Map.of(), holder -> {
                }
        );
        assertTrue(prelude.rejected.isEmpty(),
                "Solas prepare prelude uniforms without a value source: " + prelude.rejected);
    }

    /** World/terrain/graph sampler chain resolves end to end. */
    @Test
    void solasSamplersResolveForActiveSet() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);

        ShaderpackSamplerCoverage.Report report = ShaderpackSamplerCoverage.link(PACK);
        assertFalse(report.programs().isEmpty(), "no Solas program was linked");
        assertTrue(report.unresolved().isEmpty(),
                "active Solas samplers the binding table cannot resolve: " + report.unresolved()
                        + ShaderpackSamplerCoverage.describe(report));
        System.out.println("[solas-sampler-coverage] linked=" + report.programs().size()
                + " active=" + report.active().size());
    }
}
