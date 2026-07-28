package com.metallum.compat.sodium;

import com.metallum.Metallum;
import com.metallum.client.metal.fx.MetalFxConfig;
import com.metallum.client.metal.fx.MetalFxWarningScreen;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Sodium 视频设置界面集成入口（原生选项页方式）。
 *
 * <p>参照 Iris（IrisShaders/Iris）和 21Z121Z1/MetalUniversal 的 Sodium 集成方式，
 * 通过 Sodium 官方 {@link ConfigEntryPoint} API 将 MetalFX 的核心选项直接内联构建
 * 为 Sodium 原生选项页（OptionPage → OptionGroup → EnumOption/BooleanOption），
 * 而不是跳转到自定义 Screen。这样：
 * <ul>
 *   <li>选项风格与 Sodium 原生设置统一，自带搜索、tooltip、性能影响标记</li>
 *   <li>条件联动：时间超分和帧插值仅在空间超分已开启时才可用</li>
 *   <li>修改后立即通过 {@link MetalFxConfig#save()} 持久化</li>
 * </ul>
 *
 * <p>同时保留一个"高级设置"外部按钮，点击后跳转到 {@link MetalFxWarningScreen}
 * （首次进入显示适配警告）→ {@link com.metallum.client.metal.fx.MetalFxOptionsScreen}
 * （设备能力信息 + 完整选项），以及 F8 快捷键作为后备入口。
 *
 * <p><b>entrypoint 名称。</b> 必须在 fabric.mod.json 中注册为
 * {@code "sodium:config_api_user"}（不是 {@code "sodium"}），Sodium 才会
 * 扫描并调用 {@link #registerConfigLate}。
 */
@Environment(EnvType.CLIENT)
public final class MetallumSodiumConfig implements ConfigEntryPoint {
    private static final Identifier SPATIAL_ID =
            Identifier.fromNamespaceAndPath("metallum", "spatial_upscaling");
    private static final Identifier TEMPORAL_ID =
            Identifier.fromNamespaceAndPath("metallum", "temporal_upscaling");
    private static final Identifier INTERP_ID =
            Identifier.fromNamespaceAndPath("metallum", "frame_interpolation");
    private static final Identifier ADVANCED_ID =
            Identifier.fromNamespaceAndPath("metallum", "advanced_settings");

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        try {
            Metallum.LOGGER.info("[MetalFX] Registering MetalFX native option page in Sodium options");
            String version = FabricLoader.getInstance()
                    .getModContainer(Metallum.MOD_ID)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");

            OptionPageBuilder page = builder.createOptionPage()
                    .setName(Component.translatable("metallum.fx.options.title"));

            // === MetalFX 超分组 ===
            OptionGroupBuilder group = builder.createOptionGroup()
                    .setName(Component.translatable("metallum.fx.options.group"));

            // 空间超分 — 枚举选项（OFF/QUALITY/BALANCED/PERFORMANCE/ULTRA_PERFORMANCE）
            group.addOption(builder.createEnumOption(SPATIAL_ID, MetalFxConfig.SpatialMode.class)
                    .setName(Component.translatable("metallum.fx.options.spatial"))
                    .setTooltip(Component.translatable("metallum.fx.options.spatial.tooltip"))
                    .setElementNameProvider(MetallumSodiumConfig::spatialModeLabel)
                    .setDefaultValue(MetalFxConfig.SpatialMode.OFF)
                    .setImpact(OptionImpact.VARIES)
                    .setBinding(MetalFxConfig.get()::setSpatialMode,
                            MetalFxConfig.get()::spatialMode)
                    .setStorageHandler(MetallumSodiumConfig::saveConfig));

            // 时间超分 — 枚举选项（OFF/AUTO），仅空间超分开启时可用
            group.addOption(builder.createEnumOption(TEMPORAL_ID, MetalFxConfig.TemporalUpscalingMode.class)
                    .setName(Component.translatable("metallum.fx.options.temporal"))
                    .setTooltip(Component.translatable("metallum.fx.options.temporal.tooltip"))
                    .setElementNameProvider(MetallumSodiumConfig::temporalModeLabel)
                    .setDefaultValue(MetalFxConfig.TemporalUpscalingMode.OFF)
                    .setImpact(OptionImpact.HIGH)
                    .setBinding(MetalFxConfig.get()::setTemporalMode,
                            MetalFxConfig.get()::temporalMode)
                    .setStorageHandler(MetallumSodiumConfig::saveConfig)
                    .setEnabledProvider(
                            state -> state.readEnumOption(SPATIAL_ID, MetalFxConfig.SpatialMode.class)
                                    .isEnabled(),
                            SPATIAL_ID));

            // 帧插值 — 枚举选项（OFF/AUTO/FORCE_BLEND），仅空间超分开启时可用
            group.addOption(builder.createEnumOption(INTERP_ID, MetalFxConfig.FrameInterpolationMode.class)
                    .setName(Component.translatable("metallum.fx.options.frame_interpolation"))
                    .setTooltip(Component.translatable("metallum.fx.options.frame_interpolation.tooltip"))
                    .setElementNameProvider(MetallumSodiumConfig::interpModeLabel)
                    .setDefaultValue(MetalFxConfig.FrameInterpolationMode.OFF)
                    .setImpact(OptionImpact.HIGH)
                    .setBinding(MetalFxConfig.get()::setInterpolationMode,
                            MetalFxConfig.get()::interpolationMode)
                    .setStorageHandler(MetallumSodiumConfig::saveConfig)
                    .setEnabledProvider(
                            state -> state.readEnumOption(SPATIAL_ID, MetalFxConfig.SpatialMode.class)
                                    .isEnabled(),
                            SPATIAL_ID));

            page.addOptionGroup(group);

            // === 高级设置组 — 外部按钮跳转到自定义 Screen ===
            OptionGroupBuilder advancedGroup = builder.createOptionGroup()
                    .setName(Component.translatable("metallum.fx.options.advanced_group"));
            advancedGroup.addOption(builder.createExternalButtonOption(ADVANCED_ID)
                    .setName(Component.translatable("metallum.fx.button.open"))
                    .setTooltip(Component.translatable("metallum.fx.button.open.tooltip"))
                    .setScreenConsumer(MetalFxWarningScreen::openIfNotAcknowledged));
            page.addOptionGroup(advancedGroup);

            builder.registerOwnModOptions()
                    .setName("MetalUniversal")
                    .setVersion(version)
                    .addPage(page);

            Metallum.LOGGER.info("[MetalFX] MetalFX native option page registered in Sodium options successfully");
        } catch (Throwable t) {
            Metallum.LOGGER.error("[MetalFX] Failed to register MetalFX page in Sodium options (F8 hotkey still works)", t);
        }
    }

    private static void saveConfig() {
        MetalFxConfig.save();
    }

    private static Component spatialModeLabel(MetalFxConfig.SpatialMode mode) {
        return Component.translatable(switch (mode) {
            case OFF -> "metallum.fx.spatial.off";
            case QUALITY -> "metallum.fx.spatial.quality";
            case BALANCED -> "metallum.fx.spatial.balanced";
            case PERFORMANCE -> "metallum.fx.spatial.performance";
            case ULTRA_PERFORMANCE -> "metallum.fx.spatial.ultra_performance";
        });
    }

    private static Component temporalModeLabel(MetalFxConfig.TemporalUpscalingMode mode) {
        return Component.translatable(switch (mode) {
            case OFF -> "metallum.fx.temporal.off";
            case AUTO -> "metallum.fx.temporal.auto";
        });
    }

    private static Component interpModeLabel(MetalFxConfig.FrameInterpolationMode mode) {
        return Component.translatable(switch (mode) {
            case OFF -> "metallum.fx.interp.off";
            case AUTO -> "metallum.fx.interp.auto";
            case FORCE_BLEND -> "metallum.fx.interp.force_blend";
        });
    }
}
