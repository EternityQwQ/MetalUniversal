package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.GlslangSourcePrep;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for two glslang rejections found on-device with Bliss and
 * Mellow:
 *
 * <ul>
 *   <li>a pack-defined builtin (Mellow's SMAA {@code fma} overloads) that
 *       glslang rejects once the source is forced to a modern Vulkan version —
 *       the pack's definition and references are renamed;</li>
 *   <li>a hoisted {@code MetallumIrisUniforms} member that collides with a
 *       stage-local symbol of the other stage (Bliss's deferred vertex
 *       computes its own {@code sunVec} while the fragment declares
 *       {@code uniform vec3 sunVec}) — the stage-local references are
 *       renamed.</li>
 * </ul>
 */
final class IrisMetalLinkerCollisionTest {
    private static final Path BLISS =
            Path.of("build", "compat-packs", "Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip");

    @Test
    void userDefinedBuiltinIsRenamedBeforeGlslang() {
        String source = "#version 120\n"
                + "float fma(float a, float b, float c) { return a * b + c; }\n"
                + "void main() { gl_Position = vec4(fma(1.0, 2.0, 3.0)); }\n";
        String prepared = GlslangSourcePrep.buildFullSource(source, null);
        assertTrue(prepared.contains("float metallum_user_fma(float a, float b, float c)"),
                "the pack's fma definition must be renamed away from the builtin");
        assertFalse(prepared.matches("(?s).*\\bfma\\b.*"),
                "no bare fma reference may survive in the glslang source");

        String tanhSource = "#version 120\n"
                + "float tanh(float x) { return (exp(x) - exp(-x)) / (exp(x) + exp(-x)); }\n"
                + "void main() { gl_Position = vec4(tanh(0.5)); }\n";
        String tanhPrepared = GlslangSourcePrep.buildFullSource(tanhSource, null);
        assertTrue(tanhPrepared.contains("float metallum_user_tanh(float x)"),
                "Bliss's user-defined tanh must be renamed away from the builtin");
        assertFalse(tanhPrepared.matches("(?s).*\\btanh\\b.*"),
                "no bare tanh reference may survive in the glslang source");

        String biasSource = "#version 120\n"
                + "float bias() { return 0.00005; }\n"
                + "void main() { gl_Position = vec4(bias()); }\n";
        String biasPrepared = GlslangSourcePrep.buildFullSource(biasSource, null);
        assertTrue(biasPrepared.contains("float metallum_user_bias()"),
                "Bliss's bias() must be renamed away from metal::bias");
        assertFalse(biasPrepared.matches("(?s).*\\bbias\\b.*"),
                "no bare bias reference may survive in the glslang source");

        String without = "#version 120\n"
                + "float clamp01(float a) { return a; }\n"
                + "void main() { gl_Position = vec4(0.0); }\n";
        String untouched = GlslangSourcePrep.buildFullSource(without, null);
        assertTrue(untouched.contains("float clamp01(float a)"),
                "sources without a builtin collision must stay untouched");
        assertFalse(untouched.contains("metallum_user_fma"),
                "the rename must not leak into unrelated sources");
    }

    @Test
    void sharedBlockMemberDoesNotCollideWithStageLocalSymbol() throws Exception {
        Assumptions.assumeTrue(Files.exists(BLISS), "Bliss fixture missing: " + BLISS);
        Iris.testing = true;
        try (FileSystem fileSystem = FileSystems.newFileSystem(BLISS, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    Map.of(),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            try (IrisMetalWorldPrograms worldPrograms = new IrisMetalWorldPrograms(1, programs)) {
                for (ProgramSource source : programs.getComposite(ProgramArrayId.Deferred)) {
                    if (source == null || !source.isValid() || !"deferred".equals(source.getName())) {
                        continue;
                    }
                    IrisMetalGlslLinker.LinkedRasterProgram linked =
                            worldPrograms.composite(source, TextureStage.DEFERRED);
                    String vertex = linked.vertexGlsl();
                    assertTrue(vertex.contains("metallum_local_sunVec"),
                            "the vertex-local sunVec must be renamed away from the block member");
                    assertFalse(vertex.contains("vec3 sunVec = normalize"),
                            "the pack's vertex-local sunVec declaration must not collide with the block");
                    assertTrue(vertex.contains("    vec3 sunVec;"),
                            "the hoisted block member must keep its name");
                    assertFalse(GlslangSourcePrep.buildFullSource(vertex, null).matches("(?s).*\\btanh\\b.*"),
                            "the prepared Bliss vertex source must not redeclare the tanh builtin");
                    assertTrue(linked.fragmentGlsl().contains("sunVec"),
                            "the fragment stage really uses the uniform and keeps its name");
                    return;
                }
                Assumptions.abort("Bliss deferred program not found");
            }
        }
    }

    @Test
    void blissHandProgramRenamesMetalReservedBias() throws Exception {
        Assumptions.assumeTrue(Files.exists(BLISS), "Bliss fixture missing: " + BLISS);
        Iris.testing = true;
        try (FileSystem fileSystem = FileSystems.newFileSystem(BLISS, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    Map.of(),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            IrisMetalProgramFrontend frontend = new IrisMetalProgramFrontend(programs);
            IrisMetalProgramFrontend.ResolvedProgram hand =
                    frontend.resolve(ProgramId.Hand).orElseThrow();
            IrisMetalGlslLinker.LinkedRasterProgram linked = IrisMetalGlslLinker.linkDefault(
                    frontend.patchComposite(hand.source(), TextureStage.GBUFFERS_AND_SHADOW));
            assertTrue(linked.fragmentGlsl().contains("float bias("),
                    "Bliss gbuffers must define its own bias() helper (the MSL collision trigger)");
            String prepared = GlslangSourcePrep.buildFullSource(linked.fragmentGlsl(), null);
            assertTrue(prepared.contains("metallum_user_bias("),
                    "the pack's bias() must be renamed away from metal::bias");
            assertFalse(prepared.matches("(?s).*\\bbias\\b.*"),
                    "no bare bias reference may survive in the glslang source");
        }
    }
}
