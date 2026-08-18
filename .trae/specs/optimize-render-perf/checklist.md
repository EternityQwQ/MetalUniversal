# Checklist

- [x] Task 1：GitHub 认证对 `origin` 可用（`gh auth status` 有效），令牌未落盘、`git grep` 不含令牌明文
- [x] Task 1：改动仅推送分支，无未授权 Pull Request 被创建
- [x] Task 2：渲染开销计数器默认关闭时可编译，开启时可观测 JNI/texel-view 计数输出
- [x] Task 3：`multiDrawIndexed(IntBuffer,…)` 改为单次原生调用，N 条子绘制结果等价、JNI 穿越次数降为 1
- [x] Task 4：texel buffer view 缓存生效，同一参数稳态下无重复 `metallum_create_buffer_texture_view` 分配，随 buffer 释放正确回收
- [x] Task 5：`build` 工作流（macos-15，含 `buildMacNative`）通过，无热路径回归、不创建 PR