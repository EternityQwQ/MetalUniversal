package com.metallum.client.metal.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
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
 * MetalFX 设置界面。提供空间超分（滑块）、时间超分（开关）、帧插值（开关）
 * 三个控件，以及设备能力自检信息。
 *
 * <p><b>空间超分滑块。</b> 参照原版"区块加载距离"滑块的交互方式，用
 * {@link AbstractSliderButton} 替代旧的 CycleButton。滑块有 5 档
 * （关闭 / 质量 / 平衡 / 性能 / 极致性能），拖动时实时显示档位名称和
 * 渲染比例，松手后立即生效（无需点"完成"）。这与 Iris 在 Sodium 选项
 * 页面中提供滑块控件的方式一致。
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

        // 空间超分 — 滑块（5 档，像区块加载距离那样拖动）
        column.addChild(new MetalFxSpatialSlider(0, 0, CONTENT_WIDTH, BUTTON_HEIGHT, cfg));

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

    /**
     * 空间超分档位滑块。将 5 个枚举档位映射到 [0,1] 区间，拖动时
     * 吸附到最近的档位，实时显示档位名称。
     *
     * <p>映射方式：档位索引 / (档位数 - 1)。
     * 例如 5 档时 OFF=0.0, QUALITY=0.25, BALANCED=0.5,
     * PERFORMANCE=0.75, ULTRA_PERFORMANCE=1.0。
     */
    private static final class MetalFxSpatialSlider extends AbstractSliderButton {
        private static final MetalFxConfig.SpatialMode[] MODES = MetalFxConfig.SpatialMode.values();
        private final MetalFxConfig cfg;

        MetalFxSpatialSlider(int x, int y, int width, int height, MetalFxConfig cfg) {
            super(x, y, width, height, Component.empty(), modeToValue(cfg.spatialMode()));
            this.cfg = cfg;
            updateMessage();
        }

        private static double modeToValue(MetalFxConfig.SpatialMode mode) {
            if (MODES.length <= 1) return 0.0;
            return (double) mode.ordinal() / (MODES.length - 1);
        }

        private static MetalFxConfig.SpatialMode valueToMode(double value) {
            if (MODES.length <= 1) return MODES[0];
            int idx = (int) Math.round(value * (MODES.length - 1));
            if (idx < 0) idx = 0;
            if (idx >= MODES.length) idx = MODES.length - 1;
            return MODES[idx];
        }

        @Override
        protected void applyValue() {
            MetalFxConfig.SpatialMode mode = valueToMode(this.value);
            if (mode != cfg.spatialMode()) {
                cfg.setSpatialMode(mode);
                updateMessage();
            }
        }

        @Override
        protected void updateMessage() {
            MetalFxConfig.SpatialMode mode = valueToMode(this.value);
            setMessage(Component.translatable(
                    "metallum.fx.options.spatial_slider",
                    spatialModeLabel(mode),
                    String.format("%.0f%%", mode.renderScale * 100)));
        }
    }
}
