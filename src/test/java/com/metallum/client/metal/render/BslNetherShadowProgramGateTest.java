package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the "进入下界崩溃" (BSL nether) sampler failure.
 *
 * <p>With the bundled BSL fixture ({@code MULTICOLORED_BLOCKLIGHT} off),
 * {@code shaders.properties} gates the nether shadow program on
 * {@code SHADOW && MULTICOLORED_BLOCKLIGHT}, so the {@code world-1}
 * {@link ProgramSet} resolves no {@link ProgramId#ShadowSolid}. The pack's
 * nether composite still samples {@code shadowtex0}, so the generation must
 * keep owning shadow targets: {@link IrisMetalWorldResources} now allocates
 * them unconditionally (mirroring GL's lazy {@code ShadowRenderTargets}), and
 * {@link IrisMetalWorldResources#hasShadowProgram(ProgramSet)} only gates the
 * caster pass.
 *
 * <p>Pure Java: opening the pack and resolving program fallbacks never touch
 * the native Metal/glslang path, so this runs on every host; it is skipped
 * only when the fixture zip is absent (the zip lives under {@code build/}).
 */
final class BslNetherShadowProgramGateTest {
    private static final Path PACK = Path.of("build", "test-packs", "BSL_noSunGround.zip");

    @Test
    void netherGenerationResolvesNoShadowProgramWhileOverworldDoes() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "test pack missing: " + PACK);
        Iris.testing = true;

        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet nether = pack.getProgramSet(new NamespacedId("minecraft", "the_nether"));
            ProgramSet overworld = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));

            // The crash precondition: the nether generation has no shadow
            // caster program at all (not even through Shadow->ShadowSolid
            // fallback), yet its composite programs sample shadowtex0.
            assertNull(
                    new ProgramFallbackResolver(nether).resolveNullable(ProgramId.ShadowSolid),
                    "BSL nether shadow program must stay gated off by this fixture"
            );
            assertFalse(
                    IrisMetalWorldResources.hasShadowProgram(nether),
                    "nether generation must report no shadow program"
            );

            // Same fixture, overworld gate is SHADOW alone: the predicate is
            // per-generation, not a global shadow switch.
            assertTrue(
                    IrisMetalWorldResources.hasShadowProgram(overworld),
                    "overworld generation must resolve a shadow program"
            );
        }
    }
}
