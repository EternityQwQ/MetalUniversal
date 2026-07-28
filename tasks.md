# Tasks: MetalFX 入口按钮修复实施

## 任务 1：重写 VideoSettingsScreenMixin

修改 [VideoSettingsScreenMixin.java](file:///workspace/src/main/java/com/metallum/mixin/render/VideoSettingsScreenMixin.java)：

1. `@Mixin` target 从 `VideoSettingsScreen.class` 改为 `OptionsSubScreen.class`
2. `extends Screen` 不变（OptionsSubScreen 继承 Screen）
3. `@Inject(method = "init", at = @At("RETURN"))` 替换 `@Inject(method = "addOptions", at = @At("TAIL"))`
4. 方法体首行加 instanceof 过滤：
   ```java
   if (!((Object) this instanceof VideoSettingsScreen)) return;
   ```
5. 保留 `metallum$isMetalBackend()` 检查
6. 保留 `MetalFxConfig.reload()` 调用
7. 添加去重检查：遍历 `this.renderables`，若已存在 MetalFX 按钮则跳过
8. 按钮位置保持 `x = this.width - 158, y = 6, w=150, h=20`
9. 更新类级 javadoc，说明注入点变更的原因

**注意 import**：新增 `import net.minecraft.client.gui.screens.options.OptionsSubScreen;`

## 任务 2：验证 mixin 配置一致性

1. 检查 [metallum.mixins.json](file:///workspace/src/main/resources/metallum.mixins.json) — 类名仍为 `render.VideoSettingsScreenMixin`（文件名不变，无需改 JSON）
2. 检查 [MetallumMixinConfigPlugin.java](file:///workspace/src/main/java/com/metallum/mixin/MetallumMixinConfigPlugin.java) — `ALWAYS_APPLY_ON_MACOS` 仍包含 `com.metallum.mixin.render.VideoSettingsScreenMixin`（无需改）

## 任务 3：静态验证

1. `grep` 确认 `OptionsSubScreen` import 正确
2. `grep` 确认无残留的 `addOptions` 注入
3. 确认 `VideoSettingsScreen` import 仍存在（用于 instanceof 过滤）
4. 确认 JSON 文件合法

## 任务 4：提交并推送，确认 CI 通过

1. git add + commit（描述注入点变更原因）
2. git push
3. 等待 CI，确认 buildMacNative + 主构建均绿色
