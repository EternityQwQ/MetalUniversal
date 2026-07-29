# Spec: 修复 MetalFX 三个渲染缺陷（33% 冻结 / 时间超分关闭后 GUI 闪烁 / 4:3 零尺寸崩溃）

## 0. 背景

测试设备：Mac mini M1，显示器物理分辨率 3840×2160。
测试人员在多种系统分辨率 × 空间超分档位 × 时间超分开关的组合下，反馈了三个独立缺陷。
当前代码位于 `feature/metalfx-upscale-frameinterp` 分支。

### 空间超分档位与渲染缩放比

| 档位 | 枚举 | renderScale | 放大倍数 (1/scale) |
|------|------|-------------|---------------------|
| 关闭 | OFF | 1.0 | 1.0x |
| 质量 | QUALITY | 0.77 | 1.30x |
| 平衡 | BALANCED | 0.67 | 1.49x |
| 性能 | PERFORMANCE | 0.56 | 1.79x |
| 极致性能 | ULTRA_PERFORMANCE | 0.33 | **3.03x** |

MTLFXSpatialScaler / MTLFXTemporalScaler 的硬性限制：**输出尺寸 ≤ 3 × 输入尺寸**（每轴独立）。

---

## 1. 问题一：极致性能 33% 时画面冻结

### 1.1 现象

- 空间超分设为极致性能 33%，时间超分任意
- 游戏逻辑（tick、输入、声音）继续正常运行
- 渲染画面完全冻结（停在上电后第一帧或黑屏）
- 重启后可能黑屏（因为配置持久化在 `config/metallum_fx.properties`，重启后 33% 仍然生效）
- 56% / 67% / 77% 档位均正常

### 1.2 根因分析

**根因 A：33% 放大倍数超出 MTLFXSpatialScaler 的 3x 上限**

[MetalSurface.applyInternalResolution()](file:///workspace/src/main/java/com/metallum/client/metal/render/MetalSurface.java#L156-L192) 按下式计算内部渲染分辨率：

```java
targetWidth  = Math.max(1, Math.round(this.displayWidth  * scale));
targetHeight = Math.max(1, Math.round(this.displayHeight * scale));
```

当 `scale = 0.33` 时（以 3840×2160 显示器为例）：

| 维度 | displayWidth | internalWidth | 放大倍数 | 是否 ≤ 3x |
|------|-------------|---------------|---------|----------|
| 宽 | 3840 | round(3840×0.33) = 1267 | 3840/1267 = **3.03** | ❌ 超限 |
| 高 | 2160 | round(2160×0.33) = 713  | 2160/713  = **3.03** | ❌ 超限 |

Swift 侧 [metallum_fx_create_spatial_scaler](file:///workspace/src/main/native/MetallumNative.swift#L1828-L1882) 调用 `descriptor.makeSpatialScaler(device:)`，当 output > 3×input 时该方法抛出异常。`try?` 将其吞掉并返回 `nil`，Java 侧得到 `MemorySegment.NULL`（地址为 0 但 **非 Java null**）。

**根因 B：`MemorySegment.NULL` 被误判为有效的 scaler 句柄**

[MetalFxPipeline.ensureSpatialScaler()](file:///workspace/src/main/java/com/metallum/client/metal/fx/MetalFxPipeline.java#L527-L556) 的缓存检查：

```java
if (spatialScaler != null          // ← MemorySegment.NULL != null → true！
        && cachedInputWidth == inW && cachedInputHeight == inH
        && cachedOutputWidth == outW && cachedOutputHeight == outH) {
    return;  // ← 跳过重建，spatialScaler 永远停留在 NULL
}
```

`MemorySegment.NULL` 是 `MemorySegment.ofAddress(0L)`，是一个非 null 的 Java 对象。`spatialScaler != null` 对其求值为 **true**。因此一旦创建失败（spatialScaler = NULL），后续每一帧都跳过重建，scaler 永远为 NULL。

同样的缺陷存在于 `ensureTemporalScaler()`、`encodeSpatialFallback()` 以及帧插值路径中所有 `!= null` 的 scaler 句柄检查。全量列举：

| 位置 | 当前检查 | 应改为 |
|------|---------|--------|
| `ensureSpatialScaler` L528 | `spatialScaler != null` | `!isNullHandle(spatialScaler)` |
| `ensureTemporalScaler` L486 | `temporalScaler != null` | `!isNullHandle(temporalScaler)` |
| `encodeSpatialFallback` L462 | `spatialScaler != null` | `!isNullHandle(spatialScaler)` |
| `maybeEncode` L329 | `frameInterpolator == null` | `isNullHandle(frameInterpolator)` |
| `maybeEncode` L334 | `frameInterpolator != null` | `!isNullHandle(frameInterpolator)` |
| `maybeEncode` L235 | `temporalScaler != null` | `!isNullHandle(temporalScaler)` |

**根因 C：scaler 创建失败后仍返回未写入的 upscaledColorTexture**

[encodeSpatialFallback()](file:///workspace/src/main/java/com/metallum/client/metal/fx/MetalFxPipeline.java#L454-L483)：

```java
if (spatialScaler != null && upscaledColorTexture != null) {
    // spatialScaler 是 MemorySegment.NULL → 进入此分支
    MetalNativeBridge.metallum_fx_spatial_scaler_encode(
        spatialScaler, ...);  // ← Swift 侧 as? MTLFXSpatialScaler 对 null 指针返回 nil → 无操作
    return upscaledColorTexture;  // ← 返回从未被写入的纹理！
}
return sourceTexture;  // ← 永远不会到达
```

Swift 侧 [metallum_fx_spatial_scaler_encode](file:///workspace/src/main/native/MetallumNative.swift#L1884-L1908)：

```swift
if #available(macOS 13.0, iOS 16.0, *), let scaler = scaler as? MTLFXSpatialScaler {
    // scaler 是 null 指针 → as? 转换返回 nil → 整个 if 体不执行
    scaler.encode(commandBuffer: commandBuffer)  // ← 永远不执行
}
```

结果：`upscaledColorTexture`（`MTLStorageMode.Private`，初始内容未定义）被直接 blit 到 drawable，画面冻结在垃圾数据或上一帧的残留上。

### 1.3 修复方案

**修复 A：钳制内部分辨率，确保放大倍数 ≤ 3x**

在 [MetalSurface.applyInternalResolution()](file:///workspace/src/main/java/com/metallum/client/metal/render/MetalSurface.java#L156-L192) 中，计算 `targetWidth/targetHeight` 后追加钳制：

```java
// MTLFXSpatialScaler / MTLFXTemporalScaler 要求 output ≤ 3 × input（每轴）。
// 当 renderScale 过低（如 33% → 3.03x）时，钳制到恰好 1/3，使放大倍数 = 3.0x。
int minInputWidth  = (int) Math.ceil(this.displayWidth  / 3.0);
int minInputHeight = (int) Math.ceil(this.displayHeight / 3.0);
targetWidth  = Math.max(targetWidth,  minInputWidth);
targetHeight = Math.max(targetHeight, minInputHeight);
```

效果：33% 档位实际渲染分辨率从 1267×713 提升到 1280×720（3840×2160 显示器），放大倍数从 3.03x 降至 3.0x，恰好满足限制。FPS 收益几乎不受影响（内部分辨率仅增大约 1%）。

**修复 B：将所有 scaler 句柄的 `!= null` 检查替换为 `!MetalNativeBridge.isNullHandle()`**

涉及 `MetalFxPipeline` 中所有引用 `spatialScaler`、`temporalScaler`、`frameInterpolator` 的空值检查（见 1.2 根因 B 表格）。

**修复 C：scaler 创建失败后不要缓存失败结果**

在 `ensureSpatialScaler` / `ensureTemporalScaler` 中，如果 `metallum_fx_create_*` 返回 NULL，**不更新** `cachedInputWidth/Height/OutputWidth/OutputHeight`，使下一帧重试。同时设置 `spatialScaler = null`（Java null，而非 MemorySegment.NULL），确保 `encodeSpatialFallback` 的 `!isNullHandle(spatialScaler)` 检查失败，回退到 `return sourceTexture`（低分辨率直接呈现，优于冻结）。

---

## 2. 问题二：时间超分关闭后 GUI 按钮闪烁 / 无法正常显示

### 2.1 现象

- 空间超分设为 56% / 67% / 77% 任意档位，时间超分设为「自动」→ 一切正常
- 将时间超分改为「关闭」（空间超分保持不变）→ 游戏内 GUI（HUD、按钮、菜单）闪烁 / 无法正常显示
- 重新开启时间超分 → 恢复正常

### 2.2 根因分析

**根因 A：空间超分缺乏时域稳定性，GUI 高频内容产生帧间像素抖动**

当时间超分关闭、空间超分开启时，[MetalFxPipeline.maybeEncode()](file:///workspace/src/main/java/com/metallum/client/metal/fx/MetalFxPipeline.java#L298-L302) 走空间超分路径（`encodeSpatialFallback`）。MTLFXSpatialScaler 是单帧放大器——每帧独立处理，无时域历史累积。对于静态 UI 元素（文字边缘、按钮边框等高频内容），逐帧独立放大可能产生亚像素级的位置/亮度差异，表现为像素级抖动和闪烁。

时间超分（MTLFXTemporalScaler）通过时域历史累积消除这种逐帧差异，所以时间超分开启时 GUI 稳定。

**根因 B：GUI 被渲染到低分辨率源纹理中，再被超分放大**

[WindowMixin](file:///workspace/src/main/java/com/metallum/mixin/render/WindowMixin.java#L95-L111) 在空间超分激活时同时缩减 `getWidth/getHeight`（渲染目标尺寸）和 `getGuiScaledWidth/getGuiScaledHeight`（GUI 坐标空间）。Minecraft 将 3D 场景和 GUI 渲染到同一个主渲染目标（低分辨率），然后 MetalFX 将整帧（3D + GUI）一起放大。

当时间超分关闭时，空间超分的逐帧差异直接作用于 GUI 像素，导致闪烁。

### 2.3 修复方案

**方案：时间超分关闭时，将 GUI 渲染推迟到 MetalFX 放大之后（独立全分辨率绘制）**

这是测试人员建议的方向：「时间超分关闭时重新处理 GUI 渲染顺序」。

核心思路：当空间超分激活且时间超分关闭时，3D 场景仍以低分辨率渲染 + 空间超分放大，但 GUI 不再渲染到低分辨率源纹理中，而是在放大完成后以全分辨率绘制到放大纹理之上。

实现步骤：

1. **WindowMixin 条件化 GUI 缩放**

   [WindowMixin.metallum$scale()](file:///workspace/src/main/java/com/metallum/mixin/render/WindowMixin.java#L118-L131)：当时间超分关闭（`!cfg.isTemporalUpscalingActive()`）且空间超分开启时，**不缩减** `getGuiScaledWidth/getGuiScaledHeight`，使 GUI 坐标空间保持全分辨率。`getWidth/getHeight` 仍然缩减（3D 场景继续低分辨率渲染）。

   需要拆分 `metallum$scale` 为两个方法：`metallum$scaleFramebuffer`（缩减）和 `metallum$scaleGui`（仅在时间超分开启时缩减），分别注入到 `getWidth/getHeight` 和 `getGuiScaledWidth/getGuiScaledHeight`。

2. **upscaledColorTexture 添加 RenderTarget 用法**

   [MetalFxPipeline.ensureUpscaledTexture()](file:///workspace/src/main/java/com/metallum/client/metal/fx/MetalFxPipeline.java#L558-L578)：当时间超分关闭时，纹理 usage 追加 `MTLTextureUsage.RenderTarget.value`，使 GUI 可以直接渲染到放大后的纹理上。

3. **延迟 GUI 渲染到放大之后**

   添加 mixin 拦截 `GameRenderer.render()` 的 GUI 绘制阶段。当空间超分激活且时间超分关闭时：
   - 在 `renderLevel()` 之后、`renderGui()` 之前，将 MetalFX 放大结果 blit 到一个全分辨率中间纹理
   - 以该中间纹理为渲染目标执行 `renderGui()`（全分辨率）
   - 将带有 GUI 的中间纹理呈现到 drawable

   这需要 `MetalSurface` / `MetalCommandEncoder` 提供一个回调或钩子，使 GUI 渲染可以插入到 `maybeEncode` 之后、`encodePresentTextureToDrawable` 之前。

4. **回退保障**

   如果上述延迟 GUI 渲染的实现过于复杂或在某些路径下不可行，则保留当前行为（GUI 随 3D 一起放大），但在 `upscaledColorTexture` 每次空间超分 encode 前清零，消除残留数据导致的闪烁。并修复问题一的 `MemorySegment.NULL` 缺陷，确保空间超分 scaler 在切换过程中始终有效运行。

### 2.4 影响范围

| 文件 | 改动 |
|------|------|
| `WindowMixin.java` | 拆分 GUI 缩放与帧缓冲缩放；时间超分关闭时不缩减 GUI |
| `MetalFxPipeline.java` | `ensureUpscaledTexture` 按需追加 RenderTarget 用法 |
| `MetalCommandEncoder.java` | `presentTextureToDrawable` 支持在放大后、呈现前回调 GUI 渲染 |
| `MetalSurface.java` | 管理全分辨率 GUI 中间纹理（或复用 upscaledColorTexture） |
| 新增 mixin | 拦截 `GameRenderer.render()` 的 GUI 阶段 |

---

## 3. 问题三：4:3 非标准比例 + 时间超分 → Metal 零尺寸纹理崩溃

### 3.1 现象

- 系统分辨率设为 1280×960（4:3 比例）
- 开启时间超分（空间超分任意档位）
- 立即崩溃，报错：`MTLTextureDescriptor has width of zero. MTLTextureDescriptor has height of zero.`
- 16:9 分辨率（1280×720、1920×1080、2560×1440、3840×2160）均正常

### 3.2 根因分析

**根因 A：Swift 侧 `metallum_create_texture_2d` 无零尺寸校验**

[metallum_create_texture_2d](file:///workspace/src/main/native/MetallumNative.swift#L846-L890) 直接将 `width/height` 传入 `MTLTextureDescriptor.texture2DDescriptor`：

```swift
let descriptor = MTLTextureDescriptor.texture2DDescriptor(
    pixelFormat: pixelFormat,
    width: Int(width),    // ← 如果为 0，makeTexture 会触发 Metal 验证崩溃
    height: Int(height),  // ← 如果为 0，同上
    mipmapped: mipLevels > 1
)
```

当 width 或 height 为 0 时，`device.makeTexture(descriptor:)` 抛出 `MTLTextureDescriptor has width/height of zero` 验证错误，这是一个 **不可捕获的 Metal 验证断言**（在 release 构建中也直接终止进程），`try?` 无法拦截。

**根因 B：时间超分路径中存在零尺寸纹理创建的路径**

时间超分激活时，[MetalFxPipeline.maybeEncode()](file:///workspace/src/main/java/com/metallum/client/metal/fx/MetalFxPipeline.java#L228-L302) 会创建以下纹理：

1. `ensureUpscaledTexture(outputWidth, outputHeight)` — 输出尺寸
2. `ensureTemporalMotionTexture(sourceWidth, sourceHeight)` — 源尺寸
3. `ensureTemporalScaler` 内部由 `makeTemporalScaler` 创建的隐含纹理

`sourceWidth/sourceHeight` 来自 [MetalCommandEncoder.presentTextureToDrawable()](file:///workspace/src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java#L268) 的 `source.getWidth(0), source.getHeight(0)`。`outputWidth/outputHeight` 来自 `MetalSurface.outputWidth()/outputHeight()`。

在 4:3 窗口 + 16:9 显示器的组合下，可能在特定时序下（窗口刚创建 / 最小化 / framebuffer 尚未就绪）GLFW 返回的 framebuffer 尺寸或 Minecraft 渲染目标尺寸为 0，而代码中缺少对这两个值的零值校验就直接传入纹理创建函数。

虽然 `MetalSurface.configure()` 有 `config.width() <= 0` 的检查，但 `glfwGetFramebufferSize` 的返回值仅在 `> 0` 时才被使用，且 Minecraft 主渲染目标的实际尺寸可能因 WindowMixin 的缩减逻辑和窗口尺寸变化的时序差而短暂为 0。

`metallum_fx_create_temporal_scaler` 同样无零尺寸校验：`makeTemporalScaler` 在 inputWidth/inputHeight 为 0 时会内部创建零尺寸纹理并触发相同的 Metal 验证崩溃。

### 3.3 修复方案

**修复 A：Swift 侧添加零尺寸防御校验**

在 `metallum_create_texture_2d` 开头添加：

```swift
guard width > 0, height > 0 else {
    NSLog("[metallum] metallum_create_texture_2d rejected zero dimension: %llux%llu", width, height)
    return nil
}
```

在 `metallum_fx_create_spatial_scaler`、`metallum_fx_create_temporal_scaler`、`metallum_fx_create_frame_interpolator` 开头分别添加对应的零尺寸校验，返回 `nil` 而非崩溃。

**修复 B：Java 侧 `MetalFxPipeline` 添加维度前置校验**

在 `maybeEncode` 入口处、各 `ensure*` 方法入口处，校验 `sourceWidth/sourceHeight/outputWidth/outputHeight > 0`。如果任一为 0，记录警告日志并直接返回 `sourceTexture`（跳过本帧 MetalFX）。

**修复 C：`MetalSurface.configure` 加固 framebuffer 零值处理**

当 `glfwGetFramebufferSize` 返回 0 时（窗口未就绪），不要将 0 传播到 `displayWidth/displayHeight`，而是保留上一次的有效值或回退到 `config.width()/height()`。

### 3.4 影响范围

| 文件 | 改动 |
|------|------|
| `MetallumNative.swift` | `metallum_create_texture_2d` / `metallum_fx_create_spatial_scaler` / `metallum_fx_create_temporal_scaler` / `metallum_fx_create_frame_interpolator` 添加零尺寸 guard |
| `MetalFxPipeline.java` | `maybeEncode` 及各 `ensure*` 方法添加维度前置校验 |
| `MetalSurface.java` | `configure` 加固 framebuffer 零值处理 |

---

## 4. 通用修复：`MemorySegment.NULL` 句柄检查

问题一的根因 B 涉及一个影响所有 MetalFX 路径的通用缺陷：Java 侧使用 `!= null` 检查 native 句柄，但 `MemorySegment.NULL`（地址 0）不是 Java null。这导致 native 调用返回 NULL 时被误判为有效句柄。

**修复**：在 `MetalFxPipeline` 中，将所有 scaler / interpolator 句柄的 `!= null` 检查统一替换为 `!MetalNativeBridge.isNullHandle(handle)`，将 `== null` 替换为 `isNullHandle(handle)`。`isNullHandle` 已存在于 [MetalNativeBridge](file:///workspace/src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java#L1701-L1703)。

---

## 5. 非目标

- 不修改帧插值（MTLFXFrameInterpolator）路径的核心逻辑（M1 不支持，未测试）
- 不修改 Sodium 集成路径（`MetallumSodiumConfig`）
- 不修改 MetalFX 选项界面 / 警告界面的布局
- 不引入新的外部依赖
- 不修改 `MetalFxMath` 的 Halton / 抖动算法

## 6. 构建与验证

- 原生库构建：`./gradlew buildMacNative build`（CI 在 macOS-15 上运行）
- Swift 修改需要重新编译 `libmetallum.dylib`
- Java 修改由 Gradle 构建，无需额外步骤
- 未经允许不提交 PR；推送使用提供的 token
