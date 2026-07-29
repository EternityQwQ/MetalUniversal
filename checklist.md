# Checklist: MetalFX 三个渲染缺陷修复验收

## 问题一：极致性能 33% 画面冻结

### 分辨率钳制
- [ ] 33% 档位下，内部分辨率被钳制到 ≥ displayWidth/3 × displayHeight/3
- [ ] 钳制后放大倍数恰好 ≤ 3.0x（不再超限）
- [ ] 3840×2160 显示器 + 33% → 内部 ≥ 1280×720，F3 显示正确
- [ ] 1920×1080 窗口 + 33% → 内部 ≥ 640×360（2x HiDPI 下 1280×720/3）
- [ ] 56% / 67% / 77% 档位不受钳制影响（放大倍数原本 < 3x）

### MemorySegment.NULL 修复
- [ ] `MetalFxPipeline` 中所有 scaler/interpolator 句柄检查使用 `isNullHandle()` 而非 `!= null`
- [ ] scaler 创建失败后不缓存失败维度（下一帧重试）
- [ ] scaler 创建失败后 `encodeSpatialFallback` 回退到 `return sourceTexture`（低分辨率直接呈现，不冻结）
- [ ] 时间超分 scaler 创建失败时同样回退到空间超分或源纹理

### 冻结场景
- [ ] 33% + 时间超分开启 → 不冻结（时间超分 scaler 也受 3x 钳制保护）
- [ ] 33% + 时间超分关闭 → 不冻结（空间超分 scaler 创建成功）
- [ ] 重启后 33% 配置 → 不黑屏（scaler 正常创建或优雅回退）
- [ ] 游戏逻辑在所有情况下继续运行

---

## 问题二：时间超分关闭后 GUI 闪烁

### GUI 渲染顺序
- [ ] 时间超分关闭 + 空间超分开启时，GUI 以全分辨率渲染（不被空间超分放大）
- [ ] 时间超分开启时，GUI 仍随 3D 一起被时域超分放大（行为不变）
- [ ] 空间超分关闭时，GUI 正常渲染（无超分，无闪烁）
- [ ] GUI 文字、按钮边框在时间超分关闭时不再闪烁
- [ ] GUI 在空间超分各档位（56/67/77%）下均稳定显示

### WindowMixin 条件化
- [ ] `getWidth/getHeight` 在空间超分激活时仍缩减（3D 低分辨率渲染）
- [ ] `getGuiScaledWidth/getGuiScaledHeight` 仅在时间超分激活时缩减
- [ ] 时间超分关闭时 `getGuiScaledWidth/getGuiScaledHeight` 返回全分辨率
- [ ] 非 Metal 后端 / 空间超分关闭时，所有 getter 不受影响

### 回退保障
- [ ] 如延迟 GUI 渲染不可行，`upscaledColorTexture` 在空间超分 encode 前清零
- [ ] `MemorySegment.NULL` 修复确保空间超分 scaler 在模式切换时有效运行

---

## 问题三：4:3 非标准比例零尺寸崩溃

### Swift 零尺寸防御
- [ ] `metallum_create_texture_2d` 在 width/height 为 0 时返回 nil 并记日志（不崩溃）
- [ ] `metallum_fx_create_spatial_scaler` 在任一维度为 0 时返回 nil
- [ ] `metallum_fx_create_temporal_scaler` 在任一维度为 0 时返回 nil
- [ ] `metallum_fx_create_frame_interpolator` 在任一维度为 0 时返回 nil

### Java 维度校验
- [ ] `MetalFxPipeline.maybeEncode` 入口校验 sourceWidth/sourceHeight/outputWidth/outputHeight > 0
- [ ] 各 `ensure*` 方法入口校验维度 > 0
- [ ] 维度为 0 时记录警告日志并返回 sourceTexture（跳过本帧 MetalFX）
- [ ] `MetalSurface.configure` 在 GLFW 返回 0 时保留上一次有效值或回退到 config 尺寸

### 4:3 场景
- [ ] 1280×960 + 时间超分 → 不崩溃
- [ ] 1280×960 + 空间超分 → 不崩溃
- [ ] 16:9 分辨率（1280×720 / 1920×1080 / 2560×1440 / 3840×2160）不受影响
- [ ] 窗口最小化 / 恢复时不崩溃

---

## 不回归验收

- [ ] 空间超分 56% / 67% / 77% + 时间超分开启 → 画面正常（与修复前一致）
- [ ] 空间超分关闭 + 时间超分关闭 → 原生渲染路径不变
- [ ] F8 快捷键仍能打开 MetalFX 设置
- [ ] MetalFX 设置界面各控件功能正常
- [ ] 构建通过（CI 绿色）：`./gradlew buildMacNative build`
- [ ] `libmetallum.dylib` 重新编译成功（Swift 修改生效）

## 代码质量

- [ ] `MetalFxPipeline` 中无残留的 `!= null` scaler 句柄检查
- [ ] Swift 零尺寸 guard 使用 `NSLog` 记录被拒维度，便于诊断
- [ ] 无悬空引用，无未使用的 import
- [ ] 注释更新，说明 3x 钳制和零尺寸校验的原因
