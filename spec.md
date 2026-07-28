# Spec: 修复原版视频设置界面 MetalFX 入口按钮的飘移与闪烁

## 1. 问题描述

用户报告：进入原版视频设置界面（未安装 Sodium 时）后，MetalFX 入口按钮出现三种异常表现之一：

1. **按钮完全消失** — 界面上看不到 "MetalFX 设置..." 按钮
2. **按钮飘到右下角** — 按钮出现在屏幕底部 footer 区域，而非代码指定的右上角 `y=6`
3. **一闪一闪** — 按钮在可见 / 不可见之间帧间跳变

## 2. 根因分析

### 2.1 注入时机错位（主因）

当前 [VideoSettingsScreenMixin.java](file:///workspace/src/main/java/com/metallum/mixin/render/VideoSettingsScreenMixin.java) 通过：

```java
@Inject(method = "addOptions", at = @At("TAIL"))
```

在 `VideoSettingsScreen.addOptions()` 的尾部添加按钮。

在 Minecraft 26.2 中，`OptionsSubScreen.init()` 的执行顺序为：

```
init()
  ├─ 创建 HeaderAndFooterLayout
  ├─ addOptions()          ← mixin 在这里注入（TAIL）
  │    └─ addRenderableWidget(button)  ← 按钮此时被加入 renderables
  └─ layout.arrangeElements()  ← layout 排版在按钮添加之后才执行
```

**问题**：按钮在 `arrangeElements()` **之前**被加入 `renderables`。虽然 `HeaderAndFooterLayout.arrangeElements()` 理论上只重排自己的子节点，但在 26.2 的实际渲染管线中，footer 背景面板（`PanelRenderable`）在 `renderables` 之后绘制，会覆盖任何 y 坐标落在 footer 区的 widget。更关键的是，`addOptions` 在每次 `init()` / `rebuildWidgets()` 时都会被重复调用，按钮被反复销毁重建，导致帧间状态不稳定 → **闪烁**。

### 2.2 footer 背景面板覆盖（次因）

`OptionsSubScreen` 使用 `HeaderAndFooterLayout`，该 layout 在 render 时绘制一个贴底的 footer 背景面板（约屏幕底部 36–66px），此面板绘制顺序在 `renderables` **之后**。任何 y 落在该区域的按钮都会被压在面板下 → **不可见或半隐半闪**。

注释（[VideoSettingsScreenMixin.java:40-45](file:///workspace/src/main/java/com/metallum/mixin/render/VideoSettingsScreenMixin.java#L40-L45)）已记录此现象，作者曾把按钮从右下角移到右上角 `y=6` 试图规避，但由于 2.1 的时机问题，按钮最终仍可能被推入 footer 区。

### 2.3 this.width 时机不确定

`addOptions()` 执行时 `this.width` / `this.height` 可能尚未被当前 init 周期正确设置（尤其首次进入或 resize 时），导致 `x = this.width - buttonWidth - 8` 计算出屏幕外坐标 → **按钮消失**。

## 3. 修复方案

### 3.1 推荐方案：改注入 `OptionsSubScreen.init` 的 RETURN

**核心改动**：把 mixin target 从 `VideoSettingsScreen` 改为 `OptionsSubScreen`，注入点从 `addOptions` TAIL 改为 `init` RETURN。

**为什么有效**：
- `OptionsSubScreen.init()` 是 26.2 中实际 override init 的类（`VideoSettingsScreen` 不再 override init）
- `init` RETURN 时 `layout.arrangeElements()` **已经执行完毕**，footer 面板位置已确定
- 此时 `this.width` / `this.height` 已是当前周期的正确值
- 按钮添加后不会再被任何 layout 步骤影响 → **位置稳定，不飘移**

**运行时过滤**：因为 target 改成了 `OptionsSubScreen`（影响所有子类：语言、控制、视频等），需要在注入方法里加 `instanceof VideoSettingsScreen` 过滤，只在视频设置界面添加按钮。

### 3.2 按钮位置

保持右上角 `y=6`，`x = this.width - buttonWidth - 8`。此区域在 header 标题区（y=16 居中文字）之上，且远离 footer 面板（贴底），是安全区。

### 3.3 防重复添加保险

虽然 `rebuildWidgets()` 会先 `clearWidgets()` 再 `init()`，理论上不会累积，但作为防御性措施，在添加前检查 `renderables` 中是否已存在 MetalFX 按钮，避免任何不走 clear 路径的异常累积。

### 3.4 不引入新依赖

本方案**不引入 Fabric API 依赖**。虽然 `ScreenEvents.afterInit` 时机更干净，但：
- 项目当前不依赖 Fabric API，引入会增加前置条件
- Sodium 已通过 `ConfigEntryPoint` 集成（装 Sodium 的用户不受此 bug 影响）
- mixin 注入 `OptionsSubScreen.init` RETURN 已足够稳健

## 4. 影响范围

| 文件 | 改动 |
|------|------|
| `VideoSettingsScreenMixin.java` | 改 target 为 `OptionsSubScreen`，注入 `init` RETURN，加 instanceof 过滤，加去重检查 |
| `metallum.mixins.json` | 更新 mixin 类引用路径（类名不变则无需改） |
| `MetallumMixinConfigPlugin.java` | `ALWAYS_APPLY_ON_MACOS` 集合里的类名不变（仍为 `VideoSettingsScreenMixin`） |

## 5. 非目标

- 不修改 Sodium 集成路径（`MetallumSodiumConfig` 已正常工作）
- 不修改 `MetalFxWarningScreen` / `MetalFxOptionsScreen`（它们用独立 LinearLayout 排版，无此 bug）
- 不修改 F8 快捷键逻辑（`MinecraftKeybindMixin` 走 tick 轮询，与布局无关）
- 不引入 Fabric API 依赖
