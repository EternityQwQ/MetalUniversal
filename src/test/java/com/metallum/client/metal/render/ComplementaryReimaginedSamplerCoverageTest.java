package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.features.FeatureFlags;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the CR 5.9.3 first-deferred-pass crash.
 *
 * <p>The pack's shared {@code shaders/lib/uniforms.glsl} declares legacy
 * sampler names ({@code gaux2}, {@code gaux4}, {@code normals}, {@code specular},
 * {@code tex}) in every linked composite program but the compiled MSL does not
 * sample them. {@code bindRaster} used to resolve every declared sampler and
 * threw {@code "Iris pass deferred1 is missing required sampler 'gaux2'"}
 * before the pass's draw; the pending clear then crashed in
 * {@code submitRenderPass} and masked the real error.
 *
 * <p>The test links the real pack without native libraries and pins:
 * <ul>
 *   <li>the legacy declarations exist but are inactive in deferred1 (the old
 *       crash trigger),</li>
 *   <li>{@link IrisMetalExecutionGraph#activeSampledDeclarations} keeps only
 *       the compiled-in sampler set, and</li>
 *   <li>every active sampler has a standard/custom binding source under the
 *       same name rules as {@code IrisMetalExecutionGraph.textureBinding},
 *       and every program's only uniform block is the port's own block.</li>
 * </ul>
 */
final class ComplementaryReimaginedSamplerCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "ComplementaryReimagined_r5.9.3.zip");
    /** Legacy declarations from uniforms.glsl that are never compiled into MSL. */
    private static final Set<String> INACTIVE_LEGACY = Set.of(
            "gaux2", "gaux4", "normals", "specular", "tex"
    );
    private static final Pattern DECL = Pattern.compile(
            "(?m)^\\s*(?:(?:flat|noperspective|centroid|coherent|volatile|restrict|readonly|writeonly)\\s+)*"
                    + "uniform\\s+(\\w+)\\s+(\\w+)\\s*;");

    @Test
    void complementaryReimaginedWorldSamplersResolveForActiveSet() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programSet = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            int targetCount = IrisMetalRenderTargetFormats.from(programSet.getPackDirectives()).length;
            int shadowColorCount = pack.hasFeature(FeatureFlags.HIGHER_SHADOWCOLOR)
                    ? net.irisshaders.iris.shaderpack.properties.PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS
                    : net.irisshaders.iris.shaderpack.properties.PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_OF;
            Map<TextureStage, ?> customTextures = pack.getCustomTextureDataMap();
            List<String> checked = new ArrayList<>();

            try (IrisMetalWorldPrograms programs = new IrisMetalWorldPrograms(1, programSet)) {
                for (ProgramSource source : programSet.getComposite(ProgramArrayId.Deferred)) {
                    if (source != null && source.isValid()) {
                        inspect("deferred", source, TextureStage.DEFERRED,
                                programs.composite(source, TextureStage.DEFERRED),
                                targetCount, shadowColorCount, customTextures, checked);
                    }
                }
                for (ProgramSource source : programSet.getComposite(ProgramArrayId.Composite)) {
                    if (source != null && source.isValid()) {
                        inspect("composite", source, TextureStage.COMPOSITE_AND_FINAL,
                                programs.composite(source, TextureStage.COMPOSITE_AND_FINAL),
                                targetCount, shadowColorCount, customTextures, checked);
                    }
                }
                IrisMetalGlslLinker.LinkedRasterProgram finalProgram = programs.finalProgram();
                if (finalProgram != null) {
                    inspect("final", finalProgram.program().resolution().source(),
                            TextureStage.COMPOSITE_AND_FINAL, finalProgram,
                            targetCount, shadowColorCount, customTextures, checked);
                }
            }

            assertFalse(checked.isEmpty(), "no CR composite program was linked");
            System.out.println("[cr-sampler-coverage] linked=" + checked.size()
                    + " targetCount=" + targetCount
                    + " shadowColorCount=" + shadowColorCount);
        }
    }

    private static void inspect(
            final String kind,
            final ProgramSource source,
            final TextureStage stage,
            final IrisMetalGlslLinker.LinkedRasterProgram linked,
            final int targetCount,
            final int shadowColorCount,
            final Map<TextureStage, ?> customTextures,
            final List<String> checked
    ) {
        assertEquals(List.of(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME), linked.uniformBlockNames(),
                source.getName() + " needs an unsupported uniform block");

        Set<String> used = usedSamplerNames(linked);
        Set<String> activeNames = new LinkedHashSet<>();
        for (IrisMetalGlslLinker.SamplerDecl sampler
                : IrisMetalExecutionGraph.activeSampledDeclarations(linked.samplers(), used)) {
            activeNames.add(sampler.name());
            assertTrue(
                    canResolve(sampler.name(), targetCount, shadowColorCount, customTextures.get(stage)),
                    source.getName() + " actively samples '" + sampler.name()
                            + "' but the binding table cannot resolve it"
            );
        }
        assertEquals(used, activeNames,
                source.getName() + " active sampler set disagrees with the linked usage set");

        if (source.getName().equals("deferred1")) {
            Set<String> declared = new LinkedHashSet<>();
            for (IrisMetalGlslLinker.SamplerDecl sampler : linked.samplers()) {
                declared.add(sampler.name());
            }
            for (String legacy : INACTIVE_LEGACY) {
                assertTrue(declared.contains(legacy),
                        "deferred1 no longer declares legacy sampler " + legacy);
                assertFalse(used.contains(legacy),
                        "legacy sampler " + legacy + " unexpectedly active in deferred1: " + used);
                assertFalse(activeNames.contains(legacy),
                        "inactive legacy sampler " + legacy + " must not be bound");
            }
        }
        checked.add(kind + ":" + source.getName());
    }

    /**
     * Samplers sampled by the linked source, excluding their declarations. This
     * mirrors the per-stage active set the shaderpack compiler derives from
     * SPIRV-Cross reflection (which needs the native library).
     */
    private static Set<String> usedSamplerNames(final IrisMetalGlslLinker.LinkedRasterProgram linked) {
        List<String> declared = new ArrayList<>();
        for (IrisMetalGlslLinker.SamplerDecl sampler : linked.samplers()) {
            if (sampler.sampled()) {
                declared.add(sampler.name());
            }
        }
        Set<String> used = new LinkedHashSet<>();
        for (String name : declared) {
            Pattern use = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
            if (countUses(linked.vertexGlsl(), name, use) + countUses(linked.fragmentGlsl(), name, use) > 0) {
                used.add(name);
            }
        }
        return used;
    }

    private static int countUses(final String source, final String name, final Pattern use) {
        int declarations = 0;
        Matcher declaration = DECL.matcher(source);
        while (declaration.find()) {
            if (declaration.group(2).equals(name)) {
                declarations++;
            }
        }
        int total = 0;
        Matcher occurrence = use.matcher(source);
        while (occurrence.find()) {
            total++;
        }
        return total - declarations;
    }

    /**
     * Name rules of {@code IrisMetalExecutionGraph.textureBinding}, plus CR's
     * DEFERRED custom-texture override ({@code colortex3}). The world pipeline
     * in this test runs with shadows present; {@code computeResources} lookups
     * are Distant-Horizons-only and absent from the pack's declared names here.
     */
    private static boolean canResolve(
            final String name,
            final int targetCount,
            final int shadowColorCount,
            final Object customStageTextures
    ) {
        if (name.equals(IrisMetalCenterDepthSampler.SAMPLER_NAME) || name.equals("centerDepthSmooth")
                || name.equals("noisetex")
                || name.equals("depthtex0") || name.equals("depthtex1") || name.equals("depthtex2")) {
            return true;
        }
        if (name.startsWith("shadowtex")) {
            return true;
        }
        if (name.startsWith("shadowcolor")) {
            int color = name.equals("shadowcolor") ? 0 : suffix(name, "shadowcolor");
            return color >= 0 && color < shadowColorCount;
        }
        int color = suffix(name, "colortex");
        if (color >= 0) {
            return color < targetCount;
        }
        int image = suffix(name, "colorimg");
        if (image >= 0) {
            return image < targetCount;
        }
        return customStageTextures instanceof Map<?, ?> map && map.containsKey(name);
    }

    private static int suffix(final String name, final String prefix) {
        if (!name.startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
