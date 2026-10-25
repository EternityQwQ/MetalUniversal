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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the Bliss v2.1.2 (Chocapic13 lineage) gaps found by the
 * follow-up scan: the strict prewarm rejected 17 declared float/vec members
 * that neither a writer case nor the custom-uniform graph supplied, and the
 * graph sampler resolver did not know the legacy {@code shadow} alias.
 *
 * <p>Thirteen of the seventeen have no upstream supplier
 * ({@code CommonUniforms}/{@code IrisExclusiveUniforms} are empty for them);
 * four are upstream-supplied and reach the port through the production graph
 * ({@code dhFarPlane}/{@code dhNearPlane} at CommonUniforms:183-184,
 * {@code currentPlayerHealth}/{@code maxPlayerHealth} at
 * IrisExclusiveUniforms:64/66) and are registered here the same way the
 * harness registers every official input. {@code shadow} maps to shadowtex0/1
 * through {@link IrisMetalExecutionGraph#legacyShadowDepth}, mirroring
 * {@code IrisSamplers.addShadowSamplers} (pin 20e226b:143-152).
 */
final class BlissUniformCoverageTest {
    private static final Path PACK = Path.of(
            "build", "compat-packs", "Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip");
    private static final Map<String, Map<String, String>> CONFIGS = new LinkedHashMap<>();
    private static final Map<String, NamespacedId> DIMENSIONS = new LinkedHashMap<>();

    static {
        CONFIGS.put("default", Map.of());
        DIMENSIONS.put("overworld", new NamespacedId("minecraft", "overworld"));
        DIMENSIONS.put("nether", new NamespacedId("minecraft", "the_nether"));
        DIMENSIONS.put("end", new NamespacedId("minecraft", "the_end"));
    }

    /** The nine writer-defaulted members and their types (no supplier anywhere). */
    private static final List<String> DEFAULTED_FLOATS = List.of(
            "skyIntensityNight", "skyIntensity", "moonIntensity", "sunIntensity", "farPlane");
    private static final List<String> DEFAULTED_VEC3 = List.of("sunColor", "nsunColor");
    private static final List<String> DEFAULTED_VEC4 = List.of("Moon_Weather_properties", "lightCol");
    /**
     * Upstream suppliers (dh*) and pack custom uniforms fed by official inputs
     * (gameplay flags) — resolved through the production graph, never
     * defaulted. {@code CriticalDamageTaken}/{@code oneHeart}/{@code threeHeart}/
     * {@code MinorDamageTaken} are declared in shaders.properties:469-482 as
     * expressions over {@code currentPlayerHealth}/{@code maxPlayerHealth}; an
     * earlier scan missed them only because it did not register those two
     * official inputs.
     */
    private static final List<String> GRAPH_SUPPLIED = List.of(
            "dhFarPlane", "dhNearPlane", "currentPlayerHealth", "maxPlayerHealth",
            "CriticalDamageTaken", "oneHeart", "threeHeart", "MinorDamageTaken");

    @Test
    void blissWorldUniformBlocksHaveNoUnsupportedMembers() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        Map<String, UniformCoverageHarness.Audit> audits = UniformCoverageHarness.auditAll(
                PACK, DIMENSIONS, CONFIGS, BlissUniformCoverageTest::registerHeadlessInputs
        );

        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            System.out.println("[bliss-uniform-coverage] config=" + entry.getKey()
                    + " linkedPrograms=" + entry.getValue().linkedPrograms
                    + " declared=" + entry.getValue().declared.size()
                    + " graphSupplied=" + entry.getValue().graphSupplied
                    + " rejected=" + entry.getValue().rejected);
        }

        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            UniformCoverageHarness.Audit audit = entry.getValue();
            assertTrue(audit.linkedPrograms > 0,
                    "Bliss '" + entry.getKey() + "': no world program linked at all");
            assertTrue(audit.rejected.isEmpty(),
                    "Bliss '" + entry.getKey() + "' world uniforms have neither a writer case"
                            + " nor an Iris graph source: " + audit.rejected
                            + " (graph-supplied declared names: " + audit.graphSupplied + ")");
        }

        // Pin the writer defaults: removing a case must fail here.
        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            UniformCoverageHarness.Audit audit = entry.getValue();
            for (String name : DEFAULTED_FLOATS) {
                assertTrue(audit.unsupported.stream().noneMatch(entry1 -> entry1.equals(name)),
                        "Bliss '" + entry.getKey() + "' must writer-default " + name);
            }
        }

        UniformCoverageHarness.Audit overworld = audits.get("overworld/default");
        IrisMetalUniformValues values = new IrisMetalUniformValues(0.0f, () -> 0);
        for (String name : DEFAULTED_FLOATS) {
            assertZero(values, new IrisMetalGlslLinker.UniformMember("float", name, 0, 0, 4), 4, name);
        }
        for (String name : DEFAULTED_VEC3) {
            assertZero(values, new IrisMetalGlslLinker.UniformMember("vec3", name, 0, 0, 12), 12, name);
        }
        for (String name : DEFAULTED_VEC4) {
            assertZero(values, new IrisMetalGlslLinker.UniformMember("vec4", name, 0, 0, 16), 16, name);
        }

        // The four upstream suppliers stay graph-resolved; defaulting them in
        // the writer would mask a graph regression.
        for (String name : GRAPH_SUPPLIED) {
            assertTrue(overworld.graph.hasVariable(name),
                    name + " must resolve through the production custom-uniform graph");
        }
        for (String name : GRAPH_SUPPLIED) {
            assertFalse(values.unsupportedNames().contains(name),
                    name + " must not fall through to an unsupported writer path on official input");
        }
    }

    @Test
    void blissLegacyShadowSamplerResolvesEverywhere() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        ShaderpackSamplerCoverage.Report report = ShaderpackSamplerCoverage.link(PACK);
        System.out.println("[bliss-sampler-coverage] programs=" + report.programs().size()
                + " active=" + report.active().size() + " unresolved=" + report.unresolved());
        assertTrue(report.unresolved().isEmpty(),
                "Bliss active samplers with no binding route: " + report.unresolved()
                        + ShaderpackSamplerCoverage.describe(report));
        assertTrue(report.active().contains("shadow"),
                "the legacy shadow alias must stay active (the crashing declaration)");

        ShaderpackSamplerCoverage.ProgramCoverage deferred = report.program("deferred");
        assertTrue(deferred != null && deferred.fragmentGlsl().contains("sampler2DShadow shadow;"),
                "deferred must declare the legacy sampler2DShadow shadow");
        ShaderpackSamplerCoverage.ProgramCoverage compositeOne = report.program("composite1");
        assertTrue(compositeOne != null && compositeOne.fragmentGlsl().contains("sampler2D shadow;"),
                "composite1 must declare the manual-compare sampler2D shadow");
    }

    /**
     * Upstream {@code IrisSamplers.addShadowSamplers} (pin 20e226b:143-152):
     * {@code watershadow} always means shadowtex0; {@code shadow} means
     * shadowtex1 only when the program also declares {@code watershadow},
     * otherwise shadowtex0. Pure mapping, no device needed.
     */
    @Test
    void legacyShadowDepthMapsWatershadowPairLikeUpstream() {
        assertEquals(0, IrisMetalExecutionGraph.legacyShadowDepth("watershadow", false));
        assertEquals(0, IrisMetalExecutionGraph.legacyShadowDepth("watershadow", true));
        assertEquals(0, IrisMetalExecutionGraph.legacyShadowDepth("shadow", false));
        assertEquals(1, IrisMetalExecutionGraph.legacyShadowDepth("shadow", true));
        assertEquals(0, IrisMetalExecutionGraph.legacyShadowDepth("shadowtex0", false));
        assertEquals(1, IrisMetalExecutionGraph.legacyShadowDepth("shadowtex1", false));
        assertEquals(0, IrisMetalExecutionGraph.legacyShadowDepth("shadowtex0HW", false));
        assertEquals(1, IrisMetalExecutionGraph.legacyShadowDepth("shadowtex1HW", false));
        assertEquals(-1, IrisMetalExecutionGraph.legacyShadowDepth("shadowcolor", false));
        assertEquals(-1, IrisMetalExecutionGraph.legacyShadowDepth("colortex1", false));
    }

    private static void assertZero(
            final IrisMetalUniformValues values,
            final IrisMetalGlslLinker.UniformMember member,
            final int size,
            final String name
    ) {
        ByteBuffer bytes = values.writeUniformForGate(member);
        assertFalse(values.unsupportedNames().contains(name),
                name + " must be writer-handled, not rejected");
        for (int offset = 0; offset < size; offset += Float.BYTES) {
            assertEquals(0.0f, bytes.getFloat(offset), 0.0f, name + " default must be zero");
        }
    }

    /**
     * Rebuilds the headless-blocked official inputs the audit needs: the names
     * {@code CommonUniforms.generalCommonUniforms}/{@code IrisExclusiveUniforms}
     * own, with their no-context values. Production installs the real suppliers
     * in {@code MetalWorldRenderingPipeline}.
     */
    private static void registerHeadlessInputs(final net.irisshaders.iris.gl.uniform.UniformHolder holder) {
        holder.uniform2i(UniformUpdateFrequency.PER_FRAME, "eyeBrightness", Vector2i::new);
        holder.uniform1i(UniformUpdateFrequency.PER_FRAME, "isEyeInWater", () -> 0);
        holder.uniform1f(UniformUpdateFrequency.PER_TICK, "rainStrength", () -> 0.0f);
        holder.uniform1f(UniformUpdateFrequency.PER_TICK, "wetness", () -> 0.0f);
        holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "thunderStrength", () -> 0.0f);
        holder.uniform1f(UniformUpdateFrequency.PER_TICK, "endFlashIntensity", () -> 0.0f);
        holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "blindness", () -> 0.0f);
        holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "darknessLightFactor", () -> 0.0f);
        holder.uniform1b(UniformUpdateFrequency.PER_FRAME, "is_invisible", () -> false);
        holder.uniform1b(UniformUpdateFrequency.PER_FRAME, "hideGUI", () -> false);
        holder.uniform3d(UniformUpdateFrequency.PER_FRAME, "playerLookVector", Vector3d::new);
        // CommonUniforms.java:185 (also an official input; Deviceless DH -> 0).
        holder.uniform1i(UniformUpdateFrequency.PER_FRAME, "dhRenderDistance", () -> 0);
        // DHCompat.java:93/103: no Distant Horizons installed -> 0.01f.
        holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "dhFarPlane", () -> 0.01f);
        holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "dhNearPlane", () -> 0.01f);
        // IrisExclusiveUniforms.java:213-218/253-258: no client player in
        // headless -> -1f, the same value a non-survival client sees.
        holder.uniform1f(UniformUpdateFrequency.PER_TICK, "currentPlayerHealth", () -> -1.0f);
        holder.uniform1f(UniformUpdateFrequency.PER_TICK, "maxPlayerHealth", () -> -1.0f);
    }
}
