**English** | [简体中文](README_zh-CN.md)

# MetalUniversal
> This project is developed from [Metallum](https://github.com/kokodio/metallum), as a fork iteration of the original project. It preserves the original Metal rendering backend capabilities while adding full support for the iOS platform.

Metallum is a Minecraft rendering backend mod (Fabric Mod) based on the Apple Metal API, designed to replace the OpenGL/Vulkan rendering path on macOS and iOS, providing more efficient GPU rendering for Apple Silicon and iOS devices.

This project is still in the experimental stage (PoC); performance and stability may vary depending on the system and installed mods.

## Architecture

| Layer | Implementation |
|-------|----------------|
| Entry point | `com.metaluniversal.MetalUniversal` (PreLaunch + ModInitializer) |
| GPU backend | `MetalBackend` → `MetalDevice` → `MetalCommandEncoder` / `MetalRenderPass` |
| Shader compiler | `MetalCrossShaderCompiler` (GLSL/SPIR-V → MSL, based on SPIRV-Cross) |
| Native bridge | `MetalNativeBridge` (Java Foreign Memory API ↔ Swift C exported functions) |
| Native implementation | `MetalUniversalNative.swift` (Metal API calls, CAMetalLayer management, inline MSL shaders) |
| Mod injection | Mixin injection into Minecraft `PreferredGraphicsApi` and Sodium rendering backend selection |

## Compatibility

- **macOS**: Apple Silicon (M1 or later), loads `libmetallum.dylib` directly through the Native Bridge
- **iOS**: iOS 14.0 or later, ships prebuilt `libmetallum.dylib` (arm64) and `libspvc.dylib` (with MSL backend) inside the jar

## Building

### Prerequisites

- macOS (Apple Silicon)
- Xcode (with iOS SDK, for iOS targets)
- Java 25
- Swift compiler (`swiftc`)

### Build Commands

```bash
# Full build (macOS native + iOS native + iOS libspvc)
./gradlew build

# Compile macOS native dylib only
./gradlew buildMacNative

# Compile iOS native dylib only (requires Xcode + iOS SDK)
./gradlew buildIOSNative

# Compile iOS libspvc only (SPIRV-Cross MSL backend, requires Xcode + iOS SDK)
./gradlew buildIOSSpvc
```

Build artifacts:
- `src/main/resources/natives/macos/libmetallum.dylib` — macOS arm64, target 14.0
- `src/main/resources/natives/ios/libmetallum.dylib` — iOS arm64, target 14.0
- `src/main/resources/natives/ios/libspvc.dylib` — SPIRV-Cross C API (MSL backend), iOS arm64

### CI/CD

The GitHub Actions workflow (`.github/workflows/build.yml`) builds on `macos-15`, and automatically publishes to Modrinth and GitHub Releases when a `v*` tag is pushed.

## iOS Usage

1. Install the Minecraft Java Edition launcher on iOS
2. Place the jar into the Minecraft instance's `mods/` directory
3. Launch Minecraft, select "Prefer Metal" as the graphics backend in video settings, then restart the game for the change to take effect

### Notes

- `libmetallum.dylib` and `libspvc.dylib` are loaded at runtime by the launcher; no manual embedding is required
- Fabric Loader is required
- If you encounter rendering issues, try disabling other rendering-related mods first

## macOS Usage

1. Download the latest jar and place it in the `mods/` directory
2. Launch Minecraft, select "Prefer Metal" as the graphics backend in video settings, then restart the game for the change to take effect

## License

MIT License — see [LICENSE](LICENSE)