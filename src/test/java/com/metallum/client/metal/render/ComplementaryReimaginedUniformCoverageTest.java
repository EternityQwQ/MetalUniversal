package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the CR 5.9.3 {@code prewarm} crash: the world programs
 * declare uniforms whose only Iris value source is the dynamic holder, and the
 * strict {@link IrisMetalUniformValues} threw {@code IllegalStateException}
 * for the first one ({@code entityId}) before any world frame could render.
 *
 * <p>The test mirrors {@code MetalWorldRenderingPipeline.prepareWorldUniforms}
 * — the same sodium terrain keys and the same {@code WORLD_OVERRIDE_KEYS} —
 * and feeds every member of every linked uniform block through the production
 * value-source resolution. A member must be handled by a writer case, by one
 * of the pack's custom-uniform expressions, or by one of the upstream
 * non-dynamic graph registrations pinned below; anything else fails here
 * instead of at runtime. The crash-list names are asserted to be present in
 * the terrain layout and to be writer-handled, so removing one of those cases
 * cannot regress silently.
 */
final class ComplementaryReimaginedUniformCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "ComplementaryReimagined_r5.9.3.zip");
    private static final Pattern CUSTOM_UNIFORM = Pattern.compile(
            "^\\s*(?:uniform|variable)\\.\\w+\\.(\\w+)\\s*=", Pattern.MULTILINE);
    /** The six keys {@code prepareWorldUniforms} prewarms first. */
    private static final ShaderKey[] SODIUM_KEYS = {
            ShaderKey.SODIUM_TERRAIN_SOLID,
            ShaderKey.SODIUM_TERRAIN_CUTOUT,
            ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
            ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID,
            ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT,
            ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT
    };
    /** The names from the crash report that must keep a writer value source. */
    private static final Set<String> CRASH_SET = Set.of(
            "entityId", "entityColor", "blockEntityId", "currentRenderedItemId",
            "heldItemId", "heldItemId2", "heldBlockLightValue", "heldBlockLightValue2",
            "isElytraFlying", "heavyFog", "playerMood", "darknessLightFactor",
            "lightningBoltPosition", "maxBlindnessDarkness", "atlasSize"
    );
    /**
     * Names CR's world programs declare that pinned Iris 20e226b supplies in
     * production through {@code CommonUniforms.addNonDynamicUniforms} rather
     * than the writer switch: CameraUniforms (cameraPositionInt/Fract and the
     * previous pair), CelestialUniforms (endFlashPosition), and
     * CommonUniforms.generalCommonUniforms / IrisExclusiveUniforms
     * (blindness, is_invisible, playerLookVector). The production writer's
     * {@code writeOfficialUniform} resolves these from the custom-uniform
     * graph before the switch runs. Add a name here only with the upstream
     * registration site.
     */
    private static final Set<String> GRAPH_PROVIDED = Set.of(
            "blindness",
            "cameraPositionFract",
            "cameraPositionInt",
            "endFlashPosition",
            "is_invisible",
            "playerLookVector",
            "previousCameraPositionFract",
            "previousCameraPositionInt"
    );
    /**
     * Core-draw transforms that {@code upload()} skips for Mojang-core tokens
     * and {@code materializeDrawUniforms()} fills from the bound
     * DynamicTransforms/Projection blocks; the switch is never their source.
     * Mirrors the writer's private core-draw constants.
     */
    private static final Set<String> CORE_DRAW_UNIFORMS = Set.of(
            "iris_ModelViewMatInverse", "iris_ProjMatInverse", "iris_NormalMat"
    );

    @Test
    void complementaryReimaginedWorldUniformBlocksHaveNoUnsupportedMembers() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            Set<String> customUniforms = customUniformNames(fileSystem);

            // Relaxed writer: no custom graph, no game state. Unsupported
            // members are recorded instead of thrown, which is how the gate
            // observes them.
            IrisMetalUniformValues values = new IrisMetalUniformValues(0.0f, () -> 0);
            Set<String> declared = new LinkedHashSet<>();
            Set<String> unsupported = new LinkedHashSet<>();
            Set<IrisMetalGlslLinker.LinkedRasterProgram> checked =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            int linkedPrograms = 0;

            try (IrisMetalWorldPrograms worldPrograms = new IrisMetalWorldPrograms(7, programs)) {
                for (ShaderKey key : SODIUM_KEYS) {
                    linkedPrograms += inspect(
                            worldPrograms.sodium(key.getProgram(), key.getAlphaTest()),
                            values, checked, declared, unsupported, false
                    );
                }
                for (ShaderKey key : IrisMetalWorldBridge.WORLD_OVERRIDE_KEYS) {
                    IrisMetalWorldBridge.ProgramRequest request =
                            IrisMetalWorldBridge.shaderKeyToProgramRequest(key);
                    linkedPrograms += inspect(
                            worldPrograms.vanilla(
                                    request.program(),
                                    request.alphaTest(),
                                    request.lines(),
                                    request.clouds(),
                                    request.inputs()
                            ),
                            values, checked, declared, unsupported,
                            IrisMetalUniformValues.usesMojangCoreTransforms(key)
                    );
                }
            }

            assertTrue(linkedPrograms > 0, "no world program linked at all");
            Set<String> unexplained = new LinkedHashSet<>(unsupported);
            unexplained.removeAll(customUniforms);
            unexplained.removeAll(GRAPH_PROVIDED);
            assertTrue(
                    unexplained.isEmpty(),
                    "CR world uniforms have neither a writer case nor an Iris graph source: "
                            + unexplained + " (pinned graph sources: " + GRAPH_PROVIDED + ")"
            );

            Set<String> missingCrashNames = new LinkedHashSet<>(CRASH_SET);
            missingCrashNames.removeAll(declared);
            assertTrue(
                    missingCrashNames.isEmpty(),
                    "CR world layouts no longer declare crash-list uniforms: " + missingCrashNames
            );

            Set<String> unhandledCrashNames = new LinkedHashSet<>(CRASH_SET);
            unhandledCrashNames.retainAll(unsupported);
            assertTrue(
                    unhandledCrashNames.isEmpty(),
                    "crash-list uniforms are not writer-handled: " + unhandledCrashNames
            );

            System.out.println("[uniform-coverage] linkedPrograms=" + linkedPrograms
                    + " layoutNames=" + declared.size()
                    + " customGraph=" + customUniforms.size()
                    + " upstreamGraph=" + GRAPH_PROVIDED.size()
                    + " relaxedUnsupported=" + unsupported
                    + " crashSetHandled=" + CRASH_SET);
        }
    }

    private static int inspect(
            final Optional<IrisMetalGlslLinker.LinkedRasterProgram> linked,
            final IrisMetalUniformValues values,
            final Set<IrisMetalGlslLinker.LinkedRasterProgram> checked,
            final Set<String> declared,
            final Set<String> unsupported,
            final boolean coreTransforms
    ) {
        if (linked.isEmpty() || !checked.add(linked.get())) {
            return 0;
        }
        for (IrisMetalGlslLinker.UniformMember member : linked.get().uniformLayout()) {
            if (coreTransforms && CORE_DRAW_UNIFORMS.contains(member.name())) {
                continue;
            }
            declared.add(member.name());
            values.writeUniformForGate(member);
            if (values.unsupportedNames().contains(member.name())) {
                unsupported.add(member.name());
            }
        }
        return 1;
    }

    private static Set<String> customUniformNames(final FileSystem fileSystem) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = CUSTOM_UNIFORM.matcher(
                Files.readString(fileSystem.getPath("/shaders/shaders.properties"))
        );
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
