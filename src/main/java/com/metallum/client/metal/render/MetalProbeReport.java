package com.metallum.client.metal.render;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-check channel for the bisection probes that does not depend on log-file
 * search: writes a status file into the instance directory
 * ({@code metallum-probe.txt}) and posts one message to the in-game chat, both
 * visible without touching the logs. Fully inert unless at least one debug
 * switch is active; every file write and chat call is swallowed so a failure
 * can never crash the game.
 */
public final class MetalProbeReport {
    private static final Path REPORT_FILE =
            FabricLoader.getInstance().getGameDir().resolve("metallum-probe.txt");

    /** Set once the main report has been written; the whole mechanism runs once per process. */
    private static boolean reported;

    /** Lines recorded by {@link #record(String)}, replayed into the main report if written first. */
    private static final List<String> RECORDED_LINES = new ArrayList<>();

    private MetalProbeReport() {
    }

    /**
     * Called every client tick; writes the report and posts the chat message
     * exactly once, only when a world is loaded and a debug switch is active.
     */
    public static void onClientTick(final Minecraft minecraft) {
        if (reported || minecraft == null) {
            return;
        }
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        List<String> active = activeSwitches();
        if (active.isEmpty()) {
            return;
        }
        reported = true;
        writeReport(active);
        postChatMessage(active);
    }

    /**
     * Appends a timestamped line to the status file (creating it if needed) and
     * keeps it in memory for inclusion in the main report. Never throws.
     *
     * <p>Inert while no debug switch is active: several call sites (world
     * override install/skip receipts, per-draw materialisation) record on first
     * use regardless of flags, which used to create the file on every normal
     * launch. Without an active switch {@link #writeReport} also never ran, so
     * those lines were appended across launches and the file grew without
     * bound. Normal play must not touch the instance directory at all.
     */
    public static synchronized void record(final String line) {
        if (!MetalDebugSwitches.PROBES_ACTIVE) {
            return;
        }
        String timestamped = "[" + Instant.now() + "] " + line;
        RECORDED_LINES.add(timestamped);
        try {
            Files.writeString(REPORT_FILE, timestamped + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Rewrites the file as header + switches + every line recorded so far.
     * Synchronized with {@link #record(String)} so a concurrent compile-time
     * record cannot be truncated away between content building and the write.
     */
    private static synchronized void writeReport(final List<String> active) {
        StringBuilder content = new StringBuilder();
        content.append("build=").append(MetalDebugSwitches.BUILD_TAG).append(System.lineSeparator());
        content.append("timestamp=").append(Instant.now()).append(System.lineSeparator());
        content.append("active=").append(String.join(",", active)).append(System.lineSeparator());
        content.append("switches:").append(System.lineSeparator());
        content.append("  skipDeferred=").append(MetalDebugSwitches.SKIP_DEFERRED).append(System.lineSeparator());
        content.append("  skipPost=").append(MetalDebugSwitches.SKIP_POST).append(System.lineSeparator());
        content.append("  view=").append(MetalDebugSwitches.VIEW).append(System.lineSeparator());
        content.append("  skipPass=").append(MetalDebugSwitches.SKIP_PASS).append(System.lineSeparator());
        content.append("  noShadowMatrices=").append(MetalDebugSwitches.NO_SHADOW_MATRICES).append(System.lineSeparator());
        content.append("  shadowPass=").append(MetalDebugSwitches.SHADOW_PASS).append(System.lineSeparator());
        content.append("  noShadows=").append(MetalDebugSwitches.NO_SHADOWS).append(System.lineSeparator());
        content.append("  shadowCulling=").append(MetalDebugSwitches.SHADOW_CULLING).append(System.lineSeparator());
        content.append("  shadowDepthFix=").append(MetalDebugSwitches.SHADOW_DEPTH_FIX).append(System.lineSeparator());
        content.append("  vertexEngineProjection=").append(MetalDebugSwitches.VERTEX_ENGINE_PROJECTION).append(System.lineSeparator());
        content.append("  zeroVl=").append(MetalDebugSwitches.ZERO_VL).append(System.lineSeparator());
        content.append("  zeroBloom=").append(MetalDebugSwitches.ZERO_BLOOM).append(System.lineSeparator());
        content.append("  noVanillaSky=").append(MetalDebugSwitches.NO_VANILLA_SKY).append(System.lineSeparator());
        content.append("  noVanillaClouds=").append(MetalDebugSwitches.NO_VANILLA_CLOUDS).append(System.lineSeparator());
        content.append("  noCloudsHard=").append(MetalDebugSwitches.NO_CLOUDS_HARD).append(System.lineSeparator());
        content.append("  magentaClear=").append(MetalDebugSwitches.MAGENTA_CLEAR).append(System.lineSeparator());
        content.append("  blendOverrides=").append(MetalDebugSwitches.BLEND_OVERRIDES).append(System.lineSeparator());
        content.append("  dumpBindings=").append(MetalDebugSwitches.DUMP_BINDINGS).append(System.lineSeparator());
        content.append("  logUniforms=").append(MetalDebugSwitches.LOG_UNIFORMS).append(System.lineSeparator());
        content.append("  flipTrace=").append(MetalDebugSwitches.FLIP_TRACE).append(System.lineSeparator());
        content.append("  viewGain=").append(IrisMetalDebugViewBlitter.formatGain(MetalDebugSwitches.VIEW_GAIN))
                .append(System.lineSeparator());
        content.append("  frameHistory=").append(MetalDebugSwitches.FRAME_HISTORY).append(System.lineSeparator());
        content.append("  shadowSeed=").append(MetalDebugSwitches.SHADOW_SEED_MODE).append(System.lineSeparator());
        content.append("  depthCapture=").append(MetalDebugSwitches.DEPTH_CAPTURE_MODE).append(System.lineSeparator());
        content.append("  attachmentViews=").append(MetalDebugSwitches.ATTACHMENT_VIEWS_MODE).append(System.lineSeparator());
        content.append("  blitBatch=").append(MetalDebugSwitches.BLIT_BATCH ? "on" : "off").append(System.lineSeparator());
        content.append("  perf=").append(MetalDebugSwitches.PERF).append(System.lineSeparator());
        content.append("  renderScale=").append(MetalDebugSwitches.RENDER_SCALE).append(System.lineSeparator());
        content.append("  shadowResolution=").append(MetalDebugSwitches.SHADOW_RESOLUTION).append(System.lineSeparator());
        content.append("  stageStrip=").append(System.getProperty("metallum.iris.debug.stageStrip", ""))
                .append(System.lineSeparator());
        if (!RECORDED_LINES.isEmpty()) {
            content.append("recorded:").append(System.lineSeparator());
            for (String line : RECORDED_LINES) {
                content.append("  ").append(line).append(System.lineSeparator());
            }
        }
        try {
            Files.writeString(REPORT_FILE, content.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Throwable ignored) {
        }
    }

    private static void postChatMessage(final List<String> active) {
        try {
            Component message = Component.literal(
                    "[metallum] " + MetalDebugSwitches.BUILD_TAG + " active: " + String.join(", ", active)
            );
            minecraftChat().addClientSystemMessage(message);
        } catch (Throwable ignored) {
        }
    }

    private static net.minecraft.client.gui.components.ChatComponent minecraftChat() {
        return Minecraft.getInstance().gui.hud.getChat();
    }

    private static List<String> activeSwitches() {
        List<String> active = new ArrayList<>();
        addIf(active, "skipDeferred", MetalDebugSwitches.SKIP_DEFERRED);
        addIf(active, "skipPost", MetalDebugSwitches.SKIP_POST);
        addIf(active, "noShadowMatrices", MetalDebugSwitches.NO_SHADOW_MATRICES);
        addIf(active, "shadowPass=off", !MetalDebugSwitches.SHADOW_PASS);
        addIf(active, "noShadows", MetalDebugSwitches.NO_SHADOWS);
        if (!MetalDebugSwitches.SHADOW_CULLING.isEmpty()) {
            active.add("shadowCulling=" + MetalDebugSwitches.SHADOW_CULLING);
        }
        addIf(active, "shadowDepthFix=off", !MetalDebugSwitches.SHADOW_DEPTH_FIX);
        addIf(active, "vertexEngineProjection=off", !MetalDebugSwitches.VERTEX_ENGINE_PROJECTION);
        addIf(active, "zeroVl", MetalDebugSwitches.ZERO_VL);
        addIf(active, "zeroBloom", MetalDebugSwitches.ZERO_BLOOM);
        addIf(active, "noVanillaSky", MetalDebugSwitches.NO_VANILLA_SKY);
        addIf(active, "noVanillaClouds", MetalDebugSwitches.NO_VANILLA_CLOUDS);
        addIf(active, "noCloudsHard", MetalDebugSwitches.NO_CLOUDS_HARD);
        addIf(active, "magentaClear", MetalDebugSwitches.MAGENTA_CLEAR);
        addIf(active, "blendOverrides", MetalDebugSwitches.BLEND_OVERRIDES);
        addIf(active, "dumpBindings", MetalDebugSwitches.DUMP_BINDINGS);
        addIf(active, "logUniforms", MetalDebugSwitches.LOG_UNIFORMS);
        addIf(active, "flipTrace", MetalDebugSwitches.FLIP_TRACE);
        if (MetalDebugSwitches.VIEW_GAIN != 1.0f) {
            active.add("viewGain=" + IrisMetalDebugViewBlitter.formatGain(MetalDebugSwitches.VIEW_GAIN));
        }
        addIf(active, "frameHistory=off", !MetalDebugSwitches.FRAME_HISTORY);
        addIf(active, "shadowSeed=copy", !MetalDebugSwitches.SHADOW_SEED_CLEAR);
        addIf(active, "depthCapture=eager", !MetalDebugSwitches.DEPTH_CAPTURE_LAZY);
        addIf(active, "attachmentViews=subresource", !MetalDebugSwitches.ATTACHMENT_VIEWS_FULL);
        addIf(active, "blitBatch=off", !MetalDebugSwitches.BLIT_BATCH);
        addIf(active, "perf", MetalDebugSwitches.PERF);
        if (MetalDebugSwitches.RENDER_SCALE != 1.0f) {
            active.add("renderScale=" + MetalDebugSwitches.RENDER_SCALE);
        }
        if (MetalDebugSwitches.SHADOW_RESOLUTION != 0) {
            active.add("shadowResolution=" + MetalDebugSwitches.SHADOW_RESOLUTION);
        }
        if (!MetalDebugSwitches.VIEW.isEmpty()) {
            active.add("view=" + MetalDebugSwitches.VIEW);
        }
        if (!MetalDebugSwitches.SKIP_PASS.isEmpty()) {
            active.add("skipPass=" + MetalDebugSwitches.SKIP_PASS);
        }
        if (!MetalDebugSwitches.STAGE_STRIP.isEmpty()) {
            active.add("stageStrip=" + System.getProperty("metallum.iris.debug.stageStrip", ""));
        }
        return active;
    }

    private static void addIf(final List<String> active, final String name, final boolean value) {
        if (value) {
            active.add(name);
        }
    }
}
