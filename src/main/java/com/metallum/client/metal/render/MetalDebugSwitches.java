package com.metallum.client.metal.render;

import com.metallum.Metallum;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime diagnostic and rollback switches. Enable-style flags ({@link
 * #enabledFlag(String)}) are off when unset and accept a bare {@code -Dname}
 * argument or {@code -Dname=true}; the {@code =off}/{@code =false} kill
 * switches keep their own parsing. A few switches select the optimized default
 * and document the legacy value that restores the old behavior
 * ({@code shadowSeed=copy}, {@code depthCapture=eager},
 * {@code attachmentViews=subresource}, {@code blitBatch=off}).
 */
public final class MetalDebugSwitches {
    /** Identifies this probe build in device logs. */
    public static final String BUILD_TAG = "probe5";
    public static final boolean SKIP_DEFERRED = enabledFlag("metallum.iris.debug.skipDeferred");
    public static final boolean SKIP_POST = enabledFlag("metallum.iris.debug.skipPost");
    public static final String VIEW = System.getProperty("metallum.iris.debug.view", "").trim();
    /**
     * Debug-view brightness multiplier ({@code -Dmetallum.iris.debug.viewGain=<float>}),
     * baked as a literal into the sampled debug-view blit so an almost-black
     * target (MakeUp's {@code gaux3} exposure history) becomes readable. Only
     * finite positive values are accepted; default 1 (byte-identical shader).
     */
    public static final float VIEW_GAIN = parseViewGain(
            System.getProperty("metallum.iris.debug.viewGain", "1").trim()
    );
    public static final String SKIP_PASS = System.getProperty("metallum.iris.debug.skipPass", "").trim();
    public static final boolean NO_SHADOW_MATRICES = enabledFlag("metallum.iris.debug.noShadowMatrices");
    public static final boolean ZERO_VL = enabledFlag("metallum.iris.debug.zeroVl");
    public static final boolean ZERO_BLOOM = enabledFlag("metallum.iris.debug.zeroBloom");
    public static final boolean NO_VANILLA_SKY = enabledFlag("metallum.iris.debug.noVanillaSky");
    public static final boolean NO_VANILLA_CLOUDS = enabledFlag("metallum.iris.debug.noVanillaClouds");
    public static final boolean NO_CLOUDS_HARD = enabledFlag("metallum.iris.debug.noCloudsHard");
    public static final boolean MAGENTA_CLEAR = enabledFlag("metallum.iris.debug.magentaClear");
    /**
     * Opt-in performance measurement ({@code -Dmetallum.perf}): CPU stage
     * timings, draw/pass/encoder/copy/uniform counters and the Metal command
     * buffer's GPU time, summarised into {@code metallum-probe.txt} once every
     * 120 frames. Off by default; the hot path is gated by
     * {@link MetalPerformanceProbe#ENABLED} and allocates nothing when off.
     */
    public static final boolean PERF = enabledFlag("metallum.perf");
    /**
     * Opt-in whole-frame render-scale factor ({@code
     * -Dmetallum.iris.renderScale=<0.5..1.0>}, default 1.0). The Iris Metal
     * pipeline resizes the vanilla main render target to {@code window *
     * RENDER_SCALE}; every derived target (colortex, depth, {@code size.buffer}
     * base) follows that extent, and the native present path already upscales a
     * smaller source to the drawable. The GUI is upscaled with it, matching the
     * launcher's resolution-scale behavior. Values outside the range clamp into
     * it; unset, non-numeric, NaN and infinite values stay 1.0.
     */
    public static final float RENDER_SCALE = parseRenderScale(
            System.getProperty("metallum.iris.renderScale", "1.0").trim()
    );
    /**
     * Opt-in shadow-map resolution override ({@code
     * -Dmetallum.iris.shadowResolution=<256..4096>}, even, default 0 = use the
     * pack's {@code shadow.resolution} directive). Odd requests round down to
     * the previous even extent (no half-pixel viewports); 0 and out-of-range
     * values keep the pack value.
     */
    public static final int SHADOW_RESOLUTION = parseShadowResolution(
            System.getProperty("metallum.iris.shadowResolution", "0").trim()
    );
    /**
     * Latched after the first main-target resize failure. Every render-scale
     * caller (the pipeline's fallback resize and the {@code GameRenderer}
     * redirect) then falls back to 1.0 instead of retrying a failing GPU
     * allocation every frame.
     */
    private static volatile boolean renderScaleFailed;
    /**
     * Probe-only: logs every per-drawbuffer {@code blend.*} override that
     * {@code IrisMetalCompiledPrograms.colorTargets} folds into the Metal PSO
     * blend state (once per program+slot+target), so the colortex&rarr;slot
     * mapping can be verified on device.
     * {@code -Dmetallum.iris.debug.blendOverrides}.
     */
    public static final boolean BLEND_OVERRIDES = enabledFlag("metallum.iris.debug.blendOverrides");
    /**
     * Real terrain shadow-caster pass; on by default.
     * {@code -Dmetallum.iris.shadowPass=off} restores the old placeholder
     * behavior: casters are not rendered and the shadow maps are cleared to
     * "nothing occludes" before shadowcomp runs.
     */
    public static final boolean SHADOW_PASS = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.shadowPass", "on").trim()
    );
    /**
     * Debug kill switch: skips the shadow caster pass <em>and</em> the
     * shadowcomp stage, leaving the cleared shadow maps for the main pass to
     * sample (i.e. no shadows at all). {@code -Dmetallum.iris.debug.noShadows}.
     */
    public static final boolean NO_SHADOWS = enabledFlag("metallum.iris.debug.noShadows");
    /**
     * Shadow culling override: {@code advanced}, {@code box} or {@code none}.
     * Empty (default) follows the pack's {@code shadow.culling} directive.
     * {@code -Dmetallum.iris.debug.shadowCulling=advanced|box|none}.
     */
    public static final String SHADOW_CULLING = System.getProperty("metallum.iris.debug.shadowCulling", "").trim();
    /**
     * M6.1.1 GL-NDC depth emulation for shadow caster vertex shaders. BSL's
     * shadow VSH scales {@code gl_Position.z} and relies on GL's NDC
     * [-1,1]&rarr;depth [0,1] viewport transform, which Metal's clip space does
     * not perform; the fix wraps the shadow vertex main with that transform.
     * On by default; {@code -Dmetallum.iris.debug.shadowDepthFix=off} disables
     * the wrapper (and switches the caster matrices back to the zero-to-one
     * ortho), reproducing the pre-fix "shadow=0 everywhere" behavior.
     */
    public static final boolean SHADOW_DEPTH_FIX = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.debug.shadowDepthFix", "on").trim()
    );
    /**
     * Mellow waving-foliage depth fix. The main pass rasterizes in the engine's
     * zero-to-one projection space (Sodium's {@code u_ProjectionMatrix}), but
     * {@code gbufferProjection} is handed to packs in Iris's OpenGL [-1,1]
     * space. A pack vertex shader that rewrites {@code gl_Position} from
     * {@code gbufferProjection} (Mellow's WAVE_LEAVES branch) therefore stores
     * depth {@code 2d-1} instead of {@code d}, so waving leaves occlude blocks
     * out to about twice their own distance. The fix renames only the
     * vertex-stage identifier to a second block member fed by the engine-space
     * projection (the same engine-forward / pack-inverse split the port already
     * applies to {@code iris_ProjMat}/{@code iris_ProjMatInverse}), leaving
     * fragment-stage pack math in OpenGL space. On by default;
     * {@code -Dmetallum.iris.debug.vertexEngineProjection=off} restores the
     * previous shared-space behavior for A/B.
     */
    public static final boolean VERTEX_ENGINE_PROJECTION = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.debug.vertexEngineProjection", "on").trim()
    );
    /**
     * GLSL precision preamble: injects {@code precision highp float/int;}
     * before every raster shader compiled through glslang. Vulkan-mode glslang
     * treats an unqualified float parameter as having no precision and rejects
     * user overloads of built-ins such as {@code fma} ("overloaded functions
     * must have the same parameter precision qualifiers"), which Mellow's
     * FABULOUS composite10 triggers. On by default; {@code
     * -Dmetallum.iris.glslangPrecision=off} restores the old preamble.
     */
    public static final boolean GLSLANG_PRECISION_PREAMBLE = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.glslangPrecision", "on").trim()
    );
    /**
     * Renames a user-defined function that collides with a GLSL builtin before
     * glslang sees it. Packs written for {@code #version 120} legitimately
     * define builtins that only exist in later versions (Mellow's SMAA block
     * defines its own {@code fma} overloads); once the source is forced to a
     * modern Vulkan-compatible version, glslang rejects the redeclaration with
     * "overloaded functions must have the same parameter precision qualifiers",
     * which no source precision can satisfy. Renaming the pack's definition and
     * all of its references keeps the pack's intended math. On by default;
     * {@code -Dmetallum.iris.glslangBuiltinRename=off} restores the old behavior.
     */
    public static final boolean GLSLANG_BUILTIN_RENAME = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.glslangBuiltinRename", "on").trim()
    );
    /** Non-terrain world-program override; on by default, {@code -Dmetallum.iris.worldPass=off} disables it. */
    public static final boolean WORLD_PASS = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.worldPass", "on").trim()
    );
    /**
     * World-override depth attachment: {@code true} (default) makes taken-over
     * draws test/write the vanilla scene depth, so translucents (water) see
     * entity/hand depth; {@code false} restores the old Iris depthtex0
     * attachment. A/B diagnostic: {@code -Dmetallum.iris.worldPass.depthVanilla=false}.
     */
    public static final boolean WORLD_PASS_DEPTH_VANILLA = !"false".equalsIgnoreCase(
            System.getProperty("metallum.iris.worldPass.depthVanilla", "true").trim()
    );
    /**
     * Materializes the per-draw core transforms ({@code iris_NormalMat},
     * {@code iris_ModelViewMatInverse}, {@code iris_ProjMatInverse},
     * {@code renderStage}) from the engine's {@code DynamicTransforms} /
     * {@code Projection} for every taken-over world draw. {@code false}
     * restores the old behavior (those members stay zeroed):
     * {@code -Dmetallum.iris.worldPass.perDrawNormals=false}.
     */
    public static final boolean WORLD_PASS_PER_DRAW_NORMALS = !"false".equalsIgnoreCase(
            System.getProperty("metallum.iris.worldPass.perDrawNormals", "true").trim()
    );
    /**
     * Per-target {@code size.buffer.*} resolution support; on by default.
     * {@code -Dmetallum.iris.sizeBuffer=off} forces every colortex target back
     * to the base (main render target) extent, reproducing the pre-support
     * uniform-size behavior without touching the pack parser.
     */
    public static final boolean SIZE_BUFFER = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.sizeBuffer", "on").trim()
    );
    /**
     * Frame-boundary history canonicalization: moves each target's previous
     * frame read side into its main texture before the per-frame flip reset,
     * so cross-frame readers (MakeUp's {@code gaux3} auto-exposure history,
     * TAA history) always see the last write even when the final pass was
     * skipped. {@code -Dmetallum.iris.frameHistory=off} restores the old
     * behavior (canonicalization only in {@code executeFinal}).
     */
    public static final boolean FRAME_HISTORY = !"off".equalsIgnoreCase(
            System.getProperty("metallum.iris.frameHistory", "on").trim()
    );
    /**
     * Frame-start shadow depth seed. {@code clear} (default) clears shadowtex0
     * and shadowtex1 independently to the same far value;
     * {@code -Dmetallum.iris.shadowSeed=copy} restores the previous
     * shadowtex0&rarr;shadowtex1 blit (16MB of shadow-map traffic + one more
     * blit encoder per frame). Both modes leave shadowtex1 holding the value a
     * just-cleared shadowtex0 would have been copied into it.
     */
    public static final boolean SHADOW_SEED_CLEAR = shadowSeedClear(
            System.getProperty("metallum.iris.shadowSeed", "clear")
    );
    /** Effective {@code shadowSeed} mode, for the switch log / probe report. */
    public static final String SHADOW_SEED_MODE = SHADOW_SEED_CLEAR ? "clear" : "copy";
    /**
     * First non-sky world-takeover pass depthtex0 seed. {@code lazy} (default)
     * copies the vanilla main depth into Iris depthtex0 only when the program
     * about to be encoded actually declares {@code depthtex0};
     * {@code -Dmetallum.iris.debug.depthCapture=eager} restores the previous
     * unconditional once-per-frame capture.
     */
    public static final boolean DEPTH_CAPTURE_LAZY = depthCaptureLazy(
            System.getProperty("metallum.iris.debug.depthCapture", "lazy")
    );
    /** Effective {@code depthCapture} mode, for the switch log / probe report. */
    public static final String DEPTH_CAPTURE_MODE = DEPTH_CAPTURE_LAZY ? "lazy" : "eager";
    /**
     * Render-attachment views use the whole texture ({@code full}, default)
     * instead of a level-0-only subresource view. A full view makes
     * {@code MetalGpuTextureView.nativeHandle()} return the texture itself, so
     * a mipmapped attachment keeps a stable handle and the encoder can stay
     * open across passes; {@code -Dmetallum.iris.debug.attachmentViews=subresource}
     * restores the per-pass view churn for A/B comparison.
     */
    public static final boolean ATTACHMENT_VIEWS_FULL = attachmentViewsFull(
            System.getProperty("metallum.iris.debug.attachmentViews", "full")
    );
    /** Effective {@code attachmentViews} mode, for the switch log / probe report. */
    public static final String ATTACHMENT_VIEWS_MODE = ATTACHMENT_VIEWS_FULL ? "full" : "subresource";
    /**
     * Batches consecutive {@code copyTextureToTexture} calls into one blit
     * encoder instead of ending and recreating it per copy; on by default.
     * {@code -Dmetallum.iris.debug.blitBatch=off} restores one encoder per copy.
     */
    public static final boolean BLIT_BATCH = blitBatchEnabled(
            System.getProperty("metallum.iris.debug.blitBatch", "on")
    );
    /** Logs the sampler keys of the first non-terrain world pass once per process. */
    public static final boolean LOG_SAMPLERS = enabledFlag("metallum.iris.worldPass.logSamplers");
    /**
     * H1 diagnosis: per compiled shaderpack program, dumps every SAMPLED_IMAGE
     * resource's per-stage compact indices together with the
     * {@code [[texture(N)]]}/{@code [[sampler(N)]]} indices parsed from the
     * emitted MSL, and warns (never throws) when a stage's expected index is
     * absent from that stage's MSL index set.
     * {@code -Dmetallum.iris.debug.dumpBindings}.
     */
    public static final boolean DUMP_BINDINGS = enabledFlag("metallum.iris.debug.dumpBindings");
    /**
     * H3 settling aid: once per second, logs the frame time inputs the Iris
     * uniform buffer is filled from (frameTime/frameTimeCounter/frameCounter/
     * viewWidth/viewHeight). {@code -Dmetallum.iris.debug.logUniforms}.
     */
    public static final boolean LOG_UNIFORMS = enabledFlag("metallum.iris.debug.logUniforms");
    /**
     * Flip/side receipts into {@link MetalProbeReport}: composite's colortex6
     * read/write side, final's colortex6/colortex1 read side, and the
     * end-of-frame canonicalization bits ({@code 6} missing there is the red
     * flag for MakeUp's dead exposure history). Content-deduplicated and capped
     * so it cannot flood the probe. Enabled by its own
     * {@code -Dmetallum.iris.debug.flipTrace} or automatically by any probe
     * session flag (view/dumpBindings/logUniforms) so the evidence rides along
     * with the existing diagnostics.
     */
    public static final boolean FLIP_TRACE = enabledFlag("metallum.iris.debug.flipTrace")
            || DUMP_BINDINGS || LOG_UNIFORMS || !VIEW.isEmpty();
    public static final List<StripEntry> STAGE_STRIP = parseStageStrip(
            System.getProperty("metallum.iris.debug.stageStrip", "").trim()
    );
    private static final Set<String> SKIP_PASS_NAMES = parsePassNames(SKIP_PASS);
    /**
     * True when at least one probe/debug switch is active. Gates probe output
     * such as {@link MetalProbeReport} lines (and mirrors the condition for the
     * startup switch log) so release runs stay inert.
     */
    public static final boolean PROBES_ACTIVE = SKIP_DEFERRED || SKIP_POST || !VIEW.isEmpty()
            || !SKIP_PASS_NAMES.isEmpty() || NO_SHADOW_MATRICES || ZERO_VL || ZERO_BLOOM
            || !STAGE_STRIP.isEmpty() || NO_VANILLA_SKY || NO_VANILLA_CLOUDS
            || NO_CLOUDS_HARD || MAGENTA_CLEAR || !SHADOW_PASS || NO_SHADOWS
            || !SHADOW_CULLING.isEmpty() || !SHADOW_DEPTH_FIX || BLEND_OVERRIDES
            || !SIZE_BUFFER || DUMP_BINDINGS || LOG_UNIFORMS || FLIP_TRACE
            || !VERTEX_ENGINE_PROJECTION || !GLSLANG_PRECISION_PREAMBLE
            || !GLSLANG_BUILTIN_RENAME || PERF
            || !SHADOW_SEED_CLEAR || !DEPTH_CAPTURE_LAZY
            || !ATTACHMENT_VIEWS_FULL || !BLIT_BATCH
            || RENDER_SCALE != 1.0f || SHADOW_RESOLUTION != 0;

    /** One {@code stageStrip} entry: a pass name plus the target index to tile. */
    public record StripEntry(String passName, int targetIndex) {
    }

    private MetalDebugSwitches() {
    }

    /** Exact plan-name match against the comma-separated {@code skipPass} list. */
    static boolean shouldSkipPass(final String planName) {
        return planName != null && SKIP_PASS_NAMES.contains(planName);
    }

    private static Set<String> parsePassNames(final String raw) {
        Set<String> names = new HashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return Set.copyOf(names);
    }

    /**
     * Enabled-style switch: a bare {@code -Dname} (present with an empty value)
     * and an explicit {@code -Dname=true} are both on; unset, {@code =false}
     * and any other value are off. Use only for flags whose absence is the off
     * state; the {@code =off}/{@code =false} kill switches keep their own
     * parsers.
     */
    static boolean enabledFlag(final String name) {
        String value = System.getProperty(name);
        return value != null && (value.isEmpty() || Boolean.parseBoolean(value));
    }

    /** {@code shadowSeed}: only {@code copy} selects the legacy blit seed; everything else clears both maps. */
    static boolean shadowSeedClear(final @Nullable String raw) {
        return !"copy".equalsIgnoreCase(raw == null ? "" : raw.trim());
    }

    /** {@code depthCapture}: only {@code eager} restores the unconditional once-per-frame capture. */
    static boolean depthCaptureLazy(final @Nullable String raw) {
        return !"eager".equalsIgnoreCase(raw == null ? "" : raw.trim());
    }

    /** {@code attachmentViews}: only {@code subresource} restores the level-0-only attachment views. */
    static boolean attachmentViewsFull(final @Nullable String raw) {
        return !"subresource".equalsIgnoreCase(raw == null ? "" : raw.trim());
    }

    /** {@code blitBatch}: only {@code off} restores one blit encoder per copy. */
    static boolean blitBatchEnabled(final @Nullable String raw) {
        return !"off".equalsIgnoreCase(raw == null ? "" : raw.trim());
    }

    /** Finite positive gain only; anything else (unset, NaN, 0, negative) stays 1. */
    private static float parseViewGain(final String raw) {
        try {
            float gain = Float.parseFloat(raw);
            return Float.isFinite(gain) && gain > 0.0f ? gain : 1.0f;
        } catch (NumberFormatException malformed) {
            return 1.0f;
        }
    }

    /**
     * Render-scale parse: clamps into {@code 0.5..1.0}; malformed, NaN and
     * infinite input falls back to the default 1.0 (no scaling).
     */
    static float parseRenderScale(final String raw) {
        final float parsed;
        try {
            parsed = Float.parseFloat(raw);
        } catch (NumberFormatException malformed) {
            return 1.0f;
        }
        if (!Float.isFinite(parsed)) {
            return 1.0f;
        }
        return Math.max(0.5f, Math.min(1.0f, parsed));
    }

    /**
     * Rounded scaled extent: {@code round(extent * scale)}, never below 1. The
     * window framebuffer extent is always positive in production; the lower
     * bound only protects malformed zero/negative inputs.
     */
    public static int scaledExtent(final int extent, final float scale) {
        return Math.max(1, Math.round(extent * scale));
    }

    /**
     * Shadow-resolution override parse: 0 (use the pack value) or an even
     * extent in {@code 256..4096}. Odd requests round down; everything outside
     * the range falls back to 0.
     */
    static int parseShadowResolution(final String raw) {
        final int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException malformed) {
            return 0;
        }
        if (parsed == 0) {
            return 0;
        }
        if (parsed < 256 || parsed > 4096) {
            return 0;
        }
        return parsed & ~1;
    }

    /** The effective shadow resolution: the override when set, else the pack's. */
    static int resolveShadowResolution(final int packResolution, final int override) {
        return override == 0 ? packResolution : override;
    }

    /** The render scale actually in effect; 1.0 once a resize has failed. */
    public static float effectiveRenderScale() {
        return renderScaleFailed ? 1.0f : RENDER_SCALE;
    }

    /**
     * Latches the render-scale fallback after a main-target resize failure.
     *
     * @return {@code true} for the first failing caller (the one that should
     *         log the warning), {@code false} for every later caller
     */
    public static boolean failRenderScale() {
        boolean first = !renderScaleFailed;
        renderScaleFailed = true;
        return first;
    }

    private static List<StripEntry> parseStageStrip(final String raw) {
        List<StripEntry> entries = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String name = trimmed;
            int target = 0;
            int colon = trimmed.indexOf(':');
            if (colon >= 0) {
                name = trimmed.substring(0, colon).trim();
                try {
                    target = Integer.parseInt(trimmed.substring(colon + 1).trim());
                } catch (NumberFormatException malformed) {
                    continue; // Unparseable target index: drop the entry.
                }
            }
            if (!name.isEmpty() && target >= 0) {
                entries.add(new StripEntry(name, target));
            }
        }
        return List.copyOf(entries);
    }

    static {
        if (PROBES_ACTIVE) {
            Metallum.LOGGER.warn("[metallum-iris][debug] switches active: build={} skipDeferred={} skipPost={} view={} viewGain={} skipPass={} noShadowMatrices={} zeroVl={} zeroBloom={} stageStrip={} noVanillaSky={} noVanillaClouds={} noCloudsHard={} magentaClear={} shadowPass={} noShadows={} shadowCulling={} shadowDepthFix={} blendOverrides={} sizeBuffer={} dumpBindings={} logUniforms={} flipTrace={} vertexEngineProjection={} glslangPrecision={} glslangBuiltinRename={} perf={} renderScale={} shadowResolution={} shadowSeed={} depthCapture={} attachmentViews={} blitBatch={}",
                    BUILD_TAG, SKIP_DEFERRED, SKIP_POST, VIEW, VIEW_GAIN, SKIP_PASS, NO_SHADOW_MATRICES, ZERO_VL, ZERO_BLOOM,
                    System.getProperty("metallum.iris.debug.stageStrip", ""),
                    NO_VANILLA_SKY, NO_VANILLA_CLOUDS, NO_CLOUDS_HARD, MAGENTA_CLEAR,
                    SHADOW_PASS, NO_SHADOWS, SHADOW_CULLING, SHADOW_DEPTH_FIX, BLEND_OVERRIDES, SIZE_BUFFER,
                    DUMP_BINDINGS, LOG_UNIFORMS, FLIP_TRACE, VERTEX_ENGINE_PROJECTION, GLSLANG_PRECISION_PREAMBLE,
                    GLSLANG_BUILTIN_RENAME, PERF, RENDER_SCALE, SHADOW_RESOLUTION,
                    SHADOW_SEED_MODE, DEPTH_CAPTURE_MODE, ATTACHMENT_VIEWS_MODE, BLIT_BATCH ? "on" : "off");
        }
    }
}
