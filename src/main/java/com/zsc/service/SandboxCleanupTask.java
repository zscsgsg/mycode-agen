package com.zsc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定时清理超时的 Agent 沙箱工作区。
 * <p>
 * 每小时扫描一次 workspace 目录，删除超过 24 小时未修改的 session 目录，
 * 防止用户忘记丢弃 diff 导致磁盘堆积。
 */
@Component
@Slf4j
public class SandboxCleanupTask {

    @Value("${codemate.workspace.base-path:D:/codemate_data/workspace}")
    private String workspaceBasePath;

    /** 沙箱目录超过多少小时未修改即清理（默认 24 小时） */
    @Value("${codemate.workspace.cleanup-hours:24}")
    private int cleanupHours;

    /**
     * 每小时执行一次（cron: 0 0 * * * ?，即每小时整点）
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void cleanupExpiredWorkspaces() {
        Path basePath = Paths.get(workspaceBasePath);
        if (!Files.exists(basePath)) {
            return;
        }

        Instant cutoff = Instant.now().minus(cleanupHours, ChronoUnit.HOURS);
        AtomicInteger deletedCount = new AtomicInteger(0);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(basePath, "session_*")) {
            for (Path sessionDir : stream) {
                if (!Files.isDirectory(sessionDir)) {
                    continue;
                }

                try {
                    BasicFileAttributes attrs = Files.readAttributes(sessionDir, BasicFileAttributes.class);
                    Instant lastModified = attrs.lastModifiedTime().toInstant();

                    if (lastModified.isBefore(cutoff)) {
                        deleteDirectoryRecursively(sessionDir);
                        deletedCount.incrementAndGet();
                        log.info("[SandboxCleanup] 清理过期沙箱: {}, 最后修改: {}",
                                sessionDir.getFileName(), lastModified);
                    }
                } catch (IOException e) {
                    log.warn("[SandboxCleanup] 处理沙箱失败: {}, 错误: {}",
                            sessionDir.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[SandboxCleanup] 扫描 workspace 目录失败", e);
        }

        if (deletedCount.get() > 0) {
            log.info("[SandboxCleanup] 本次清理 {} 个过期沙箱", deletedCount.get());
        }
    }

    /**
     * 递归删除目录及其所有子文件
     */
    private void deleteDirectoryRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
