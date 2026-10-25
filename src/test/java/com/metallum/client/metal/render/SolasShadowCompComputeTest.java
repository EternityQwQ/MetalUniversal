package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the Solas V3.7b HIGH-profile on-device crash
 * {@code Failed to compile Iris compute shadowcomp} (glslang: "non-opaque
 * uniforms outside a block" and "sampler/texture/image requires
 * layout(binding=X)").
 *
 * <p>Upstream {@code TransformPatcher.patchCompute} emits GL-style compute
 * GLSL, and the per-slot {@code world0/shadowcomp.csh} chain is enabled by
 * default. It appears in {@code ProgramSet#getCompute(ShadowComposite)},
 * <em>not</em> in {@code getShadowCompute()} — an earlier scan checked the
 * wrong array and wrongly concluded the program was disabled.
 * {@link IrisMetalGlslLinker#linkCompute} now normalizes the version, hoists
 * the five loose uniforms into {@code MetallumIrisUniforms} and injects
 * explicit {@code layout(binding=N)} on the sampler/image declarations. The
 * real shaderc compile only exists on device; this gate pins the exact source
 * transformation the Vulkan rules rejected before it.
 */
final class SolasShadowCompComputeTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "Solas Shader V3.7b.zip");
    private static final TextureStage STAGE = TextureStage.GBUFFERS_AND_SHADOW;
    /**
     * The resolved option values of {@code profile.HIGH} (Iris infers the
     * active profile from option values, so a bare {@code profile=HIGH} config
     * key is ignored).
     */
    private static final Map<String, String> HIGH = Map.ofEntries(
            Map.entry("LPV_FOG", "true"),
            Map.entry("REFRACTION", "true"),
            Map.entry("BLOOM", "true"),
            Map.entry("VOLUMETRIC_CLOUDS", "true"),
            Map.entry("VL", "true"),
            Map.entry("AURORA", "true"),
            Map.entry("GENERATED_NORMALS", "true"),
            Map.entry("GENERATED_SPECULAR", "true"),
            Map.entry("NETHER_SMOKE", "true"),
            Map.entry("END_DISK", "true"),
            Map.entry("SHADOW_COLOR", "true"),
            Map.entry("WATER_NORMALS", "3"),
            Map.entry("shadowMapResolution", "2048"),
            Map.entry("shadowDistance", "192.0"),
            Map.entry("VOXEL_VOLUME_SIZE", "192"),
            Map.entry("VL_SAMPLES", "8")
    );

    @Test
    void shadowCompLowersLooseUniformsAndOpaqueBindingsForVulkan() throws Exception {
        Iris.testing = true;
        try (ComputeFixture fixture = open()) {
            IrisMetalGlslLinker.LinkedComputeProgram linked = fixture.linked();

            // Version: shaderc targets Vulkan; 430 compatibility is rejected
            // before the uniform checks even run.
            assertTrue(linked.glsl().startsWith("#version 460 core"),
                    "compute lowering must normalize the version: "
                            + linked.glsl().substring(0, Math.min(40, linked.glsl().length())));

            // Nine loose uniforms hoisted into the std140 block: the four
            // iris_Fog* declarations come from lib/common.glsl (renamed by the
            // upstream transformer), the five below from shadowcomp.glsl.
            // Offsets follow std140 alignment for the declaration order.
            assertEquals(
                    List.of("iris_FogDensity", "iris_FogStart", "iris_FogEnd", "iris_FogColor",
                            "frameCounter", "wetness", "cameraPosition", "previousCameraPosition",
                            "frameTimeCounter"),
                    linked.uniformLayout().stream().map(IrisMetalGlslLinker.UniformMember::name).toList()
            );
            assertEquals(0, linked.uniformLayout().get(0).offset());
            assertEquals(4, linked.uniformLayout().get(1).offset());
            assertEquals(8, linked.uniformLayout().get(2).offset());
            assertEquals(16, linked.uniformLayout().get(3).offset());
            assertEquals(32, linked.uniformLayout().get(4).offset());
            assertEquals(36, linked.uniformLayout().get(5).offset());
            assertEquals(48, linked.uniformLayout().get(6).offset());
            assertEquals(64, linked.uniformLayout().get(7).offset());
            assertEquals(76, linked.uniformLayout().get(8).offset());
            assertEquals(80, linked.uniformBlockSize());
            assertTrue(linked.glsl().contains("layout(std140, binding=" + linked.uniformBinding() + ") uniform "
                            + IrisMetalGlslLinker.UNIFORM_BLOCK_NAME),
                    "hoisted block must carry an explicit binding: " + linked.uniformBinding());
            assertFalse(linked.glsl().contains("uniform int frameCounter;"),
                    "loose uniforms must not remain outside the block");

            // Multi-declarator opaque declarations are split and bound 0..N-1;
            // the uniform block takes N (5).
            assertEquals(
                    List.of("voxelSampler", "floodfill_img", "floodfill_img_copy",
                            "floodfillSampler", "floodfillSamplerCopy"),
                    linked.resourceNames()
            );
            assertEquals(5, linked.uniformBinding());
            for (int index = 0; index < linked.resourceNames().size(); index++) {
                assertTrue(linked.glsl().contains("layout(binding=" + index + ")"),
                        "resource " + linked.resourceNames().get(index) + " must get binding " + index);
            }
            assertTrue(linked.glsl().contains("layout(binding=1) writeonly uniform image3D floodfill_img;"),
                    "split storage-image declarator expected");
            assertTrue(linked.glsl().contains("layout(binding=2) writeonly uniform image3D floodfill_img_copy;"),
                    "split storage-image declarator expected");

            // Every hoisted member has a production value source, so prewarm
            // cannot reject the compute block.
            IrisMetalUniformValues values = new IrisMetalUniformValues(0.0f, () -> 0);
            for (IrisMetalGlslLinker.UniformMember member : linked.uniformLayout()) {
                assertNotNull(values.writeUniformForGate(member));
            }
            assertTrue(values.unsupportedNames().isEmpty(),
                    "compute members without a writer: " + values.unsupportedNames());
        }
    }

    private static ComputeFixture open() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of());
        ShaderPack pack = new ShaderPack(
                fileSystem.getPath("/shaders"),
                HIGH,
                StandardMacros.createStandardEnvironmentDefines(),
                false
        );
        ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
        ComputeSource[][] slots = programs.getCompute(ProgramArrayId.ShadowComposite);
        ComputeSource source = slots.length > 0 && slots[0].length > 0 ? slots[0][0] : null;
        Assumptions.assumeTrue(source != null && source.isValid(),
                "Solas shadowcomp is not enabled for this configuration");
        IrisMetalWorldPrograms worldPrograms = new IrisMetalWorldPrograms(1, programs);
        IrisMetalProgramFrontend.ComputeProgram patched = worldPrograms.compute(source, STAGE);
        return new ComputeFixture(
                fileSystem,
                worldPrograms,
                IrisMetalGlslLinker.linkCompute(source.getName(), patched.patchedSource())
        );
    }

    private record ComputeFixture(
            FileSystem fileSystem,
            IrisMetalWorldPrograms worldPrograms,
            IrisMetalGlslLinker.LinkedComputeProgram linked
    ) implements AutoCloseable {
        @Override
        public void close() {
            worldPrograms.close();
            try {
                fileSystem.close();
            } catch (Exception ignored) {
                // Best-effort fixture teardown.
            }
        }
    }
}
