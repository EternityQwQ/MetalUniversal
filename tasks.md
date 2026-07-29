# Tasks: MetalFX 三个渲染缺陷修复实施

## 任务 1：Swift 侧零尺寸防御校验（问题三修复 A）

修改 [MetallumNative.swift](file:///workspace/src/main/native/MetallumNative.swift)：

1. `metallum_create_texture_2d`（L846）：开头添加 `guard width > 0, height > 0 else { NSLog(...); return nil }`
2. `metallum_fx_create_spatial_scaler`（L1828）：开头添加 `guard inputWidth > 0, inputHeight > 0, outputWidth > 0, outputHeight > 0 else { NSLog(...); return nil }`
3. `metallum_fx_create_temporal_scaler`（L1910）：同上
4. `metallum_fx_create_frame_interpolator`（L1999）：开头添加 `guard outputWidth > 0, outputHeight > 0 else { NSLog(...); return nil }`

**验证**：`swiftc -O -emit-library ...` 编译通过（CI 的 `buildMacNative` 步骤）

---

## 任务 2：`MemorySegment.NULL` 句柄检查修复（通用修复 / 问题一修复 B）

修改 [MetalFxPipeline.java](file:///workspace/src/main/java/com/metallum/client/metal/fx/MetalFxPipeline.java)：

将以下位置的 `!= null` / `== null` 替换为 `!MetalNativeBridge.isNullHandle()` / `MetalNativeBridge.isNullHandle()`：

1. `ensureSpatialScaler`（L528）：`spatialScaler != null` → `!isNullHandle(spatialScaler)`
2. `ensureSpatialScaler`（L533）：`spatialScaler != null`（release 分支）→ 同上
3. `ensureSpatialScaler`（L539）：`temporalScaler != null` → `!isNullHandle(temporalScaler)`
4. `ensureTemporalScaler`（L486）：`temporalScaler != null` → `!isNullHandle(temporalScaler)`
5. `ensureTemporalScaler`（L491）：`temporalScaler != null`（release 分支）→ 同上
6. `ensureTemporalScaler`（L498）：`spatialScaler != null` → `!isNullHandle(spatialScaler)`
7. `encodeSpatialFallback`（L462）：`spatialScaler != null` → `!isNullHandle(spatialScaler)`
8. `maybeEncode`（L235）：`temporalScaler != null` → `!isNullHandle(temporalScaler)`
9. `maybeEncode`（L329）：`frameInterpolator == null` → `isNullHandle(frameInterpolator)`
10. `maybeEncode`（L334）：`frameInterpolator != null` → `!isNullHandle(frameInterpolator)`

需要添加 `import com.metallum.client.metal.render.bridge.MetalNativeBridge;`（如果尚未导入）。

---

## 任务 3：scaler 创建失败不缓存（问题一修复 C）

修改 [MetalFxPipeline.java](file:///workspace/src/main/java/com/metallum/client/metal/fx/MetalFxPipeline.java)：

### `ensureSpatialScaler`（L527-L556）

```java
spatialScaler = MetalNativeBridge.metallum_fx_create_spatial_scaler(...);
if (MetalNativeBridge.isNullHandle(spatialScaler)) {
    // 创建失败：不更新缓存维度，下一帧重试。
    // spatialScaler 保持 null（isNullHandle=true），encodeSpatialFallback 回退到 sourceTexture。
    Metallum.LOGGER.warn("[MetalFX] spatial scaler creation failed for {}x{} -> {}x{}; will retry next frame",
            inW, inH, outW, outH);
    return;  // ← 不执行后面的 cachedInputWidth = inW 等
}
cachedInputWidth = inW;
cachedInputHeight = inH;
cachedOutputWidth = outW;
cachedOutputHeight = outH;
```

### `ensureTemporalScaler`（L485-L525）

同样的模式：创建失败时不更新缓存，记录警告并返回。

---

## 任务 4：分辨率 3x 钳制（问题一修复 A）

修改 [MetalSurface.applyInternalResolution()](file:///workspace/src/main/java/com/metallum/client/metal/render/MetalSurface.java#L156-L192)：

在计算 `targetWidth/targetHeight` 之后、赋值 `this.internalWidth/internalHeight` 之前，追加：

```java
// MTLFXSpatialScaler / MTLFXTemporalScaler 要求 output ≤ 3 × input（每轴）。
// 当 renderScale 过低（如 33% → 3.03x）时，钳制到恰好 1/3。
int minInputWidth  = (int) Math.ceil(this.displayWidth  / 3.0);
int minInputHeight = (int) Math.ceil(this.displayHeight / 3.0);
if (targetWidth < minInputWidth || targetHeight < minInputHeight) {
    targetWidth  = Math.max(targetWidth,  minInputWidth);
    targetHeight = Math.max(targetHeight, minInputHeight);
    Metallum.LOGGER.info(
            "[MetalFX] clamped internal resolution to {}x{} (min 1/3 of display {}x{})",
            targetWidth, targetHeight, this.displayWidth, this.displayHeight);
}
```

---

## 任务 5：Java 侧维度前置校验（问题三修复 B）

修改 [MetalFxPipeline.java](file:///workspace/src/main/java/com/metallum/client/metal/fx/MetalFxPipeline.java)：

### `maybeEncode` 入口（L206-L226）

在 `needsUpscale` 计算之前，添加：

```java
if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) {
    Metallum.LOGGER.warn("[MetalFX] rejecting zero/negative dimensions: source={}x{} output={}x{}; presenting source directly",
            sourceWidth, sourceHeight, outputWidth, outputHeight);
    return sourceTexture;
}
```

### 各 `ensure*` 方法入口

在 `ensureSpatialScaler`、`ensureTemporalScaler`、`ensureUpscaledTexture`、`ensureTemporalMotionTexture`、`ensureMotionVectorTexture`、`ensurePreviousTexture`、`ensureInterpolationOutput` 的开头添加维度校验，维度 ≤ 0 时直接 return。

---

## 任务 6：MetalSurface.configure 零值加固（问题三修复 C）

修改 [MetalSurface.configure()](file:///workspace/src/main/java/com/metallum/client/metal/render/MetalSurface.java#L104-L141)：

```java
int realWidth;
int realHeight;
try (MemoryStack stack = MemoryStack.stackPush()) {
    var pW = stack.mallocInt(1);
    var pH = stack.mallocInt(1);
    GLFW.glfwGetFramebufferSize(windowHandle, pW, pH);
    realWidth = pW.get(0);
    realHeight = pH.get(0);
}
// GLFW 可能在窗口未就绪时返回 0。回退到 config 尺寸（已校验 > 0）。
if (realWidth <= 0 || realHeight <= 0) {
    realWidth = config.width();
    realHeight = config.height();
}
this.displayWidth = realWidth;
this.displayHeight = realHeight;
```

移除原来的 `realWidth > 0 ? realWidth : config.width()` 三元表达式（已被显式 if 替代）。

---

## 任务 7：时间超分关闭时 GUI 全分辨率渲染（问题二修复）

### 7a. WindowMixin 条件化 GUI 缩放

修改 [WindowMixin.java](file:///workspace/src/main/java/com/metallum/mixin/render/WindowMixin.java)：

将 `metallum$scale` 拆分为两个方法：

```java
// 用于 getWidth/getHeight：空间超分激活时始终缩减
private static Integer metallum$scaleFramebuffer(int original) {
    if (!metallum$isMetalBackend()) return null;
    MetalFxConfig cfg = MetalFxConfig.get();
    if (!cfg.isSpatialUpscalingActive()) return null;
    float scale = cfg.spatialMode().renderScale;
    if (scale >= 1.0f) return null;
    return Math.max(1, Math.round(original * scale));
}

// 用于 getGuiScaledWidth/getGuiScaledHeight：仅在时间超分激活时缩减
// 时间超分关闭时，GUI 保持全分辨率，在 MetalFX 放大后独立绘制
private static Integer metallum$scaleGui(int original) {
    if (!metallum$isMetalBackend()) return null;
    MetalFxConfig cfg = MetalFxConfig.get();
    if (!cfg.isSpatialUpscalingActive()) return null;
    // 时间超分关闭时 GUI 不缩减（将在放大后以全分辨率绘制）
    if (!cfg.isTemporalUpscalingActive()) return null;
    float scale = cfg.spatialMode().renderScale;
    if (scale >= 1.0f) return null;
    return Math.max(1, Math.round(original * scale));
}
```

更新 `getWidth/getHeight` 注入调用 `metallum$scaleFramebuffer`，`getGuiScaledWidth/getGuiScaledHeight` 注入调用 `metallum$scaleGui`。

### 7b. upscaledColorTexture 添加 RenderTarget 用法

修改 [MetalFxPipeline.ensureUpscaledTexture()](file:///workspace/src/main/java/com/metallum/client/metal/fx/MetalFxPipeline.java#L558-L578)：

当时间超分关闭（`!cfg.isTemporalUpscalingActive()`）且空间超分开启时，usage 追加 `MTLTextureUsage.RenderTarget.value`：

```java
long usage = USAGE_SHADER_RW;
MetalFxConfig cfg = MetalFxConfig.get();
if (cfg.isSpatialUpscalingActive() && !cfg.isTemporalUpscalingActive()) {
    // 时间超分关闭时，GUI 需要直接渲染到放大纹理上
    usage |= MTLTextureUsage.RenderTarget.value;
}
upscaledColorTexture = MetalNativeBridge.metallum_create_texture_2d(
        deviceHandle(), MTLPixelFormat.BGRA8Unorm, width, height,
        1L, 1L, 0L, usage, MTLStorageMode.Private, "metallum-fx-upscaled");
```

### 7c. 延迟 GUI 渲染钩子

在 `MetalCommandEncoder.presentTextureToDrawable` 中，当空间超分激活且时间超分关闭时，在 `maybeEncode` 返回 upscaledColorTexture 后、`encodePresentTextureToDrawable` 之前，提供一个回调点供 GUI 渲染。

如果此部分实现过于复杂，执行任务 7d 回退方案。

### 7d. 回退方案：upscaledColorTexture 清零

如果 7c 无法实现，在 `encodeSpatialFallback` 中，每次空间超分 encode 前清零 `upscaledColorTexture`：

```java
// 清零放大纹理，防止上一帧残留数据在 scaler 未覆盖区域造成闪烁
MetalNativeBridge.metallum_fx_clear_texture(commandBuffer, upscaledColorTexture);
```

---

## 任务 8：静态验证

1. `grep` 确认 `MetalFxPipeline.java` 中无残留的 `spatialScaler != null` / `temporalScaler != null` / `frameInterpolator != null`
2. `grep` 确认 `MetallumNative.swift` 中四个创建函数均有 `guard ... > 0`
3. `grep` 确认 `MetalSurface.java` 中有 3x 钳制逻辑
4. `grep` 确认 `WindowMixin.java` 中 `metallum$scaleGui` 仅在时间超分激活时缩减
5. 确认 `MetalFxPipeline.java` import 了 `MetalNativeBridge`

---

## 任务 9：构建验证

1. `./gradlew buildMacNative build`（macOS 上）或等待 CI
2. 确认 Swift 编译通过（零尺寸 guard 语法正确）
3. 确认 Java 编译通过（isNullHandle 调用正确）
4. 确认无新的编译警告

---

## 任务 10：提交并推送

1. `git add` 相关文件
2. `git commit`（描述三个缺陷的修复）
3. 使用提供的 token 推送到 `origin/feature/metalfx-upscale-frameinterp`
4. **不提交 PR**（未经允许禁止提交 PR）
5. 等待 CI 确认 buildMacNative + 主构建均绿色
