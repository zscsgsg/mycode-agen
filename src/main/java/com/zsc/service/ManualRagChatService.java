package com.zsc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zsc.indexing.HybridRetrievalService;
import com.zsc.tools.WebSearchTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 手工驱动的 RAG 聊天：自己拉检索结果（混合检索） → 把片段先以 SSE 事件发给前端 →
 * 再把片段拼进 prompt 让 LLM 回答。这样用户能在右侧看到"本次检索到 N 个片段"。
 * <p>
 * 不走 Spring AI 的 RetrievalAugmentationAdvisor（避免重复检索 + 拿不到中间结果）。
 */
@Service
@Slf4j
public class ManualRagChatService {

    private final HybridRetrievalService hybridService;
    private final ChatClient bareRagChatClient;
    private final WebSearchTool webSearchTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 检索上下文缓存：conversationId -> 上次检索到的文件路径集合
    private final Map<String, Set<String>> retrievalCache = new ConcurrentHashMap<>();

    public ManualRagChatService(HybridRetrievalService hybridService,
                                @Qualifier("bareRagChatClient") ChatClient bareRagChatClient,
                                WebSearchTool webSearchTool) {
        this.hybridService = hybridService;
        this.bareRagChatClient = bareRagChatClient;
        this.webSearchTool = webSearchTool;
    }

    // 检索结果数量（调大至 35，确保 LoginServlet 等关键代码不被截断）
    private static final int TOP_K = 35;
    // 片段预览字符数
    private static final int SNIPPET_PREVIEW_CHARS = 400;

    public Flux<ServerSentEvent<String>> chat(String conversationId, String projectId, String userMessage) {
        // 1. 同步做混合检索
        HybridRetrievalService.HybridResult result;
        try {
            result = hybridService.search(userMessage, projectId, TOP_K);
        } catch (Exception e) {
            log.error("[manualRag] 检索失败", e);
            result = new HybridRetrievalService.HybridResult(Collections.emptyList(), 0, 0, Collections.emptyMap());
        }

        // 2. CRAG 质检 + Web 搜索兜底
        if (result.documents.isEmpty() || result.lowConfidence) {
            log.info("[manualRag] CRAG 质检未通过: docs={}, lowConfidence={}, avgScore={} → 回退 Web 搜索",
                    result.documents.size(), result.lowConfidence,
                    String.format("%.4f", result.avgRerankScore));

            // 2.1 Web 搜索兜底
            String webResult = null;
            try {
                WebSearchTool.Request webReq = new WebSearchTool.Request();
                webReq.setQuery(userMessage);
                webResult = webSearchTool.apply(webReq);
            } catch (Exception e) {
                log.warn("[manualRag] Web 搜索兜底失败: {}", e.getMessage());
            }

            // 2.2 Web 搜到了有用内容 → 校验相关性
            if (webResult != null && !webResult.isBlank()
                    && !webResult.contains("未找到") && !webResult.contains("搜索失败")
                    && isWebResultRelevant(userMessage, webResult)) {
                log.info("[manualRag] Web 搜索兜底成功，基于 Web 结果回答");
                String webPrompt = "内部代码库检索未找到相关内容，以下是从互联网搜索到的参考资料：\n" +
                        webResult + "\n\n用户问题：\n" + userMessage +
                        "\n\n请基于上述参考资料回答，如果参考资料不足以回答，请如实说明。";

                // 发空的 retrieved_docs 事件（前端不显示检索片段）
                ServerSentEvent<String> emptyDocs = ServerSentEvent.<String>builder()
                        .event("retrieved_docs").data("[]").build();

                Flux<ServerSentEvent<String>> webStream = bareRagChatClient.prompt()
                        .user(webPrompt)
                        .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .stream()
                        .content()
                        .map(chunk -> ServerSentEvent.<String>builder()
                                .event("message").data(chunk).build())
                        .onErrorResume(err -> {
                            log.error("[manualRag] Web 兜底 LLM 流式错误", err);
                            return Flux.just(ServerSentEvent.<String>builder()
                                    .event("message").data("\n\n[错误] " + err.getMessage()).build());
                        });

                return Flux.concat(
                        Flux.just(emptyDocs),
                        webStream,
                        Flux.just(ServerSentEvent.<String>builder().event("done").data("ok").build())
                );
            }

            // 2.3 Web 也没搜到或结果不相关
            // 如果内部检索有文档（即使 lowConfidence），仍然基于内部结果回答，不拒答
            if (!result.documents.isEmpty()) {
                log.info("[manualRag] Web 兜底失败，但内部有 {} 条文档，基于内部结果回答", result.documents.size());
                // 继续走正常 RAG 流程（跳到步骤 3）
            } else {
                // 内部也没有文档 → 真正拒答
                log.info("[manualRag] 内部检索为空且 Web 兜底失败，拒答");
                String refuseMsg = "内部代码库和互联网检索均未找到与问题相关的信息，建议：\n" +
                        "1. 提供更具体的类名、方法名或文件路径\n" +
                        "2. 换一个角度描述你的问题\n" +
                        "3. 切换到 **Agent 模式**，我可以主动探查代码（getRepoMap + grep 精确搜索）";
                return Flux.just(
                        ServerSentEvent.<String>builder().event("retrieved_docs").data("[]").build(),
                        ServerSentEvent.<String>builder().event("message").data(refuseMsg).build(),
                        ServerSentEvent.<String>builder().event("done").data("ok").build()
                );
            }
        }

        // 3. 质检通过 → 正常 RAG 流程
        // 3.1 先发 retrieved_docs 事件
        ServerSentEvent<String> docsEvent;
        try {
            docsEvent = ServerSentEvent.<String>builder()
                    .event("retrieved_docs")
                    .data(toDocsJson(result))
                    .build();
        } catch (Exception e) {
            log.error("[manualRag] 序列化检索结果失败", e);
            docsEvent = ServerSentEvent.<String>builder().event("retrieved_docs").data("[]").build();
        }

        // 3.2 构造带上下文的 prompt
        String contextBlock = buildContextBlock(result.documents);

        // 3.3 检索上下文缓存：提取文件路径，检查与上次检索的重叠
        Set<String> currentFiles = result.documents.stream()
                .map(d -> d.getMetadata() == null ? null : (String) d.getMetadata().get("file_path"))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<String> prevFiles = retrievalCache.get(conversationId);
        String cacheHint = "";
        if (prevFiles != null && !prevFiles.isEmpty()) {
            Set<String> overlap = new HashSet<>(currentFiles);
            overlap.retainAll(prevFiles);
            if (!overlap.isEmpty()) {
                cacheHint = "\n\n[上下文提示] 本轮检索结果与上一轮有 " + overlap.size() + " 个共同文件：" +
                        String.join(", ", overlap) +
                        "。如果是追问上轮话题，可优先参考这些文件。";
                log.info("[manualRag] 检索上下文缓存命中: overlap={} 个文件", overlap.size());
            }
        }
        // 更新缓存
        retrievalCache.put(conversationId, currentFiles);

        String finalPrompt = (contextBlock.isEmpty()
                ? userMessage
                : contextBlock + cacheHint + "\n\n用户问题：\n" + userMessage);

        // 4. 流式调 LLM（仅 Memory advisor，不再走 RAG advisor）
        Flux<ServerSentEvent<String>> textStream = bareRagChatClient.prompt()
                .user(finalPrompt)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content()
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(chunk)
                        .build())
                .onErrorResume(err -> {
                    log.error("[manualRag] LLM 流式错误", err);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("message")
                            .data("\n\n[错误] " + err.getMessage())
                            .build());
                });

        ServerSentEvent<String> doneEvent = ServerSentEvent.<String>builder()
                .event("done")
                .data("ok")
                .build();

        // 5. 拼接  三个流合并 返回
        return Flux.concat(
                Flux.just(docsEvent),
                textStream,
                Flux.just(doneEvent)
        );
    }

    /**
     * 校验 Web 搜索结果是否与用户问题相关。
     * 提取用户问题中的关键术语（3字符以上的标识符/名词），
     * 检查 Web 结果是否至少包含其中一个，避免用不相关的搜索结果误导 LLM。
     */
    private boolean isWebResultRelevant(String userMessage, String webResult) {
        // 提取关键术语：按空格、标点拆分，过滤通用词
        Set<String> stopwords = Set.of("框架", "方法", "哪里", "实现", "怎么", "什么", "哪些",
                "如何", "为什么", "可以", "能否", "是否", "介绍", "说明", "请问",
                "在哪", "the", "and", "for", "with", "from", "has", "are", "was", "but");
        List<String> terms = Arrays.stream(userMessage.split("[\\s\\p{Punct}]+"))
                .map(String::trim)
                .filter(t -> t.length() >= 3)
                .filter(t -> !stopwords.contains(t.toLowerCase()))
                .toList();

        if (terms.isEmpty()) return true; // 无法提取关键词，放行

        String webLower = webResult.toLowerCase();
        long matchCount = terms.stream()
                .filter(t -> webLower.contains(t.toLowerCase()))
                .count();

        log.info("[manualRag] Web 相关性校验: queryTerms={}, matches={}", terms, matchCount);
        return matchCount >= 1; // 至少命中一个关键术语才算相关
    }

    /** 把检索片段拼成系统上下文块，带来源标记，便于 LLM 引用。 */
    private String buildContextBlock(List<Document> docs) {
        if (docs == null || docs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("以下是从项目代码库中检索到的相关片段。重要规则：\n")
                .append("1. 每个片段顶部的 [meta] 行明确标注了真实归属（file/class/method/lines）；\n")
                .append("2. 判断某个方法/字段属于哪个类时，必须以 [meta] 中的 class 字段为准，禁止臆测；\n")
                .append("3. 回答中引用代码必须使用真实文件路径，格式 [meta.file#Lmeta.start-Lmeta.end]；\n")
                .append("4. \"片段 N\" 只是临时序号，不是文件名，禁止把它写进引用；\n")
                .append("5. 若 [meta] 缺少 class 字段（旧索引），可结合 file 路径中的类名判断。\n\n");
        sb.append("<context>\n");
        int i = 1;
        for (Document d : docs) {
            Map<String, Object> meta = d.getMetadata() == null ? Collections.emptyMap() : d.getMetadata();
            sb.append("--- 片段 ").append(i++).append(" ---\n");
            sb.append("[meta] file=").append(meta.getOrDefault("file_path", "unknown"));
            Object cls = meta.getOrDefault("name", meta.get("parent_class"));
            if (cls != null) sb.append(" | class=").append(cls);
            if (meta.get("method_name") != null) sb.append(" | method=").append(meta.get("method_name"));
            if (meta.get("start_line") != null) {
                sb.append(" | lines=L").append(meta.get("start_line"));
                if (meta.get("end_line") != null) sb.append("-L").append(meta.get("end_line"));
            }
            if (meta.get("tag") != null) sb.append(" | tag=").append(meta.get("tag"));
            sb.append("\n");
            sb.append(d.getText()).append("\n");
        }
        sb.append("</context>\n");
        return sb.toString();
    }

    private String toDocsJson(HybridRetrievalService.HybridResult r) throws JsonProcessingException {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (Document d : r.documents) {
            Map<String, Object> meta = d.getMetadata();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", d.getId());
            // 元数据 表明片段的来源
            item.put("file_path", meta.get("file_path"));
            // 元数据 代码类型标签 比如：class、method、interface
            item.put("tag", meta.get("tag"));
            // 元数据 片段开始的行号
            item.put("start_line", meta.get("start_line"));
            // 元数据 片段结束的行号
            item.put("end_line", meta.get("end_line"));
            // 元数据 片段的类名
            item.put("class_name", meta.getOrDefault("name", meta.get("parent_class")));
            // 元数据 片段的函数名
            item.put("method_name", meta.get("method_name"));
            // 元数据 片段的包名
            item.put("package_name", meta.get("package_name"));
            // 元数据 片段的分支名
            item.put("branch", meta.get("_branch"));
            // 元数据 片段的 RRF 分数（这段代码和问题的相关度得分）
            item.put("rrf_score", meta.get("_rrf_score"));
            String text = d.getText() == null ? "" : d.getText();
            // 片段的预览
            item.put("snippet", text.length() > SNIPPET_PREVIEW_CHARS ? text.substring(0, SNIPPET_PREVIEW_CHARS) : text);
            // 片段的完整文本
            item.put("full_text", text);
            arr.add(item);
        }
        Map<String, Object> wrap = new LinkedHashMap<>();
        // 向量检索（语义搜索）召回的片段总数（可能包含重复，融合后会去重）
        wrap.put("vector_hits", r.vectorHits);
        // BM25 全文检索召回的片段总数（可能包含重复）
        wrap.put("bm25_hits", r.bm25Hits);
        // 融合后最终返回的片段列表（已去重、RRF 排序、截取前 topK）
        wrap.put("docs", arr);
        // 把整个 “大包裹” 转成 JSON 字符串
        return objectMapper.writeValueAsString(wrap);
    }
}
