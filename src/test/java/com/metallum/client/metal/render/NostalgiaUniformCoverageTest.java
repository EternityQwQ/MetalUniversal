package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uniform coverage gate for Nostalgia Shader v5.1.
 *
 * <p>Three gaps found by the headless scan:
 * <ul>
 *   <li>{@code skyCaptureResolution} (vec2) is declared in
 *       {@code world0/world1/deferred.fsh} and {@code deferred/sspt.fsh} but
 *       has no supplier in the pack or in pinned Iris, so GL leaves it (0,0)
 *       and the writer must do the same;</li>
 *   <li>{@code hideGUI} (declared {@code int}) is supplied upstream as a
 *       {@code bool} (CommonUniforms.java:144); the writer must translate
 *       {@code booleanReturn} for int members instead of reading the untouched
 *       {@code intReturn};</li>
 *   <li>{@code currentColorSpace} (int) is supplied by upstream
 *       IrisExclusiveUniforms.java:45, registered here like production.</li>
 * </ul>
 *
 * <p>The prepare prelude (which owns the {@code texture.prepare.colortex7}
 * ResourceData override) is swept separately through
 * {@link UniformCoverageHarness#auditPrelude}.
 */
final class NostalgiaUniformCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "Nostalgia_v5.1.zip");
    private static final Map<String, Map<String, String>> CONFIGS = new LinkedHashMap<>();
    private static final Map<String, NamespacedId> DIMENSIONS = new LinkedHashMap<>();
    private static final String[] BIOME_OR_SCREEN = {"skyCaptureResolution", "hideGUI", "currentColorSpace"};

    static {
        CONFIGS.put("default", Map.of());
        CONFIGS.put("profile=Ultra", Map.of("profile", "Ultra"));
        DIMENSIONS.put("overworld", new NamespacedId("minecraft", "overworld"));
        DIMENSIONS.put("nether", new NamespacedId("minecraft", "the_nether"));
        DIMENSIONS.put("end", new NamespacedId("minecraft", "the_end"));
    }

    @Test
    void nostalgiaWorldUniformBlocksHaveNoUnsupportedMembers() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        Map<String, UniformCoverageHarness.Audit> audits = UniformCoverageHarness.auditAll(
                PACK, DIMENSIONS, CONFIGS,
                holder -> {
                    holder.uniform2i(UniformUpdateFrequency.PER_FRAME, "eyeBrightness", () -> new Vector2i());
                    holder.uniform1i(UniformUpdateFrequency.PER_FRAME, "isEyeInWater", () -> 0);
                    holder.uniform1f(UniformUpdateFrequency.PER_TICK, "rainStrength", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_TICK, "wetness", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "thunderStrength", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "blindness", () -> 0.0f);
                    holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "darknessLightFactor", () -> 0.0f);
                    // Upstream suppliers (CommonUniforms.java:144 /
                    // IrisExclusiveUniforms.java:45); headless cannot run
                    // those registration blocks.
                    holder.uniform1b(UniformUpdateFrequency.PER_FRAME, "hideGUI", () -> false);
                    holder.uniform1i(UniformUpdateFrequency.PER_FRAME, "currentColorSpace", () -> 0);
                    holder.uniform1b(UniformUpdateFrequency.PER_FRAME, "is_invisible", () -> false);
                    holder.uniform3d(UniformUpdateFrequency.PER_FRAME, "playerLookVector", () -> new Vector3d());
                }
        );

        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            System.out.println("[nostalgia-uniform-coverage] config=" + entry.getKey()
                    + " linkedPrograms=" + entry.getValue().linkedPrograms
                    + " declared=" + entry.getValue().declared.size()
                    + " rejected=" + entry.getValue().rejected);
        }
        for (Map.Entry<String, UniformCoverageHarness.Audit> entry : audits.entrySet()) {
            assertTrue(entry.getValue().linkedPrograms > 0,
                    "Nostalgia '" + entry.getKey() + "': no world program linked");
            assertTrue(entry.getValue().rejected.isEmpty(),
                    "Nostalgia '" + entry.getKey() + "' uniforms without a value source: "
                            + entry.getValue().rejected
                            + " (graph-supplied: " + entry.getValue().graphSupplied + ")");
        }

        for (UniformCoverageHarness.Audit audit : audits.values()) {
            for (String name : BIOME_OR_SCREEN) {
                assertTrue(audit.declared.contains(name),
                        "layouts must declare " + name + "; declared=" + audit.declared);
                assertFalse(audit.unsupported.contains(name),
                        name + " must have a value source");
            }
        }

        // skyCaptureResolution: GLSL default (0,0), layout-independent check.
        UniformCoverageHarness.Audit overworld = audits.get("overworld/default");
        ByteBuffer resolution = overworld.relaxed.writeUniformForGate(
                new IrisMetalGlslLinker.UniformMember("vec2", "skyCaptureResolution", 0, 0, 8)
        );
        assertFalse(overworld.relaxed.unsupportedNames().contains("skyCaptureResolution"),
                "skyCaptureResolution must be answered by the writer's explicit default");
        assertEquals(0.0f, resolution.getFloat(0), 0.0f);
        assertEquals(0.0f, resolution.getFloat(Float.BYTES), 0.0f);

        // bool graph variable -> int member conversion (hideGUI).
        assertBoolToIntConversion();

        // Prepare prelude: uniform layouts and the PREPARE-stage custom texture.
        for (Map.Entry<String, NamespacedId> dimension : DIMENSIONS.entrySet()) {
            UniformCoverageHarness.Audit prelude = UniformCoverageHarness.auditPrelude(
                    PACK, dimension.getValue(), Map.of(), holder -> {
                        holder.uniform2i(UniformUpdateFrequency.PER_FRAME, "eyeBrightness", () -> new Vector2i());
                        holder.uniform1i(UniformUpdateFrequency.PER_FRAME, "isEyeInWater", () -> 0);
                    }
            );
            assertTrue(prelude.rejected.isEmpty(),
                    "Nostalgia '" + dimension.getKey() + "' prelude uniforms without a value source: "
                            + prelude.rejected);
        }
    }

    /** World/terrain/graph sampler chain resolves end to end. */
    @Test
    void nostalgiaSamplersResolveForActiveSet() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);

        ShaderpackSamplerCoverage.Report report = ShaderpackSamplerCoverage.link(PACK);
        assertFalse(report.programs().isEmpty(), "no Nostalgia program was linked");
        assertTrue(report.unresolved().isEmpty(),
                "active Nostalgia samplers the binding table cannot resolve: " + report.unresolved()
                        + ShaderpackSamplerCoverage.describe(report));
        System.out.println("[nostalgia-sampler-coverage] linked=" + report.programs().size()
                + " active=" + report.active().size());
    }

    /**
     * Pins the bool-to-int writer path: upstream supplies {@code hideGUI} as a
     * bool while Nostalgia declares it int, so a true boolean must write 1
     * rather than the untouched {@code intReturn} 0.
     */
    private static void assertBoolToIntConversion() throws Exception {
        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    net.irisshaders.iris.gl.shader.StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            FrameUpdateNotifier notifier = new FrameUpdateNotifier();
            CustomUniforms graph = pack.customUniforms.build(holder -> {
                UniformCoverageHarness.registerTestInputUniforms(
                        holder, pack, programs, notifier, ignored -> {
                        }
                );
                holder.uniform1b(UniformUpdateFrequency.PER_FRAME, "hideGUI", () -> true);
            });
            assertTrue(graph.hasVariable("hideGUI"), "test graph must define hideGUI");
            // Refresh only hideGUI: CustomUniforms.update() would also evaluate
            // CelestialUniforms.getUpPosition, which needs a captured frame
            // headless does not have. Production calls the full update() from
            // IrisMetalUniformValues.updateFrame before upload.
            ((net.irisshaders.iris.uniforms.custom.cached.CachedUniform)
                    graph.getVariable("hideGUI")).update();

            IrisMetalUniformValues writer = new IrisMetalUniformValues(
                    programs.getPackDirectives().getSunPathRotation(),
                    graph,
                    notifier,
                    () -> 0,
                    programs.getPackDirectives().getShadowDirectives()
            );
            ByteBuffer bytes = writer.writeUniformForGate(
                    new IrisMetalGlslLinker.UniformMember("int", "hideGUI", 0, 0, 4)
            );
            assertFalse(writer.unsupportedNames().contains("hideGUI"),
                    "hideGUI must resolve through the Iris graph");
            assertEquals(1, bytes.getInt(0),
                    "a true bool graph variable must write int 1 (GL uploads bools as 0/1)");
        }
    }
}
