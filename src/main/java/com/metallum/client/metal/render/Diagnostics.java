package com.metallum.client.metal.render;

import com.metallum.Metallum;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 诊断日志节流：per-key 只输出一次（error 级 + [diag] 前缀）。
 *
 * <p>背景：fix-diag 的 createRenderPass/presentTexture 在每帧高频路径打 error 级
 * 日志，iOS 上控制台输出开销导致卡死。目标（纹理/pass/present 源）数量有限，
 * 每个目标打一次即可覆盖"首次渲染链是否成立、目标是否一致"的诊断意图。
 *
 * <p>开关：-Dmetallum.diag=false 可关闭全部诊断日志（默认开启）。
 */
final class Diagnostics {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("metallum.diag", "true"));
    private static final Set<String> ONCE = ConcurrentHashMap.newKeySet();

    private Diagnostics() {
    }

    /**
     * 按 key 去重输出一次诊断日志；重复 key 静默跳过。
     */
    static void once(final String key, final String format, final Object... args) {
        if (ENABLED && ONCE.add(key)) {
            Metallum.LOGGER.error("[diag] " + format, args);
        }
    }
}
