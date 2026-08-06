package com.metallum;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Metallum implements ModInitializer, PreLaunchEntrypoint {
    public static final String MOD_ID = "metallum";

    // This logger is used to write text to the console and the log file.
    // It is considered best practice to use your mod id as the logger's name.
    // That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onPreLaunch() {
        // PreLaunch 是 Fabric Loader 提供的最早入口点，在游戏启动之前调用，
        // 早于任何 Minecraft 类（包括 VulkanBackend、GlBackend、MetalBackend）被加载。
        // 必须在这里设置 Configuration.SPVC_LIBRARY_NAME，因为 LWJGL 的 Spvc.SPVC 是
        // static final 字段，类初始化时一次性读取配置并缓存，之后修改无效。
        // 如果等到 onInitialize 或 MetalBackend.createDevice，Spvc 类可能已被
        // VulkanBackend 的类加载触发初始化，配置就来不及了。
        // 非 iOS 环境下此方法立即返回（isIOS() 检查）。
        MetalNativeBridge.ensureSpvcLibraryConfigured();
    }

    @Override
    public void onInitialize() {
        // Sodium 0.8.x（1.21.11 时代）直接调用 GL API，无后端抽象（0.9.x 的
        // DrawBackend/VK_INDIRECT 体系是 26.x 才有的）：Metal 后端下 Sodium 无法工作。
        // macOS/iOS 上 Sodium 本身也不受支持，这里仅提示，不阻断启动。
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")) {
            LOGGER.warn("MetalUniversal 检测到 Sodium：1.21.11 的 Sodium（0.8.x）直接调用 GL API，"
                    + "与 Metal 后端互斥（无后端抽象可注入），在 Metal 主机上可能出现黑屏或崩溃，建议移除。");
        }

        // 自更新：daemon 线程经 GitHub Actions artifact 检查新版并自动替换（重启生效）
        MetallumSelfUpdater.startIfNeeded();
    }
}