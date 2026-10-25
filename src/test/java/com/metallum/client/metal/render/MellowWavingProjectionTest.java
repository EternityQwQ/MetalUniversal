package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the Mellow "waving leaves occlude nearer blocks" bug.
 *
 * <p>The main pass rasterizes with the engine's zero-to-one projection
 * (Sodium {@code u_ProjectionMatrix}), but packs receive {@code gbufferProjection}
 * converted back to Iris's OpenGL [-1,1] space for fragment reconstruction
 * ({@link MetalIrisDepthConvention}). Mellow's {@code gbuffers_terrain.vsh}
 * WAVE_LEAVES branch rewrites {@code gl_Position} with that pack-space matrix,
 * so waving vertices stored depth {@code 2d-1}: leaves occluded blocks out to
 * about twice their own distance, and moving closer restored the order.
 *
 * <p>With {@link MetalDebugSwitches#VERTEX_ENGINE_PROJECTION} (default) the
 * vertex stage's {@code gbufferProjection}/{@code iris_ProjectionMatrix}
 * identifiers are renamed to the engine-space member, while the fragment stage
 * keeps the OpenGL-space one. This test links the real terrain program and pins
 * both halves plus the matrix algebra behind them.
 */
final class MellowWavingProjectionTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "Mellow_3.4.1a.zip");
    private static final Pattern PACK_PROJECTION = Pattern.compile("\\bgbufferProjection\\b");

    @Test
    void mellowTerrainVertexUsesEngineSpaceProjection() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Assumptions.assumeTrue(MetalDebugSwitches.VERTEX_ENGINE_PROJECTION,
                "vertex engine projection fix is switched off");
        Iris.testing = true;

        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            try (IrisMetalWorldPrograms worldPrograms = new IrisMetalWorldPrograms(1, programs)) {
                IrisMetalGlslLinker.LinkedRasterProgram terrain = worldPrograms.vanilla(
                        ProgramId.TerrainCutout,
                        AlphaTest.ALWAYS,
                        false,
                        false,
                        new ShaderAttributeInputs(true, true, true, true, true)
                ).orElseThrow(() -> new AssertionError("Mellow gbuffers_terrain must link"));

                // Vertex stage: renamed to the engine-space member, and the
                // WAVE_LEAVES gl_Position computation now uses it. The shared
                // uniform block still lists the pack-space member (fragment
                // stage), so compare the body outside that block.
                String vertexBody = stripUniformBlock(terrain.vertexGlsl());
                assertTrue(terrain.vertexGlsl().contains(
                                IrisMetalGlslLinker.VERTEX_ENGINE_PROJECTION_UNIFORM),
                        "vertex stage must declare the engine-space projection member");
                assertTrue(vertexBody.contains(
                                IrisMetalGlslLinker.VERTEX_ENGINE_PROJECTION_UNIFORM + " *"),
                        "WAVE_LEAVES must project with the engine-space member: " + vertexBody);
                assertFalse(PACK_PROJECTION.matcher(vertexBody).find(),
                        "vertex body must not reference the OpenGL-space pack projection");
                assertTrue(terrain.uniformLayout().stream().anyMatch(member ->
                                member.name().equals(IrisMetalGlslLinker.VERTEX_ENGINE_PROJECTION_UNIFORM)
                                        && member.type().equals("mat4")),
                        "layout must carry the engine-space projection member: " + terrain.uniformLayout());

                // Fragment stage: DOF still needs the OpenGL-space matrix.
                assertTrue(terrain.fragmentGlsl().contains("gbufferProjection"),
                        "fragment stage must keep the OpenGL-space pack projection");

                // The writer answers the new member instead of rejecting it.
                IrisMetalGlslLinker.UniformMember member = terrain.uniformLayout().stream()
                        .filter(candidate -> candidate.name()
                                .equals(IrisMetalGlslLinker.VERTEX_ENGINE_PROJECTION_UNIFORM))
                        .findFirst()
                        .orElseThrow();
                IrisMetalUniformValues writer = new IrisMetalUniformValues(0.0f, () -> 0);
                writer.writeUniformForGate(member);
                assertFalse(writer.unsupportedNames().contains(member.name()),
                        "writer must supply the engine-space projection member");
            }
        }
    }

    private static String stripUniformBlock(final String source) {
        String marker = "uniform " + IrisMetalGlslLinker.UNIFORM_BLOCK_NAME + " {";
        int start = source.indexOf(marker);
        if (start < 0) {
            return source;
        }
        int end = source.indexOf("};", start);
        return end < 0 ? source : source.substring(0, start) + source.substring(end + 2);
    }

    /**
     * Pins the algebra behind the bug: the pack-space projection differs from
     * the engine zero-to-one projection exactly by the GL NDC viewport remap on
     * the z row ({@code 2z - w}), and the two conversions are mutual inverses.
     */
    @Test
    void packAndEngineProjectionSpacesDifferByTheDepthRow() {
        Matrix4f engine = new Matrix4f().setPerspective(
                (float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 256.0f, true
        );
        Matrix4f pack = MetalIrisDepthConvention.packProjection(engine);

        assertEquals(2.0f * engine.m02() - engine.m03(), pack.m02(), 1.0e-6f, "m02");
        assertEquals(2.0f * engine.m12() - engine.m13(), pack.m12(), 1.0e-6f, "m12");
        assertEquals(2.0f * engine.m22() - engine.m23(), pack.m22(), 1.0e-6f, "m22");
        assertEquals(2.0f * engine.m32() - engine.m33(), pack.m32(), 1.0e-6f, "m32");

        Matrix4f roundTrip = new Matrix4f(pack);
        ProjectionDepthRemap.apply(roundTrip);
        assertTrue(engine.equals(roundTrip, 1.0e-5f),
                "pack->engine must be the exact inverse of engine->pack:\n"
                        + engine + "\nvs\n" + roundTrip);
    }
}
