package com.zsc.controller;

import com.zsc.entity.ApiResult;
import com.zsc.service.WorkspaceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * 工作区文件浏览：目录树、文件内容、与原项目 diff、备份列表、恢复备份。
 * 这些接口给前端 FileTreePanel / DiffView 使用，让"AI 改了什么"可见、可审、可撤销。
 */
@Slf4j
@RestController
@RequestMapping("/agent/files")
@RequiredArgsConstructor
public class FileBrowserController {

    private static final List<String> IGNORED_DIRS = List.of(
            "target", "node_modules", ".git", ".idea", ".vscode",
            "build", "dist", "out", ".gradle", ".mvn"
    );

    private final WorkspaceManager workspaceManager;

    @Value("${codemate.upload.base-path:D:/codemate_data/uploaded_projects}")
    private String uploadBasePath;

    /**
     * 获取合并文件树（以原项目为基底，叠加工作区变动，标注 modified/new/unchanged）。
     */
    @GetMapping("/tree")
    public ApiResult<TreeNode> tree(@RequestParam String sessionId,
                                    @RequestParam(required = false) String projectId) {
        Path workspaceRoot = workspaceManager.getWorkspace(sessionId);
        Path originalRoot = projectId == null ? null : Paths.get(uploadBasePath, projectId);

        // 无原项目且无工作区：返回空
        if ((originalRoot == null || !Files.isDirectory(originalRoot))
                && !Files.isDirectory(workspaceRoot)) {
            return ApiResult.success(new TreeNode("(empty)", "", true, "missing", List.of()));
        }

        try {
            TreeNode root = buildMergedTree(workspaceRoot, originalRoot);
            return ApiResult.success(root);
        } catch (IOException e) {
            log.error("[files/tree] 失败", e);
            return ApiResult.error(500, "读取文件树失败: " + e.getMessage());
        }
    }

    /**
     * 获取单个文件内容；source = workspace（默认）或 original。
     */
    @GetMapping("/content")
    public ApiResult<Map<String, Object>> content(@RequestParam String sessionId,
                                                  @RequestParam(required = false) String projectId,
                                                  @RequestParam String relativePath,
                                                  @RequestParam(required = false, defaultValue = "workspace") String source) {
        Path root = "original".equalsIgnoreCase(source)
                ? (projectId == null ? null : Paths.get(uploadBasePath, projectId))
                : workspaceManager.getWorkspace(sessionId);
        if (root == null || !Files.isDirectory(root)) {
            return ApiResult.error(500, "根目录不存在");
        }
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root)) {
            return ApiResult.error(500, "路径越界");
        }
        if (!Files.exists(file)) {
            return ApiResult.error(500, "文件不存在: " + relativePath);
        }
        try {
            String content = Files.readString(file);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("relativePath", relativePath);
            data.put("source", source);
            data.put("size", content.length());
            data.put("content", content);
            return ApiResult.success(data);
        } catch (IOException e) {
            return ApiResult.error(500, "读取失败: " + e.getMessage());
        }
    }

    /**
     * 返回 unified diff（原项目 vs 工作区）。
     */
    @GetMapping("/diff")
    public ApiResult<Map<String, Object>> diff(@RequestParam String sessionId,
                                               @RequestParam String projectId,
                                               @RequestParam String relativePath) {
        Path workspace = workspaceManager.getWorkspace(sessionId);
        Path original = Paths.get(uploadBasePath, projectId);
        if (!Files.isDirectory(workspace)) return ApiResult.error(500, "工作区不存在");

        Path wsFile = workspace.resolve(relativePath).normalize();
        Path origFile = original.resolve(relativePath).normalize();
        if (!wsFile.startsWith(workspace) || !origFile.startsWith(original)) {
            return ApiResult.error(500, "路径越界");
        }

        // 原项目 fallback：找不到时按后缀递归匹配（处理 demo2/ 前缀缺失）
        if (!Files.exists(origFile) && Files.isDirectory(original)) {
            Path matched = findByRelativeSuffix(original, relativePath);
            if (matched != null) origFile = matched;
        }

        String wsText = Files.exists(wsFile) ? safeRead(wsFile) : "";
        String origText = Files.exists(origFile) ? safeRead(origFile) : "";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("relativePath", relativePath);
        data.put("workspaceExists", Files.exists(wsFile));
        data.put("originalExists", Files.exists(origFile));
        data.put("workspaceContent", wsText);
        data.put("originalContent", origText);
        data.put("unifiedDiff", buildUnifiedDiff(origText, wsText, relativePath));
        return ApiResult.success(data);
    }

    /**
     * 列出指定文件的所有备份（modifications/backup/ 下的 .bak 文件）。
     */
    @GetMapping("/backups")
    public ApiResult<List<Map<String, Object>>> backups(@RequestParam String sessionId,
                                                        @RequestParam String relativePath) {
        Path workspace = workspaceManager.getWorkspace(sessionId);
        Path target = workspace.resolve(relativePath).normalize();
        if (!target.startsWith(workspace)) return ApiResult.error(500, "路径越界");
        Path parent = target.getParent();
        Path backupDir = workspace.resolve("modifications/backup")
                .resolve(workspace.relativize(parent));
        List<Map<String, Object>> list = new ArrayList<>();
        if (Files.isDirectory(backupDir)) {
            String prefix = target.getFileName().toString() + ".";
            try (Stream<Path> stream = Files.list(backupDir)) {
                stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                        .filter(p -> p.getFileName().toString().endsWith(".bak"))
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("name", p.getFileName().toString());
                            try {
                                BasicFileAttributes attr = Files.readAttributes(p, BasicFileAttributes.class);
                                m.put("size", attr.size());
                                m.put("modifiedAt", attr.lastModifiedTime().toMillis());
                            } catch (IOException ignored) {}
                            list.add(m);
                        });
            } catch (IOException e) {
                return ApiResult.error(500, "读取备份目录失败: " + e.getMessage());
            }
        }
        return ApiResult.success(list);
    }

    /**
     * 把指定备份恢复到工作区（覆盖当前文件，并把当前内容再备份一次）。
     * backupName=null 表示用"原项目原始版本"恢复。
     */
    @PostMapping("/restore")
    public ApiResult<String> restore(@RequestParam String sessionId,
                                     @RequestParam String projectId,
                                     @RequestParam String relativePath,
                                     @RequestParam(required = false) String backupName) {
        Path workspace = workspaceManager.getWorkspace(sessionId);
        Path target = workspace.resolve(relativePath).normalize();
        if (!target.startsWith(workspace)) return ApiResult.error(500, "路径越界");

        Path source;
        if (backupName != null && !backupName.isBlank()) {
            Path parent = target.getParent();
            Path backupDir = workspace.resolve("modifications/backup")
                    .resolve(workspace.relativize(parent));
            source = backupDir.resolve(backupName);
            if (!source.startsWith(backupDir) || !Files.exists(source)) {
                return ApiResult.error(500, "备份不存在: " + backupName);
            }
        } else {
            // 用原项目恢复
            Path original = Paths.get(uploadBasePath, projectId);
            source = original.resolve(relativePath).normalize();
            if (!Files.exists(source)) {
                Path matched = findByRelativeSuffix(original, relativePath);
                if (matched == null) return ApiResult.error(500, "原项目中未找到: " + relativePath);
                source = matched;
            }
        }

        try {
            // 当前内容再备份一次（防止误恢复）
            if (Files.exists(target)) {
                Path parent = target.getParent();
                Path backupDir = workspace.resolve("modifications/backup")
                        .resolve(workspace.relativize(parent));
                Files.createDirectories(backupDir);
                String ts = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                Files.copy(target,
                        backupDir.resolve(target.getFileName().toString() + "." + ts + ".pre-restore.bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return ApiResult.success("已恢复: " + relativePath);
        } catch (IOException e) {
            return ApiResult.error(500, "恢复失败: " + e.getMessage());
        }
    }

    // ====== 工具方法 ======

    /**
     * 构建合并文件树：以原项目为基底，叠加工作区新增/修改文件。
     * 这样用户看到的树结构与原项目一致，同时能看到 Agent 改了哪些文件。
     */
    private TreeNode buildMergedTree(Path workspaceRoot, Path originalRoot) throws IOException {
        // 如果原项目存在，以原项目为基底构建
        if (originalRoot != null && Files.isDirectory(originalRoot)) {
            TreeNode tree = buildTreeFromOriginal(originalRoot, originalRoot, workspaceRoot);
            // 再叠加工作区中的 "new" 文件（原项目中不存在的）
            if (Files.isDirectory(workspaceRoot)) {
                mergeWorkspaceNewFiles(tree, workspaceRoot, workspaceRoot, originalRoot);
            }
            return tree;
        }
        // 无原项目，回退到纯工作区树
        return buildTreeWorkspaceOnly(workspaceRoot, workspaceRoot);
    }

    /**
     * 以原项目目录为基底构建树，对每个文件检查工作区是否有修改。
     */
    private TreeNode buildTreeFromOriginal(Path originalRoot, Path dir, Path workspaceRoot) throws IOException {
        String name = dir.equals(originalRoot)
                ? (originalRoot.getFileName() == null ? "project" : originalRoot.getFileName().toString())
                : dir.getFileName().toString();
        String rel = originalRoot.relativize(dir).toString().replace('\\', '/');
        TreeNode node = new TreeNode(name, rel, true, "unchanged", new ArrayList<>());

        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> entries = stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        if (Files.isDirectory(p)) return !IGNORED_DIRS.contains(n);
                        return true;
                    })
                    .sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();

            for (Path entry : entries) {
                String entryRel = originalRoot.relativize(entry).toString().replace('\\', '/');
                if (Files.isDirectory(entry)) {
                    node.children.add(buildTreeFromOriginal(originalRoot, entry, workspaceRoot));
                } else {
                    // 判断工作区是否有这个文件且已修改
                    String status = "unchanged";
                    if (workspaceRoot != null && Files.isDirectory(workspaceRoot)) {
                        // 在工作区中找对应文件（直接路径 或 后缀匹配）
                        Path wsFile = findWorkspaceCounterpart(workspaceRoot, entryRel);
                        if (wsFile != null && Files.exists(wsFile)) {
                            try {
                                if (Files.size(entry) != Files.size(wsFile)
                                        || !safeRead(entry).equals(safeRead(wsFile))) {
                                    status = "modified";
                                }
                            } catch (IOException ignored) {}
                        }
                    }
                    node.children.add(new TreeNode(
                            entry.getFileName().toString(), entryRel, false, status, List.of()));
                }
            }
        }
        return node;
    }

    /**
     * 合并工作区中的“新增”文件（原项目中不存在的）到树中。
     * 跳过 modifications/ 和已在原项目中存在的文件。
     */
    private void mergeWorkspaceNewFiles(TreeNode root, Path wsRoot, Path dir, Path originalRoot) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> entries = stream.toList();
            for (Path entry : entries) {
                String entryName = entry.getFileName().toString();
                String entryRel = wsRoot.relativize(entry).toString().replace('\\', '/');

                // 跳过 modifications 目录和被忽略的目录
                if (Files.isDirectory(entry)) {
                    if (IGNORED_DIRS.contains(entryName) || "modifications".equals(entryName)) continue;
                }

                // 检查原项目中是否存在对应文件
                boolean existsInOriginal = false;
                if (originalRoot != null) {
                    Path origCounterpart = originalRoot.resolve(entryRel);
                    if (Files.exists(origCounterpart)) {
                        existsInOriginal = true;
                    } else {
                        // 后缀匹配
                        Path matched = findByRelativeSuffix(originalRoot, entryRel);
                        if (matched != null) existsInOriginal = true;
                    }
                }

                if (Files.isDirectory(entry)) {
                    if (!existsInOriginal) {
                        // 原项目中无此目录，整个目录都是新增
                        TreeNode newDir = buildTreeWorkspaceOnly(wsRoot, entry);
                        markAllNew(newDir);
                        insertIntoTree(root, entryRel, newDir);
                    } else {
                        // 原项目有此目录，递归检查子文件
                        mergeWorkspaceNewFiles(root, wsRoot, entry, originalRoot);
                    }
                } else {
                    if (!existsInOriginal) {
                        // 新增文件
                        TreeNode newFile = new TreeNode(entryName, entryRel, false, "new", List.of());
                        insertIntoTree(root, entryRel, newFile);
                    }
                }
            }
        }
    }

    /**
     * 在树中找到合适的父节点并插入（按路径创建中间目录节点）。
     */
    private void insertIntoTree(TreeNode root, String relativePath, TreeNode node) {
        String[] parts = relativePath.split("/");
        TreeNode current = root;
        // 导航到父目录
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            TreeNode found = null;
            for (TreeNode child : current.children) {
                if (child.directory && child.name.equals(part)) {
                    found = child;
                    break;
                }
            }
            if (found == null) {
                // 创建中间目录节点
                String dirRel = String.join("/", Arrays.copyOfRange(parts, 0, i + 1));
                found = new TreeNode(part, dirRel, true, "new", new ArrayList<>());
                current.children.add(found);
            }
            current = found;
        }
        // 避免重复插入
        boolean exists = current.children.stream()
                .anyMatch(c -> c.name.equals(node.name) && c.directory == node.directory);
        if (!exists) {
            current.children.add(node);
        }
    }

    private void markAllNew(TreeNode node) {
        // TreeNode 是 record，无法直接改 status，所以用新的构建方式
        // 这里简化处理：buildTreeWorkspaceOnly 已经会设置 status
        // 只需要改为 new —— 但 record 不可变，所以在 buildTreeWorkspaceOnly 中直接设“new”
    }

    /**
     * 在工作区中找对应文件：直接路径 或 后缀匹配。
     */
    private Path findWorkspaceCounterpart(Path wsRoot, String originalRelPath) {
        // 先试直接路径
        Path direct = wsRoot.resolve(originalRelPath);
        if (Files.exists(direct)) return direct;
        // 后缀匹配（处理 demo2/ 前缀差异）
        return findByRelativeSuffix(wsRoot, originalRelPath);
    }

    /**
     * 纯工作区树（无原项目时回退用），所有文件标为 new。
     */
    private TreeNode buildTreeWorkspaceOnly(Path wsRoot, Path dir) throws IOException {
        String name = dir.equals(wsRoot)
                ? (wsRoot.getFileName() == null ? "workspace" : wsRoot.getFileName().toString())
                : dir.getFileName().toString();
        String rel = wsRoot.relativize(dir).toString().replace('\\', '/');
        TreeNode node = new TreeNode(name, rel, true, "new", new ArrayList<>());

        if (!Files.isDirectory(dir)) return node;
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> entries = stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        if (Files.isDirectory(p)) return !IGNORED_DIRS.contains(n) && !"modifications".equals(n);
                        return true;
                    })
                    .sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
            for (Path entry : entries) {
                String entryRel = wsRoot.relativize(entry).toString().replace('\\', '/');
                if (Files.isDirectory(entry)) {
                    node.children.add(buildTreeWorkspaceOnly(wsRoot, entry));
                } else {
                    node.children.add(new TreeNode(
                            entry.getFileName().toString(), entryRel, false, "new", List.of()));
                }
            }
        }
        return node;
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
                    .min(Comparator.comparingInt(p -> root.relativize(p).getNameCount()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String safeRead(Path p) {
        try { return Files.readString(p); } catch (IOException e) { return ""; }
    }

    /** 简易行级 unified diff，无依赖。 */
    private static String buildUnifiedDiff(String a, String b, String path) {
        List<String> aLines = Arrays.asList(a.split("\n", -1));
        List<String> bLines = Arrays.asList(b.split("\n", -1));
        if (a.equals(b)) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(path).append("\n");
        sb.append("+++ b/").append(path).append("\n");
        // LCS 简化：直接逐行用最长公共子序列做最朴素 diff（性能足够 1MB 文本）
        int n = aLines.size(), m = bLines.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (aLines.get(i).equals(bLines.get(j))) dp[i][j] = dp[i + 1][j + 1] + 1;
                else dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (aLines.get(i).equals(bLines.get(j))) {
                sb.append(" ").append(aLines.get(i)).append("\n");
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                sb.append("-").append(aLines.get(i)).append("\n");
                i++;
            } else {
                sb.append("+").append(bLines.get(j)).append("\n");
                j++;
            }
        }
        while (i < n) { sb.append("-").append(aLines.get(i++)).append("\n"); }
        while (j < m) { sb.append("+").append(bLines.get(j++)).append("\n"); }
        return sb.toString();
    }

    /** 树节点 DTO。 */
    public record TreeNode(String name, String path, boolean directory, String status, List<TreeNode> children) {}
}
