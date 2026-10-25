package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java shaderpack sampler coverage harness shared by the per-pack
 * regression tests (MakeUp, Sildur's, Unbound, Photon).
 *
 * <p>Links every graph program (deferred/composite/composite-final) and every
 * gbuffers/shadow raster program with {@link IrisMetalWorldPrograms} (no native
 * libraries), derives the active sampler set the same way the compiled-MSL
 * reflection would (a declared sampler is active when the linked GLSL samples
 * it), and classifies each active name against the port's resolution rules.
 * {@code unresolved} is the crash surface: an active name the binding tables
 * cannot resolve aborts the pass.
 */
final class ShaderpackSamplerCoverage {
    /**
     * A GLSL sampler declaration in the linked stage source. Mirrors
     * {@code IrisMetalGlslLinker}'s uniform-statement + opaque-type split
     * (which removes loose non-opaque uniforms), allowing an optional
     * {@code layout(...)} qualifier and precision qualifiers:
     * {@code [layout(...)] uniform [precision] samplerType name}.
     */
    private static final Pattern UNIFORM_SAMPLER = Pattern.compile(
            "(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+"
                    + "(?:lowp\\s+|mediump\\s+|highp\\s+)?"
                    + "(?:[iu]?sampler\\w*|texture\\w*)\\s+([A-Za-z_]\\w*)"
    );

    /**
     * One linked program and its active/unresolved sampler names.
     *
     * <p>{@code vertexGlsl}/{@code fragmentGlsl} are the linked stage sources
     * (sampler declarations + body), exposed so tests can feed
     * {@link #declaredSamplers} / {@link #sampledNames} through
     * {@link ShaderpackSamplerIndexPlan} exactly as the compiler's per-stage
     * compact index assignment does.
     */
    record ProgramCoverage(
            String kind,
            String name,
            Set<String> active,
            Set<String> unresolved,
            String vertexGlsl,
            String fragmentGlsl
    ) {
        ProgramCoverage {
            active = Set.copyOf(active);
            unresolved = Set.copyOf(unresolved);
        }
    }

    /** Whole-pack coverage outcome. */
    record Report(
            int targetCount,
            int shadowColorCount,
            List<ProgramCoverage> programs,
            Set<String> active,
            Set<String> unresolved
    ) {
        /** First linked program with this source name, or {@code null}. */
        ProgramCoverage program(final String name) {
            for (ProgramCoverage program : programs) {
                if (program.name().equals(name)) {
                    return program;
                }
            }
            return null;
        }
    }

    private ShaderpackSamplerCoverage() {
    }

    /** Links a pack with its own default options. */
    static Report link(final Path pack) throws Exception {
        return link(pack, Map.of());
    }

    /**
     * Links a pack with Iris {@code changedConfigs} option overrides applied
     * (same path as a user's saved shader options).
     */
    static Report link(final Path pack, final Map<String, String> changedConfigs) throws Exception {
        Iris.testing = true;
        try (FileSystem fileSystem = FileSystems.newFileSystem(pack, Map.of())) {
            ShaderPack shaderPack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    changedConfigs,
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programSet = shaderPack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            int targetCount = IrisMetalRenderTargetFormats.from(programSet.getPackDirectives()).length;
            int shadowColorCount = shaderPack.hasFeature(FeatureFlags.HIGHER_SHADOWCOLOR)
                    ? net.irisshaders.iris.shaderpack.properties.PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS
                    : net.irisshaders.iris.shaderpack.properties.PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_OF;
            Map<TextureStage, ?> customTextures = shaderPack.getCustomTextureDataMap();
            Set<String> irisCustomTextures = Set.copyOf(shaderPack.getIrisCustomTextureDataMap().keySet());
            Resolver resolver = new Resolver(targetCount, shadowColorCount, customTextures, irisCustomTextures);
            List<ProgramCoverage> coverage = new ArrayList<>();

            try (IrisMetalWorldPrograms programs = new IrisMetalWorldPrograms(1, programSet)) {
                for (ProgramSource source : programSet.getComposite(ProgramArrayId.Deferred)) {
                    if (source != null && source.isValid()) {
                        inspect("deferred", source,
                                programs.composite(source, TextureStage.DEFERRED),
                                customTextures.get(TextureStage.DEFERRED), resolver, coverage);
                    }
                }
                for (ProgramSource source : programSet.getComposite(ProgramArrayId.Composite)) {
                    if (source != null && source.isValid()) {
                        inspect("composite", source,
                                programs.composite(source, TextureStage.COMPOSITE_AND_FINAL),
                                customTextures.get(TextureStage.COMPOSITE_AND_FINAL), resolver, coverage);
                    }
                }
                IrisMetalGlslLinker.LinkedRasterProgram finalProgram = programs.finalProgram();
                if (finalProgram != null) {
                    inspect("final", finalProgram.program().resolution().source(), finalProgram,
                            customTextures.get(TextureStage.COMPOSITE_AND_FINAL), resolver, coverage);
                }

                ShaderAttributeInputs inputs = new ShaderAttributeInputs(true, true, true, true, true);
                for (ProgramId id : ProgramId.values()) {
                    if (id == ProgramId.Final || id.getGroup() == ProgramGroup.Dh) {
                        continue;
                    }
                    Optional<IrisMetalGlslLinker.LinkedRasterProgram> linked =
                            programs.vanilla(id, AlphaTest.ALWAYS, false, false, inputs);
                    if (linked.isPresent()) {
                        inspect("raster:" + id.name(), linked.orElseThrow().program().resolution().source(),
                                linked.orElseThrow(), customTextures.get(TextureStage.GBUFFERS_AND_SHADOW),
                                resolver, coverage);
                    }
                }
            }

            Set<String> active = new LinkedHashSet<>();
            Set<String> unresolved = new LinkedHashSet<>();
            for (ProgramCoverage program : coverage) {
                active.addAll(program.active());
                unresolved.addAll(program.unresolved());
            }
            return new Report(targetCount, shadowColorCount, List.copyOf(coverage), active, unresolved);
        }
    }

    /** One-line-per-program description of where unresolved names live. */
    static String describe(final Report report) {
        StringBuilder builder = new StringBuilder();
        for (ProgramCoverage program : report.programs()) {
            if (!program.unresolved().isEmpty()) {
                builder.append("\n  ").append(program.kind()).append(':').append(program.name())
                        .append(" -> ").append(program.unresolved());
            }
        }
        return builder.toString();
    }

    private static void inspect(
            final String kind,
            final ProgramSource source,
            final IrisMetalGlslLinker.LinkedRasterProgram linked,
            final Object customStageTextures,
            final Resolver resolver,
            final List<ProgramCoverage> coverage
    ) {
        Set<String> used = usedSamplerNames(linked);
        Set<String> active = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();
        for (IrisMetalGlslLinker.SamplerDecl sampler
                : IrisMetalExecutionGraph.activeSampledDeclarations(linked.samplers(), used)) {
            active.add(sampler.name());
            if (!resolver.canResolve(sampler.name(), kind.startsWith("raster:"), customStageTextures)) {
                unresolved.add(sampler.name());
            }
        }
        coverage.add(new ProgramCoverage(
                kind, source.getName(), active, unresolved,
                linked.vertexGlsl(), linked.fragmentGlsl()
        ));
    }

    /**
     * Name rules of the port's samplers: {@code IrisMetalExecutionGraph.textureBinding},
     * {@code IrisMetalWorldBridge.standardSampler} and
     * {@code IrisMetalTerrainBridge.standardSampler} (all shared through
     * {@link IrisMetalRenderTargets#renderTargetIndex}), plus the vanilla /
     * terrain aliases only the raster bridges own and the PBR defaults
     * {@link IrisMetalPbrDefaults} supplies.
     */
    private static final class Resolver {
        private final int targetCount;
        private final int shadowColorCount;
        private final Map<TextureStage, ?> customTextures;
        private final Set<String> irisCustomTextures;

        Resolver(
                final int targetCount,
                final int shadowColorCount,
                final Map<TextureStage, ?> customTextures,
                final Set<String> irisCustomTextures
        ) {
            this.targetCount = targetCount;
            this.shadowColorCount = shadowColorCount;
            this.customTextures = customTextures;
            this.irisCustomTextures = irisCustomTextures;
        }

        boolean canResolve(
                final String name,
                final boolean rasterProgram,
                final Object customStageTextures
        ) {
            if (customStageTextures instanceof Map<?, ?> map && map.containsKey(name)) {
                return true;
            }
            if (this.irisCustomTextures.contains(name)) {
                // Raw directives (customtexN) are global; upstream's
                // TextureTransformer renames their sampler references before
                // linking and IrisSamplers.addCustomTextures resolves them on
                // every stage.
                return true;
            }
            if (rasterProgram && (IrisMetalWorldBridge.isAlbedoAlias(name)
                    || name.equals("iris_overlay") || name.equals("lightmap")
                    || name.equals("u_BlockTex") || name.equals("u_LightTex"))) {
                return true;
            }
            if (rasterProgram && IrisMetalPbrDefaults.isPbrSampler(name)) {
                // Upstream level samplers bind the neutral PBR defaults when no
                // _n/_s resource pack texture was loaded.
                return true;
            }
            if (name.equals(IrisMetalCenterDepthSampler.SAMPLER_NAME) || name.equals("centerDepthSmooth")) {
                // Only the graph's textureBinding owns the center-depth sampler;
                // the raster bridges leave it unresolved.
                return !rasterProgram;
            }
            if (name.equals("noisetex")
                    || name.equals("depthtex0") || name.equals("depthtex1") || name.equals("depthtex2")) {
                return true;
            }
            if (name.equals("shadowtex0") || name.equals("shadowtex1")
                    || name.equals("shadowtex0HW") || name.equals("shadowtex1HW")
                    || name.equals("watershadow") || name.equals("shadow")) {
                // `shadow`/`watershadow` are the legacy OptiFine aliases upstream
                // IrisSamplers maps onto shadowtex0/1; the graph and both
                // bridges resolve them through legacyShadowDepth.
                return true;
            }
            if (name.startsWith("shadowcolor")) {
                int color = name.equals("shadowcolor") ? 0 : suffix(name, "shadowcolor");
                return color >= 0 && color < shadowColorCount;
            }
            int color = IrisMetalRenderTargets.renderTargetIndex(name);
            if (color >= 0) {
                if (name.startsWith("colortex")) {
                    return color < targetCount;
                }
                // Legacy aliases fall back to the white pixel when out of range.
                return true;
            }
            int image = !rasterProgram ? suffix(name, "colorimg") : -1;
            if (image >= 0) {
                return image < targetCount;
            }
            return false;
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

    /**
     * Sampler names declared by one shader stage's linked GLSL, in declaration
     * order (de-duplicated). Feeds {@link ShaderpackSamplerIndexPlan}, which
     * assigns the compact {@code [[sampler(N)]]} indices from declaration order.
     */
    static List<String> declaredSamplers(final String stageGlsl) {
        Matcher matcher = UNIFORM_SAMPLER.matcher(stageGlsl);
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        while (matcher.find()) {
            if (seen.add(matcher.group(1))) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * Names actually sampled by one stage's linked GLSL (same call-based
     * detection as {@link #usedSamplerNames}).
     */
    static Set<String> sampledNames(final String stageGlsl, final Collection<String> declared) {
        Set<String> sampled = new LinkedHashSet<>();
        for (String name : declared) {
            Pattern use = Pattern.compile(
                    "\\b[A-Za-z_]\\w*\\s*\\(\\s*" + Pattern.quote(name) + "\\b"
            );
            if (countMatches(stageGlsl, use) > 0) {
                sampled.add(name);
            }
        }
        return sampled;
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
        Set<String> used = new LinkedHashSet<>(sampledNames(linked.vertexGlsl(), declared));
        used.addAll(sampledNames(linked.fragmentGlsl(), declared));
        return used;
    }

    private static int countMatches(final String source, final Pattern pattern) {
        int total = 0;
        Matcher occurrence = pattern.matcher(source);
        while (occurrence.find()) {
            total++;
        }
        return total;
    }
}
