# MetalUniversal (1.12.2 Forge 移植版)

> 本仓库是 [MetalUniversal](https://github.com/EternityQwQ/MetalUniversal)（Fabric / Minecraft 1.21+ / Java 25）向 **Minecraft 1.12.2 + Forge + Java 8** 的完整移植。原项目基于 Apple Metal API，在 macOS / iOS 上替代 OpenGL/Vulkan 渲染路径，为 Apple Silicon 与 iOS 设备提供原生 GPU 渲染。

本移植保留了原项目的核心架构 —— **GL-over-Metal 翻译层**，并将 Fabric/Mixin 0.8 的接入方式适配为 Forge 1.12.2 的 Coremod + Mixin 0.8.5 方案，使其能加载并运行于 1.12.2，同时兼容绝大多数模组（含 OptiFine）。

## 移植要点

| 原项目 (Fabric 1.21+) | 本移植 (Forge 1.12.2) |
|---|---|
| Java 25 + FFM (Panama) Memory API | Java 8 + JNI `native` 方法桥接 `libmetallum.dylib` |
| Mixin 0.8 (Fabric Loader) | Mixin 0.8.5（通过 `IFMLLoadingPlugin` Coremod 引导，打包进 jar） |
| `GlStateManager` / Sodium / Iris 钩子 | `GlStateManager` / `Tessellator` / `EntityRenderer` / `RenderGlobal` / `Framebuffer` / `Minecraft` / `OpenGlHelper` 钩子 |
| MetalFX（按用户要求跳过，iOS 不支持） | 已移除 MetalFX 相关类与配置 |

## 架构

```
com.metallum
├─ Metallum.java                      模组主类（@Mod，FML 生命周期事件）
├─ client.metal
│  ├─ MetalConfig.java                配置（HUD / OptiFine 兼容开关）
│  ├─ MetalRuntime.java               运行时：检测平台、加载 dylib、激活翻译层
│  ├─ gl.GlMetalTranslator.java       ★ GL→Metal 翻译层（架构核心）
│  └─ render/
│     ├─ MetalDevice.java             MTLDevice 封装（含 Unsafe 指针工具）
│     ├─ MetalGpuBuffer/Texture/Sampler  资源封装
│     ├─ MetalCommandEncoder.java     帧提交 / 信号量同步
│     ├─ MetalRenderPass.java         渲染通道
│     ├─ MetalDestructionQueue.java   延迟释放队列
│     ├─ Stats.java                   内存/绘制统计
│     ├─ bridge/MetalNativeBridge.java  ★ JNI 桥（所有 native 方法）
│     ├─ bridge/NativePointer.java    long 句柄封装
│     └─ mtl/*.java                   MTL 枚举（PixelFormat/CompareFunction/...）
├─ coremod/MetallumCoremod.java       IFMLLoadingPlugin：注册 Mixin 引导
└─ mixin/
   ├─ MetallumMixinConfigPlugin.java  IMixinConfigPlugin：按平台/OptiFine 选择性应用
   └─ client/*.java                   8 个 Mixin（GlStateManager/Tessellator/.../OptiFineCompat）
```

### GL-over-Metal 翻译层（核心）

1.12.2 没有可插拔的渲染后端：OpenGL 调用散落在 `GlStateManager`、`Tessellator`、`EntityRenderer` 以及每个模组（含 OptiFine）自己的渲染器里。本移植不重写渲染管线，而是在 **GL 调用边界** 拦截状态与绘制调用，翻译为 Metal 命令缓冲区：

- **状态镜像**：`GlStateManagerMetalMixin` 把 blend/depth/cull/viewport/纹理绑定等状态镜像进 `GlMetalTranslator`。
- **绘制捕获**：`TessellatorMetalMixin` / `RenderGlobalMetalMixin` 在 `draw()` / `renderBlockLayer` 前把 `BufferBuilder` 暂存的顶点/索引字节上传为 Metal buffer，再翻译绘制调用。
- **帧目标**：`FramebufferMetalMixin` 把 FBO 绑定切换为 Metal 渲染目标（含 OptiFine 自定义 draw buffer）。
- **主循环**：`MinecraftMetalMixin` / `EntityRendererMetalMixin` 标注帧边界，`MetalCommandEncoder` 提交命令缓冲区并通过信号量做 in-flight 同步。

因为拦截发生在 **GL 之下**，OptiFine 与绝大多数模组的 GL 调用会被自动路由进 Metal，无需逐模组适配。

### 兼容性策略

- **非 Metal 平台**（Windows/Linux）：`MetalRuntime.bootstrap()` 是 no-op，所有 Mixin 经 `MetallumMixinConfigPlugin` 拒绝应用，模组零影响，vanilla OpenGL 路径不变 —— 可安全随任何整合包分发。
- **OptiFine**：`OptiFineCompatMixin` 在 `OpenGlHelper.initializeTextures` 后报告 GL 2.1+ 能力，避免 OptiFine 着色器路径在 Metal 后端下被提前禁用；其余 GL 调用由翻译层透明承载。
- **Mixin refmap**：构建时由 MixinGradle 注解处理器生成 `metallum.refmap.json`，含 MCP→SRG (`func_*`) 映射，确保 Mixin 在**生产环境混淆版 MC** 中同样生效。

## 构建

要求 **JDK 8**（推荐 Temurin 8u422+）与联网（首次需下载 MC/Forge/MCP）。

```bash
./gradlew build
```

产物：
- `build/libs/MetalUniversal-1.12.2-<version>-all.jar` —— **最终可发布 jar**（含 reobf 后的模组类 + 内置 Mixin 0.8.5 + ASM 9.2 + 资源 + refmap + coremod 清单）
- `build/libs/MetalUniversal-1.12.2-<version>.jar` —— 仅 reobf 模组类（不含 Mixin/ASM 运行时）

### 构建说明

- Forge 版本锁定为 `14.23.5.2847`：2860 在 Forge maven 上不发布 `forge-*-userdev.jar`（ForgeGradle 2.3 必需），2847 是最后一个发布 userdev 的 1.12.2 构建，功能等价。
- Mixin + ASM 以 `embed` 依赖打包进最终 jar（1.12.2 Forge 不自带 Mixin 运行时）。
- `mergeEmbedded` 任务在 `reobfJar` 之后合并 embed 作用域，规避 ForgeGradle 2.3 旧版 ASM 读取 ASM 9.2 类时的 `ClassReader` 崩溃。

## 原生库（运行时必需）

本模组仅包含 Java 侧；实际 Metal 调用通过 JNI 进入 `libmetallum.dylib`（Swift，复用原项目 `src/main/native/*.swift`，与 MC 版本无关）。运行前需将该 dylib 置于 jar 内 `/natives/macos/`（macOS）或由宿主启动器提供签名 framework（iOS，因代码签名限制不能从可写目录加载未签名 dylib）。在非 Metal 平台上即使无 dylib，模组也仅保持不激活状态，不影响游戏。

## 致谢

- 原始 Metallum / MetalUniversal Fabric 实现：[kokodio](https://github.com/kokodio/metallum)、EternityQwQ、yitenchen123
- 1.12.2 Forge 移植：linfuhui272722

本项目仍处于实验性阶段（PoC），性能与稳定性可能因系统和安装 Mod 而异。

## 架构

| 层级 | 实现 |
|------|------|
| 入口点 | `com.metaluniversal.MetalUniversal`（PreLaunch + ModInitializer） |
| GPU 后端 | `MetalBackend` → `MetalDevice` → `MetalCommandEncoder` / `MetalRenderPass` |
| 着色器编译器 | `MetalCrossShaderCompiler`（GLSL/SPIR-V → MSL，基于 SPIRV-Cross） |
| 原生桥接 | `MetalNativeBridge`（Java Foreign Memory API ↔ Swift C 导出函数） |
| 原生实现 | `MetalUniversalNative.swift`（Metal API 调用、CAMetalLayer 管理、MSL 内联着色器） |
| 模组注入 | Mixin 注入 Minecraft `PreferredGraphicsApi` 和 Sodium 渲染后端选择 |

## 兼容性

- **macOS**：Apple Silicon（M1 或更新），通过 Native Bridge 直接加载 `libmetallum.dylib`
- **iOS**：iOS 14.0 或更高版本，预编译 `libmetallum.dylib`（arm64）和 `libspvc.dylib`（带 MSL 后端）内置于 jar 中

## 构建

### 前置条件

- macOS（Apple Silicon）
- Xcode（含 iOS SDK，用于 iOS 目标）
- Java 25
- Swift 编译器（`swiftc`）

### 构建命令



```bash
# 完整构建（macOS 原生 + iOS 原生 + iOS libspvc）
./gradlew build

# 仅编译 macOS 原生 dylib
./gradlew buildMacNative

# 仅编译 iOS 原生 dylib（需要 Xcode + iOS SDK）
./gradlew buildIOSNative

# 仅编译 iOS libspvc（SPIRV-Cross MSL 后端，需要 Xcode + iOS SDK）
./gradlew buildIOSSpvc
```

构建产物：
- `src/main/resources/natives/macos/libmetallum.dylib` — macOS arm64, target 14.0
- `src/main/resources/natives/ios/libmetallum.dylib` — iOS arm64, target 14.0
- `src/main/resources/natives/ios/libspvc.dylib` — SPIRV-Cross C API（MSL 后端），iOS arm64

### CI/CD

GitHub Actions 工作流（`.github/workflows/build.yml`）在 `macos-15` 上构建，推送带 `v*` tag 时自动发布到 Modrinth 和 GitHub Releases。

## iOS 使用说明

1. 在IOS系统上安装Minecraft Java Edition启动器
2. 将 Metallum jar 放入 Minecraft 实例的 `mods/` 目录
3. 启动 Minecraft，在视频设置中将图形后端选择为 "Prefer Metal"重启游戏即可生效
### 注意事项

- `libmetallum.dylib` 和 `libspvc.dylib` 由启动器在运行时加载，无需手动嵌入
- 必须使用 Fabric Loader
- 如遇渲染问题，先尝试禁用其他渲染相关模组

## macOS 使用说明

1. 下载最新 Metallum jar 并放入 `mods/` 目录
2. 启动 Minecraft，在视频设置中将图形后端选择为 "Prefer Metal"重启游戏即可生效


## 许可

MIT License — 详见 [LICENSE](LICENSE)
