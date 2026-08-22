**简体中文** | [English](README.md)

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

1. 在 iOS 系统上安装 Minecraft Java Edition 启动器
2. 将 jar 放入 Minecraft 实例的 `mods/` 目录
3. 启动 Minecraft，在视频设置中将图形后端选择为 "Prefer Metal" 重启游戏即可生效

### 注意事项

- `libmetallum.dylib` 和 `libspvc.dylib` 由启动器在运行时加载，无需手动嵌入
- 必须使用 Fabric Loader
- 如遇渲染问题，先尝试禁用其他渲染相关模组

## macOS 使用说明

1. 下载最新 jar 并放入 `mods/` 目录
2. 启动 Minecraft，在视频设置中将图形后端选择为 "Prefer Metal" 重启游戏即可生效

## 许可

MIT License — 详见 [LICENSE](LICENSE)