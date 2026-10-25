package com.metallum.client.metal.render;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the MakeUp-UltraFast "Iris pass composite is missing
 * required sampler 'gaux3'" crash.
 *
 * <p>The pack is older than the colortexN naming convention and samples the
 * legacy render-target aliases in its graph programs ({@code composite} and
 * {@code final}: {@code gaux3} for exposure; {@code deferred}: {@code gaux2})
 * and in its raster programs ({@code gbuffers_water}: {@code gaux1/2/4};
 * {@code gbuffers_armor_glint}: {@code gaux3}; the rest: {@code gaux4}). The
 * execution graph resolved sampler names through {@code colortexN} only, so an
 * active {@code gaux3} came back null and {@code bindRaster} threw before the
 * pass draw.
 *
 * <p>{@link ShaderpackSamplerCoverage} links the real pack without native
 * libraries and pins that every active sampler resolves under the port's name
 * tables, that the crash's {@code gaux3} is among the names actually
 * exercised, and that all four aliases are covered.
 */
final class MakeUpSamplerCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "MakeUp-UltraFast-9.5f.zip");
    private static final Set<String> EXPECTED_LEGACY_ACTIVE = Set.of("gaux1", "gaux2", "gaux3", "gaux4");

    @Test
    void makeUpLegacySamplersResolveForActiveSet() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);

        ShaderpackSamplerCoverage.Report report = ShaderpackSamplerCoverage.link(PACK);
        assertFalse(report.programs().isEmpty(), "no MakeUp program was linked");
        assertTrue(report.unresolved().isEmpty(),
                "active MakeUp samplers the binding table cannot resolve: " + report.unresolved()
                        + ShaderpackSamplerCoverage.describe(report));

        // The crash was exactly this pass: composite's vertex stage samples
        // gaux3 for exposure.
        ShaderpackSamplerCoverage.ProgramCoverage composite = report.program("composite");
        assertNotNull(composite, "MakeUp 'composite' was not linked"
                + ShaderpackSamplerCoverage.describe(report));
        assertTrue(composite.active().contains("gaux3"),
                "MakeUp 'composite' must actively sample gaux3 (the crash trigger); active="
                        + composite.active());

        Set<String> activeLegacy = new LinkedHashSet<>();
        for (String name : report.active()) {
            if (!name.startsWith("colortex") && IrisMetalRenderTargets.renderTargetIndex(name) >= 0) {
                activeLegacy.add(name);
            }
        }
        assertTrue(activeLegacy.containsAll(EXPECTED_LEGACY_ACTIVE),
                "MakeUp must exercise gaux1-4; active legacy=" + activeLegacy);
        System.out.println("[makeup-sampler-coverage] linked=" + report.programs().size()
                + " active=" + report.active().size()
                + " activeLegacy=" + activeLegacy);
    }

    /**
     * Step 5 gate for the H1 black-screen investigation: the compact per-stage
     * sampler index plan must agree with the stage GLSL the runtime binding
     * table is built from.
     *
     * <p>First pins the vertex-stage exposure reads that drive MakeUp's final
     * multiply ({@code final_vertex.glsl:28} reads {@code gaux3}; composite's
     * vertex stage reads {@code gaux3} and {@code colortex1}), then replays the
     * compiler's native-free per-stage plan ({@link ShaderpackSamplerIndexPlan})
     * over every linked stage: the plan must cover each sampled name exactly
     * once, in declaration order, within Metal's 16-slot limit.
     */
    @Test
    void makeupVertexSamplersAndPerStageIndexPlansAreConsistent() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);

        ShaderpackSamplerCoverage.Report report = ShaderpackSamplerCoverage.link(PACK);

        ShaderpackSamplerCoverage.ProgramCoverage composite = report.program("composite");
        assertNotNull(composite, "MakeUp 'composite' was not linked"
                + ShaderpackSamplerCoverage.describe(report));
        List<String> compositeVertexDeclared =
                ShaderpackSamplerCoverage.declaredSamplers(composite.vertexGlsl());
        Set<String> compositeVertexSampled =
                ShaderpackSamplerCoverage.sampledNames(composite.vertexGlsl(), compositeVertexDeclared);
        assertTrue(compositeVertexSampled.contains("gaux3"),
                "MakeUp composite vertex must sample gaux3 (exposure history); declared="
                        + compositeVertexDeclared);
        assertTrue(compositeVertexSampled.contains("colortex1"),
                "MakeUp composite vertex must sample colortex1 (exposure readback); declared="
                        + compositeVertexDeclared);

        ShaderpackSamplerCoverage.ProgramCoverage finalProgram = report.program("final");
        assertNotNull(finalProgram, "MakeUp 'final' was not linked"
                + ShaderpackSamplerCoverage.describe(report));
        List<String> finalVertexDeclared =
                ShaderpackSamplerCoverage.declaredSamplers(finalProgram.vertexGlsl());
        Set<String> finalVertexSampled =
                ShaderpackSamplerCoverage.sampledNames(finalProgram.vertexGlsl(), finalVertexDeclared);
        assertTrue(finalVertexSampled.contains("gaux3"),
                "MakeUp final vertex must sample gaux3 (the exposure multiply input); declared="
                        + finalVertexDeclared);

        int stages = 0;
        for (ShaderpackSamplerCoverage.ProgramCoverage program : report.programs()) {
            assertStagePlan(program, "vertex", program.vertexGlsl());
            assertStagePlan(program, "fragment", program.fragmentGlsl());
            stages += 2;
        }
        assertTrue(stages > 0, "no linked stage was planned");
        System.out.println("[makeup-sampler-index-plan] programs=" + report.programs().size()
                + " stages=" + stages
                + " compositeVertexSampled=" + compositeVertexSampled
                + " finalVertexSampled=" + finalVertexSampled);
    }

    /**
     * Replays {@link ShaderpackSamplerIndexPlan} on one linked stage. The plan
     * is the same native-free policy the compiler applies to SPIRV-Cross
     * decorations, so a stage with more than 16 sampled names fails here the
     * same way it would fail MSL compilation.
     */
    private static void assertStagePlan(
            final ShaderpackSamplerCoverage.ProgramCoverage program,
            final String stage,
            final String stageGlsl
    ) throws Exception {
        List<String> declared = ShaderpackSamplerCoverage.declaredSamplers(stageGlsl);
        Set<String> sampled = ShaderpackSamplerCoverage.sampledNames(stageGlsl, declared);
        String label = program.kind() + ':' + program.name() + ' ' + stage;
        Map<String, Integer> plan = ShaderpackSamplerIndexPlan.assignSampledImageIndices(
                declared, sampled, label
        );
        assertEquals(sampled, plan.keySet(),
                label + ": plan must cover exactly the sampled names; declared=" + declared);
        int expected = 0;
        for (String name : declared) {
            Integer index = plan.get(name);
            if (index == null) {
                continue;
            }
            assertEquals(expected, index,
                    label + ": compact index plan is not declaration-ordered; plan=" + plan
                            + " declared=" + declared);
            expected++;
        }
        assertEquals(sampled.size(), plan.size(), label + ": plan size mismatch; plan=" + plan);
        assertTrue(plan.size() <= ShaderpackSamplerIndexPlan.MAX_METAL_SAMPLERS_PER_STAGE,
                label + ": " + plan.size() + " sampled names exceed Metal's per-stage limit; plan=" + plan);
    }
}
