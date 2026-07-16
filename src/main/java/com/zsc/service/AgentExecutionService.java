package com.zsc.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zsc.config.AgentConfig;
import com.zsc.context.ConversationContextBuilder;
import com.zsc.indexing.HybridRetrievalService;
import com.zsc.memory.TwoLevelChatMemoryRepository;
import com.zsc.tools.WebSearchTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class AgentExecutionService {

    private final AgentConfig agentConfig;
    private final WorkspaceManager workspaceManager;
    private final TwoLevelChatMemoryRepository memoryRepository;
    private final HybridRetrievalService hybridRetrievalService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final WebSearchTool webSearchTool;
    private final ConversationContextBuilder contextBuilder;

    public AgentExecutionService(AgentConfig agentConfig,
                                  WorkspaceManager workspaceManager,
                                  TwoLevelChatMemoryRepository memoryRepository,
                                  HybridRetrievalService hybridRetrievalService,
                                  ChatClient chatClient,
                                  ObjectMapper objectMapper,
                                  WebSearchTool webSearchTool,
                                  ConversationContextBuilder contextBuilder) {
        this.agentConfig = agentConfig;
        this.workspaceManager = workspaceManager;
        this.memoryRepository = memoryRepository;
        this.hybridRetrievalService = hybridRetrievalService;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.webSearchTool = webSearchTool;
        this.contextBuilder = contextBuilder;
    }

    @Value("${codemate.upload.base-path:./uploaded_projects}")
    private String uploadBasePath;

    private String classifyIntent(String userTask) {
        String prompt = """
                请判断以下用户需求属于哪种类型：
                - ADD: 用户要求创建新功能、新类、新方法、新文件，或者实现新的逻辑（不涉及修改现有代码）。
                - MODIFY: 用户要求修改、优化、修复、重构、调整现有的代码或方法。
                - QUERY: 用户询问代码库相关的问题，如“某个类有哪些字段”、“某个方法的逻辑是什么”、“找出所有包含某注解的类”、“项目的结构是怎样的”等。这类问题需要搜索代码库来回答。
                - KNOWLEDGE: 用户询问与代码库无关的通用技术问题，如“Spring Boot 3 有哪些新特性”、“Java 21 虚拟线程怎么用”、“什么是微服务”等。这类问题不需要搜索代码库，需要基于通用技术知识回答。
                只回答 ADD、MODIFY、QUERY 或 KNOWLEDGE，不要有其他内容。
                用户需求：%s
                """.formatted(userTask);
        String response = chatClient.prompt().user(prompt).call().content();
        if (response != null && response.contains("MODIFY")) {
            return "MODIFY";
        } else if (response != null && response.contains("KNOWLEDGE")) {
            return "KNOWLEDGE";
        } else if (response != null && response.contains("QUERY")) {
            return "QUERY";
        }
        return "ADD";
    }

    public Flux<ServerSentEvent<String>> streamExecuteTask(String sessionId, String conversationId, String projectId, String userTask, String model) throws GraphRunnerException {

        log.info("========== [streamExecuteTask] sessionId={}, conversationId={}, projectId={}, task={}, model={}",
                sessionId, conversationId, projectId, userTask, model);
        // 1. 意图分类
        String intent = classifyIntent(userTask);
        log.info("[streamExecuteTask] 意图分类结果={}", intent);

        // 2. 分流处理：QUERY 走增强混合检索 + LLM 问答
        if ("QUERY".equals(intent)) {
            return handleQueryIntent(conversationId, projectId, userTask);
        }
        if ("KNOWLEDGE".equals(intent)) {
            return handleKnowledgeIntent(conversationId, userTask);
        }

        // 3. ADD / MODIFY：初始化工作区并构建 per-request 的 ReactAgent
        Path workspace;
        ReactAgent agent;
        try {
            // 初始化工作区
            workspace = workspaceManager.createWorkspace(sessionId);
            // 初始化 Java 项目环境
            workspaceManager.initJavaProject(workspace, projectId, uploadBasePath);
            log.info("Workspace path: {}", workspace);
            log.info("Workspace exists: {}", Files.exists(workspace));

            String workspacePath = workspace.toString();
            // 工具状态（workspaceRoot / projectId / uploadBasePath）通过构造注入，
            // 以 final 字段形式随工具实例一起被 ReactAgent 持有——
            // 后续即便在 reactor 线程池的任意线程上调用，也能拿到正确的上下文。
            agent = agentConfig.buildCodingAgent(workspacePath, projectId, uploadBasePath, sessionId, model);
            log.info("[streamExecuteTask] ReactAgent 构建完成，workspacePath={}, uploadBasePath={}", workspacePath, uploadBasePath);
        } catch (Exception e) {
            log.error("初始化 Agent 失败", e);
            return Flux.just(ServerSentEvent.<String>builder().data("错误：初始化失败 - " + e.getMessage()).build());
        }

        // 4. 加载历史对话并构建 prompt（窗口裁剪：保留最近 N 条 + 旧消息摘要）
        List<Message> history = memoryRepository.findByConversationId(conversationId);
        List<Message> windowedHistory = contextBuilder.buildWindowedHistory(history);
        String fullPrompt = buildPromptWithHistory(windowedHistory, userTask);
        UserMessage userMessage = new UserMessage(fullPrompt);

        final StringBuilder assistantBuf = new StringBuilder();
        // 性能监控：记录开始时间和工具调用次数
        final Instant startTime = Instant.now();
        final AtomicInteger toolCallCount = new AtomicInteger(0);

        // 5. 流式执行：把 ReactAgent 节点流转成 SSE 命名事件
        //   - AssistantMessage.toolCalls 非空 → event: tool_call
        //   - ToolResponseMessage         → event: tool_result
        //   - AssistantMessage 文本 chunk → 默认 message 事件（前端按 onText/data 拼接）
        return agent.stream(userMessage)
                // 只留下流式片段
                .filter(nodeOutput -> nodeOutput instanceof StreamingOutput)
                // 把通用类型转成 StreamingOutput
                .cast(StreamingOutput.class)
                //转成前端能识别的 SSE 事件 fromIterable（）这个是把集合变成流 一个一个发出去
                //toEvents(so, assistantBuf) 把 AI 输出的碎片 变成前端能识别的 SSE 事件（message / tool_call / tool_result）
                //同时把碎片拼进 assistantBuf
                .flatMap(so -> Flux.fromIterable(toEvents(so, assistantBuf, toolCallCount)))
                // 超时保护：2 分钟内必须完成，防止 Agent 陷入工具死循环
                .timeout(java.time.Duration.ofMinutes(2))
                .onErrorResume(java.util.concurrent.TimeoutException.class, e -> {
                    log.warn("[streamExecuteTask] Agent 执行超时（2分钟），强制终止");
                    String timeoutMsg = "\n\n[超时] Agent 执行超过 2 分钟，已自动终止。请简化问题或拆分成多个小问题重试。";
                    assistantBuf.append(timeoutMsg);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("message").data(timeoutMsg).build(),
                            ServerSentEvent.<String>builder()
                            .event("done").data("timeout").build());
                })
                // 容错：LLM 生成畸形 JSON 工具调用时不崩溃，返回错误提示继续流
                .onErrorResume(e -> {
                    String rootMsg = e.getMessage();
                    if (e.getCause() != null) rootMsg = e.getCause().getMessage();
                    if (rootMsg == null) rootMsg = e.getClass().getSimpleName();
                    if (rootMsg.contains("Conversion from JSON") || rootMsg.contains("JsonParse")
                            || rootMsg.contains("JsonMapping") || rootMsg.contains("must be in JSON format")) {
                        log.warn("[streamExecuteTask] LLM 生成畸形 JSON 工具调用，跳过本次: {}", rootMsg);
                        String errMsg = "\n\n[警告] 模型生成了格式错误的工具调用参数，已自动跳过。请重试或简化任务。";
                        assistantBuf.append(errMsg);
                        return Flux.just(ServerSentEvent.<String>builder()
                                .event("message").data(errMsg).build());
                    }
                    // 非 JSON 问题，继续传播
                    return Flux.error(e);
                })
                // doOnComplete 这个是等 AI 把所有文字都流式输出完了    持久化 persistTurn() = 把这一轮对话保存下来
                .doOnComplete(() -> {
                    persistTurn(conversationId, userTask, assistantBuf.toString());
                })
                // 流结束后追加性能指标事件
                .concatWith(Flux.defer(() -> {
                    long durationMs = Duration.between(startTime, Instant.now()).toMillis();
                    int estimatedTokens = assistantBuf.length() / 4; // 粗略估算：4字符≈1token
                    Map<String, Object> metrics = new LinkedHashMap<>();
                    metrics.put("durationMs", durationMs);
                    metrics.put("durationSec", String.format("%.1f", durationMs / 1000.0));
                    metrics.put("toolCalls", toolCallCount.get());
                    metrics.put("estimatedTokens", estimatedTokens);
                    metrics.put("outputChars", assistantBuf.length());
                    String metricsJson = writeJson(metrics);
                    log.info("[性能监控] 耗时={}ms, 工具调用={}次, 预估tokens={}, 输出字符={}",
                            durationMs, toolCallCount.get(), estimatedTokens, assistantBuf.length());
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("metrics")
                            .data(metricsJson)
                            .build());
                }))
                .doOnError(e -> {
                    log.error("[streamExecuteTask] 流异常，尝试持久化部分输出", e);
                    persistTurn(conversationId, userTask, assistantBuf.toString());
                });
    }

    /**
     * 把 ReactAgent 的一个 StreamingOutput 拆成 0~N 个 SSE 事件。
     * 同时把 AssistantMessage 的纯文本 chunk 累积到 assistantBuf 用于落库。
     */
    private List<ServerSentEvent<String>> toEvents(StreamingOutput so, StringBuilder assistantBuf, AtomicInteger toolCallCount) {
        //拿到 AI 输出
        Message msg = so.message();
        if (msg == null) {
            return List.of();
        }
        // 创建一个集合，用来装要推送给前端的事件
        List<ServerSentEvent<String>> list = new ArrayList<>();
        //判断是否是助手输出
        if (msg instanceof AssistantMessage am) {
            // 工具调用（ReactAgent 决定调用某个工具） 工具调用
            if (am.hasToolCalls()) {
                for (AssistantMessage.ToolCall call : am.getToolCalls()) {
                    // 推送中文进度提示
                    String progressMsg = getToolProgress(call.name());
                    if (progressMsg != null) {
                        list.add(ServerSentEvent.<String>builder()
                                .event("progress")
                                .data(progressMsg)
                                .build());
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    // 工具调用 id
                    payload.put("id", nullSafe(call.id()));
                    // 工具调用名称
                    payload.put("tool", nullSafe(call.name()));
                    // 工具调用参数
                    payload.put("params", nullSafe(call.arguments()));
                    //转成 json
                    String json = writeJson(payload);
                    list.add(ServerSentEvent.<String>builder()
                            .event("tool_call") //事件类型
                            .data(json) //数据：工具信息
                            .build());
                    toolCallCount.incrementAndGet(); // 统计工具调用次数
                    log.info("[stream→SSE] tool_call name={}, args={}", call.name(), call.arguments());
                }
            }
            // 文本 chunk（增量 token）
            String text = am.getText();
            if (text != null && !text.isEmpty()) {
                // 去重：检查新文本是否已被已发送内容覆盖（子串匹配，容忍微小差异）
                String trimmed = text.trim();
                boolean isDuplicate = false;
                if (trimmed.length() > 20) {
                    // 取前 40 个字符作为指纹，检查是否已在已发送内容中
                    String fingerprint = trimmed.substring(0, Math.min(40, trimmed.length()));
                    if (assistantBuf.toString().contains(fingerprint)) {
                        log.debug("[stream→SSE] 跳过重复文本片段 (指纹匹配, {} 字符)", trimmed.length());
                        isDuplicate = true;
                    }
                }
                if (!isDuplicate) {
                    assistantBuf.append(text);
                    list.add(ServerSentEvent.<String>builder().data(text).build());
                }
            }
        } else if (msg instanceof ToolResponseMessage tm) {
            // 工具执行结果
            for (ToolResponseMessage.ToolResponse resp : tm.getResponses()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("id", nullSafe(resp.id()));
                payload.put("tool", nullSafe(resp.name()));
                payload.put("result", nullSafe(resp.responseData()));
                String json = writeJson(payload);
                list.add(ServerSentEvent.<String>builder()
                        .event("tool_result")
                        .data(json)
                        .build());
                log.info("[stream→SSE] tool_result name={}, len={}", resp.name(),
                        resp.responseData() == null ? 0 : resp.responseData().length());
            }
        }
        return list;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("[stream→SSE] JSON 序列化失败", e);
            return "{}";
        }
    }

    /**
     * 根据工具名返回中文进度提示，用于前端实时反馈 Agent 当前在做什么。
     */
    private static String getToolProgress(String toolName) {
        if (toolName == null) return null;
        return switch (toolName) {
            case "getRepoMap" -> "🔍 正在探查项目结构...";
            case "searchCode" -> "🔍 正在搜索代码...";
            case "grepSearch" -> "🔍 正在精确搜索关键词...";
            case "listDir" -> "📁 正在浏览目录结构...";
            case "readFile" -> "📖 正在读取文件内容...";
            case "editFile" -> "✏️ 正在修改代码...";
            case "writeFile" -> "📝 正在写入文件...";
            case "executeShell" -> "⚙️ 正在执行命令...";
            case "searchWeb" -> "🌐 正在搜索互联网...";
            default -> null;
        };
    }

    /**
     * 将本轮用户输入与助手输出追加写入（只 INSERT 新消息，不做全量替换）。
     * 比 saveAll 更高效：对话 50 轮时，只插 2 条而不是删 49 轮再插 50 轮。
     */
    private void persistTurn(String conversationId, String userTask, String assistantText) {
        try {
            List<Message> newMessages = new ArrayList<>();
            newMessages.add(new UserMessage(userTask));
            if (assistantText != null && !assistantText.isEmpty()) {
                newMessages.add(new AssistantMessage(assistantText));
            }
            memoryRepository.appendTurn(conversationId, newMessages);
            log.info("[streamExecuteTask] 已追加 {} 条消息, conversationId={}, assistantLen={}",
                    newMessages.size(), conversationId, assistantText == null ? 0 : assistantText.length());
        } catch (Exception e) {
            log.error("[streamExecuteTask] 持久化消息失败, conversationId=" + conversationId, e);
        }
    }

    /**
     * QUERY 意图：增强混合检索 + LLM 流式回答。
     * 复用 HybridRetrievalService 的完整管道（查询重写→混合召回→RRF→Rerank）。
     * 增加拒答机制：检索质量过低时不硬答，提示用户补充信息。
     */
    private Flux<ServerSentEvent<String>> handleQueryIntent(String conversationId, String projectId, String userTask) {
        // 1. 增强混合检索
        HybridRetrievalService.HybridResult result;
        try {
            result = hybridRetrievalService.search(userTask, projectId, 25);
        } catch (Exception e) {
            log.error("[QUERY] 检索失败", e);
            result = new HybridRetrievalService.HybridResult(
                    java.util.Collections.emptyList(), 0, 0, java.util.Collections.emptyMap());
        }

        // 2. CRAG 质检 + Web 搜索兜底：检索结果为空 或 低置信度 → 回退 Web 搜索
        if (result.documents.isEmpty() || result.lowConfidence) {
            log.info("[QUERY] CRAG 质检未通过: docs={}, lowConfidence={}, avgScore={} → 回退 Web 搜索",
                    result.documents.size(), result.lowConfidence,
                    String.format("%.4f", result.avgRerankScore));

            // 2.1 Web 搜索兜底
            String webResult = null;
            try {
                WebSearchTool.Request webReq = new WebSearchTool.Request();
                webReq.setQuery(userTask);
                webResult = webSearchTool.apply(webReq);
            } catch (Exception e) {
                log.warn("[QUERY] Web 搜索兜底失败: {}", e.getMessage());
            }

            // 2.2 Web 搜到了有用内容 → 校验相关性
            if (webResult != null && !webResult.isBlank()
                    && !webResult.contains("未找到") && !webResult.contains("搜索失败")
                    && isWebResultRelevant(userTask, webResult)) {
                log.info("[QUERY] Web 搜索兜底成功，基于 Web 结果回答");
                String webPrompt = "内部代码库检索未找到相关内容，以下是从互联网搜索到的参考资料：\n" +
                        webResult + "\n\n用户问题：\n" + userTask +
                        "\n\n请基于上述参考资料回答，如果参考资料不足以回答，请如实说明。";
                return chatClient.prompt()
                        .user(webPrompt)
                        .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .stream()
                        .content()
                        .map(text -> ServerSentEvent.<String>builder().data(text).build())
                        .onErrorResume(err -> {
                            log.error("[QUERY] Web 兜底 LLM 流式错误", err);
                            return Flux.just(ServerSentEvent.<String>builder()
                                    .data("\n\n[错误] " + err.getMessage()).build());
                        });
            }

            // 2.3 Web 也没搜到或结果不相关 → 拒答
            log.info("[QUERY] Web 搜索兜底也未找到相关内容，拒答");
            String refuseMsg = "内部代码库和互联网检索均未找到与问题相关的信息，建议：\n" +
                    "1. 提供更具体的类名、方法名或文件路径\n" +
                    "2. 换一个角度描述你的问题\n" +
                    "3. 切换到 **Agent 模式**，我可以主动探查代码（getRepoMap + grep 精确搜索）\n\n" +
                    String.format("（检索诊断：内部召回 %d 条，Rerank 均分 %.4f）",
                            result.documents.size(), result.avgRerankScore);
            return Flux.just(
                    ServerSentEvent.<String>builder().event("message").data(refuseMsg).build(),
                    ServerSentEvent.<String>builder().event("done").data("ok").build()
            );
        }

        // 3. 构造上下文 prompt
        String contextBlock = buildContextBlock(result.documents);
        String finalPrompt = contextBlock.isEmpty() ? userTask : contextBlock + "\n\n用户问题：\n" + userTask;

        // 4. 流式调 LLM（不重复走 RAG pipeline）
        return chatClient.prompt()
                .user(finalPrompt)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content()
                .map(text -> ServerSentEvent.<String>builder().data(text).build())
                .onErrorResume(err -> {
                    log.error("[QUERY] LLM 流式错误", err);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .data("\n\n[错误] " + err.getMessage())
                            .build());
                });
    }

    /**
     * KNOWLEDGE 意图：跳过代码检索，直接 Web 搜索 + LLM 流式回答。
     * 适用于通用技术问题（如“Spring Boot 3 新特性”、“Java 21 虚拟线程”）。
     */
    private Flux<ServerSentEvent<String>> handleKnowledgeIntent(String conversationId, String userTask) {
        log.info("[KNOWLEDGE] 直接走 Web 搜索: {}", userTask);

        // 1. Web 搜索
        String webResult = null;
        try {
            WebSearchTool.Request webReq = new WebSearchTool.Request();
            webReq.setQuery(userTask);
            webResult = webSearchTool.apply(webReq);
        } catch (Exception e) {
            log.warn("[KNOWLEDGE] Web 搜索失败: {}", e.getMessage());
        }

        // 2. 构造最终 prompt
        String finalPrompt;
        if (webResult != null && !webResult.isBlank()
                && !webResult.contains("未找到") && !webResult.contains("搜索失败")) {
            log.info("[KNOWLEDGE] Web 搜索成功");
            finalPrompt = "以下是从互联网搜索到的参考资料：\n" + webResult +
                    "\n\n用户问题：\n" + userTask +
                    "\n\n请基于上述参考资料回答，如果参考资料不足以回答，请结合你的知识补充。";
        } else {
            log.info("[KNOWLEDGE] Web 搜索无结果，基于 LLM 自身知识回答");
            finalPrompt = userTask;
        }

        // 3. 流式调 LLM
        return chatClient.prompt()
                .user(finalPrompt)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content()
                .map(text -> ServerSentEvent.<String>builder().data(text).build())
                .onErrorResume(err -> {
                    log.error("[KNOWLEDGE] LLM 流式错误", err);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .data("\n\n[错误] " + err.getMessage()).build());
                });
    }

    /**
     * 校验 Web 搜索结果是否与用户问题相关。
     * 提取用户问题中的关键术语（3字符以上的标识符/名词），
     * 检查 Web 结果是否至少包含其中一个，避免用不相关的搜索结果误导 LLM。
     */
    private boolean isWebResultRelevant(String userMessage, String webResult) {
        Set<String> stopwords = Set.of("框架", "方法", "哪里", "实现", "怎么", "什么", "哪些",
                "如何", "为什么", "可以", "能否", "是否", "介绍", "说明", "请问",
                "在哪", "the", "and", "for", "with", "from", "has", "are", "was", "but");
        List<String> terms = Arrays.stream(userMessage.split("[\\s\\p{Punct}]+"))
                .map(String::trim)
                .filter(t -> t.length() >= 3)
                .filter(t -> !stopwords.contains(t.toLowerCase()))
                .toList();

        if (terms.isEmpty()) return true;

        String webLower = webResult.toLowerCase();
        long matchCount = terms.stream()
                .filter(t -> webLower.contains(t.toLowerCase()))
                .count();

        log.info("[{}] Web 相关性校验: queryTerms={}, matches={}",
                "QUERY", terms, matchCount);
        return matchCount >= 1;
    }

    /** 把检索片段拼成上下文提示，带来源标记。 */
    private String buildContextBlock(List<Document> docs) {
        if (docs == null || docs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("以下是从项目代码库中检索到的相关片段：\n")
                .append("若引用了上下文中的代码，请在引用处末尾标注来源，")
                .append("格式：[文件相对路径#L起始行-L结束行]。\n\n");
        int i = 1;
        for (Document d : docs) {
            Map<String, Object> meta = d.getMetadata() == null ? Map.of() : d.getMetadata();
            sb.append("--- 片段 ").append(i++).append(" ---\n");
            sb.append("[路径: ").append(meta.getOrDefault("file_path", "unknown"))
                    .append(" L").append(meta.getOrDefault("start_line", "?"))
                    .append("-L").append(meta.getOrDefault("end_line", "?"))
                    .append("]\n");
            sb.append(d.getText()).append("\n");
        }
        return sb.toString();
    }

    private String buildPromptWithHistory(List<Message> history, String currentUserMessage) {
        if (history == null || history.isEmpty()) {
            return currentUserMessage;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是之前的对话记录：\n");
        for (Message msg : history) {
            String role = msg instanceof UserMessage ? "用户" : "助手";
            sb.append(role).append(": ").append(msg.getText()).append("\n");
        }
        sb.append("用户现在的新问题: ").append(currentUserMessage);
        sb.append("\n请基于上述对话历史回答，如果需要修改之前生成的代码，请直接生成完整的新版本（不是 diff）。");
        return sb.toString();
    }
}
