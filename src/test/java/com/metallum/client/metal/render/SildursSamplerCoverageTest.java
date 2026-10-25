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
 * Regression gate for Sildur's Vibrant 2.02 sampler coverage.
 *
 * <p>Sildur's has no custom images, SSBOs, compute or {@code size.buffer}
 * directives; its samplers are the standard names plus the raster aliases the
 * bridges already own ({@code tex}, {@code lightmap}). The one gap is the PBR
 * name {@code normals}: {@code gbuffers_textured.fsh} declares and samples it
 * (bump mapping / POM) under {@code #if nMap >= 1}. The shipped default is
 * {@code nMap 0} (bump mapping off), so the active set is clean by default;
 * enabling the option used to abort the pass with a missing sampler. The port
 * now binds upstream's neutral PBR default (flat normal).
 */
final class SildursSamplerCoverageTest {
    private static final Path PACK = Path.of("build", "compat-packs", "Sildurs.zip");

    @Test
    void sildursDefaultSamplersResolve() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);

        ShaderpackSamplerCoverage.Report report = ShaderpackSamplerCoverage.link(PACK);
        assertFalse(report.programs().isEmpty(), "no Sildur's program was linked");
        assertTrue(report.unresolved().isEmpty(),
                "active Sildur's samplers the binding table cannot resolve: " + report.unresolved()
                        + ShaderpackSamplerCoverage.describe(report));
        assertFalse(report.active().contains("normals"),
                "normals must stay inactive with the shipped nMap=0; active=" + report.active());
        System.out.println("[sildurs-sampler-coverage] linked=" + report.programs().size()
                + " active=" + report.active().size());
    }

    @Test
    void sildursNormalMapOptionResolvesViaPbrDefault() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);

        ShaderpackSamplerCoverage.Report report =
                ShaderpackSamplerCoverage.link(PACK, Map.of("nMap", "1"));
        assertTrue(report.unresolved().isEmpty(),
                "nMap=1 activates 'normals' but the binding table cannot resolve it: "
                        + report.unresolved() + ShaderpackSamplerCoverage.describe(report));
        ShaderpackSamplerCoverage.ProgramCoverage textured = report.program("gbuffers_textured");
        assertNotNull(textured, "nMap=1 must resolve gbuffers_textured"
                + ShaderpackSamplerCoverage.describe(report));
        assertTrue(textured.active().contains("normals"),
                "nMap=1 must actively sample 'normals'; active=" + textured.active());
        System.out.println("[sildurs-sampler-coverage] nMap=1 linked=" + report.programs().size()
                + " normals resolved via PBR default");
    }
}
