# MetalUniversal
> 本项目基于 [Metallum](https://github.com/kokodio/metallum) 开发，为原项目的 Fork 迭代版本，在保留原有 Metal 渲染后端能力的基础上，新增了对 iOS 平台的完整支持

MetalUniversal 是一个基于 Apple Metal API 的 Minecraft 渲染后端模组（Fabric Mod），用于在 macOS 和 iOS 上替代 OpenGL/Vulkan 渲染路径，为 Apple Silicon 和 iOS 设备提供更高效的 GPU 渲染。

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

## 运行依赖（前置条件）

| 依赖 | 版本 | 说明 |
|------|------|------|
| Fabric Loader | >= 0.19.2 | **必需** — 模组加载器 |
| Minecraft | 26.2 | **必需** — 目标游戏版本 |
| Java | >= 25 | **必需** — 运行时环境 |
| Sodium | mc26.2-0.9.0-fabric | **可选，强烈推荐** — 提供完整的视频设置界面适配 |

> ### 关于 Sodium
>
> 安装 Sodium 后，MetalFX 设置会作为**独立页面**自动集成到 Sodium 的视频设置界面中（与 Iris 的 "Shader Packs" 页面注册方式一致），由 Sodium 官方 `ConfigEntryPoint` API 提供稳定的 UI 集成，自动获得与 Sodium 原生选项一致的渲染、搜索索引和 tab 排序行为。
>
> **未安装 Sodium 时**，MetalFX 设置入口仍可通过原版视频设置界面右上角的 "MetalFX 设置..." 按钮或全局快捷键 `F8` 打开，功能完全不受影响。

## MetalFX 超分辨率与帧插值

本模组集成了 Apple [MetalFX](https://developer.apple.com/documentation/metalfx) 框架，提供空间超分辨率（Spatial Upscaling）与帧插值（Frame Interpolation）两项 GPU 加速功能，可在支持的设备上显著提升帧率与画面流畅度。

### 功能说明

| 功能 | 说明 |
|------|------|
| **空间超分（Spatial Scaler）** | 在低分辨率渲染游戏画面，再用 MetalFX 专用硬件放大到原生分辨率，降低 GPU 着色负担，提升帧率。提供 Quality / Balanced / Performance / Ultra Performance 四档预设（分别对应 77% / 67% / 56% / 33% 内部渲染分辨率） |
| **帧插值（Frame Interpolator）** | 利用硬件光流估计在两帧之间合成中间帧，将有效帧率翻倍，大幅改善快速移动场景的流畅度 |

### 适配的系统与芯片

以下为 Apple 官方 MetalFX 框架的适配要求。本模组严格遵循官方支持范围，不支持硬件路径的设备将自动禁用对应功能（不会崩溃，仅静默无效）。

#### 空间超分（MTLFXSpatialScaler）

| 平台 | 系统版本 | 芯片要求 |
|------|---------|---------|
| macOS | 13.0+ | Apple GPU family 7+（M1 / A14 及以上） |
| iOS | 16.0+ | Apple GPU family 7+（A14 及以上） |

#### 时间超分（MTLFXTemporalScaler）

| 平台 | 系统版本 | 芯片要求 |
|------|---------|---------|
| macOS | 13.0+ | Apple GPU family 7+（M1 / A14 及以上） |
| iOS | 16.0+ | Apple GPU family 7+（A14 及以上） |

#### 帧插值（MTLFXFrameInterpolator，硬件加速路径）

| 平台 | 系统版本 | 芯片要求 |
|------|---------|---------|
| macOS | 14.0+ | Apple GPU family 9+（M3 及以上） |
| iOS | 17.0+ | Apple GPU family 9+（A17 Pro 及以上） |

> ⚠️ **关于不支持硬件帧插值的设备**：M1 / M2 / A14–A16 等芯片不具备 Apple GPU family 9 的硬件光流加速单元，本模组在这些设备上**不会启用**帧插值。早期版本曾使用 50/50 混合作为回退，但该方案在快速移动的第一人称视角下会产生严重拖影，效果反而不如关闭，已在当前版本移除。

### 使用方法

1. 启动 Minecraft，进入 **视频设置**
   - **安装了 Sodium**：在 Sodium 视频设置左侧 mod 列表中点击 **MetalUniversal** 图标，即可进入 MetalFX 设置页面（与 Iris "Shader Packs" 入口位置一致）
   - **未安装 Sodium**：点击原版视频设置界面右上角的 **"MetalFX 设置..."** 按钮，或随时按 `F8` 快捷键打开
2. 首次进入会弹出**适配警告界面**，列出上述官方系统与芯片要求，请确认您的设备满足条件
3. 点击 **"开启 MetalFX"** 进入设置界面（点击 "不开启" 则返回，下次进入仍会提示）
4. 在设置界面选择空间超分模式、时间超分模式与帧插值模式，点击 **"完成"** 保存

### 设备能力自检

设置界面会自动显示当前设备的支持情况，包括：

- GPU 设备名称
- 空间超分是否支持
- 帧插值是否支持

如果某项功能显示"不支持"，说明当前芯片或系统版本不满足 Apple 官方要求，启用该选项将不会有任何效果。

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
