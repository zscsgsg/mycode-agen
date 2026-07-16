package com.zsc.indexing;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 增强混合检索服务：查询重写 → 向量召回 + BM25 召回 → RRF 融合 → Rerank 精排。
 * <p>
 * 管道流程：
 * <ol>
 *   <li>查询重写：将用户模糊自然语言改写为代码检索专用查询</li>
 *   <li>向量召回：pgvector cosine_distance，每路 20 条</li>
 *   <li>BM25 召回：PostgreSQL tsvector 全文搜索，每路 20 条</li>
 *   <li>RRF 融合：score = Σ 1/(60 + rank_i)，去重合并</li>
 *   <li>Rerank 精排：DashScope Rerank 模型对融合结果二次打分，过滤低质量文档</li>
 * </ol>
 */
@Service
@Slf4j
public class HybridRetrievalService {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final DashScopeChatModel chatModel;
    private final RerankModel rerankModel;
    private final ChatClient rewriteClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Rerank 动态阈值：取最高分的 15% 作为过滤线（代码检索场景分数偏低，放宽阈值避免误过滤）
    private static final double RERANK_RELATIVE_RATIO = 0.15;
    // RRF 融合参数
    private static final int RRF_K = 60;
    // 向量路和 BM25 路各自召回的文档数量上限（各 20 条）
    private static final int RECALL_PER_BRANCH = 20;
    // RRF 融合后送入 Rerank 的候选数量上限
    private static final int RERANK_CANDIDATE_LIMIT = 30;

    // [JDK 21 虚拟线程] 混合检索并行执行器：向量召回 + BM25 召回并行，I/O 等待时不占平台线程
    private final ExecutorService retrievalExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public HybridRetrievalService(VectorStore vectorStore, JdbcTemplate jdbcTemplate,
                                  DashScopeChatModel chatModel, RerankModel rerankModel) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.chatModel = chatModel;
        this.rerankModel = rerankModel;
        // 缓存一个专门用于查询重写的轻量 ChatClient，避免每次 search 都 new
        this.rewriteClient = ChatClient.builder(chatModel)
                .defaultSystem("你是一个代码搜索查询优化器。只返回改写后的查询文本，不要解释。")
                .build();
    }

    public static class HybridResult {
        //融合后的文档列表
        public final List<Document> documents;
        //向量路的召回数量
        public final int vectorHits;
        //BM25 路的召回数量
        public final int bm25Hits;
        //记录每个文档 ID 的来源
        public final Map<String, BranchSource> sourceMap;
        // Rerank 平均分（质量指标，无 Rerank 时为 0）
        public final double avgRerankScore;
        // 是否低置信度（检索结果整体质量差，建议拒答或重试）
        public final boolean lowConfidence;

        public HybridResult(List<Document> documents, int vectorHits, int bm25Hits, Map<String, BranchSource> sourceMap) {
            this(documents, vectorHits, bm25Hits, sourceMap, 0.0, false);
        }

        public HybridResult(List<Document> documents, int vectorHits, int bm25Hits,
                            Map<String, BranchSource> sourceMap, double avgRerankScore, boolean lowConfidence) {
            this.documents = documents;
            this.vectorHits = vectorHits;
            this.bm25Hits = bm25Hits;
            this.sourceMap = sourceMap;
            this.avgRerankScore = avgRerankScore;
            this.lowConfidence = lowConfidence;
        }
    }

    public enum BranchSource { VECTOR, BM25, BOTH }

    /**
     * 增强混合检索（完整管道）。
     * <p>
     * 流程：查询重写 → 向量+BM25召回 → RRF融合 → Rerank精排。
     *
     * @param query     用户原始查询语句
     * @param projectId 项目 id（隔离）
     * @param topK      最终返回前 K 个
     * @return 融合+精排后的文档
     */
    public HybridResult search(String query, String projectId, int topK) {
        // 1. 查询重写：模糊自然语言 → 代码检索专用查询
        String rewrittenQuery = rewriteQuery(query);
        log.info("[hybrid] 查询重写: '{}' → '{}'", query, rewrittenQuery);

        // 2. 两路召回（虚拟线程并行执行，互不阻塞）
        CompletableFuture<List<Document>> vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorRecall(rewrittenQuery, projectId), retrievalExecutor);
        CompletableFuture<List<Document>> bm25Future = CompletableFuture.supplyAsync(
                () -> bm25Recall(rewrittenQuery, projectId), retrievalExecutor);
        List<Document> vectorDocs = vectorFuture.join();
        List<Document> bm25Docs = bm25Future.join();

        // 3. RRF 融合（先取较多候选，后续 Rerank 再精简）
        int rrfTopK = Math.max(topK * 3, RERANK_CANDIDATE_LIMIT);
        HybridResult rrfResult = fuse(vectorDocs, bm25Docs, rrfTopK);

        // 4. Rerank 精排（当 Rerank 结果太少或为空时，降级为 RRF 直接截取）
        // 用变量保存 Rerank 质量评估结果，确保降级路径也能传递 lowConfidence
        double qualityAvgScore = 0.0;
        boolean qualityLowConf = false;
        boolean rerankAttempted = false;

        if (!rrfResult.documents.isEmpty() && rerankModel != null) {
            List<Document> reranked = doRerank(query, rrfResult.documents, topK);
            log.info("[hybrid] Rerank: {} → {} (relativeRatio={})",
                    rrfResult.documents.size(), reranked.size(), RERANK_RELATIVE_RATIO);
            // 计算 Rerank 平均分作为质量指标
            double avgScore = reranked.stream()
                    .mapToDouble(d -> {
                        Object s = d.getMetadata().get("_rerank_score");
                        return s instanceof Number ? ((Number) s).doubleValue() : 0;
                    }).average().orElse(0);
            // 质量判断：
            // 1. 平均分极低（< 0.01）→ 结果质量差，lowConfidence
            // 2. RRF 降级后文档数充足（≥ 5）且有 Rerank 结果 → 不算 lowConfidence
            //    （代码检索场景 Rerank 分数普遍偏低，但 RRF 融合结果包含相关文件）
            // 3. RRF 降级后文档很少 且 平均分不高 → lowConfidence
            boolean lowConf;
            if (avgScore < 0.01) {
                lowConf = true;
            } else if (reranked.size() >= Math.max(1, topK / 3) || avgScore >= 0.6) {
                lowConf = false;
            } else {
                // Rerank 结果少但 RRF 降级后文档充足 → 不算 lowConfidence
                lowConf = rrfResult.documents.size() < 5;
            }
            log.info("[hybrid] 质量评估: avgRerankScore={}, lowConfidence={}",
                    String.format("%.4f", avgScore), lowConf);
            // 保存质量评估结果
            qualityAvgScore = avgScore;
            qualityLowConf = lowConf;
            rerankAttempted = true;

            // Rerank 返回结果不足 topK 一半时，说明模型排序质量差，降级用 RRF
            if (reranked.size() >= Math.max(1, topK / 2)) {
                return new HybridResult(reranked, rrfResult.vectorHits, rrfResult.bm25Hits, rrfResult.sourceMap, avgScore, lowConf);
            }
            log.info("[hybrid] Rerank 结果不足({}条)，降级为 RRF 直接截取", reranked.size());
        }

        // Rerank 不可用或降级时，直接截取 RRF 结果（携带质量评估信息）
        if (rrfResult.documents.size() > topK) {
            List<Document> truncated = rrfResult.documents.subList(0, topK);
            List<String> paths = truncated.stream()
                    .map(d -> (String)d.getMetadata().get("file_path"))
                    .toList();
            log.info("[hybrid] 最终返回文档({}条): {}", truncated.size(), paths);
            return new HybridResult(truncated, rrfResult.vectorHits, rrfResult.bm25Hits, rrfResult.sourceMap, qualityAvgScore, qualityLowConf);
        }
        List<String> paths = rrfResult.documents.stream()
                .map(d -> (String)d.getMetadata().get("file_path"))
                .toList();
        log.info("[hybrid] 最终返回文档({}条): {}", rrfResult.documents.size(), paths);
        // 如果尝试过 Rerank，携带质量评估信息；否则用默认值（0.0, false）
        if (rerankAttempted) {
            return new HybridResult(rrfResult.documents, rrfResult.vectorHits, rrfResult.bm25Hits, rrfResult.sourceMap, qualityAvgScore, qualityLowConf);
        }
        return rrfResult;
    }

    /**
     * 查询重写：将用户模糊的自然语言查询改写为更适合代码检索的关键词查询。
     * 使用构造时缓存的 rewriteClient，避免每次 new ChatClient。
     */
    private String rewriteQuery(String originalQuery) {
        try {
            String prompt = """
                    将中文问题转换为 3~4 个搜索关键词（用空格分隔），包含英文和中文混合。
                    规则：
                    - 前 2 个为英文核心词（动词+名词）
                    - 第 3 个为代码级同义词（如类名、注解名、方法名）
                    - 第 4 个为中文关键词（用于匹配中文注释）
                    - 过滤泛词：在哪、逻辑、怎么、如何、什么
                    - 禁止编造类名
                    - 示例：
                      "用户注册流程" → "user register signup 注册"
                      "数据库连接配置" → "database connection JDBC 数据库"
                      "老师的登录逻辑在哪" → "teacher login authenticate 登录"
                      "找出所有 Servlet" → "Servlet WebServlet HttpServlet Servlet"
                    用户问题：%s
                    """.formatted(originalQuery);
            String rewritten = rewriteClient.prompt().user(prompt).call().content();
            if (rewritten != null && !rewritten.isBlank()) {
                return rewritten.strip();
            }
        } catch (Exception e) {
            log.warn("[hybrid] 查询重写失败，使用原始查询: {}", e.getMessage());
        }
        return originalQuery;
    }

    /**
     * Rerank 精排：使用 DashScope Rerank 模型对候选文档重新打分，用动态相对阈值过滤低分文档。
     * 策略：取最高分 × RERANK_RELATIVE_RATIO 作为过滤线，适配不同模型的分数尺度。
     */
    private List<Document> doRerank(String query, List<Document> candidates, int topK) {
        try {
            // 调用 Rerank 模型（直接传入 Spring AI Document 列表）
            RerankRequest request = new RerankRequest(query, candidates);
            RerankResponse response = rerankModel.call(request);

            // 按分数排序，取 topK
            List<Document> reranked = new ArrayList<>();
            if (response != null && response.getResults() != null) {
                var results = response.getResults();

                // 动态计算阈值：最高分 × 相对比例
                double maxScore = results.stream()
                        .mapToDouble(r -> r.getScore() != null ? r.getScore() : 0)
                        .max().orElse(0);
                double dynamicThreshold = maxScore * RERANK_RELATIVE_RATIO;
                log.debug("[hybrid] Rerank: maxScore={}, dynamicThreshold={} ({}% of max)",
                        String.format("%.4f", maxScore),
                        String.format("%.4f", dynamicThreshold),
                        (int)(RERANK_RELATIVE_RATIO * 100));

                results.stream()
                        .filter(r -> r.getScore() != null && r.getScore() >= dynamicThreshold)
                        .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                        .limit(topK)
                        .forEach(r -> {
                            Document doc = r.getOutput();
                            if (doc != null) {
                                doc.getMetadata().put("_rerank_score", r.getScore());
                                reranked.add(doc);
                            }
                        });
            }
            return reranked;
        } catch (Exception e) {
            String msg = e.getMessage();
            // 403 是 API Key 未开通 Rerank 权限，属于配置问题，不打印堆栈
            if (msg != null && msg.contains("403")) {
                log.info("[hybrid] Rerank 不可用(API未开通)，降级为 RRF 截取");
            } else {
                log.warn("[hybrid] Rerank 失败，降级为 RRF 直接截取: {}", msg);
            }
            // 降级：直接截取前 topK
            return candidates.size() > topK ? candidates.subList(0, topK) : candidates;
        }
    }

    /** 仅向量路（兜底使用）。 */
    public List<Document> vectorRecall(String query, String projectId) {
        try {
            //构建过滤表达式 project_id == 'xxx'，确保只检索当前项目的文档。
            String filter = "project_id == '" + projectId + "'";
            SearchRequest req = SearchRequest.builder()
                    .query(query)
                    .topK(RECALL_PER_BRANCH)
                    .similarityThreshold(0.0)
                    .filterExpression(filter)
                    .build();
            List<Document> docs = vectorStore.similaritySearch(req);
            // 过滤非代码文件（CSS/图片等已索引的残留数据）
            return docs == null ? Collections.emptyList()
                    : docs.stream().filter(HybridRetrievalService::isCodeFile).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("[hybrid] 向量检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 仅 BM25 路。 */
    public List<Document> bm25Recall(String query, String projectId) {
        try {
            // 检查 content_tsv 列是否存在
            Integer hasCol = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                            "WHERE table_name = 'vector_store' AND column_name = 'content_tsv'",
                    Integer.class);
            if (hasCol == null || hasCol == 0) {
                log.warn("[hybrid] content_tsv 列不存在，BM25 路跳过");
                return Collections.emptyList();
            }

            // 把搜索词用 | 拼接成 OR 语义的 tsquery，避免 plainto_tsquery 的 AND 语义导致全军覆没
            // "teacher login logic" → to_tsquery('simple', 'teacher | login | logic')
            String tsquery = Arrays.stream(query.split("\\s+"))
                    .filter(t -> !t.isBlank())
                    .collect(Collectors.joining(" | "));
            if (tsquery.isEmpty()) {
                return Collections.emptyList();
            }

            String sql = "SELECT id::text AS id, content, metadata, " +
                    "ts_rank(content_tsv, to_tsquery('simple', ?)) AS rank " +
                    "FROM vector_store " +
                    "WHERE metadata->>'project_id' = ? " +
                    "AND content_tsv @@ to_tsquery('simple', ?) " +
                    "ORDER BY rank DESC LIMIT ?";
            List<Document> docs = jdbcTemplate.query(sql, ps -> {
                ps.setString(1, tsquery);
                ps.setString(2, projectId);
                ps.setString(3, tsquery);
                ps.setInt(4, RECALL_PER_BRANCH);
            }, (rs, rowNum) -> {
                String id = rs.getString("id");
                String content = rs.getString("content");
                String metaJson = rs.getString("metadata");
                Map<String, Object> meta = parseMetadata(metaJson);
                Document doc = new Document(id, content, meta);
                // 把 BM25 rank 临时塞进 metadata，方便后续融合
                meta.put("_bm25_rank", rs.getDouble("rank"));
                return doc;
            });
            // 过滤非代码文件（CSS/图片等已索引的残留数据）
            return docs.stream().filter(HybridRetrievalService::isCodeFile).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("[hybrid] BM25 检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /** 排除的文件扩展名（检索时过滤已索引的非代码文件） */
    private static final Set<String> NON_CODE_EXTENSIONS = Set.of(
            ".css", ".scss", ".less", ".sass",
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".svg", ".ico", ".webp",
            ".woff", ".woff2", ".ttf", ".eot", ".otf",
            ".map", ".min.js", ".min.css",
            ".class", ".jar", ".war", ".zip", ".gz", ".tar"
    );

    private static final Set<String> NON_CODE_DIRS = Set.of(
            "element-ui", "bootstrap", "jquery", "layui", "node_modules"
    );

    /**
     * 判断文档是否为代码文件（通过 file_path 元数据）
     */
    private static boolean isCodeFile(Document doc) {
        String filePath = (String) doc.getMetadata().get("file_path");
        if (filePath == null) return true; // 无路径信息时保留
        String lower = filePath.toLowerCase().replace('\\', '/');
        for (String ext : NON_CODE_EXTENSIONS) {
            if (lower.endsWith(ext)) return false;
        }
        for (String dir : NON_CODE_DIRS) {
            if (lower.contains("/" + dir + "/") || lower.startsWith(dir + "/")) return false;
        }
        return true;
    }

    /** RRF 融合两路结果。 */
    private HybridResult fuse(List<Document> vec, List<Document> bm, int topK) {
        // 去重
        Map<String, Document> docPool = new LinkedHashMap<>();
        // 记录 RRF 分数
        Map<String, Double> rrfScores = new HashMap<>();
        // 记录来源
        Map<String, BranchSource> sourceMap = new HashMap<>();

        for (int i = 0; i < vec.size(); i++) {
            Document d = vec.get(i);
            String id = d.getId();
            if (id == null) continue;
            docPool.putIfAbsent(id, d); // 放入文档池（自动去重）
            // RRF 分数 Double::sum   Double::sum 旧的分数 + 新的分数 = 加在一起
            rrfScores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            sourceMap.put(id, BranchSource.VECTOR);
        }
        for (int i = 0; i < bm.size(); i++) {
            Document d = bm.get(i);
            String id = d.getId();
            if (id == null) continue;
            docPool.putIfAbsent(id, d);
            rrfScores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            sourceMap.merge(id, BranchSource.BM25,
                    (oldV, newV) -> oldV == BranchSource.VECTOR ? BranchSource.BOTH : BranchSource.BM25);
        }

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(rrfScores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<Document> merged = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, sorted.size()); i++) {
            String id = sorted.get(i).getKey();
            Document d = docPool.get(id);
            if (d == null) continue;
            // 把融合分数写入 metadata，便于上层展示
            // RRF 分数
            d.getMetadata().put("_rrf_score", sorted.get(i).getValue());
            // 来源
            d.getMetadata().put("_branch", sourceMap.getOrDefault(id, BranchSource.VECTOR).name());
            merged.add(d);
        }
        log.info("[hybrid] vec={}, bm25={}, fused topK={} (return {})",
                vec.size(), bm.size(), topK, merged.size());
        return new HybridResult(merged, vec.size(), bm.size(), sourceMap);
    }
}
