package com.zsc.tools;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.zsc.service.WorkspaceChangeTracker;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/**
 * 专门是在工作区用于写文件的工具类，提供以下功能：
 * 你可以把它理解成 “沙箱文件管家”，所有对沙箱里文件的 写入、编辑、备份、变更记录 都通过它完成
 * 写文件 / 备份 / 重建向量索引的共用逻辑，由 writeFile 和 editFile 复用。
 * 状态全部以 final 字段持有，线程安全，可在多线程内被多个 Tool 共享。
 */
@Slf4j
public class WorkspaceWriter {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    // 工作区根目录
    private final String workspaceRoot;
    // 项目 ID
    private final String projectId;
    // 上传项目根目录
    private final String uploadBasePath;
    // 会话 ID
    private final String sessionId;
    // 改动记录
    private final WorkspaceChangeTracker changeTracker;

    public WorkspaceWriter(String workspaceRoot,
                           String projectId,
                           String uploadBasePath,
                           String sessionId,
                           WorkspaceChangeTracker changeTracker) {
        this.workspaceRoot = workspaceRoot;
        this.projectId = projectId;
        this.uploadBasePath = uploadBasePath;
        this.sessionId = sessionId;
        this.changeTracker = changeTracker;
    }

    /**
     * 仅返回工作区目标路径（绝对路径），不验证是否存在（writeFile 用：覆盖写入）。
     * 把 relativePath 拼接到工作区根目录后面，得到一个绝对路径

     * */
    public Path resolveWritePath(String relativePath) {
        return Paths.get(workspaceRoot, relativePath);
    }

    /**
     * 解析编辑目标：
     * 1. 工作区已有 → 直接返回
     * 2. 工作区没有但原项目有 → 从原项目复制到工作区后返回
     * 3. 都找不到（含 demo2/ 前缀差异 fallback 后） → 返回 null
     */
    public Path resolveOrCopy(String relativePath) throws IOException {
        Path workspaceFile = Paths.get(workspaceRoot, relativePath);
        // 工作区判断是否真的有这个文件目录 有 → 直接返回
        if (Files.exists(workspaceFile)) return workspaceFile;
        // 没有看看是否有项目 ID 和上传项目根目录 如果都为空 这个相当于没有上传文件，返回空
        if (projectId == null || uploadBasePath == null) return null;
        //如果有 项目ID 和上传项目根目录 这个拼接成目录路径
        Path projectRoot = Paths.get(uploadBasePath, projectId);
        // 判断是否是个目录 不是直接返回null
        if (!Files.isDirectory(projectRoot)) return null;
        // 这个是目录，这个进行拼接  就是项目根目录 + 文件路径
        Path originalFile = projectRoot.resolve(relativePath);
        //判读是否存在 就是这个路径上的文件是否存在
        if (!Files.exists(originalFile)) {
            //不存在 模糊匹配  会递归遍历目录 只关心结尾是否一样（就是目录结构不一样看看结尾是否一样）
            Path matched = findByRelativeSuffix(projectRoot, relativePath);
            // 如果匹配到 就返回这个路径
            if (matched != null) originalFile = matched;
        }

        // 再次匹配 fallback：去掉 src/main/java/ 或 src/test/java/ 前缀再尝试（处理 Agent 用标准Maven路径、但原项目为扁平结构的场景）
        if (!Files.exists(originalFile)) {
            Path stripped = tryStripSrcPrefix(projectRoot, relativePath);
            if (stripped != null) originalFile = stripped;
        }
        // 不存在 就返回null
        if (!Files.exists(originalFile)) return null;
        //存在 创建创建工作区父目录
        Files.createDirectories(workspaceFile.getParent());
        //复制文件到工作区  StandardCopyOption.REPLACE_EXISTING 工作区的文件有冲突时替换
        Files.copy(originalFile, workspaceFile, StandardCopyOption.REPLACE_EXISTING);
        log.info("[workspaceWriter] 从原项目复制到工作区 {} -> {}", originalFile, workspaceFile);
        return workspaceFile;
    }

    /**
     * 备份现有文件 + 写入新内容 + 记录会话改动。
     * <p>
     * B 策略：沙箱期间不更新向量库，我们只在用户点「应用到原项目」时
     * 由 {@link com.zsc.service.AgentDiffService#applyDiff} 触发 {@code reindexProjectFromDisk}
     * 全项目重建（避免沙箱中间态污染正式向量库）。
     */
    public void persist(Path target, String relativePath, String content) throws IOException {
        boolean existed = Files.exists(target);
        if (existed) {
            // 这个文件存在 那就先备份
            createBackup(target);
        }
        // 创建工作区父目录
        Files.createDirectories(target.getParent());
        // 写入新内容
        Files.writeString(target, content);
        // 记录本会话改动，供 "工作区 diff 应用" 功能使用
        if (changeTracker != null && sessionId != null) {
            changeTracker.markChanged(sessionId, relativePath);
        }
    }

    private void createBackup(Path originalFile) throws IOException {
        // 工作区根目录
        Path wsRoot = Paths.get(workspaceRoot);
        // 相对路径 从 wsRoot出发，怎么走到 originalFile 只保留originalFile
        String relativePathStr = wsRoot.relativize(originalFile).toString();
        //构建备份目录  获取relativePathStr的父目录
        Path parent = Paths.get(relativePathStr).getParent();
        //拼接备份目录路径
        Path backupDir = wsRoot.resolve("modifications").resolve("backup")
                .resolve(parent == null ? Paths.get("") : parent);
        //创建备份目录
        Files.createDirectories(backupDir);
        //生成时间戳（用来区分备份版本）
        String timestamp = LocalDateTimeUtil.format(LocalDateTime.now(), TIMESTAMP_FORMAT);
        //生成备份文件名
        String backupFileName = originalFile.getFileName().toString() + "." + timestamp + ".bak";
        //复制文件到备份目录
        Files.copy(originalFile, backupDir.resolve(backupFileName), StandardCopyOption.REPLACE_EXISTING);
    }

    private Path findByRelativeSuffix(Path root, String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        return rel.equals(normalized) || rel.endsWith("/" + normalized);
                    })
                    .min((a, b) -> Integer.compare(
                            root.relativize(a).getNameCount(),
                            root.relativize(b).getNameCount()))
                    .orElse(null);
        } catch (IOException e) {
            log.warn("[workspaceWriter] fallback 扫描失败 root={}", root, e);
            return null;
        }
    }

    /**
     * 尝试去掉 src/main/java/ 或 src/test/java/ 前缀后在原项目中查找文件。
     * 处理 Agent 使用标准 Maven 路径、但原项目为扁平布局的场景。
     */
    private Path tryStripSrcPrefix(Path projectRoot, String relativePath) {
        String stripped = relativePath.replace('\\', '/');
        for (String prefix : new String[]{"src/main/java/", "src/test/java/"}) {
            if (stripped.startsWith(prefix)) {
                Path candidate = projectRoot.resolve(stripped.substring(prefix.length()));
                if (Files.exists(candidate)) return candidate;
                return findByRelativeSuffix(projectRoot, stripped.substring(prefix.length()));
            }
        }
        return null;
    }
}
