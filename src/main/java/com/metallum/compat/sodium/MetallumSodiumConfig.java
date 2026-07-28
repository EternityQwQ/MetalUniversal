package com.metallum.compat.sodium;

import com.metallum.Metallum;
import com.metallum.client.metal.fx.MetalFxWarningScreen;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ExternalPageBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Sodium 视频设置界面集成入口。
 *
 * <p>参照 Iris（IrisShaders/Iris）的 Sodium 集成方式，通过 Sodium 官方
 * {@link ConfigEntryPoint} API 将 Metallum 的 MetalFX 设置注册为 Sodium
 * 视频设置左侧 mod 列表中的一个独立页面。用户点击页面图标即可打开
 * {@link MetalFxWarningScreen}（首次进入会显示适配警告）或直接进入
 * MetalFX 选项界面。
 *
 * <p><b>为什么用 ConfigEntryPoint 而不是 Mixin。</b> 之前的
 * {@code SodiumVideoSettingsScreenMixin} 通过 {@code @Inject} 往 Sodium
 * 的界面注入按钮，但 Sodium 在 0.9.x 用自己的 OptionsScreen 完全替换了
 * 原版 VideoSettingsScreen，导致原版 mixin 失效（按钮不出现）。
 * ConfigEntryPoint 是 Sodium 官方公开的稳定 API，由 Sodium 自身负责
 * 渲染注册的页面，不会因 Sodium 内部重构而失效。
 *
 * <p><b>Sodium 未安装时的行为。</b> 当 Sodium 不在 classpath 中时，
 * {@link ConfigEntryPoint} 类不存在，Fabric Loader 在扫描 {@code sodium}
 * entrypoint 时会捕获 {@code NoClassDefFoundError} 并静默跳过。Metallum
 * 仍通过原版 {@code VideoSettingsScreenMixin} 提供入口按钮，并保留
 * {@code F8} 快捷键作为后备。
 *
 * <p><b>异常容错。</b> 整个注册过程包裹在 try-catch 中。任何 Sodium API
 * 变更导致的异常都会被记录到日志，不会崩溃游戏 — 用户仍可使用
 * {@code F8} 快捷键打开 MetalFX 设置。
 */
@Environment(EnvType.CLIENT)
public final class MetallumSodiumConfig implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        try {
            Metallum.LOGGER.info("[MetalFX] Registering MetalFX page in Sodium options");
            // registerOwnModOptions() 自动从 fabric.mod.json 读取 mod 名称
            // 和版本，无需手动 setName/setVersion。
            ModOptionsBuilder modOptions = builder.registerOwnModOptions();

            // ExternalPage: 点击页面图标时打开 MetalFxWarningScreen（首次）
            // 或 MetalFxOptionsScreen（已确认）。
            ExternalPageBuilder page = builder.createExternalPage();
            page.setName(Component.translatable("metallum.fx.options.title"));
            page.setScreenConsumer(MetalFxWarningScreen::openIfNotAcknowledged);
            modOptions.addPage(page);

            Metallum.LOGGER.info("[MetalFX] MetalFX page registered in Sodium options successfully");
        } catch (Throwable t) {
            // Sodium API 可能在小版本间变化。记录错误但不崩溃 —
            // 用户仍可用 F8 快捷键打开 MetalFX 设置。
            Metallum.LOGGER.error("[MetalFX] Failed to register MetalFX page in Sodium options (F8 hotkey still works)", t);
        }
    }
}
