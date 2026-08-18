# 渲染链路性能优化（MetalUniversal）Spec

## Why
本项目（`EternityQwQ/MetalUniversal`，Fork 自 kokodio/metallum，分支 `26.2-fabric`）是一个基于 Apple Metal 的 Minecraft Fabric 渲染后端模组。渲染热路径通过 Java Foreign Memory / `@_cdecl` 桥接调用 Swift(Metal)。当前实现存在若干每帧/每 draw call 的固定开销：多绘制（multi-draw）在 Java 侧逐条循环穿过多次 JNI 边界、texel buffer 每次 push 都新建并随后销毁一个 Metal buffer-texture-view。这些开销在 Minecraft 数千级 draw call 的高负载场景下会放大为可测量的 CPU 侧瓶颈。

## What Changes
- **操作约束**：未经用户明确许可不得开启任何 Pull Request；只将改动推送到分支并在 CI 上触发/观察 `build` 工作流。
- **GitHub 授权**：使用用户提供的 PAT 完成对 `github.com/EternityQwQ/MetalUniversal` 的推送、`build` 工作流触发与状态监视能力；令牌只保存于凭据存储（`gh`/git credential），绝不写入任何项目文件或提交。
- **渲染开销度量**：新增默认关闭的 opt-in 计数器（JNI draw 调用次数、texel-view 分配次数、drawIndexedNative 次数），用于量化后续优化的前后对比，避免空谈性能。
- **multiDrawIndexed 合并为单次原生调用**：`MetalRenderPass.multiDrawIndexed(IntBuffer,…)` 当前在 Java 侧 `for` 循环内逐条 `drawIndexedNative`（每条一次 JNI 穿越）。改为一次原生调用，在 Swift 内完成 `firstIndex(index 单位)→byte offset` 换算与遍历，把 N 次 JNI 穿越降为 1 次。
- **texel buffer texture view 缓存**：`MetalRenderPass.pushTexelBufferDescriptor` 当前每次 push 都调用 `metallum_create_buffer_texture_view` 新建 view 并入队销毁。改为按 `(buffer 句柄, offset, pixelFormat, bytesPerRow, length)` 缓存并复用，随底层 buffer 释放时回收，消除稳态下每帧的重复分配/释放。

## Impact
- 受影响 Spec：渲染路径（Java 桥接 + Swift 原生），不涉及 CI 工作流语义与发布流程本身（不新增发布任务）。
- 受影响代码：
  - `src/main/java/com/metallum/client/metal/render/MetalRenderPass.java`（multiDrawIndexed、pushTexelBufferDescriptor）
  - `src/main/java/com/metallum/client/metal/render/MetalDevice.java`（texel view 缓存生命周期宿主）
  - `src/main/java/com/metallum/client/metal/render/Stats.java` 或独立计数器辅助类（度量）
  - `src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java`（新增 native 绑定）
  - `src/main/native/MetallumNative.swift`（新增多绘制/换算入口点）
- 不做：不重构帧同步管道（`submit()` 的 N-in-flight 等待）与销毁队列（高风险、收益不明确）；不在未测环境下改动着色器逻辑。

## ADDED Requirements

### Requirement: GitHub 推送与 CI 权限（不创建 PR）
系统 SHALL 使用用户提供的 PAT 完成对 `origin`（`github.com/EternityQwQ/MetalUniversal`）的认证推送、触发 `build` 工作流并查看其运行结果。

#### Scenario: 授权与推送
- **WHEN** 使用提供的 PAT 完成 GitHub 认证
- **THEN** `gh auth status`/git 推送对 `origin` 有效，令牌存于凭据存储且不落盘到项目文件，`git grep` 用户令牌返回空

#### Scenario: 禁止自动 PR
- **WHEN** 在任何任务完成后需要共享改动
- **THEN** SHALL NOT 自动创建 Pull Request；仅推送到分支并把分支/CI 结果交由用户决定。用户明确批准后才可开 PR

### Requirement: 渲染开销计数器
系统 SHALL 提供默认关闭的 opt-in 计数，用于度量渲染热路径开销。

#### Scenario: 启动计数
- **WHEN** 渲染计数器开关（自定义系统属性/调试选项）开启
- **THEN** 每帧记录 JNI 绘制相关调用次数与 texel-view 分配次数，可持续观测以对比优化前后

### Requirement: multiDrawIndexed 单次原生调用
系统 SHALL 将 `multiDrawIndexed(IntBuffer, …)` 的逐条 Java 循环绘制改为一次原生调用。

#### Scenario: 多绘制批量执行
- **WHEN** 某一次多绘制包含 N 条子绘制
- **THEN** 只发生 1 次 javac→native 边界穿越（替代 N 次），Swift 内完成索引 offset 换算，绘制结果与优化前一致，计数器显示子绘制仍为 N 但 JNI 调用数为 1

### Requirement: texel buffer view 缓存
系统 SHALL 缓存按 texel buffer 参数标识的 Metal buffer-texture-view，避免稳态下重复分配。

#### Scenario: 复用不重复分配
- **WHEN** 同一 texel buffer（相同 buffer/offset/format/bytesPerRow/length）被连续 push
- **THEN** 复用既有 view，不产生新的 `metallum_create_buffer_texture_view` 分配；当底层 buffer 释放时缓存项随之回收