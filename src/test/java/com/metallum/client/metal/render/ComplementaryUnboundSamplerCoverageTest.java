package com.metallum.client.metal.render;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for Complementary Unbound r5.9.3 sampler coverage.
 *
 * <p>Unbound is CR-family: its shared {@code lib/uniforms.glsl} declares the
 * legacy/PBR names in every linked program, and the legacy render-target
 * aliases ({@code gaux2}/{@code gaux4}) must resolve through
 * {@link IrisMetalRenderTargets#renderTargetIndex}. {@code specular} is only
 * sampled when the labPBR emissive path is enabled
 * ({@code IPBR_EMISSIVE_MODE == 3} or {@code RP_MODE == 3}); the shipped
 * default is {@code IPBR_EMISSIVE_MODE 1}, so it is merely declared. The port
 * now binds upstream's neutral PBR defaults (zero specular, flat normal) when
 * the option is turned on.
 */
final class ComplementaryUnboundSamplerCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "ComplementaryUnbound.zip");

    @Test
    void unboundDefaultSamplersResolve() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);

        ShaderpackSamplerCoverage.Report report = ShaderpackSamplerCoverage.link(PACK);
        assertFalse(report.programs().isEmpty(), "no Unbound program was linked");
        assertTrue(report.unresolved().isEmpty(),
                "active Unbound samplers the binding table cannot resolve: " + report.unresolved()
                        + ShaderpackSamplerCoverage.describe(report));
        assertFalse(report.active().contains("specular"),
                "specular must stay inactive at the shipped IPBR_EMISSIVE_MODE=1; active="
                        + report.active());
        System.out.println("[unbound-sampler-coverage] linked=" + report.programs().size()
                + " active=" + report.active().size());
    }

    @Test
    void unboundLabPbrModeResolvesSpecularAndNormals() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);

        ShaderpackSamplerCoverage.Report report =
                ShaderpackSamplerCoverage.link(PACK, Map.of("RP_MODE", "3"));
        assertTrue(report.unresolved().isEmpty(),
                "labPBR mode activates PBR samplers the binding table cannot resolve: "
                        + report.unresolved() + ShaderpackSamplerCoverage.describe(report));
        assertTrue(report.active().contains("specular"),
                "RP_MODE=3 must actively sample 'specular'; active=" + report.active());
        assertTrue(report.active().contains("normals"),
                "RP_MODE=3 must actively sample 'normals'; active=" + report.active());
        assertPbrDefaultUsedByRaster(report, "specular");
        System.out.println("[unbound-sampler-coverage] RP_MODE=3 linked=" + report.programs().size()
                + " specular+normals resolved via PBR defaults");
    }

    @Test
    void unboundIpbrEmissiveModeResolvesSpecular() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);

        ShaderpackSamplerCoverage.Report report =
                ShaderpackSamplerCoverage.link(PACK, Map.of("IPBR_EMISSIVE_MODE", "3"));
        assertTrue(report.unresolved().isEmpty(),
                "IPBR_EMISSIVE_MODE=3 activates 'specular' but the binding table cannot resolve it: "
                        + report.unresolved() + ShaderpackSamplerCoverage.describe(report));
        assertTrue(report.active().contains("specular"),
                "IPBR_EMISSIVE_MODE=3 must actively sample 'specular'; active=" + report.active());
        assertPbrDefaultUsedByRaster(report, "specular");
        System.out.println("[unbound-sampler-coverage] IPBR_EMISSIVE_MODE=3 linked="
                + report.programs().size() + " specular resolved via PBR default");
    }

    /** The PBR default must be reachable from a gbuffers raster program (level samplers). */
    private static void assertPbrDefaultUsedByRaster(
            final ShaderpackSamplerCoverage.Report report,
            final String sampler
    ) {
        boolean rasterActive = report.programs().stream().anyMatch(program ->
                program.kind().startsWith("raster:")
                        && program.active().contains(sampler)
                        && program.unresolved().isEmpty());
        assertTrue(rasterActive, "no raster program actively samples '" + sampler + "'"
                + ShaderpackSamplerCoverage.describe(report));
    }
}
