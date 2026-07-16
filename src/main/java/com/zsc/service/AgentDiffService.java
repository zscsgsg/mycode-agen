package com.zsc.service;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;
import com.zsc.indexing.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Agent 模式的「工作区 diff 应用到原项目」服务。
 *
 * 仅处理本会话 Agent 改动过的文件（由 {@link WorkspaceChangeTracker} 记录）。
 *
 * B 策略（增量重建）：
 *  - 沙箱期间：Agent 改文件只写沙箱，**不更新**向量库（避免沙箱中间态污染正式库）；
 *  - 用户点「应用」：把沙箱里改动过的文件覆盖到 uploaded_projects/{projectId}/，
 *    再对每个改动文件单独调用 {@link IndexingService#updateFileVector(String, String, String)}（毫秒级）；
 *  - 用户点「丢弃」：仅清空 tracker，沙箱文件保留（不影响后续会话操作）。
 *
 * 注意：单文件 reindex 按 file_path 精确匹配清理旧切片。如向量库里存在历史脏数据
 * （比如早期双写策略残留的同名文件不同路径变体），需要走 {@link IndexingService#reindexProjectFromDisk(String)}
 * 做一次全量重建（暴露在 /agent/reindex-project 端点）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentDiffService {

    private final WorkspaceManager workspaceManager;
    private final WorkspaceChangeTracker changeTracker;
    private final IndexingService indexingService;

    @Value("${codemate.upload.base-path: D:/codemate_data/uploaded_projects}")
    private String uploadBasePath;

    public enum Status { CREATED, MODIFIED, UNCHANGED }

    /**
     * 一条 diff 记录。前端按需展示 unifiedDiff（git diff 风格），统计信息用 additions/deletions。
     */
    public record DiffEntry(
            String path,           // 文件路径（哪个文件被改了）
            Status status,        // 状态：新建 / 修改 / 没变化
            int additions,        // 加了多少行代码
            int deletions,        // 删了多少行代码
            String unifiedDiff,   // 代码差异（红色删除、绿色新增那种）
            String oldContent,    // 改之前的内容
            String newContent     // 改之后的内容
    ) {}

    /**
     * 计算本会话所有改动文件的 diff，相对于 uploaded_projects/{projectId}/。
     * 根据会话 ID + 项目 ID，计算所有改动文件的 diff
     */
    public List<DiffEntry> computeDiff(String sessionId, String projectId) throws IOException {
        //根据会话ID获取所有改动文件路径
        Set<String> changed = changeTracker.getChangedFiles(sessionId);
        if (changed.isEmpty()) return List.of();
        //获取沙箱工作区根路径
        Path workspaceRoot = workspaceManager.getWorkspace(sessionId);
        //获取原项目根路径
        Path projectRoot = Paths.get(uploadBasePath, projectId);

        List<DiffEntry> result = new ArrayList<>();
        //遍历所有改动文件
        for (String relPath : changed) {
            try {
                //获取沙箱文件路径  resolve这个是拼接路径
                Path wsFile = workspaceRoot.resolve(relPath);
                //沙箱文件是否存在
                if (!Files.exists(wsFile)) {
                    // 沙箱里被外部清理了，跳过
                    continue;
                }
                //获取沙箱文件内容
                String newContent = safeReadString(wsFile);
                //获取原项目文件路径
                Path origFile = projectRoot.resolve(relPath);
                //原项目文件是否存在 存在就读取内容
                String oldContent = Files.exists(origFile) ? safeReadString(origFile) : "";
                //检查「原项目里的这个文件」是否真实存在。 不存在则创建 文件存在 则修改状态
                Status status = Files.exists(origFile) ? Status.MODIFIED : Status.CREATED;
                //如果文件状态是 “已修改”，但是 新内容 == 旧内容
                if (status == Status.MODIFIED && newContent.equals(oldContent)) {
                    // 内容未真正变化，不展示
                    continue;
                }
                //获取差异对象
                DiffStats stats = buildUnifiedDiff(relPath, oldContent, newContent);
                result.add(new DiffEntry(
                        relPath, status,
                        stats.additions, stats.deletions,
                        stats.unifiedDiff,
                        oldContent, newContent
                ));
            } catch (Exception e) {
                // 单文件异常不应该弄坏整个接口：记下日志，跳过该文件
                log.warn("[computeDiff] 跳过文件 {} : {} - {}",
                        relPath, e.getClass().getSimpleName(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * 把本会话改动文件覆盖到原项目，并对每个改动文件做单文件 reindex（增量）。
     * 比全量重建快得多，适合大多数小修改场景。返回应用了多少个文件。
     * <p>
     * 修复：Agent 使用标准 Maven 路径（如 src/main/java/...），但原项目可能是扁平结构。
     * 通过文件名后缀匹配找到原项目中的实际文件位置，保证覆盖到正确的文件。
     */
    public int applyDiff(String sessionId, String projectId) throws IOException {
        //获取改动文件路径
        Set<String> changed = changeTracker.getChangedFiles(sessionId);
        if (changed.isEmpty()) return 0;
         //获取沙箱工作区根路径
        Path workspaceRoot = workspaceManager.getWorkspace(sessionId);
        //获取原项目根路径
        Path projectRoot = Paths.get(uploadBasePath, projectId);
        //创建原项目根路径
        Files.createDirectories(projectRoot);

        // 记录 (实际落盘路径, 旧向量 file_path 需清理) 的映射
        List<ApplyTarget> applyTargets = new ArrayList<>();
        //遍历所有改动文件
        for (String relPath : changed) {
            //获取沙箱文件路径  resolve这个是拼接路径
            Path wsFile = workspaceRoot.resolve(relPath);
            //沙箱文件不存在
            if (!Files.exists(wsFile)) continue;

            // 尝试在原项目中按文件名后缀匹配找到实际文件位置
            Path destFile = findOriginalFile(projectRoot, relPath);
            String oldFilePath = null;  // 需要清理的旧向量路径

            if (destFile != null) {
                // 在原项目中找到了同名文件 → 直接覆盖该文件
                String actualRel = projectRoot.relativize(destFile).toString().replace('\\', '/');
                if (!actualRel.equals(relPath)) {
                    // 路径不同，说明 Agent 用的路径和原项目不一致，需要清理旧路径的向量
                    oldFilePath = actualRel;
                    log.info("[applyDiff] 路径映射: Agent路径={} → 原项目实际路径={}", relPath, actualRel);
                }
            } else {
                // 原项目中没找到 → 新建文件（可能是 Agent 创建的新文件）
                destFile = projectRoot.resolve(relPath);
            }

            //获取原始项目的父目录并且创建
            Files.createDirectories(destFile.getParent());
            //复制沙箱文件到原始项目
            Files.copy(wsFile, destFile, StandardCopyOption.REPLACE_EXISTING);
            //记录已应用的目标
            String finalRelPath = projectRoot.relativize(destFile).toString().replace('\\', '/');
            applyTargets.add(new ApplyTarget(finalRelPath, oldFilePath));
        }

        // 增量重建：每个改动文件单独 deleteVectorsByFile + 重新切片入库
        for (ApplyTarget target : applyTargets) {
            try {
                // 如果原项目路径与 Agent 路径不一致，先清理旧路径的向量
                if (target.oldFilePath != null && !target.oldFilePath.equals(target.newFilePath)) {
                    indexingService.deleteVectorsByFile(projectId, target.oldFilePath);
                    log.info("[applyDiff] 清理旧路径向量: {}", target.oldFilePath);
                }
                //获取文件内容
                String content = Files.readString(projectRoot.resolve(target.newFilePath), StandardCharsets.UTF_8);
                //更新向量库
                indexingService.updateFileVector(projectId, target.newFilePath, content);
            } catch (Exception e) {
                log.warn("[applyDiff] 单文件 reindex 失败 path={} : {} - {}",
                        target.newFilePath, e.getClass().getSimpleName(), e.getMessage());
            }
        }
        //清空改动记录
        changeTracker.clear(sessionId);
        log.info("[applyDiff] sessionId={}, projectId={}, appliedFiles={} (incremental reindex)",
                sessionId, projectId, applyTargets.size());
        return applyTargets.size();
    }

    /**
     * 在原项目目录中按文件名后缀匹配，找到 Agent 相对路径对应的实际文件。
     * <p>
     * 解决 Agent 使用标准 Maven 路径（src/main/java/...）但原项目可能是扁平结构的问题。
     * 匹配策略：
     * 1. 先精确路径匹配
     * 2. 去掉 src/main/java/ / src/test/java/ 前缀后匹配
     * 3. 按文件名（最后一段）后缀匹配
     *
     * @return 匹配到的实际文件路径，未找到返回 null
     */
    private Path findOriginalFile(Path projectRoot, String agentRelPath) {
        String normalized = agentRelPath.replace('\\', '/');

        // 策略1：精确路径匹配
        Path exact = projectRoot.resolve(normalized);
        if (Files.exists(exact)) return exact;

        // 策略2：去掉 Maven src 前缀后匹配
        for (String prefix : new String[]{"src/main/java/", "src/test/java/"}) {
            if (normalized.startsWith(prefix)) {
                Path stripped = projectRoot.resolve(normalized.substring(prefix.length()));
                if (Files.exists(stripped)) return stripped;
            }
        }

        // 策略3：按文件名后缀在项目目录中搜索
        String fileName = Paths.get(normalized).getFileName().toString();
        try {
            return Files.walk(projectRoot)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    // 跳过 target/build/.git/modifications 等目录
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return !s.contains("/target/")
                                && !s.contains("/build/")
                                && !s.contains("/.git/")
                                && !s.contains("/modifications/");
                    })
                    // 优先选路径最短的（最接近根目录的）
                    .min((a, b) -> Integer.compare(a.getNameCount(), b.getNameCount()))
                    .orElse(null);
        } catch (IOException e) {
            log.warn("[findOriginalFile] 搜索失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 丢弃本会话 diff 记录（沙箱文件保留）。
     */
    public void discardDiff(String sessionId) {
        changeTracker.clear(sessionId);
        log.info("[discardDiff] sessionId={} 已清空改动记录", sessionId);
    }

    // ---------- internal ----------

    /** applyDiff 中记录每个文件的落盘路径和需要清理的旧向量路径 */
    private record ApplyTarget(String newFilePath, String oldFilePath) {}

    private record DiffStats(String unifiedDiff, int additions, int deletions) {}

    private DiffStats buildUnifiedDiff(String relPath, String oldContent, String newContent) {
        // 把旧代码按行拆成列表
        List<String> oldLines = splitLines(oldContent);
        List<String> newLines = splitLines(newContent);
        //对比新旧文件每行内容，生成文本差异补丁对象（对比哪个改了新增删除）
        Patch<String> patch = DiffUtils.diff(oldLines, newLines);

        int additions = 0;
        int deletions = 0;
        //遍历差异补丁对象 patch.getDeltas() 获取所有修改片段
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            //获取修改片段类型
            DeltaType type = delta.getType();
            //修改片段类型是插入
            if (type == DeltaType.INSERT) {
                //纯新增：把新增的行数加到 additions
                additions += delta.getTarget().getLines().size();
            } else if (type == DeltaType.DELETE) {
                //纯删除：把删除的行数加到 deletions
                deletions += delta.getSource().getLines().size();
            } else if (type == DeltaType.CHANGE) {
                //内容修改 = 先删旧行 + 再加新行
                deletions += delta.getSource().getLines().size();
                additions += delta.getTarget().getLines().size();
            }
        }
        //生成标准的 Git 风格统一差异文本（unified diff）
        List<String> unified;
        try {
            unified = UnifiedDiffUtils.generateUnifiedDiff(
                    "a/" + relPath, "b/" + relPath, oldLines, patch, 3);
        } catch (Exception e) {
            log.warn("[buildUnifiedDiff] 生成 unified diff 失败 path={}", relPath, e);
            unified = Collections.emptyList();
        }
        //返回差异补丁对象、新增行数、删除行数、把diff所有行用换行连起来，变成一整段文本
        return new DiffStats(String.join("\n", unified), additions, deletions);
    }

    private List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) return List.of();
        // 保留空行；split 不丢尾部空行 → 用 -1
        return Arrays.asList(content.split("\\r?\\n", -1));
    }

    private String safeReadString(Path p) throws IOException {
        //读这个文件里的内容并返回
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
