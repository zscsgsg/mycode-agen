package com.zsc.tools;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 列出工作区或原项目的目录结构。给 AI 一个"看见整体"的能力，避免 searchCode 摸黑抽样。
 * 工作区路径 / 项目 ID / 原项目根目录通过构造函数注入，线程安全。
 */
@Slf4j
public class ListDirTool implements Function<ListDirTool.Request, String> {

    private static final int DEFAULT_MAX_DEPTH = 4;
    private static final int MAX_ENTRIES = 500;

    /** 默认忽略的目录（避免 AI 看到 target/node_modules 等噪音） */
    private static final List<String> IGNORED_DIRS = List.of(
            "target", "node_modules", ".git", ".idea", ".vscode",
            "build", "dist", "out", ".gradle", ".mvn",
            "modifications" // 我们自己写入的备份目录
    );

    private final String workspaceRoot;
    private final String projectId;
    private final String uploadBasePath;

    public ListDirTool(String workspaceRoot, String projectId, String uploadBasePath) {
        this.workspaceRoot = workspaceRoot;
        this.projectId = projectId;
        this.uploadBasePath = uploadBasePath;
    }

    public static class Request {
        /** 起始相对路径，留空表示项目根 */
        private String path;
        /** 来源："workspace"（默认）或 "original" */
        private String source;
        /** 最大递归深度，默认 4 */
        private Integer maxDepth;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public Integer getMaxDepth() { return maxDepth; }
        public void setMaxDepth(Integer maxDepth) { this.maxDepth = maxDepth; }
    }

    @Override
    public String apply(Request request) {
        String relPath = request == null || request.getPath() == null ? "" : request.getPath();
        String source = request == null || request.getSource() == null ? "workspace" : request.getSource().toLowerCase();
        int maxDepth = request != null && request.getMaxDepth() != null && request.getMaxDepth() > 0
                ? Math.min(request.getMaxDepth(), 8) : DEFAULT_MAX_DEPTH;
        log.info("[listDir] 调用 path={}, source={}, maxDepth={}", relPath, source, maxDepth);

        Path root = resolveRoot(source);
        if (root == null) {
            return "错误：无法定位 " + source + " 根目录";
        }
        Path target = relPath.isEmpty() ? root : root.resolve(relPath).normalize();
        if (!target.startsWith(root)) {
            return "错误：路径越界，不允许访问项目外部";
        }
        if (!Files.exists(target)) {
            return "目录不存在: " + relPath + "（来源：" + source + "）";
        }
        if (!Files.isDirectory(target)) {
            return "不是目录: " + relPath;
        }

        List<String> lines = new ArrayList<>();
        lines.add("[" + source + "] 根目录: " + root);
        lines.add("当前目录: " + (relPath.isEmpty() ? "(项目根)" : relPath));
        int[] count = new int[]{0};
        boolean[] truncated = new boolean[]{false};
        try {
            collect(root, target, 0, maxDepth, lines, count, truncated);
        } catch (IOException e) {
            log.error("[listDir] 遍历失败", e);
            return "遍历目录失败: " + e.getMessage();
        }
        if (truncated[0]) {
            lines.add("... （仅显示前 " + MAX_ENTRIES + " 条，请用更精确的 path 缩小范围）");
        }
        return String.join("\n", lines);
    }

    private Path resolveRoot(String source) {
        if ("original".equals(source)) {
            if (uploadBasePath == null || projectId == null) return null;
            Path p = Paths.get(uploadBasePath, projectId);
            return Files.isDirectory(p) ? p : null;
        }
        if (workspaceRoot == null) return null;
        Path p = Paths.get(workspaceRoot);
        return Files.isDirectory(p) ? p : null;
    }

    private void collect(Path root, Path dir, int depth, int maxDepth,
                         List<String> lines, int[] count, boolean[] truncated) throws IOException {
        if (depth >= maxDepth || count[0] >= MAX_ENTRIES) {
            if (count[0] >= MAX_ENTRIES) truncated[0] = true;
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> entries = stream
                    .sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
            for (Path entry : entries) {
                if (count[0] >= MAX_ENTRIES) {
                    truncated[0] = true;
                    return;
                }
                String name = entry.getFileName().toString();
                boolean isDir = Files.isDirectory(entry);
                if (isDir && IGNORED_DIRS.contains(name)) continue;
                String indent = "  ".repeat(depth);
                String rel = root.relativize(entry).toString().replace('\\', '/');
                if (isDir) {
                    lines.add(indent + "📁 " + name + "/  (" + rel + ")");
                    count[0]++;
                    collect(root, entry, depth + 1, maxDepth, lines, count, truncated);
                } else {
                    long size = 0;
                    try { size = Files.size(entry); } catch (IOException ignored) {}
                    lines.add(indent + "📄 " + name + "  (" + rel + ", " + size + "B)");
                    count[0]++;
                }
            }
        }
    }
}
