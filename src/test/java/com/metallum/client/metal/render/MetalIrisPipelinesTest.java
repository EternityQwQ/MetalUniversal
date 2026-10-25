package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.metallum.client.metal.render.bridge.GlslangBridge;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless parity check between {@link MetalIrisPipelines} and the upstream
 * {@code IrisPipelines.coreShaderMap} the port tracks.
 *
 * <p>The test classpath carries the pinned Iris 1.11.2 jar, so upstream's own
 * mapping table is the expected value. Constant entries must agree exactly
 * (and the port's drift/adopt counters must stay at zero); selector entries
 * are asserted in the neutral context (no hand rendering, phase NONE, which
 * includes the null-pipeline degenerate case used by upstream's own
 * duplicate-detection calls). A {@code net.irisshaders.iris.pathways.HandRenderer}
 * test facade keeps the hand state inert without a Minecraft instance.</p>
 */
final class MetalIrisPipelinesTest {
    private static final String FIXTURE = "/shaderpacks/BSL_v10.1.3.zip";

    /** Pipelines upstream routes through state-dependent selectors, with their neutral-context key. */
    private static final Map<RenderPipeline, ShaderKey> SELECTOR_NEUTRAL_EXPECTATIONS = Map.ofEntries(
            Map.entry(RenderPipelines.ENTITY_CUTOUT, ShaderKey.ENTITIES_CUTOUT_DIFFUSE),
            Map.entry(RenderPipelines.ENTITY_CUTOUT_CULL, ShaderKey.ENTITIES_CUTOUT_DIFFUSE),
            Map.entry(RenderPipelines.ENTITY_CUTOUT_DISSOLVE, ShaderKey.ENTITIES_CUTOUT_DIFFUSE),
            Map.entry(RenderPipelines.ENTITY_TRANSLUCENT_CULL, ShaderKey.ENTITIES_TRANSLUCENT),
            Map.entry(RenderPipelines.ITEM_TRANSLUCENT, ShaderKey.ENTITIES_TRANSLUCENT),
            Map.entry(RenderPipelines.ITEM_CUTOUT, ShaderKey.ENTITIES_CUTOUT_DIFFUSE),
            Map.entry(RenderPipelines.ENTITY_TRANSLUCENT, ShaderKey.ENTITIES_TRANSLUCENT),
            Map.entry(RenderPipelines.ENTITY_SHADOW, ShaderKey.ENTITIES_TRANSLUCENT),
            Map.entry(RenderPipelines.ARMOR_CUTOUT_NO_CULL, ShaderKey.ENTITIES_CUTOUT_DIFFUSE),
            Map.entry(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, ShaderKey.ENTITIES_CUTOUT_DIFFUSE),
            Map.entry(RenderPipelines.ARMOR_TRANSLUCENT, ShaderKey.ENTITIES_TRANSLUCENT),
            Map.entry(RenderPipelines.BREEZE_WIND, ShaderKey.ENTITIES_TRANSLUCENT),
            Map.entry(RenderPipelines.ENTITY_SOLID, ShaderKey.ENTITIES_SOLID),
            Map.entry(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, ShaderKey.ENTITIES_SOLID),
            Map.entry(RenderPipelines.TEXT, ShaderKey.TEXT),
            Map.entry(RenderPipelines.TEXT_POLYGON_OFFSET, ShaderKey.TEXT),
            Map.entry(RenderPipelines.TEXT_SEE_THROUGH, ShaderKey.TEXT),
            Map.entry(RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH, ShaderKey.TEXT_INTENSITY),
            Map.entry(RenderPipelines.TEXT_GRAYSCALE, ShaderKey.TEXT_INTENSITY),
            Map.entry(RenderPipelines.BANNER_PATTERN, ShaderKey.ENTITIES_TRANSLUCENT)
    );

    @Test
    void constantMappingsFollowUpstreamBaseline() throws Exception {
        Map<RenderPipeline, ShaderKey> upstream = upstreamConstantBaseline("coreShaderMap");
        assertFalse(upstream.isEmpty(), "upstream coreShaderMap baseline was not readable");
        for (Map.Entry<RenderPipeline, ShaderKey> entry : upstream.entrySet()) {
            if (SELECTOR_NEUTRAL_EXPECTATIONS.containsKey(entry.getKey())) {
                continue;
            }
            assertEquals(
                    entry.getValue(),
                    MetalIrisPipelines.getShaderKeyForPipeline(null, entry.getKey()),
                    "constant mapping disagrees with upstream for " + entry.getKey().getLocation()
            );
        }
    }

    @Test
    void selectorMappingsMatchUpstreamInNeutralContext() throws Exception {
        for (Map.Entry<RenderPipeline, ShaderKey> entry : SELECTOR_NEUTRAL_EXPECTATIONS.entrySet()) {
            assertEquals(
                    entry.getValue(),
                    MetalIrisPipelines.getShaderKeyForPipeline(null, entry.getKey()),
                    "port selector disagrees in neutral context for " + entry.getKey().getLocation()
            );
            assertEquals(
                    entry.getValue(),
                    upstreamGetPipeline(entry.getKey()),
                    "upstream selector expectation wrong for " + entry.getKey().getLocation()
            );
        }
    }

    @Test
    void allStaticPipelinesAgreeWithUpstreamGetPipeline() throws Exception {
        List<RenderPipeline> pipelines = RenderPipelines.getStaticPipelines();
        assertFalse(pipelines.isEmpty());
        for (RenderPipeline pipeline : pipelines) {
            assertEquals(
                    upstreamGetPipeline(pipeline),
                    MetalIrisPipelines.getShaderKeyForPipeline(null, pipeline),
                    "mapping disagrees with upstream IrisPipelines.getPipeline for "
                            + pipeline.getLocation()
            );
        }
    }

    @Test
    void pinnedTableHasNoDriftAgainstLoadedIris() throws Exception {
        // Force MetalIrisPipelines class init (its static baseline reflection)
        // before reading the counters.
        MetalIrisPipelines.getShaderKeyForPipeline(null, RenderPipelines.SOLID_BLOCK);
        assertEquals(
                0,
                MetalIrisPipelines.upstreamDriftCount(),
                "pinned mapping table drifted from the loaded Iris version"
        );
        assertEquals(
                0,
                MetalIrisPipelines.upstreamAdoptedCount(),
                "upstream carries constant mappings missing from the pinned table"
        );
        assertEquals(
                0,
                MetalIrisPipelines.upstreamMissingCount(),
                "pinned table carries mappings the loaded Iris version no longer has"
        );
    }

    @Test
    void shadowMappingsFollowUpstreamBaseline() throws Exception {
        Map<RenderPipeline, ShaderKey> upstream = upstreamConstantBaseline("coreShaderMapShadow");
        assertFalse(upstream.isEmpty(), "upstream coreShaderMapShadow baseline was not readable");
        Class<?> shadowRenderer = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
        Field active = shadowRenderer.getField("ACTIVE");
        boolean previous = active.getBoolean(null);
        try {
            active.setBoolean(null, true);
            // M6.2 ports upstream assignToShadow in full; every upstream shadow
            // entry must resolve identically.
            for (Map.Entry<RenderPipeline, ShaderKey> entry : upstream.entrySet()) {
                assertEquals(
                        entry.getValue(),
                        MetalIrisPipelines.getShaderKeyForPipeline(null, entry.getKey()),
                        "shadow mapping disagrees with upstream for " + entry.getKey().getLocation()
                );
            }
            // M6.2 caster paths: entities and block-entity models.
            assertEquals(
                    ShaderKey.SHADOW_ENTITIES_CUTOUT,
                    MetalIrisPipelines.getShaderKeyForPipeline(null, RenderPipelines.ENTITY_CUTOUT)
            );
            assertEquals(
                    ShaderKey.SHADOW_ENTITIES_CUTOUT,
                    MetalIrisPipelines.getShaderKeyForPipeline(null, RenderPipelines.ENTITY_SOLID)
            );
            assertEquals(
                    ShaderKey.SHADOW_BEACON_BEAM,
                    MetalIrisPipelines.getShaderKeyForPipeline(null, RenderPipelines.BEACON_BEAM_OPAQUE)
            );
            assertEquals(
                    ShaderKey.SHADOW_BLOCK,
                    MetalIrisPipelines.getShaderKeyForPipeline(null, RenderPipelines.END_PORTAL)
            );
            // Moving blocks keep flowing through the shadow terrain keys.
            assertEquals(
                    ShaderKey.SHADOW_TERRAIN_CUTOUT,
                    MetalIrisPipelines.getShaderKeyForPipeline(null, RenderPipelines.SOLID_BLOCK)
            );
            assertEquals(
                    ShaderKey.SHADOW_LINES,
                    MetalIrisPipelines.getShaderKeyForPipeline(null, RenderPipelines.LINES)
            );
        } finally {
            active.setBoolean(null, previous);
        }
    }

    @Test
    void entityVanillaProgramCompilesWithAttributeBindings() throws Exception {
        boolean canLoadGlslang;
        try {
            GlslangBridge.compileGlslToSpv(
                    GlslangBridge.Stage.VERTEX,
                    "#version 460\nvoid main(){gl_Position=vec4(0);}",
                    null
            );
            canLoadGlslang = true;
        } catch (Throwable throwable) {
            canLoadGlslang = false;
        }
        Assumptions.assumeTrue(canLoadGlslang, "Skipped: native libglslang unavailable (non-macOS host)");

        try (LoadedPack loaded = loadFixture()) {
            ProgramSet programs = loaded.pack().getProgramSet(new NamespacedId("minecraft", "overworld"));
            IrisMetalProgramFrontend frontend = new IrisMetalProgramFrontend(programs);
            IrisMetalProgramFrontend.ResolvedProgram resolved = frontend.resolve(ProgramId.Entities)
                    .orElseThrow();
            ShaderAttributeInputs inputs = new ShaderAttributeInputs(
                    IrisVertexFormats.ENTITY, false, false, false, false, false
            );
            IrisMetalProgramFrontend.RasterProgram patched = frontend.patchVanilla(
                    resolved, AlphaTests.ONE_TENTH_ALPHA, false, false, inputs
            );
            assertNotNull(patched.vertexSource());
            assertNotNull(patched.fragmentSource());
            IrisMetalGlslLinker.LinkedRasterProgram linked = IrisMetalGlslLinker.linkDefault(patched);

            // Dry path (no physicalInputNames): must compile, but by design it
            // maps every vertex input generically and emits no [[attribute(N)]].
            MetalCrossShaderCompiler.ShaderpackMslResult dryResult =
                    MetalCrossShaderCompiler.tryCompileShaderpackMsl(
                            linked.name(),
                            linked.vertexGlsl(),
                            null,
                            null,
                            null,
                            linked.fragmentGlsl(),
                            null
                    );
            assertNotNull(dryResult.vertexMsl());
            assertNotNull(dryResult.fragmentMsl());

            // Attribute-mapped path: the same physical input names the
            // production compileShaderpack derives from the vertex format
            // binding, passed through to spirvToMsl so the ENTITY vertex MSL
            // carries real [[attribute(N)]] locations.
            List<String> physicalInputNames = MetalCrossShaderCompiler.vertexInputNames(
                    new VertexFormat[]{IrisVertexFormats.ENTITY}
            );
            MetalCrossShaderCompiler.ShaderpackMslResult boundResult =
                    MetalCrossShaderCompiler.tryCompileShaderpackMsl(
                            linked.name(),
                            linked.vertexGlsl(),
                            linked.fragmentGlsl(),
                            null,
                            physicalInputNames
                    );
            assertNotNull(boundResult.vertexMsl());

            Matcher attributes = Pattern.compile("\\[\\[attribute\\((\\d+)\\)]]")
                    .matcher(boundResult.vertexMsl());
            Set<Integer> locations = new LinkedHashSet<>();
            while (attributes.find()) {
                locations.add(Integer.parseInt(attributes.group(1)));
            }
            assertFalse(
                    locations.isEmpty(),
                    "ENTITY vertex MSL carries no attribute locations: " + boundResult.vertexMsl()
            );
            assertTrue(
                    locations.contains(0),
                    "ENTITY vertex MSL lacks attribute(0): " + locations
            );
            // Unused stage inputs can be eliminated by SPIRV-Cross, so the
            // declared attribute count may be smaller than the format's input
            // count; every declared location must still map onto a physical
            // slot, which is what proves the attribute mapping took effect.
            assertTrue(
                    locations.stream().allMatch(location ->
                            location >= 0 && location < physicalInputNames.size()),
                    "ENTITY vertex MSL attribute locations outside the physical range; "
                            + "inputs=" + physicalInputNames + ", locations=" + locations
                            + ", msl=" + boundResult.vertexMsl()
            );
        }
    }

    private static Object upstreamGetPipeline(final RenderPipeline pipeline) throws Exception {
        Class<?> irisPipelines = Class.forName("net.irisshaders.iris.pipeline.IrisPipelines");
        Class<?> irisRenderingPipeline = Class.forName(
                "net.irisshaders.iris.pipeline.IrisRenderingPipeline",
                false,
                MetalIrisPipelinesTest.class.getClassLoader()
        );
        Method getPipeline = irisPipelines.getMethod("getPipeline", irisRenderingPipeline, RenderPipeline.class);
        return getPipeline.invoke(null, null, pipeline);
    }

    private static Map<RenderPipeline, ShaderKey> upstreamConstantBaseline(final String fieldName)
            throws Exception {
        Class<?> irisPipelines = Class.forName("net.irisshaders.iris.pipeline.IrisPipelines");
        Field field = irisPipelines.getDeclaredField(fieldName);
        assertTrue(field.trySetAccessible(), "upstream " + fieldName + " is not accessible");
        Map<?, ?> raw = (Map<?, ?>) field.get(null);
        Map<RenderPipeline, ShaderKey> baseline = new java.util.HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            RenderPipeline pipeline = (RenderPipeline) entry.getKey();
            try {
                baseline.put(
                        pipeline,
                        (ShaderKey) ((it.unimi.dsi.fastutil.Function<?, ?>) entry.getValue()).apply(null)
                );
            } catch (Throwable ignored) {
                // Selector entries are not null-safe without a client; they are
                // covered by selectorMappingsMatchUpstreamInNeutralContext via
                // the HandRenderer facade instead.
            }
        }
        return baseline;
    }

    private static LoadedPack loadFixture() throws IOException {
        Path zip = Files.createTempFile("metal-iris-pipelines-", ".zip");
        zip.toFile().deleteOnExit();
        try (var input = MetalIrisPipelinesTest.class.getResourceAsStream(FIXTURE)) {
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
