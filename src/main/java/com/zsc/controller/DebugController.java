package com.zsc.controller;

import com.zsc.indexing.HybridRetrievalService;
import com.zsc.tools.RAGSearchTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 调试用：直接查询 pgvector 的 vector_store 表，
 * 帮助排查 searchCode 工具返回 "未找到相关代码片段" 的问题。
 * 用完可删除。
 */
@RestController
@RequestMapping("/agent/debug")
@RequiredArgsConstructor
@Slf4j
public class DebugController {

    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;
    private final HybridRetrievalService hybridRetrievalService;

    /** 总览：所有 project_id 和对应行数 */
    @GetMapping("/projects")
    public Map<String, Object> projects() {
        Map<String, Object> r = new LinkedHashMap<>();
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", Long.class);
        r.put("total", total);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT metadata->>'project_id' AS project_id, COUNT(*) AS cnt " +
                        "FROM vector_store GROUP BY metadata->>'project_id' ORDER BY cnt DESC");
        r.put("groupByProject", rows);
        return r;
    }

    /** 看某 projectId 的样本：前 N 条 metadata + 内容前 200 字符 */
    @GetMapping("/sample")
    public List<Map<String, Object>> sample(@RequestParam String projectId,
                                            @RequestParam(defaultValue = "10") int limit) {
        return jdbcTemplate.queryForList(
                "SELECT id, metadata, LEFT(content, 200) AS content_preview " +
                        "FROM vector_store WHERE metadata->>'project_id' = ? LIMIT ?",
                projectId, limit);
    }

    /**
     * 直接调 VectorStore 用不同阈值搜，绕过 RAGSearchTool 看相似度分布。
     * 返回每条命中的距离/分数（如有），排查阈值是否过高。
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String projectId,
                                      @RequestParam String query,
                                      @RequestParam(defaultValue = "0.0") double threshold,
                                      @RequestParam(defaultValue = "10") int topK) {
        Map<String, Object> r = new LinkedHashMap<>();
        String filter = "project_id == '" + projectId + "'";
        SearchRequest req = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .filterExpression(filter)
                .build();
        List<Document> docs = vectorStore.similaritySearch(req);
        r.put("query", query);
        r.put("projectId", projectId);
        r.put("threshold", threshold);
        r.put("topK", topK);
        r.put("hitCount", docs == null ? 0 : docs.size());

        List<Map<String, Object>> hits = new ArrayList<>();
        if (docs != null) {
            for (Document d : docs) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("id", d.getId());
                h.put("score", d.getScore()); // 注意：Spring AI 1.x 的 score 字段
                h.put("metadata", d.getMetadata());
                String text = d.getText();
                h.put("preview", text == null ? "" : (text.length() > 300 ? text.substring(0, 300) : text));
                hits.add(h);
            }
        }
        r.put("hits", hits);
        return r;
    }

    /** 走和 RAGSearchTool 一样的链路（默认阈值 0.4），方便对照 */
    @GetMapping("/search-via-tool")
    public String searchViaTool(@RequestParam String projectId,
                                @RequestParam String query) {
        RAGSearchTool tool = new RAGSearchTool(hybridRetrievalService, projectId);
        RAGSearchTool.Request req = new RAGSearchTool.Request();
        req.setQuery(query);
        return tool.apply(req);
    }
}
