package com.metallum.compat.sodium;

import com.metallum.Metallum;
import com.metallum.client.metal.fx.MetalFxWarningScreen;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Sodium 视频设置界面集成入口。
 *
 * <p>参照 Iris（IrisShaders/Iris）的 Sodium 集成方式，通过 Sodium 官方
 * {@link ConfigEntryPoint} API 将 Metallum 的 MetalFX 设置注册为 Sodium
 * 视频设置左侧 mod 列表中的一个独立页面。用户点击页面图标即可打开
 * {@link MetalFxWarningScreen}（首次进入会显示适配警告）或直接进入
 * MetalFX 选项界面。
 *
 * <p><b>为什么用 ConfigEntryPoint 而不是 Mixin。</b> Sodium 0.9.x 用自己的
 * OptionsScreen 完全替换了原版 VideoSettingsScreen，原版 Mixin 注入失效。
 * ConfigEntryPoint 是 Sodium 官方公开的稳定 API，由 Sodium 自身负责
 * 渲染注册的页面。
 *
 * <p><b>entrypoint 名称。</b> 必须在 fabric.mod.json 中注册为
 * {@code "sodium:config_api_user"}（不是 {@code "sodium"}），Sodium 才会
 * 扫描并调用 {@link #registerConfigLate}。这是之前页面不出现的根因。
 *
 * <p><b>异常容错。</b> 整个注册过程包裹在 try-catch 中，Sodium API 变更时
 * 不会崩溃游戏，用户仍可用 {@code F8} 快捷键打开 MetalFX 设置。
 */
@Environment(EnvType.CLIENT)
public final class MetallumSodiumConfig implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        try {
            Metallum.LOGGER.info("[MetalFX] Registering MetalFX page in Sodium options");
            // 链式调用，参照 Iris 的 IrisConfig.registerConfigLate 写法。
            // registerOwnModOptions() 返回的 builder 用于设置 mod 显示名、
            // 版本并添加页面。addPage 接收一个 ExternalPage，点击后通过
            // setScreenConsumer 打开 MetalFxWarningScreen（首次）或
            // MetalFxOptionsScreen（已确认）。
            String version = FabricLoader.getInstance()
                    .getModContainer(Metallum.MOD_ID)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
            builder.registerOwnModOptions()
                    .setName("MetalUniversal")
                    .setVersion(version)
                    .addPage(builder.createExternalPage()
                            .setName(Component.translatable("metallum.fx.options.title"))
                            .setScreenConsumer(MetalFxWarningScreen::openIfNotAcknowledged));
            Metallum.LOGGER.info("[MetalFX] MetalFX page registered in Sodium options successfully");
        } catch (Throwable t) {
            Metallum.LOGGER.error("[MetalFX] Failed to register MetalFX page in Sodium options (F8 hotkey still works)", t);
        }
    }
}
