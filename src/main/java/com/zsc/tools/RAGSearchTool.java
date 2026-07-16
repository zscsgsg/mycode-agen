package com.zsc.tools;

import com.zsc.indexing.HybridRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.function.Function;

/**
 * 在当前项目的向量库中检索代码片段。
 * 使用混合检索（向量 + BM25 RRF 融合）提高关键词查询召回。
 */
@Slf4j
public class RAGSearchTool implements Function<RAGSearchTool.Request, String> {

    private final HybridRetrievalService hybridService;
    private final String projectId;

    public RAGSearchTool(HybridRetrievalService hybridService, String projectId) {
        this.hybridService = hybridService;
        this.projectId = projectId;
    }

    public static class Request {
        private String query;
        private String filePath;  // 可选，限定文件
        private String tag;       // 可选，限定类型: file_header, class, field, method

        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }
    }

    @Override
    public String apply(Request request) {
        log.info("[searchCode] 调用 query={}, filePath={}, tag={}, projectId={}",
                request.getQuery(), request.getFilePath(), request.getTag(), projectId);
        if (projectId == null) {
            log.warn("[searchCode] 未设置项目ID");
            return "错误：未设置项目ID，无法检索代码库。";
        }

        HybridRetrievalService.HybridResult result = hybridService.search(request.getQuery(), projectId, 35);
        List<Document> all = result.documents;

        // 可选后过滤：filePath / tag
        List<Document> results = all.stream().filter(d -> {
            Object fp = d.getMetadata().get("file_path");
            Object tg = d.getMetadata().get("tag");
            if (request.getFilePath() != null && !request.getFilePath().isEmpty()
                    && (fp == null || !fp.toString().equals(request.getFilePath()))) return false;
            if (request.getTag() != null && !request.getTag().isEmpty()
                    && (tg == null || !tg.toString().equals(request.getTag()))) return false;
            return true;
        }).toList();

        log.info("[searchCode] 混合检索 vec={}, bm25={}, 过滤后 {} 条",
                result.vectorHits, result.bm25Hits, results.size());
        if (results.isEmpty()) {
            return "未找到相关代码片段。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("检索到 ").append(results.size()).append(" 个相关代码片段");
        sb.append("（混合检索：向量=").append(result.vectorHits)
                .append(" 条 / BM25=").append(result.bm25Hits).append(" 条）：\n\n");
        int idx = 1;
        for (Document doc : results) {
            sb.append("## 片段 ").append(idx++).append("\n");
            sb.append("文件: ").append(doc.getMetadata().get("file_path")).append("\n");
            sb.append("类型: ").append(doc.getMetadata().get("tag")).append("\n");
            Object branch = doc.getMetadata().get("_branch");
            if (branch != null) sb.append("来源: ").append(branch).append("\n");
            Object rrf = doc.getMetadata().get("_rrf_score");
            if (rrf != null) sb.append("RRF分: ").append(String.format("%.4f", ((Number) rrf).doubleValue())).append("\n");
            if (doc.getMetadata().containsKey("method_name")) {
                sb.append("方法名: ").append(doc.getMetadata().get("method_name")).append("\n");
            }
            if (doc.getMetadata().containsKey("package_name")) {
                sb.append("包名: ").append(doc.getMetadata().get("package_name")).append("\n");
            }
            sb.append("代码:\n").append(doc.getText()).append("\n---\n");
        }
        return sb.toString();
    }
}
