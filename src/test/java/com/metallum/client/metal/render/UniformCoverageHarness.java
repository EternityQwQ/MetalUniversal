package com.metallum.client.metal.render;

import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.BiomeUniforms;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.IdMapUniforms;
import net.irisshaders.iris.uniforms.IrisTimeUniforms;
import net.irisshaders.iris.uniforms.MatrixUniforms;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.ViewportUniforms;
import net.irisshaders.iris.uniforms.WorldTimeUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shared native-free harness for the per-pack uniform coverage gates
 * ({@code SildursUniformCoverageTest}, {@code ComplementaryUnboundUniformCoverageTest};
 * MakeUp/CR keep their own copies until the follow-up migration).
 *
 * <p>{@link #auditAll} links every dimension program set under every supplied
 * option configuration and mirrors
 * {@code MetalWorldRenderingPipeline.prepareWorldUniforms}: the six sodium
 * terrain keys plus every {@link IrisMetalWorldBridge#WORLD_OVERRIDE_KEYS}
 * entry, followed by the graph programs (deferred/composite/final). Each
 * linked layout member is fed through the production value-source resolution
 * with the pack's real {@link CustomUniforms} graph, so a member counts as
 * rejected only when neither a writer case nor the graph supplies it.
 *
 * <p>{@link #registerTestInputUniforms} rebuilds the official Iris inputs the
 * production graph would carry minus the headless-hostile
 * {@code IrisExclusiveUniforms}/generalCommonUniforms registration blocks;
 * pack-specific names those blocks own are added by the caller through the
 * {@code additionalInputs} consumer.
 */
final class UniformCoverageHarness {
    /** The six keys {@code prepareWorldUniforms} prewarms first. */
    static final ShaderKey[] SODIUM_KEYS = {
            ShaderKey.SODIUM_TERRAIN_SOLID,
            ShaderKey.SODIUM_TERRAIN_CUTOUT,
            ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
            ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID,
            ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT,
            ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT
    };
    /**
     * Core-draw transforms that {@code upload()} skips for Mojang-core tokens
     * and {@code materializeDrawUniforms()} fills from the bound
     * DynamicTransforms/Projection blocks; the switch is never their source.
     * Mirrors the writer's private core-draw constants.
     */
    static final Set<String> CORE_DRAW_UNIFORMS = Set.of(
            "iris_ModelViewMatInverse", "iris_ProjMatInverse", "iris_NormalMat"
    );

    private UniformCoverageHarness() {
    }

    /**
     * Audits every dimension x configuration combination, keyed
     * {@code "<dimension>/<config>"} in iteration order.
     */
    static Map<String, Audit> auditAll(
            final Path pack,
            final Map<String, NamespacedId> dimensions,
            final Map<String, Map<String, String>> configs,
            final Consumer<UniformHolder> additionalInputs
    ) throws Exception {
        Map<String, Audit> audits = new LinkedHashMap<>();
        for (Map.Entry<String, NamespacedId> dimension : dimensions.entrySet()) {
            for (Map.Entry<String, Map<String, String>> config : configs.entrySet()) {
                audits.put(
                        dimension.getKey() + "/" + config.getKey(),
                        audit(pack, dimension.getValue(), config.getValue(), additionalInputs)
                );
            }
        }
        return audits;
    }

    /** One pack/dimension/config audit run. */
    static Audit audit(
            final Path pack,
            final NamespacedId dimension,
            final Map<String, String> changedConfigs,
            final Consumer<UniformHolder> additionalInputs
    ) throws Exception {
        try (FileSystem fileSystem = FileSystems.newFileSystem(pack, Map.of())) {
            ShaderPack shaderPack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    changedConfigs,
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = shaderPack.getProgramSet(dimension);
            FrameUpdateNotifier notifier = new FrameUpdateNotifier();
            CustomUniforms graph = shaderPack.customUniforms.build(
                    holder -> registerTestInputUniforms(holder, shaderPack, programs, notifier, additionalInputs)
            );
            Audit audit = new Audit(
                    graph,
                    new IrisMetalUniformValues(0.0f, () -> 0)
            );

            try (IrisMetalWorldPrograms worldPrograms = new IrisMetalWorldPrograms(1, programs)) {
                for (ShaderKey key : SODIUM_KEYS) {
                    audit.inspect(worldPrograms.sodium(key.getProgram(), key.getAlphaTest()), false);
                }
                for (ShaderKey key : IrisMetalWorldBridge.WORLD_OVERRIDE_KEYS) {
                    IrisMetalWorldBridge.ProgramRequest request =
                            IrisMetalWorldBridge.shaderKeyToProgramRequest(key);
                    audit.inspect(
                            worldPrograms.vanilla(
                                    request.program(),
                                    request.alphaTest(),
                                    request.lines(),
                                    request.clouds(),
                                    request.inputs()
                            ),
                            IrisMetalUniformValues.usesMojangCoreTransforms(key)
                    );
                }
                for (ProgramSource source : programs.getComposite(ProgramArrayId.Deferred)) {
                    if (source != null && source.isValid()) {
                        audit.inspect(Optional.of(worldPrograms.composite(
                                source, TextureStage.DEFERRED
                        )), false);
                    }
                }
                for (ProgramSource source : programs.getComposite(ProgramArrayId.Composite)) {
                    if (source != null && source.isValid()) {
                        audit.inspect(Optional.of(worldPrograms.composite(
                                source, TextureStage.COMPOSITE_AND_FINAL
                        )), false);
                    }
                }
                IrisMetalGlslLinker.LinkedRasterProgram finalProgram = worldPrograms.finalProgram();
                if (finalProgram != null) {
                    audit.inspect(Optional.of(finalProgram), false);
                }
            }
            return audit;
        }
    }

    /**
     * Audits the graph prelude (Begin/Prepare programs) for one
     * pack/dimension/config. The main {@link #audit} covers the world and
     * deferred/composite/final chains; packs that bind custom textures or
     * uniforms in their prepare passes (Nostalgia's
     * {@code texture.prepare.colortex7}) need this second sweep.
     */
    static Audit auditPrelude(
            final Path pack,
            final NamespacedId dimension,
            final Map<String, String> changedConfigs,
            final Consumer<UniformHolder> additionalInputs
    ) throws Exception {
        try (FileSystem fileSystem = FileSystems.newFileSystem(pack, Map.of())) {
            ShaderPack shaderPack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    changedConfigs,
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = shaderPack.getProgramSet(dimension);
            FrameUpdateNotifier notifier = new FrameUpdateNotifier();
            CustomUniforms graph = shaderPack.customUniforms.build(
                    holder -> registerTestInputUniforms(holder, shaderPack, programs, notifier, additionalInputs)
            );
            Audit audit = new Audit(
                    graph,
                    new IrisMetalUniformValues(0.0f, () -> 0)
            );

            try (IrisMetalWorldPrograms worldPrograms = new IrisMetalWorldPrograms(1, programs)) {
                for (ProgramArrayId id : new ProgramArrayId[]{ProgramArrayId.Begin, ProgramArrayId.Prepare}) {
                    TextureStage stage = id == ProgramArrayId.Begin ? TextureStage.BEGIN : TextureStage.PREPARE;
                    for (ProgramSource source : programs.getComposite(id)) {
                        if (source != null && source.isValid()) {
                            audit.inspect(Optional.of(worldPrograms.composite(source, stage)), false);
                        }
                    }
                }
            }
            return audit;
        }
    }

    /**
     * Registers the official Iris inputs the pack's custom uniforms can
     * reference, mirroring {@code CommonUniforms.addNonDynamicUniforms} minus
     * its {@code IrisExclusiveUniforms} block: that one dereferences
     * {@code Minecraft.getInstance().level} while registering and cannot run
     * headless. Pack-specific names those blocks own are registered by
     * {@code additionalInputs} (production builds the full graph in
     * {@code MetalWorldRenderingPipeline}).
     */
    static void registerTestInputUniforms(
            final UniformHolder holder,
            final ShaderPack pack,
            final ProgramSet programs,
            final FrameUpdateNotifier notifier,
            final Consumer<UniformHolder> additionalInputs
    ) {
        PackDirectives directives = programs.getPackDirectives();
        CameraUniforms.addCameraUniforms(holder, notifier);
        ViewportUniforms.addViewportUniforms(holder);
        WorldTimeUniforms.addWorldTimeUniforms(holder);
        SystemTimeUniforms.addSystemTimeUniforms(holder);
        BiomeUniforms.addBiomeUniforms(holder);
        new CelestialUniforms(directives.getSunPathRotation()).addCelestialUniforms(holder);
        IrisTimeUniforms.addTimeUniforms(holder);
        MatrixUniforms.addMatrixUniforms(holder, directives);
        IdMapUniforms.addIdMapUniforms(notifier, holder, pack.getIdMap(), directives.isOldHandLight());
        additionalInputs.accept(holder);
    }

    /** Mutable audit accumulator shared by the pack gates. */
    static final class Audit {
        final CustomUniforms graph;
        final IrisMetalUniformValues relaxed;
        final Set<IrisMetalGlslLinker.LinkedRasterProgram> checked =
                Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> declared = new LinkedHashSet<>();
        final Set<String> graphSupplied = new LinkedHashSet<>();
        final Set<String> unsupported = new LinkedHashSet<>();
        final Set<String> rejected = new LinkedHashSet<>();
        int linkedPrograms;

        Audit(final CustomUniforms graph, final IrisMetalUniformValues relaxed) {
            this.graph = graph;
            this.relaxed = relaxed;
        }

        void inspect(
                final Optional<IrisMetalGlslLinker.LinkedRasterProgram> linked,
                final boolean coreTransforms
        ) {
            if (linked.isEmpty() || !checked.add(linked.get())) {
                return;
            }
            this.linkedPrograms++;
            for (IrisMetalGlslLinker.UniformMember member : linked.get().uniformLayout()) {
                if (coreTransforms && CORE_DRAW_UNIFORMS.contains(member.name())) {
                    continue;
                }
                this.declared.add(member.name());
                this.relaxed.writeUniformForGate(member);
                boolean graphHas = this.graph.hasVariable(member.name());
                if (graphHas) {
                    this.graphSupplied.add(member.name());
                } else if (this.relaxed.unsupportedNames().contains(member.name())) {
                    this.unsupported.add(member.name());
                    this.rejected.add(member.name() + " (" + member.type() + ")");
                }
            }
        }
    }
}
