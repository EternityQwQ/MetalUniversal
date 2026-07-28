package com.metallum.client.metal.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time warning dialog shown before the user enters the MetalFX options
 * for the first time. Lists the officially supported OS versions and Apple
 * Silicon chip families (taken verbatim from Apple's MetalFX framework
 * documentation), so the user can decide whether their device is suitable
 * before enabling features that may not work — or may not work well — on
 * unsupported hardware.
 *
 * <p>Layout: a vertical linear layout with a title, a multi-line
 * capability table (spatial / temporal / interpolation, each with its
 * macOS+iOS requirements and required chip), and a two-button row
 * ({@code Enable} / {@code Do Not Enable}) at the bottom.
 *
 * <p>{@code Enable} marks the warning acknowledged (persisted via
 * {@link MetalFxConfig#save()}) and opens {@link MetalFxOptionsScreen}.
 * {@code Do Not Enable} returns to the parent screen without persisting
 * the acknowledgement — so the warning re-appears the next time the user
 * opens the MetalFX entry point.
 *
 * <p>The screen is intentionally reachable from both the vanilla and
 * Sodium video-settings mixins (via
 * {@link #openIfNotAcknowledged(Screen)}), so it shows up identically
 * regardless of which video-settings UI the user opened it from.
 */
@Environment(EnvType.CLIENT)
public final class MetalFxWarningScreen extends Screen {
    private static final int CONTENT_WIDTH = 360;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 4;
    private static final int ENABLE_BUTTON_WIDTH = 150;
    private static final int CANCEL_BUTTON_WIDTH = 150;

    private final Screen parent;

    public MetalFxWarningScreen(@Nullable Screen parent) {
        super(Component.translatable("metallum.fx.warning.title"));
        this.parent = parent;
    }

    /**
     * Convenience entry point used by the video-settings mixins: if the
     * user has already acknowledged the warning, skip straight to the
     * MetalFX options screen; otherwise show the warning first. The
     * {@code parent} is preserved through the chain so {@code Done}
     * eventually returns to the original video-settings screen.
     */
    public static void openIfNotAcknowledged(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        MetalFxConfig cfg = MetalFxConfig.get();
        if (cfg.acknowledged()) {
            mc.setScreenAndShow(new MetalFxOptionsScreen(parent));
        } else {
            mc.setScreenAndShow(new MetalFxWarningScreen(parent));
        }
    }

    @Override
    protected void init() {
        LinearLayout column = LinearLayout.vertical().spacing(SPACING);

        // Header line.
        column.addChild(new StringWidget(CONTENT_WIDTH, BUTTON_HEIGHT,
                Component.translatable("metallum.fx.warning.header"),
                this.font));

        // Multi-line adaptation table. Each line is a separate translation
        // key with substitutions so the lang files can format it freely.
        for (Component line : buildAdaptationLines()) {
            column.addChild(new StringWidget(CONTENT_WIDTH, BUTTON_HEIGHT, line, this.font));
        }

        // Spacer
        column.addChild(new StringWidget(CONTENT_WIDTH, SPACING, Component.empty(), this.font));

        // Risk note.
        column.addChild(new StringWidget(CONTENT_WIDTH, BUTTON_HEIGHT,
                Component.translatable("metallum.fx.warning.note"),
                this.font));

        // Spacer
        column.addChild(new StringWidget(CONTENT_WIDTH, SPACING, Component.empty(), this.font));

        // Two-button row: [Enable] [Do Not Enable]
        LinearLayout buttonRow = LinearLayout.horizontal().spacing(SPACING);
        Button enableButton = Button.builder(
                Component.translatable("metallum.fx.warning.enable"),
                this::onEnable
        ).width(ENABLE_BUTTON_WIDTH).build();
        Button cancelButton = Button.builder(
                Component.translatable("metallum.fx.warning.cancel"),
                this::onCancel
        ).width(CANCEL_BUTTON_WIDTH).build();
        buttonRow.addChild(enableButton);
        buttonRow.addChild(cancelButton);
        column.addChild(buttonRow);

        column.visitWidgets(this::addRenderableWidget);
        column.arrangeElements();
        // Center within the full screen so the dialog appears at the true
        // center. The title is drawn separately at y=16 and the content block
        // is short enough that it never overlaps the title on any reasonable
        // resolution.
        FrameLayout.alignInRectangle(column, 0, 0, this.width, this.height, 0.5F, 0.5F);
    }

    private void onEnable(Button button) {
        // Mark acknowledged and persist; the user has explicitly opted in.
        MetalFxConfig cfg = MetalFxConfig.get();
        cfg.setAcknowledged(true);
        MetalFxConfig.save();
        Minecraft.getInstance().setScreenAndShow(new MetalFxOptionsScreen(this.parent));
    }

    private void onCancel(Button button) {
        // Do NOT persist acknowledgement; warning re-appears next time.
        Minecraft.getInstance().setScreenAndShow(this.parent);
    }

    @Override
    public void onClose() {
        // ESC behaves the same as "Do Not Enable".
        Minecraft.getInstance().setScreenAndShow(this.parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFF5555);
    }

    /**
     * Builds the official Apple MetalFX adaptation table. The strings below
     * mirror Apple's MetalFX framework reference exactly:
     * <ul>
     *   <li>MTLFXSpatialScaler — spatial upscaling. macOS 13.0+, iOS 16.0+,
     *       all Apple GPU devices (Apple GPU family 7+, i.e. M1 / A14 and
     *       newer).</li>
     *   <li>MTLFXTemporalScaler — temporal upscaling. macOS 14.0+,
     *       iOS 17.0+.</li>
     *   <li>MTLFXFrameInterpolator — frame interpolation. macOS 26.0+,
     *       iOS 26.0+, requires Apple GPU family 9 (M3 / A17 Pro and
     *       newer) for the hardware-accelerated path.</li>
     * </ul>
     * On devices that lack the hardware path the MetalFXPipeline falls
     * back to a 50/50 frame-blend, which is documented in the warning so
     * the user knows what to expect on, e.g., an M2 / A16.
     */
    private static List<Component> buildAdaptationLines() {
        List<Component> lines = new ArrayList<>(8);
        // Spatial scaler — official: macOS 13+, iOS 16+, Apple GPU family 7+ (M1 / A14+)
        lines.add(Component.translatable("metallum.fx.warning.spatial.line"));
        // Temporal scaler — official: macOS 14+, iOS 17+
        lines.add(Component.translatable("metallum.fx.warning.temporal.line"));
        // Frame interpolator — official: macOS 26+, iOS 26+, hardware path requires M3 / A17 Pro+
        lines.add(Component.translatable("metallum.fx.warning.interp.line"));
        return lines;
    }
}
