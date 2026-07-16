package com.zsc.tools;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 在工作区或原项目中精确搜索字符串/正则表达式。
 * 类似 Cursor 里的 ripgrep / grep，用于知道具体类名/方法名/注解时的精确检索。
 * 与 searchCode（语义检索）互补：语义搜不到时切 grep 精确搜。
 */
@Slf4j
public class GrepTool implements Function<GrepTool.Request, String> {

    private final String workspaceRoot;
    private final String projectId;
    private final String uploadBasePath;
    private static final int MAX_RESULTS = 30;
    private static final int MAX_LINE_LENGTH = 300;

    public GrepTool(String workspaceRoot, String projectId, String uploadBasePath) {
        this.workspaceRoot = workspaceRoot;
        this.projectId = projectId;
        this.uploadBasePath = uploadBasePath;
    }

    public static class Request {
        private String pattern;
        private String filePattern; // 可选，如 "*.java"
        private String source;      // "workspace" 或 "original"，默认 workspace

        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }
        public String getFilePattern() { return filePattern; }
        public void setFilePattern(String filePattern) { this.filePattern = filePattern; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    @Override
    public String apply(Request request) {
        String pattern = request.getPattern();
        String filePattern = request.getFilePattern();
        String source = request.getSource() == null ? "original" : request.getSource();

        log.info("[grepSearch] pattern={}, filePattern={}, source={}", pattern, filePattern, source);

        if (pattern == null || pattern.isBlank()) {
            return "错误：pattern 不能为空";
        }

        // 确定搜索根目录
        Path searchRoot;
        if ("original".equals(source) && projectId != null && uploadBasePath != null) {
            searchRoot = Paths.get(uploadBasePath, projectId);
        } else {
            searchRoot = Paths.get(workspaceRoot);
        }

        if (!Files.isDirectory(searchRoot)) {
            return "错误：搜索目录不存在: " + searchRoot;
        }

        // 编译正则（如果失败则当作普通字符串匹配）
        Pattern regex;
        try {
            regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            regex = Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE);
        }

        // glob 过滤器
        PathMatcher globMatcher = null;
        if (filePattern != null && !filePattern.isBlank()) {
            globMatcher = FileSystems.getDefault().getPathMatcher("glob:" + filePattern);
        }

        List<String> results = new ArrayList<>();
        int matchCount = 0;

        try {
            final PathMatcher finalGlobMatcher = globMatcher;
            final Pattern finalRegex = regex;

            matchCount = searchDirectory(searchRoot, finalRegex, finalGlobMatcher, results);
        } catch (IOException e) {
            log.error("[grepSearch] 搜索失败", e);
            return "错误：搜索过程中发生 IO 异常: " + e.getMessage();
        }

        if (results.isEmpty()) {
            return String.format("未找到匹配 '%s' 的内容（搜索目录：%s）", pattern, searchRoot);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("grep 搜索 '%s' 命中 %d 处（最多显示 %d 条）：\n\n", pattern, matchCount, MAX_RESULTS));
        for (String line : results) {
            sb.append(line).append("\n");
        }
        if (matchCount > MAX_RESULTS) {
            sb.append(String.format("\n... 还有 %d 处未显示，请缩小搜索范围或加 filePattern 过滤", matchCount - MAX_RESULTS));
        }
        return sb.toString();
    }

    private int searchDirectory(Path root, Pattern regex, PathMatcher globMatcher, List<String> results) throws IOException {
        int[] count = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (count[0] >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                // 跳过隐藏目录和编译产物
                String rel = root.relativize(file).toString();
                if (rel.contains(".git") || rel.contains("target") || rel.contains("node_modules")
                        || rel.contains(".class") || rel.contains(".jar")) {
                    return FileVisitResult.CONTINUE;
                }
                // glob 过滤
                if (globMatcher != null && !globMatcher.matches(file.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }
                // 只搜文本文件（简单判断后缀）
                String name = file.getFileName().toString().toLowerCase();
                if (name.endsWith(".class") || name.endsWith(".jar") || name.endsWith(".png")
                        || name.endsWith(".jpg") || name.endsWith(".gif") || name.endsWith(".ico")) {
                    return FileVisitResult.CONTINUE;
                }

                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (count[0] >= MAX_RESULTS) break;
                        String line = lines.get(i);
                        if (regex.matcher(line).find()) {
                            count[0]++;
                            String display = line.length() > MAX_LINE_LENGTH
                                    ? line.substring(0, MAX_LINE_LENGTH) + "..."
                                    : line;
                            results.add(String.format("%s:L%d | %s", rel, i + 1, display.trim()));
                        }
                    }
                } catch (Exception e) {
                    // 无法读取的文件跳过
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (dirName.startsWith(".") || dirName.equals("target") || dirName.equals("node_modules")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }
}
