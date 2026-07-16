package com.zsc.service;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * 记录每个会话在沙箱（workspace）中改动过的相对路径集合。
 * <p>
 * 用于 Agent 模式的 "工作区 diff 应用到原项目" 功能：
 * 仅展示和应用本次会话内 Agent 修改过的文件，避免把沙箱里的历史脏数据/无关文件一并 diff。
 * <p>
 * 单例 + ConcurrentHashMap，多会话并发安全。会话结束 / 应用 diff 后调用 {@link #clear(String)} 释放。
 */
@Service
public class WorkspaceChangeTracker {

    private final ConcurrentHashMap<String, ConcurrentSkipListSet<String>> changedBySession = new ConcurrentHashMap<>();


    /**
     * 标记某会话改动过某个相对路径（相对项目根，使用正斜杠）
     * @param sessionId 会话 ID
     * @param relativePath 相对路径 比如 src/main/java/com/zsc/service/WorkspaceChangeTracker.java
     */
    public void markChanged(String sessionId, String relativePath) {
        if (sessionId == null || relativePath == null || relativePath.isBlank()) return;
        String normalized = relativePath.replace('\\', '/');
        changedBySession
                // 如果没有该会话的改动记录，则创建一个 如果有的话直接返回已有的 ConcurrentSkipListSet 这个computeIfAbsent在并发下安全
                .computeIfAbsent(sessionId, k -> new ConcurrentSkipListSet<>())
                // 添加该相对路径
                .add(normalized);
    }

    /** 返回该会话所有改动过的相对路径（不可变快照）。 */
    public Set<String> getChangedFiles(String sessionId) {
        if (sessionId == null) return Set.of();
        ConcurrentSkipListSet<String> set = changedBySession.get(sessionId);
        if (set == null || set.isEmpty()) return Set.of();
        // 返回一个不可变快照（只能读）
        return Collections.unmodifiableSet(new java.util.TreeSet<>(set));
    }

    /** 清空该会话的改动记录，通常在 apply / discard 后调用。
     *用户点「应用到原项目」
     *用户点「丢弃改动」 这个才会调用
     * */
    public void clear(String sessionId) {
        if (sessionId == null) return;
        changedBySession.remove(sessionId);
    }
}
