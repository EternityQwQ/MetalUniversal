package com.metallum.compat.sodium;

import com.metallum.client.metal.fx.MetalFxWarningScreen;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ExternalPageBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Sodium 视频设置界面集成入口，参照 Iris 的实现方式，通过 Sodium 官方
 * {@link ConfigEntryPoint} API 将 Metallum 的 MetalFX 设置注册为 Sodium
 * 视频设置中的一个独立 mod 页面。
 *
 * <p>用户在 Sodium 视频设置界面点击 Metallum 图标即可打开
 * {@link MetalFxWarningScreen}（首次进入会显示适配警告）或直接进入
 * MetalFX 选项界面。这与 Iris 注册 "Shader Packs" 外部页面的方式完全
 * 一致。
 *
 * <p><b>为什么用 ConfigEntryPoint 而不是 Mixin。</b> 之前的
 * {@code SodiumVideoSettingsScreenMixin} 通过 {@code @Inject} 往 Sodium
 * 的 {@code VideoSettingsScreen.init} 尾部注入一个按钮。这种做法脆弱：
 * Sodium 内部 UI 结构（tab 布局、搜索框位置、footer 面板）在任何小版本
 * 都可能变化，注入点一旦失效就会崩溃整个视频设置屏幕，导致用户无法调整
 * 任何视频选项。ConfigEntryPoint 是 Sodium 官方公开的稳定 API，由
 * Sodium 自身负责渲染注册的页面，自动获得与原生选项一致的渲染、搜索
 * 索引和 tab 排序行为，不会因 Sodium 内部重构而失效。
 *
 * <p><b>Sodium 未安装时的行为。</b> 当 Sodium 不在 classpath 中时，
 * {@link ConfigEntryPoint} 类不存在，Fabric Loader 在扫描 {@code sodium}
 * entrypoint 时会捕获 {@code NoClassDefFoundError} 并静默跳过注册，不
 * 影响游戏运行。Metallum 仍通过原版 {@code VideoSettingsScreenMixin}
 * 在原版视频设置界面右上角提供 MetalFX 入口按钮，并保留 {@code F8}
 * 快捷键作为后备。
 *
 * <p><b>页面内容。</b> 使用 {@link ExternalPageBuilder} 而非
 * {@code OptionPageBuilder}，因为 MetalFX 的设置界面已经以
 * {@code MetalFxOptionsScreen} 独立实现（包含设备能力自检、适配警告、
 * 三个 CycleButton 和持久化逻辑），无需在 Sodium 内嵌重新实现一遍控件。
 * ExternalPage 允许我们指定一个 {@code Consumer<Screen>}，点击页面图标
 * 时打开我们的屏幕 — 与 Iris 打开 {@code ShaderPackScreen} 的模式一致。
 */
@Environment(EnvType.CLIENT)
public final class MetallumSodiumConfig implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        // registerOwnModOptions() 自动从 fabric.mod.json 读取 mod 名称
        // ("MetalUniversal") 和版本，无需手动 setName/setVersion。
        ModOptionsBuilder modOptions = builder.registerOwnModOptions();

        // ExternalPage: 点击页面图标时通过 setScreenConsumer 打开我们
        // 的 MetalFxWarningScreen（首次）或 MetalFxOptionsScreen（已确认）。
        // 方法引用 MetalFxWarningScreen::openIfNotAcknowledged 的签名
        // (Screen) -> void 正好匹配 Consumer<Screen>。
        ExternalPageBuilder page = builder.createExternalPage();
        page.setName(Component.translatable("metallum.fx.options.title"));
        page.setScreenConsumer(MetalFxWarningScreen::openIfNotAcknowledged);
        modOptions.addPage(page);
    }
}
