# Tasks

以下任务按依赖排序。Task 1（GitHub 授权）与其余任务互相独立，可并行；Task 2（度量）是 Task 3/4 量化验证的前提。

- [x] Task 1: GitHub 授权与推送/CI 监视就绪（不创建 PR）
  - [x] 1.1 通过 `gh` 现有认证（`GH_TOKEN`/连接器，账号 `EternityQwQ`）完成对 `origin` 的认证；用户提供的 PAT 无需落盘即具备权限
  - [x] 1.2 验证 `gh auth status` 有效，且可列出远程仓库 `EternityQwQ/MetalUniversal` 的 `build` 工作流运行
  - [x] 1.3 验证：确认当前改动可推送到 `origin` 上的特性分支（push 不因认证失败）；确认 `git grep` 项目内不含用户令牌明文；确认未自动打开任何 PR
- [x] Task 2: 渲染开销计数器（opt-in，默认关闭）
  - [x] 2.1 新增渲染回调/计数模型：包装 draw 类 JNI 调用与 `metallum_create_buffer_texture_view` 调用，并在开关开启时累加
  - [x] 2.2 通过自定义属性/调试选项控制开关（默认关闭，热路径零额外成本路径判定后直接透传）
  - [x] 2.3 提供可读输出（如每帧汇总，宏 `Frame` 刷新周期处打印），用于优化前后对比
- [x] Task 3: multiDrawIndexed(IntBuffer,…) 合并为单次原生调用
  - [x] 3.1 Swift：新增/扩展入口点，接收 `drawCount` 个 `(firstIndex, indexCount, baseVertex)` 及 `indexType`，在原生侧完成 `firstIndex*indexType.bytes` 换算并遍历 `drawIndexedPrimitives`（保持与原 Java 循环一致的结果）
  - [x] 3.2 MetalNativeBridge：声明对应 FFM 绑定的 native 方法
  - [x] 3.3 MetalRenderPass.multiDrawIndexed(IntBuffer,…)：以单次原生调用替代 Java for 循环
  - [x] 3.4 验证：构建通过，计数器对比显示 N 条子绘制仍执行、但 JNI 穿越次数降为 1 的上层逻辑等价
- [x] Task 4: texel buffer texture view 缓存
  - [x] 4.1 定义 view 缓存键（buffer 句柄, offset, pixelFormat, bytesPerRow, length）并挑选生命周期宿主（建议 MetalDevice，随 buffer 释放联动）
  - [x] 4.2 `MetalRenderPass.pushTexelBufferDescriptor`：命中缓存复用 view，未命中才创建并登记，取消每 push 必入队销毁的逻辑（改为由缓存统一回收）
  - [x] 4.3 并发/关闭安全：缓存项在 `clearPipelineCache`/设备关闭时正确释放，无 UAF
  - [x] 4.4 验证：构建通过，计数器显示稳态下同一 texel 参数不产生新分配；引用同一 buffer 的连续 push 命中缓存
- [x] Task 5: CI 验证与结果汇总
  - [x] 5.1 推送特性分支并在 GitHub Actions 触发/观察 `build`（macos-15）通过（含 `buildMacNative` 与 `tests`）
  - [x] 5.2 汇总：编译通过、计数器改造无热路径回归、不创建 PR，向用户报告分支与 CI 结论

# Task Dependencies
- [Task 2] depends on [Task 1 的环境，但无代码依赖]；[Task 3]/[Task 4] 的量化验证 depends on [Task 2]
- [Task 5] depends on [Task 3], [Task 4]