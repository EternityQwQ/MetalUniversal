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
 * MetalFX 设置界面。提供空间超分（循环按钮）、时间超分（开关）、帧插值（开关）
 * 三个控件，以及设备能力自检信息。
 *
 * <p><b>布局。</b> 垂直线性布局，宽度 320，居中。同时适配 macOS（宽屏）
 * 和 iOS（窄竖屏）。
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

        // 标题
        column.addChild(new StringWidget(CONTENT_WIDTH, BUTTON_HEIGHT,
                Component.translatable("metallum.fx.options.header"),
                this.font));

        // 设备能力信息
        MetalFxConfig cfg = MetalFxConfig.get();
        for (Component line : buildCapabilityLines(cfg)) {
            column.addChild(new StringWidget(CONTENT_WIDTH, BUTTON_HEIGHT, line, this.font));
        }

        // 空白间隔
        column.addChild(new StringWidget(CONTENT_WIDTH, SPACING, Component.empty(), this.font));

        // 空间超分 — 循环按钮（OFF / QUALITY / BALANCED / PERFORMANCE / ULTRA_PERFORMANCE）
        CycleButton<MetalFxConfig.SpatialMode> spatialButton =
                CycleButton.<MetalFxConfig.SpatialMode>builder(MetalFxOptionsScreen::spatialModeLabel, cfg.spatialMode())
                        .withValues(MetalFxConfig.SpatialMode.values())
                        .create(Component.translatable("metallum.fx.options.spatial"),
                                (button, mode) -> cfg.setSpatialMode(mode));
        spatialButton.setWidth(CONTENT_WIDTH);
        column.addChild(spatialButton);

        // 时间超分 — 开关（OFF / AUTO）
        CycleButton<MetalFxConfig.TemporalUpscalingMode> temporalButton =
                CycleButton.<MetalFxConfig.TemporalUpscalingMode>builder(MetalFxOptionsScreen::temporalModeLabel, cfg.temporalMode())
                        .withValues(MetalFxConfig.TemporalUpscalingMode.values())
                        .create(Component.translatable("metallum.fx.options.temporal"),
                                (button, mode) -> cfg.setTemporalMode(mode));
        temporalButton.setWidth(CONTENT_WIDTH);
        column.addChild(temporalButton);

        // 帧插值 — 开关（OFF / AUTO）
        CycleButton<MetalFxConfig.FrameInterpolationMode> interpButton =
                CycleButton.<MetalFxConfig.FrameInterpolationMode>builder(MetalFxOptionsScreen::interpModeLabel, cfg.interpolationMode())
                        .withValues(MetalFxConfig.FrameInterpolationMode.values())
                        .create(Component.translatable("metallum.fx.options.frame_interpolation"),
                                (button, mode) -> cfg.setInterpolationMode(mode));
        interpButton.setWidth(CONTENT_WIDTH);
        column.addChild(interpButton);

        // 空白间隔
        column.addChild(new StringWidget(CONTENT_WIDTH, SPACING, Component.empty(), this.font));

        // 完成按钮 — 持久化配置并返回上级界面
        Button doneButton = Button.builder(Component.translatable("gui.done"), this::onDone)
                .width(CONTENT_WIDTH)
                .build();
        column.addChild(doneButton);

        column.visitWidgets(this::addRenderableWidget);
        column.arrangeElements();
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
     * 构建设备能力信息（芯片名称 + 各 MetalFX 特性支持情况）。
     * 每行用带替换参数的翻译键，可本地化。
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
