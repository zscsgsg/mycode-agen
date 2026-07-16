package com.zsc.tools;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;
import java.util.stream.Stream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 读取工作区文件。若文件不存在，会尝试从上传的原项目复制一份到工作区后再读。
 * 工作区路径 / 项目 ID / 原项目根目录通过构造函数注入，线程安全。
 */
@Slf4j
public class ReadFileTool implements Function<ReadFileTool.Request, String> {

    private final String workspaceRoot;
    private final String projectId;
    private final String uploadBasePath;

    public ReadFileTool(String workspaceRoot, String projectId, String uploadBasePath) {
        this.workspaceRoot = workspaceRoot;
        this.projectId = projectId;
        this.uploadBasePath = uploadBasePath;
    }

    public static class Request {
        private String relativePath;
        public String getRelativePath() { return relativePath; }
        public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
    }

    @Override
    public String apply(Request request) {
        log.info("[readFile] 调用 relativePath={}", request == null ? null : request.getRelativePath());
        if (workspaceRoot == null) {
            log.warn("[readFile] 未设置工作目录");
            return "错误：未设置工作目录";
        }

        Path workspaceFile = Paths.get(workspaceRoot, request.getRelativePath());
        log.info("[readFile] 工作区路径: {}, 文件存在: {}", workspaceFile, Files.exists(workspaceFile));
        // 如果工作区文件不存在，尝试从原项目复制
        if (!Files.exists(workspaceFile)) {
            if (projectId != null && uploadBasePath != null) {
                Path projectRoot = Paths.get(uploadBasePath, projectId);
                Path originalFile = projectRoot.resolve(request.getRelativePath());
                log.info("[readFile] 尝试从原项目复制 originalFile={}, 存在: {}", originalFile, Files.exists(originalFile));
                // fallback 1：直接拼接不到时，递归扫描匹配结尾同名文件（处理项目根目录前缀如 demo2/不一致的情况）
                if (!Files.exists(originalFile) && Files.isDirectory(projectRoot)) {
                    Path matched = findByRelativeSuffix(projectRoot, request.getRelativePath());
                    if (matched != null) {
                        log.info("[readFile] fallback(后缀匹配) 匹配到原项目文件: {}", matched);
                        originalFile = matched;
                    }
                }
                // fallback 2：去掉 src/main/java/ 或 src/test/java/ 前缀再尝试（处理 Agent 用标准Maven路径、但原项目为扁平结构的场景）
                if (!Files.exists(originalFile) && Files.isDirectory(projectRoot)) {
                    Path stripped = tryStripSrcPrefix(projectRoot, request.getRelativePath());
                    if (stripped != null) {
                        log.info("[readFile] fallback(前缀剥离) 匹配到原项目文件: {}", stripped);
                        originalFile = stripped;
                    }
                }
                if (Files.exists(originalFile)) {
                    try {
                        Files.createDirectories(workspaceFile.getParent());
                        Files.copy(originalFile, workspaceFile, StandardCopyOption.REPLACE_EXISTING);
                        log.info("[readFile] 已从原项目复制到工作区: {} -> {}", originalFile, workspaceFile);
                    } catch (IOException e) {
                        log.error("[readFile] 复制原项目文件失败", e);
                        return "复制原项目文件失败: " + e.getMessage();
                    }
                } else {
                    log.warn("[readFile] 文件不存在: {} (原项目中也没有)", request.getRelativePath());
                    return "文件不存在: " + request.getRelativePath() + " (原项目中也没有)";
                }
            } else {
                log.warn("[readFile] 文件不存在且无法定位原项目: {}", request.getRelativePath());
                return "文件不存在且无法定位原项目: " + request.getRelativePath();
            }
        }

        try {
            String content = Files.readString(workspaceFile);
            log.info("[readFile] 读取成功 path={}, 长度={}", workspaceFile, content.length());
            return content;
        } catch (IOException e) {
            log.error("[readFile] 读取文件失败", e);
            return "读取文件失败: " + e.getMessage();
        }
    }

    /**
     * 在 root 下递归查找路径以 relativePath 结尾的文件，优先路径较短的匹配。
     * 用于处理 AI 传入 "src/main/java/Bean/Student.java" 但原项目实际路径为 "demo2/src/main/java/Bean/Student.java" 的场景。
     */
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
            log.warn("[readFile] fallback 扫描失败 root={}", root, e);
            return null;
        }
    }

    /**
     * 尝试去掉 src/main/java/ 或 src/test/java/ 前缀后在原项目中查找文件。
     * 处理 Agent 使用标准 Maven 路径（如 src/main/java/Bean/Student.java）、
     * 但原项目为扁平布局（如 Bean/Student.java）的场景。
     */
    private Path tryStripSrcPrefix(Path projectRoot, String relativePath) {
        String stripped = relativePath.replace('\\', '/');
        for (String prefix : new String[]{"src/main/java/", "src/test/java/"}) {
            if (stripped.startsWith(prefix)) {
                Path candidate = projectRoot.resolve(stripped.substring(prefix.length()));
                if (Files.exists(candidate)) return candidate;
                // 再用后缀匹配兜底
                return findByRelativeSuffix(projectRoot, stripped.substring(prefix.length()));
            }
        }
        return null;
    }
}
