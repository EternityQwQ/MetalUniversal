package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the MakeUp-UltraFast 9.5f prewarm crash
 * {@code Iris uniform 'ditherShift' (float) has no Metal or Iris value source}.
 *
 * <p>The pack defines both {@code ditherShift} and {@code taaOffset} only
 * inside its {@code #if AA_TYPE > 0} block in {@code shaders.properties}, but
 * {@code lib/dither.glsl} declares {@code uniform float ditherShift;} under
 * {@code MC_VERSION >= 11300} regardless of that option, and the
 * {@code src/taa_offset.glsl} declaration is compiled whenever one of its
 * include sites survives. OptiFine/Iris semantics for a declared uniform with
 * no supplier are the GLSL default (0), so the writer must supply that default
 * when the pack's custom-uniform graph does not define the name; the graph is
 * resolved first, so an AA-enabled config keeps the pack's own expression.
 *
 * <p>The test mirrors {@code MetalWorldRenderingPipeline.prepareWorldUniforms}
 * — the same sodium terrain keys and the same
 * {@link IrisMetalWorldBridge#WORLD_OVERRIDE_KEYS} — and feeds every linked
 * member through the production value-source resolution under three
 * configurations (default, {@code no_effects}/AA_TYPE=0,
 * {@code medium}/AA_TYPE=2). The pack's real {@link CustomUniforms} graph is
 * built exactly like production so option gating is observed where it
 * actually happens; a member counts as rejected only when neither a writer
 * case nor the graph supplies it.
 */
final class MakeUpUniformCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "MakeUp-UltraFast-9.5f.zip");
    /** The six keys {@code prepareWorldUniforms} prewarms first. */
    private static final ShaderKey[] SODIUM_KEYS = {
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
    private static final Set<String> CORE_DRAW_UNIFORMS = Set.of(
            "iris_ModelViewMatInverse", "iris_ProjMatInverse", "iris_NormalMat"
    );
    /** {@code shaders.properties} profile in parentheses; AA_TYPE selects the gated block. */
    private static final Map<String, Map<String, String>> CONFIGS = new LinkedHashMap<>();

    static {
        CONFIGS.put("default", Map.of());
        CONFIGS.put("no_effects", Map.of("AA_TYPE", "0"));
        CONFIGS.put("medium", Map.of("AA_TYPE", "2"));
    }

    /** Synthetic members for the two option-gated names, used for value assertions. */
    private static final IrisMetalGlslLinker.UniformMember DITHER_SHIFT =
            new IrisMetalGlslLinker.UniformMember("float", "ditherShift", 0, 0, 4);
    private static final IrisMetalGlslLinker.UniformMember TAA_OFFSET =
            new IrisMetalGlslLinker.UniformMember("vec2", "taaOffset", 0, 0, 8);

    @Test
    void makeUpWorldUniformBlocksHaveNoUnsupportedMembersAcrossConfigs() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        Map<String, Audit> audits = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> config : CONFIGS.entrySet()) {
            audits.put(config.getKey(), audit(config.getValue()));
        }

        for (Map.Entry<String, Audit> entry : audits.entrySet()) {
            System.out.println("[makeup-uniform-coverage] config=" + entry.getKey()
                    + " linkedPrograms=" + entry.getValue().linkedPrograms
                    + " declared=" + entry.getValue().declared.size()
                    + " graphSupplied=" + entry.getValue().graphSupplied
                    + " graphWriteHandled=" + entry.getValue().graphWriteHandled
                    + " rejected=" + entry.getValue().rejected);
        }

        for (Map.Entry<String, Audit> entry : audits.entrySet()) {
            Audit audit = entry.getValue();
            assertTrue(audit.linkedPrograms > 0,
                    "MakeUp '" + entry.getKey() + "': no world program linked at all");
            assertTrue(audit.rejected.isEmpty(),
                    "MakeUp '" + entry.getKey() + "' world uniforms have neither a writer case"
                            + " nor an Iris graph source: " + audit.rejected
                            + " (graph-supplied declared names: " + audit.graphSupplied + ")");
        }

        // AA off: `#if AA_TYPE > 0` removes both pack definitions, while
        // dither.glsl still declares `uniform float ditherShift;`. The writer's
        // explicit default must answer the declared uniform with 0.
        Audit noEffects = audits.get("no_effects");
        assertTrue(noEffects.declared.contains("ditherShift"),
                "no_effects layouts must declare ditherShift (dither.glsl includes are"
                        + " unconditional): " + noEffects.declared);
        assertFalse(noEffects.graph.hasVariable("ditherShift"),
                "AA_TYPE=0 must remove MakeUp's ditherShift custom-uniform definition");
        assertFalse(noEffects.graph.hasVariable("taaOffset"),
                "AA_TYPE=0 must remove MakeUp's taaOffset custom-uniform definition");
        assertExplicitDefault(noEffects, DITHER_SHIFT, "ditherShift");
        // taa_offset.glsl includes are all AA-gated, so AA_TYPE=0 layouts do
        // not even declare taaOffset (declared=48 vs 49 with AA). Pin the
        // explicit default with a synthetic member anyway: it is the same
        // writer path a future config that does declare it without a pack
        // definition must take.
        assertExplicitDefault(noEffects, TAA_OFFSET, "taaOffset");

        // AA on: the pack graph owns both names and writeOfficialUniform must
        // resolve them (graph wins over the switch fallback).
        Audit medium = audits.get("medium");
        assertTrue(medium.graph.hasVariable("ditherShift"),
                "AA_TYPE=2 must define MakeUp's ditherShift custom uniform");
        assertTrue(medium.graph.hasVariable("taaOffset"),
                "AA_TYPE=2 must define MakeUp's taaOffset custom uniform");
        assertGraphSupplied(medium, DITHER_SHIFT);
        assertGraphSupplied(medium, TAA_OFFSET);
        assertTrue(medium.declared.contains("ditherShift"),
                "medium layouts must declare ditherShift: " + medium.declared);
        assertTrue(medium.declared.contains("taaOffset"),
                "medium layouts must declare taaOffset: " + medium.declared);
    }

    private static void assertExplicitDefault(
            final Audit audit,
            final IrisMetalGlslLinker.UniformMember member,
            final String name
    ) {
        ByteBuffer bytes = audit.relaxed.writeUniformForGate(member);
        assertFalse(audit.relaxed.unsupportedNames().contains(name),
                "AA_TYPE=0 '" + name + "' must be answered by the writer's explicit default");
        for (int offset = 0; offset < member.byteSize(); offset += Float.BYTES) {
            assertEquals(0.0f, bytes.getFloat(offset), 0.0f,
                    "AA_TYPE=0 '" + name + "' default must be zero");
        }
    }

    private static void assertGraphSupplied(
            final Audit audit,
            final IrisMetalGlslLinker.UniformMember member
    ) {
        // writeOfficialUniform is the production resolver: it consults the
        // pack graph before the writer switch and must claim the member.
        assertTrue(
                audit.strict.writeOfficialUniform(ByteBuffer.allocate(member.byteSize()), member),
                "AA_TYPE=2 '" + member.name() + "' must be resolved by the pack custom-uniform graph"
        );
        assertTrue(audit.graphWriteHandled.contains(member.name()),
                "AA_TYPE=2 '" + member.name() + "' must be graph-handled in the linked layouts");
    }

    private static Audit audit(final Map<String, String> changedConfigs) throws Exception {
        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    changedConfigs,
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            FrameUpdateNotifier notifier = new FrameUpdateNotifier();
            CustomUniforms graph = pack.customUniforms.build(
                    holder -> registerTestInputUniforms(holder, pack, programs, notifier)
            );
            Audit audit = new Audit(
                    graph,
                    new IrisMetalUniformValues(0.0f, () -> 0),
                    new IrisMetalUniformValues(
                            programs.getPackDirectives().getSunPathRotation(),
                            graph,
                            notifier,
                            () -> 0,
                            programs.getPackDirectives().getShadowDirectives()
                    )
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
                // Step 4: the graph programs (composite + final) go through the
                // same strict value-source dispatch at draw time; gate their
                // uniform layouts too so an unhandled member cannot reach the
                // per-frame upload instead of prewarm.
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
     * Registers the official Iris inputs the pack's custom uniforms can
     * reference, mirroring {@code CommonUniforms.addNonDynamicUniforms} minus
     * its {@code IrisExclusiveUniforms} block: that one dereferences
     * {@code Minecraft.getInstance().level} while registering and cannot run
     * headless. MakeUp's custom uniforms reference only viewport, time and
     * matrix inputs, all registered here.
     */
    private static void registerTestInputUniforms(
            final UniformHolder holder,
            final ShaderPack pack,
            final ProgramSet programs,
            final FrameUpdateNotifier notifier
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
        // CommonUniforms.generalCommonUniforms cannot run headless (its
        // `client.gui.hud::isHidden` method reference dereferences
        // Minecraft.getInstance()), so register the one name MakeUp's world
        // layouts take from it. Production builds the full graph in
        // MetalWorldRenderingPipeline, which is where blindness comes from.
        holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "blindness", () -> 0.0f);
    }

    /** Mutable audit accumulator. */
    private static final class Audit {
        private final CustomUniforms graph;
        private final IrisMetalUniformValues relaxed;
        private final IrisMetalUniformValues strict;
        private final Set<IrisMetalGlslLinker.LinkedRasterProgram> checked =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<String> declared = new LinkedHashSet<>();
        private final Set<String> graphSupplied = new LinkedHashSet<>();
        private final Set<String> graphWriteHandled = new LinkedHashSet<>();
        private final Set<String> rejected = new LinkedHashSet<>();
        private int linkedPrograms;

        private Audit(
                final CustomUniforms graph,
                final IrisMetalUniformValues relaxed,
                final IrisMetalUniformValues strict
        ) {
            this.graph = graph;
            this.relaxed = relaxed;
            this.strict = strict;
        }

        private void inspect(
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
                    this.strict.writeUniformForGate(member);
                    this.graphWriteHandled.add(member.name());
                } else if (this.relaxed.unsupportedNames().contains(member.name())) {
                    this.rejected.add(member.name() + " (" + member.type() + ")");
                }
            }
        }
    }
}
