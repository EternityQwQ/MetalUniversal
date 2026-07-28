# Checklist: MetalFX 入口按钮修复验收

## 功能验收

- [ ] 未安装 Sodium 时，进入原版视频设置界面，"MetalFX 设置..." 按钮稳定显示在右上角
- [ ] 按钮位置为 `x = width - 158, y = 6`，不随帧飘移
- [ ] 按钮不闪烁（不会帧间消失/重现）
- [ ] 窗口 resize 后按钮重新出现在正确位置
- [ ] 在视频设置界面修改任意原版选项（触发 rebuildWidgets）后，按钮仍正常显示
- [ ] 点击按钮打开 `MetalFxWarningScreen`（首次）或 `MetalFxOptionsScreen`（已确认）
- [ ] 从 MetalFX 设置界面点"完成"返回视频设置界面后，按钮仍在
- [ ] 非 Metal 后端（OpenGL/Vulkan）下按钮不显示
- [ ] 非 macOS 平台 mixin 不应用

## 不回归验收

- [ ] 安装 Sodium 时，Sodium 视频设置里的 MetalUniversal 页面仍正常（ConfigEntryPoint 不受影响）
- [ ] F8 快捷键仍能打开 MetalFX 设置
- [ ] 语言设置 / 控制设置等其他 OptionsSubScreen 子类**不**出现 MetalFX 按钮（instanceof 过滤生效）
- [ ] 构建通过（CI 绿色）

## 代码质量

- [ ] mixin target 类名与 metallum.mixins.json 一致
- [ ] MetallumMixinConfigPlugin 的 ALWAYS_APPLY_ON_MACOS 包含该 mixin 类
- [ ] 注释更新，说明为何注入 OptionsSubScreen.init RETURN 而非 addOptions TAIL
- [ ] 无悬空引用，无未使用的 import
