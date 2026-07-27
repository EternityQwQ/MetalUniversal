package com.metallum.client.metal.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
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
 * Standalone screen for configuring MetalFX spatial upscaling and frame
 * interpolation. Designed to be opened from a button injected into both
 * the vanilla {@code VideoSettingsScreen} and Sodium's video settings
 * screen, as well as from a dedicated keybind (fallback for headless /
 * iOS environments where the parent screen may not be reachable).
 *
 * <p>The screen renders three cycling buttons (spatial mode, frame
 * interpolation mode, post-upscale FXAA toggle), a multi-line device
 * capability readout (chip name + which MetalFX features are supported),
 * and a "Done" button that persists the configuration via
 * {@link MetalFxConfig#save()} and returns to the parent screen.
 *
 * <p>Layout is intentionally simple — a vertical linear layout that wraps
 * to the screen width — so it works equally well on macOS (wide window)
 * and iOS (narrow portrait orientation).
 */
@Environment(EnvType.CLIENT)
public final class MetalFxOptionsScreen extends Screen {
    private static final int CONTENT_WIDTH = 320;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 4;

    private final Screen parent;

    public MetalFxOptionsScreen(@Nullable Screen parent) {
        super(Component.translatable("metallum.fx.options.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        LinearLayout column = LinearLayout.vertical().spacing(SPACING);

        // Header
        column.addChild(new StringWidget(CONTENT_WIDTH, BUTTON_HEIGHT,
                Component.translatable("metallum.fx.options.header"),
                this.font));

        // Device capability readout
        MetalFxConfig cfg = MetalFxConfig.get();
        List<Component> capabilityLines = buildCapabilityLines(cfg);
        for (Component line : capabilityLines) {
            column.addChild(new StringWidget(CONTENT_WIDTH, BUTTON_HEIGHT, line, this.font));
        }

        // Spacer
        column.addChild(new StringWidget(CONTENT_WIDTH, SPACING, Component.empty(), this.font));

        // Spatial upscaling mode (OFF / QUALITY / BALANCED / PERFORMANCE / ULTRA_PERFORMANCE)
        CycleButton<MetalFxConfig.SpatialMode> spatialButton = CycleButton.<MetalFxConfig.SpatialMode>builder(MetalFxOptionsScreen::spatialModeLabel, cfg.spatialMode())
                .withValues(MetalFxConfig.SpatialMode.values())
                .create(Component.translatable("metallum.fx.options.spatial"),
                        (button, mode) -> cfg.setSpatialMode(mode));
        spatialButton.setWidth(CONTENT_WIDTH);
        column.addChild(spatialButton);

        // Temporal upscaling mode (OFF / AUTO). Replaces the spatial scaler
        // with MTLFXTemporalScaler when active — higher quality, uses
        // temporal history. Requires a non-OFF spatial mode (render scale).
        CycleButton<MetalFxConfig.TemporalUpscalingMode> temporalButton =
                CycleButton.<MetalFxConfig.TemporalUpscalingMode>builder(MetalFxOptionsScreen::temporalModeLabel, cfg.temporalMode())
                        .withValues(MetalFxConfig.TemporalUpscalingMode.values())
                        .create(Component.translatable("metallum.fx.options.temporal"),
                                (button, mode) -> cfg.setTemporalMode(mode));
        temporalButton.setWidth(CONTENT_WIDTH);
        column.addChild(temporalButton);

        // Frame interpolation mode (OFF / AUTO / FORCE_BLEND)
        CycleButton<MetalFxConfig.FrameInterpolationMode> interpButton =
                CycleButton.<MetalFxConfig.FrameInterpolationMode>builder(MetalFxOptionsScreen::interpModeLabel, cfg.interpolationMode())
                        .withValues(MetalFxConfig.FrameInterpolationMode.values())
                        .create(Component.translatable("metallum.fx.options.frame_interpolation"),
                                (button, mode) -> cfg.setInterpolationMode(mode));
        interpButton.setWidth(CONTENT_WIDTH);
        column.addChild(interpButton);

        // Spacer
        column.addChild(new StringWidget(CONTENT_WIDTH, SPACING, Component.empty(), this.font));

        // Done button — persists the config and returns to parent
        Button doneButton = Button.builder(Component.translatable("gui.done"), this::onDone)
                .width(CONTENT_WIDTH)
                .build();
        column.addChild(doneButton);

        column.visitWidgets(this::addRenderableWidget);
        column.arrangeElements();
        // Center the column horizontally; vertically place it below the title
        // (which is drawn at y=16) and let it grow downward.
        FrameLayout.alignInRectangle(column, 0, 36, this.width, this.height, 0.5F, 0.0F);
    }

    private void onDone(Button button) {
        MetalFxConfig.save();
        Minecraft.getInstance().setScreenAndShow(this.parent);
    }

    @Override
    public void onClose() {
        MetalFxConfig.save();
        Minecraft.getInstance().setScreenAndShow(this.parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
    }

    /**
     * Builds the per-device capability readout shown at the top of the screen.
     * Each line is a translation key with substitution args so it can be
     * localized, falling back to English if the lang file is missing.
     */
    private static List<Component> buildCapabilityLines(MetalFxConfig cfg) {
        List<Component> lines = new ArrayList<>(4);

        String deviceName = cfg.deviceName();
        if (deviceName == null || deviceName.isEmpty() || "<unknown>".equals(deviceName)) {
            deviceName = Component.translatable("metallum.fx.capability.unknown_device").getString();
        }
        lines.add(Component.translatable("metallum.fx.capability.device", deviceName));

        lines.add(Component.translatable("metallum.fx.capability.spatial",
                cfg.spatialSupported()
                        ? Component.translatable("metallum.fx.capability.supported")
                        : Component.translatable("metallum.fx.capability.not_supported")));

        lines.add(Component.translatable("metallum.fx.capability.temporal",
                cfg.temporalSupported()
                        ? Component.translatable("metallum.fx.capability.supported")
                        : Component.translatable("metallum.fx.capability.not_supported")));

        lines.add(Component.translatable("metallum.fx.capability.interpolation",
                cfg.interpolationSupported()
                        ? Component.translatable("metallum.fx.capability.supported")
                        : Component.translatable("metallum.fx.capability.not_supported")));

        // Hint for users on devices without hardware frame interpolation
        if (!cfg.interpolationSupported()) {
            lines.add(Component.translatable("metallum.fx.capability.interp_hint"));
        }

        return lines;
    }

    private static Component spatialModeLabel(MetalFxConfig.SpatialMode mode) {
        return switch (mode) {
            case OFF -> Component.translatable("metallum.fx.spatial.off");
            case QUALITY -> Component.translatable("metallum.fx.spatial.quality");
            case BALANCED -> Component.translatable("metallum.fx.spatial.balanced");
            case PERFORMANCE -> Component.translatable("metallum.fx.spatial.performance");
            case ULTRA_PERFORMANCE -> Component.translatable("metallum.fx.spatial.ultra_performance");
        };
    }

    private static Component temporalModeLabel(MetalFxConfig.TemporalUpscalingMode mode) {
        return switch (mode) {
            case OFF -> Component.translatable("metallum.fx.temporal.off");
            case AUTO -> Component.translatable("metallum.fx.temporal.auto");
        };
    }

    private static Component interpModeLabel(MetalFxConfig.FrameInterpolationMode mode) {
        return switch (mode) {
            case OFF -> Component.translatable("metallum.fx.interp.off");
            case AUTO -> Component.translatable("metallum.fx.interp.auto");
            case FORCE_BLEND -> Component.translatable("metallum.fx.interp.force_blend");
        };
    }
}
