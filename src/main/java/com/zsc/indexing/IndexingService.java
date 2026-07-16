package com.zsc.indexing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {

    private final CodeChunker codeChunker;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RepoMapService repoMapService;
    private final DbInitRunner dbInitRunner;

    @Value("${codemate.upload.base-path: D:/codemate_data/uploaded_projects}")
    private String uploadBasePath;

    // DashScope Embedding API 限制：单次最多 25 条文本，这里使用 20 留有余量
    private static final int EMBEDDING_BATCH_SIZE = 20;

    // 排除的文件扩展名（不建索引）
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            ".css", ".scss", ".less", ".sass",
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".svg", ".ico", ".webp",
            ".woff", ".woff2", ".ttf", ".eot", ".otf",
            ".map", ".min.js", ".min.css",
            ".class", ".jar", ".war", ".zip", ".gz", ".tar",
            ".lock", ".log"
    );

    // 排除的目录（第三方库、构建产物等）
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "node_modules", "dist", "build", "target", ".git",
            "element-ui", "bootstrap", "jquery", "layui"
    );

    // [JDK 21 虚拟线程] 每个文件一个虚拟线程，I/O 等待时自动释放载体线程
    // DashScope Embedding API 限流约 30~50 QPS，虚拟线程可充分并发
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // API 并发保护：虚拟线程极轻量但 API 有限流，用信号量控制同时调用 Embedding API 的并发数
    private final Semaphore apiPermits = new Semaphore(20);

    /**
     * 将文档列表分批添加到向量库（每批不超过 EMBEDDING_BATCH_SIZE）
     */
    private void addToVectorStoreBatched(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        for (int i = 0; i < documents.size(); i += EMBEDDING_BATCH_SIZE) {
            // 处理当前批次 并计算出结束位置
            int end = Math.min(i + EMBEDDING_BATCH_SIZE, documents.size());
            List<Document> batch = documents.subList(i, end);
            vectorStore.add(batch);
            log.debug("Inserted batch of {} embeddings", batch.size());
        }
        // 写入后补充 BM25 索引（首次启动表刚建好的场景，幂等安全）
        try {
            dbInitRunner.ensureBM25Index();
        } catch (Exception ignored) {}
    }

    /**
     * [线程优化] 处理单个文件（供并行调用）
     */
    private void processSingleFile(MultipartFile file, String projectId) {
        try {
            String originalName = file.getOriginalFilename();
            // 文件类型过滤：跳过非代码文件
            if (!shouldIndex(originalName, "")) {
                log.debug("跳过非代码文件: {}", originalName);
                return;
            }
            // 1. 保存原始文件到磁盘
            Path projectDir = Paths.get(uploadBasePath, projectId);
            // 创建项目目录
            Files.createDirectories(projectDir);
            //拼接文件路径
            Path targetFile = projectDir.resolve(file.getOriginalFilename());
            if (file.getOriginalFilename().contains("/") || file.getOriginalFilename().contains("\\")) {
                Files.createDirectories(targetFile.getParent());
            }
            //真正保存文件到硬盘
            file.transferTo(targetFile.toFile());
            // 计算相对路径
            String relativePath = projectDir.relativize(targetFile).toString().replace('\\', '/');

            // 2. 读取文件内容
            String content = Files.readString(targetFile, StandardCharsets.UTF_8);

            // 3. 切片
            Document rawDoc = new Document(content, Map.of(
                    "file_name", file.getOriginalFilename(),
                    "project_id", projectId,
                    "file_path", relativePath
            ));
            List<Document> chunks = codeChunker.apply(List.of(rawDoc));
            if (chunks.isEmpty()) {
                log.warn("No chunks generated for file: {}", file.getOriginalFilename());
                return;
            }

            // 4. 为每个切片补充元数据和 UUID
            List<Document> finalChunks = new ArrayList<>();
            for (Document chunk : chunks) {
                String id = UUID.randomUUID().toString();
                Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
                //metadata.put("project_id", projectId);
                Document newDoc = new Document(id, chunk.getText(), metadata);
                finalChunks.add(newDoc);
            }

            // 5. 分批写入向量库（每个文件独立批次，但仍遵守 API 限制）
            apiPermits.acquire();
            try {
                addToVectorStoreBatched(finalChunks);
            } finally {
                apiPermits.release();
            }
            log.info("Indexed {} chunks from file: {}", finalChunks.size(), file.getOriginalFilename());

        } catch (Exception e) {
            log.error("Failed to process file: {}", file.getOriginalFilename(), e);
        }
    }

    /**
     * 索引单个文件（同步，保持原有接口）
     */
    public void indexFile(MultipartFile file, String projectId) throws IOException {
        processSingleFile(file, projectId);
    }

    /**
     * [线程优化] 索引多个文件（并行处理）
     * 使用线程池并发处理每个文件，大幅缩短总耗时
     */
    public void indexFiles(List<MultipartFile> files, String projectId) {
        if (files == null || files.isEmpty()) {
            return;
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (MultipartFile file : files) {
            // [线程优化] 提交任务到线程池异步执行
            CompletableFuture<Void> future = CompletableFuture.runAsync(() ->
                    processSingleFile(file, projectId), executor
            );
            futures.add(future);
        }

        // [线程优化] 等待所有文件处理完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("Completed parallel indexing for {} files", files.size());

        // 项目向量索引完成后，异步预热 RepoMap（项目级符号摘要）
        try {
            repoMapService.buildAsync(projectId);
        } catch (Exception e) {
            log.warn("[indexFiles] 触发 RepoMap 异步构建失败 projectId={}", projectId, e);
        }
    }

    /**
     * 删除某个项目下的所有向量（按 project_id 过滤删除）
     */
    public void deleteProjectVectors(String projectId) {
        String sql = "DELETE FROM vector_store WHERE metadata->>'project_id' = ?";
        jdbcTemplate.update(sql, projectId);
        log.info("Deleted all vectors for project: {}", projectId);
    }

    /**
     * 更新单个文件的向量索引（删除旧切片，重新切片并插入）
     */
    public void updateFileVector(String projectId, String filePath, String newContent) {
        // 1. 删除旧切片
        deleteVectorsByFile(projectId, filePath);

        // 2. 重新切片
        Document rawDoc = new Document(newContent, Map.of(
                "file_name", Paths.get(filePath).getFileName().toString(),
                "project_id", projectId,
                "file_path", filePath
        ));
        List<Document> chunks = codeChunker.apply(List.of(rawDoc));
        if (chunks.isEmpty()) {
            log.warn("No chunks generated for file: {}", filePath);
            return;
        }

        // 3. 补充 project_id 并生成 UUID
        List<Document> finalChunks = new ArrayList<>();
        for (Document chunk : chunks) {
            String id = UUID.randomUUID().toString();
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("project_id", projectId);
            Document newDoc = new Document(id, chunk.getText(), metadata);
            finalChunks.add(newDoc);
        }

        // 4. 分批存入向量库（防止单个文件切片过多）
        addToVectorStoreBatched(finalChunks);
        log.info("Updated vector index for file: {}, chunks: {}", filePath, finalChunks.size());
    }

    /**
     * 删除某个项目下特定文件的所有旧切片
     */
    public void deleteVectorsByFile(String projectId, String filePath) {
        String sql = "DELETE FROM vector_store WHERE metadata->>'project_id' = ? AND metadata->>'file_path' = ?";
        jdbcTemplate.update(sql, projectId, filePath);
        log.debug("Deleted old vectors for file: {}", filePath);
    }

    /**
     * 全项目重建向量索引：从磁盘上的 uploaded_projects/{projectId}/ 重新读取所有文件，
     * 先删除该项目全部向量再逐个切片入库。
     * 用于“应用工作区 diff 到原项目”后保证向量库与原项目强一致。
     */
    public void reindexProjectFromDisk(String projectId) throws IOException {
        Path projectDir = Paths.get(uploadBasePath, projectId);
        if (!Files.isDirectory(projectDir)) {
            log.warn("[reindexProjectFromDisk] 项目目录不存在: {}", projectDir);
            return;
        }
        deleteProjectVectors(projectId);
        try (var stream = Files.walk(projectDir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String relativePath = projectDir.relativize(p).toString().replace('\\', '/');
                    // 文件类型过滤：跳过非代码文件
                    if (!shouldIndex(p.getFileName().toString(), relativePath)) {
                        log.debug("[reindexProjectFromDisk] 跳过非代码文件: {}", relativePath);
                        return;
                    }
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    updateFileVector(projectId, relativePath, content);
                } catch (Exception e) {
                    log.warn("[reindexProjectFromDisk] 跳过文件 {} : {}", p, e.getMessage());
                }
            });
        }
        try {
            repoMapService.buildAsync(projectId);
        } catch (Exception e) {
            log.warn("[reindexProjectFromDisk] RepoMap 重建失败 projectId={}", projectId, e);
        }
        log.info("[reindexProjectFromDisk] 重建完成 projectId={}", projectId);
    }

    /**
     * 查询指定项目下哪些文件已存在于磁盘（用于断点续传：跳过已上传的文件）
     * @param projectId 项目ID
     * @param fileNames 待检查的文件名列表（相对路径，如 "src/main/java/UserService.java"）
     * @return 已存在的文件名列表
     */
    public List<String> findExistingFiles(String projectId, List<String> fileNames) {
        Path projectDir = Paths.get(uploadBasePath, projectId);
        if (!Files.isDirectory(projectDir)) {
            return Collections.emptyList();
        }
        List<String> existing = new ArrayList<>();
        for (String fileName : fileNames) {
            Path filePath = projectDir.resolve(fileName);
            if (Files.exists(filePath)) {
                existing.add(fileName);
            }
        }
        if (!existing.isEmpty()) {
            log.debug("[findExistingFiles] projectId={}, existing={}/{}", projectId, existing.size(), fileNames.size());
        }
        return existing;
    }

    // 虚拟线程执行器由 JVM 管理，无需手动关闭

    /**
     * 判断文件是否应该被索引（排除 CSS/图片/第三方库等非代码文件）
     */
    private boolean shouldIndex(String fileName, String relativePath) {
        if (fileName == null) return false;
        String lowerName = fileName.toLowerCase();
        // 检查扩展名
        for (String ext : EXCLUDED_EXTENSIONS) {
            if (lowerName.endsWith(ext)) return false;
        }
        // 检查目录路径
        if (relativePath != null && !relativePath.isEmpty()) {
            String lowerPath = relativePath.toLowerCase().replace('\\', '/');
            for (String dir : EXCLUDED_DIRS) {
                if (lowerPath.contains("/" + dir + "/") || lowerPath.startsWith(dir + "/")) return false;
            }
        }
        return true;
    }
}