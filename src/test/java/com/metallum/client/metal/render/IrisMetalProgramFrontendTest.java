package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalProgramFrontendTest {
    private static final String FIXTURE = "/shaderpacks/BSL_v10.1.3.zip";

    @Test
    void realBslProgramSetUsesIrisFallbackAndTransforms() throws Exception {
        Iris.testing = true;
        try (LoadedPack loaded = loadFixture()) {
            ProgramSet programs = loaded.pack().getProgramSet(new NamespacedId("minecraft", "overworld"));
            IrisMetalProgramFrontend frontend = new IrisMetalProgramFrontend(programs);

            IrisMetalProgramFrontend.ResolvedProgram terrain = frontend.resolve(ProgramId.TerrainSolid)
                    .orElseThrow();
            assertTrue(terrain.usedFallback(), "BSL terrain_solid should resolve through Iris to terrain");
            assertTrue(terrain.source().getName().endsWith("gbuffers_terrain"));

            IrisMetalProgramFrontend.RasterProgram patchedTerrain =
                    frontend.patchSodium(terrain, AlphaTest.ALWAYS);
            assertRequiredRasterStages(patchedTerrain);
            assertArrayEquals(terrain.source().getDirectives().getDrawBuffers(), patchedTerrain.drawBuffers());
            assertFalse(patchedTerrain.requiresUnsupportedMetalStage());
            assertPatchedCoreSyntax(patchedTerrain.vertexSource());
            assertPatchedCoreSyntax(patchedTerrain.fragmentSource());
            IrisMetalGlslLinker.LinkedRasterProgram linkedTerrain =
                    IrisMetalGlslLinker.linkSodium(patchedTerrain);
            assertTrue(linkedTerrain.uniformBlockSize() > 0);
            assertTrue(linkedTerrain.uniformLayout().stream()
                    .anyMatch(member -> member.name().equals("gbufferModelView")));
            assertCrossCompiles(linkedTerrain);

            ProgramSource composite = Arrays.stream(programs.getComposite(ProgramArrayId.Composite))
                    .filter(source -> source != null && source.isValid())
                    .findFirst()
                    .orElseThrow();
            IrisMetalProgramFrontend.RasterProgram patchedComposite =
                    frontend.patchComposite(composite, TextureStage.COMPOSITE_AND_FINAL);
            assertRequiredRasterStages(patchedComposite);
            assertArrayEquals(composite.getDirectives().getDrawBuffers(), patchedComposite.drawBuffers());
            assertPatchedCoreSyntax(patchedComposite.vertexSource());
            assertPatchedCoreSyntax(patchedComposite.fragmentSource());
            assertCrossCompiles(IrisMetalGlslLinker.linkDefault(patchedComposite));

            ProgramSource finalSource = programs.get(ProgramId.Final).orElseThrow();
            IrisMetalProgramFrontend.RasterProgram patchedFinal =
                    frontend.patchComposite(finalSource, TextureStage.COMPOSITE_AND_FINAL);
            assertRequiredRasterStages(patchedFinal);
            assertPatchedCoreSyntax(patchedFinal.vertexSource());
            assertPatchedCoreSyntax(patchedFinal.fragmentSource());
            assertCrossCompiles(IrisMetalGlslLinker.linkDefault(patchedFinal));

            try (IrisMetalWorldPrograms first = new IrisMetalWorldPrograms(41, programs);
                 IrisMetalWorldPrograms second = new IrisMetalWorldPrograms(42, programs)) {
                IrisMetalGlslLinker.LinkedRasterProgram firstTerrain =
                        first.sodium(ProgramId.TerrainSolid, AlphaTest.ALWAYS).orElseThrow();
                assertSame(
                        firstTerrain,
                        first.sodium(ProgramId.TerrainSolid, AlphaTest.ALWAYS).orElseThrow(),
                        "one generation did not cache its linked terrain program"
                );
                assertSame(
                        first.composite(composite, TextureStage.COMPOSITE_AND_FINAL),
                        first.composite(composite, TextureStage.COMPOSITE_AND_FINAL),
                        "one generation did not cache its linked composite program"
                );
                assertTrue(first.cachedProgramCount() == 2);
                assertNotSame(
                        firstTerrain,
                        second.sodium(ProgramId.TerrainSolid, AlphaTest.ALWAYS).orElseThrow(),
                        "separate generations shared a linked program instance"
                );
                first.close();
                assertTrue(first.cachedProgramCount() == 0);
                assertThrows(
                        IllegalStateException.class,
                        () -> first.sodium(ProgramId.TerrainSolid, AlphaTest.ALWAYS)
                );
            }
        }
    }

    private static void assertRequiredRasterStages(final IrisMetalProgramFrontend.RasterProgram program) {
        assertNotNull(program.stages().get(PatchShaderType.VERTEX));
        assertNotNull(program.stages().get(PatchShaderType.FRAGMENT));
    }

    private static void assertPatchedCoreSyntax(final String source) {
        assertFalse(source.matches("(?s).*\\b(attribute|varying|ftransform)\\b.*"), source);
    }

    private static void assertCrossCompiles(
            final IrisMetalGlslLinker.LinkedRasterProgram program
    ) throws Exception {
        MetalCrossShaderCompiler.ShaderpackMslResult result =
                MetalCrossShaderCompiler.tryCompileShaderpackMsl(
                        program.name(),
                        program.vertexGlsl(),
                        null,
                        null,
                        null,
                        program.fragmentGlsl(),
                        null
                );
        assertNotNull(result.vertexMsl());
        assertNotNull(result.fragmentMsl());
        assertUniqueBindings(result.vertexMsl(), "texture");
        assertUniqueBindings(result.vertexMsl(), "sampler");
        assertUniqueBindings(result.fragmentMsl(), "texture");
        assertUniqueBindings(result.fragmentMsl(), "sampler");
        assertSamplerIndicesWithinMetalLimit(result.vertexMsl(), "vertex");
        assertSamplerIndicesWithinMetalLimit(result.fragmentMsl(), "fragment");
        if (program.uniformBlockNames().contains(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME)) {
            assertTrue(
                    resourceBinding(result.vertexMsl(), IrisMetalGlslLinker.UNIFORM_BLOCK_NAME, "buffer")
                            == resourceBinding(result.fragmentMsl(), IrisMetalGlslLinker.UNIFORM_BLOCK_NAME, "buffer"),
                    "shared Iris UBO differs across MSL stages"
            );
        }
    }

    private static void assertUniqueBindings(final String msl, final String kind) {
        Matcher matcher = Pattern.compile("\\[\\[" + kind + "\\((\\d+)\\)]]").matcher(msl);
        int count = 0;
        Set<Integer> unique = new HashSet<>();
        while (matcher.find()) {
            count++;
            unique.add(Integer.parseInt(matcher.group(1)));
        }
        assertTrue(unique.size() == count, kind + " bindings collide in generated MSL");
    }

    /**
     * Metal rejects {@code [[sampler(N)]]} for {@code N > 15}; the shaderpack
     * path must keep every per-stage sampler attribute inside that range.
     */
    private static void assertSamplerIndicesWithinMetalLimit(final String msl, final String stage) {
        Matcher matcher = Pattern.compile("\\[\\[sampler\\((\\d+)\\)]]").matcher(msl);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            assertTrue(
                    index < 16,
                    stage + " MSL emits out-of-range [[sampler(" + index + ")]]"
            );
        }
    }

    private static int resourceBinding(final String msl, final String type, final String kind) {
        Matcher matcher = Pattern.compile(
                "\\b" + Pattern.quote(type) + "&\\s+\\w+\\s*\\[\\[" + kind + "\\((\\d+)\\)]]"
        ).matcher(msl);
        assertTrue(matcher.find(), "missing " + type + " " + kind + " binding in generated MSL");
        return Integer.parseInt(matcher.group(1));
    }

    private static LoadedPack loadFixture() throws IOException {
        Path zip = Files.createTempFile("bsl-frontend-", ".zip");
        zip.toFile().deleteOnExit();
        try (var input = IrisMetalProgramFrontendTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(input, "missing BSL fixture " + FIXTURE);
            Files.copy(input, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        FileSystem fileSystem = FileSystems.newFileSystem(zip, Map.of());
        try {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            return new LoadedPack(fileSystem, pack);
        } catch (Throwable throwable) {
            fileSystem.close();
            throw throwable;
        }
    }

    private record LoadedPack(FileSystem fileSystem, ShaderPack pack) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            fileSystem.close();
        }
    }
}
