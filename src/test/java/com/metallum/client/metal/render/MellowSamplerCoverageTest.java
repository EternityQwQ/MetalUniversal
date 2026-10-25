package com.metallum.client.metal.render;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the Mellow v3.4.1a custom-texture crash chain.
 *
 * <p>Links the real pack without native libraries and pins that every active
 * sampler resolves under the port's name tables. Mellow's raw 3D volume is
 * renamed from {@code colortex6} to a global {@code customtexN} name by the
 * upstream texture transform; the declaration must reach the port's linked
 * programs so {@link IrisMetalCustomTextures} answers it from the global map
 * (the companion {@code MellowCustomTextureDefinitionsTest} pins the parsed
 * data and the rename table).
 */
final class MellowSamplerCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "Mellow_3.4.1a.zip");

    @Test
    void mellowSamplersResolveForActiveSet() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);

        ShaderpackSamplerCoverage.Report report = ShaderpackSamplerCoverage.link(PACK);
        assertFalse(report.programs().isEmpty(), "no Mellow program was linked");
        assertTrue(report.unresolved().isEmpty(),
                "active Mellow samplers the binding table cannot resolve: " + report.unresolved()
                        + ShaderpackSamplerCoverage.describe(report));

        // The renamed raw sampler must be declared in the linked programs, even
        // when the volumetric-cloud path that samples it is compiled out at the
        // default options. IrisMetalCustomTextures now resolves these names
        // from the global customtex map on any stage.
        boolean sawCustomtexDeclaration = false;
        for (ShaderpackSamplerCoverage.ProgramCoverage program : report.programs()) {
            if (program.fragmentGlsl().contains("customtex")
                    || program.vertexGlsl().contains("customtex")) {
                sawCustomtexDeclaration = true;
                break;
            }
        }
        assertTrue(sawCustomtexDeclaration,
                "Mellow's raw volume must be renamed to customtexN in the linked programs");
        assertFalse(report.active().contains("colortex6"),
                "raw colortex6 must not remain an active sampler name after the rename; active="
                        + report.active());
        System.out.println("[mellow-sampler-coverage] linked=" + report.programs().size()
                + " active=" + report.active().size()
                + " customtexDeclared=" + sawCustomtexDeclaration);
    }
}
