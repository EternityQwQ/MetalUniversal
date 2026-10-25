package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.GlslangBridge;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * macOS-gated regression test for the shaderpack per-stage compact sampler
 * index remap.
 *
 * <p>Complementary Reimagined's {@code deferred1} fragment stage declares 23
 * combined image samplers and uses 7 of them. Before the compact remap it
 * emitted {@code [[sampler(17)]]}/{@code [[sampler(23)]]}, which Metal rejects
 * with {@code 'sampler' attribute parameter is out of bounds: must be between
 * 0 and 15}, failing the whole pass. This test cross-compiles the real pack's
 * deferred1 program and pins:
 * <ul>
 *   <li>every {@code [[sampler(N)]]} is {@code N <= 15} and unique;</li>
 *   <li>the compact plan covers 0..n-1 with no holes;</li>
 *   <li>the four crash-critical resources (colortex0, depthtex0, noisetex,
 *       shadowtex0) keep matching texture and sampler indices below 16.</li>
 * </ul>
 *
 * <p>Skipped on hosts without the native glslang/SPIRV-Cross libraries
 * (non-macOS) and when the compat pack has not been fetched.
 */
final class ComplementaryReimaginedSamplerBindingTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "ComplementaryReimagined_r5.9.3.zip");
    private static final List<String> CRASH_CRITICAL_SAMPLERS =
            List.of("colortex0", "depthtex0", "noisetex", "shadowtex0");

    @Test
    void deferred1FragmentSamplersAreCompactedBelowMetalLimit() throws Exception {
        Assumptions.assumeTrue(canLoadGlslang(), "Skipped: native libglslang unavailable (non-macOS host)");
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            ProgramSource deferred1 = Arrays.stream(programs.getComposite(ProgramArrayId.Deferred))
                    .filter(Objects::nonNull)
                    .filter(ProgramSource::isValid)
                    .filter(source -> source.getName().contains("deferred1"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "CR pack has no deferred1 program; deferred="
                                    + Arrays.toString(programs.getComposite(ProgramArrayId.Deferred))
                    ));

            IrisMetalProgramFrontend frontend = new IrisMetalProgramFrontend(programs);
            IrisMetalProgramFrontend.RasterProgram patched =
                    frontend.patchComposite(deferred1, TextureStage.DEFERRED);
            IrisMetalGlslLinker.LinkedRasterProgram linked = IrisMetalGlslLinker.linkDefault(patched);

            MetalCrossShaderCompiler.ShaderpackMslResult result =
                    MetalCrossShaderCompiler.tryCompileShaderpackMsl(
                            linked.name(),
                            linked.vertexGlsl(),
                            null,
                            null,
                            null,
                            linked.fragmentGlsl(),
                            null
                    );
            assertNotNull(result.fragmentMsl());

            Map<String, Integer> plan = result.fragmentSampledImageIndices();
            int activeCount = plan.size();
            // The crash log's active set is 7; allow modest pack/define drift
            // while still proving the remap engaged.
            assertTrue(
                    activeCount >= 4 && activeCount < 16,
                    "unexpected active sampled image count: " + plan
            );
            Set<Integer> assigned = new HashSet<>(plan.values());
            assertEquals(activeCount, assigned.size(), "compact indices collide: " + plan);
            for (int index = 0; index < activeCount; index++) {
                assertTrue(assigned.contains(index), "compact plan has a hole at " + index + ": " + plan);
            }

            assertSamplerIndicesWithinMetalLimit(result.fragmentMsl(), "deferred1 fragment");
            assertUniqueBindings(result.fragmentMsl(), "sampler");

            for (String name : CRASH_CRITICAL_SAMPLERS) {
                Integer planned = plan.get(name);
                assertNotNull(planned, name + " is not active in CR deferred1 fragment; plan=" + plan);
                assertTrue(planned < 16, name + " planned index " + planned + " exceeds Metal's limit");
                assertEquals(
                        planned.intValue(),
                        textureBinding(result.fragmentMsl(), name),
                        name + " texture binding disagrees with the compact plan; msl=" + result.fragmentMsl()
                );
                assertEquals(
                        planned.intValue(),
                        samplerBinding(result.fragmentMsl(), name),
                        name + " sampler binding disagrees with the compact plan (crash regression)"
                );
            }
        }
    }

    private static boolean canLoadGlslang() {
        try {
            GlslangBridge.compileGlslToSpv(
                    GlslangBridge.Stage.VERTEX,
                    "#version 460\nvoid main(){gl_Position=vec4(0);}",
                    null
            );
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static void assertSamplerIndicesWithinMetalLimit(final String msl, final String stage) {
        Matcher matcher = Pattern.compile("\\[\\[sampler\\((\\d+)\\)]]").matcher(msl);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            assertTrue(index < 16, stage + " MSL emits out-of-range [[sampler(" + index + ")]]");
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
        assertTrue(unique.size() == count, kind + " bindings collide in CR deferred1 MSL");
    }

    private static int textureBinding(final String msl, final String resourceName) {
        Matcher matcher = Pattern.compile(
                "\\b" + Pattern.quote(resourceName) + "\\s*\\[\\[texture\\((\\d+)\\)]]"
        ).matcher(msl);
        assertTrue(matcher.find(), "missing texture binding for " + resourceName);
        return Integer.parseInt(matcher.group(1));
    }

    private static int samplerBinding(final String msl, final String resourceName) {
        Matcher matcher = Pattern.compile(
                "\\bsampler\\s+" + Pattern.quote(resourceName) + "(?:Smplr)?\\s*\\[\\[sampler\\((\\d+)\\)]]"
        ).matcher(msl);
        assertTrue(matcher.find(), "missing sampler binding for " + resourceName);
        return Integer.parseInt(matcher.group(1));
    }
}
