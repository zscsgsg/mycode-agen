package com.zsc.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zsc.config.AgentConfig;
import com.zsc.context.ConversationContextBuilder;
import com.zsc.dto.PlanStep;
import com.zsc.indexing.HybridRetrievalService;
import com.zsc.memory.TwoLevelChatMemoryRepository;
import com.zsc.tools.WebSearchTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Planner-Executor 分层：
 * <ul>
 *   <li>generatePlan — 调用 LLM 出分步计划（JSON 数组）</li>
 *   <li>executePlan — 按计划逐步调用 ReactAgent，每步发 SSE 事件</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlannerService {

    private final ChatClient chatClient;
    private final AgentConfig agentConfig;
    private final WorkspaceManager workspaceManager;
    private final TwoLevelChatMemoryRepository memoryRepository;
    private final ObjectMapper objectMapper;
    private final IntentService intentService;
    private final HybridRetrievalService hybridRetrievalService;
    private final WebSearchTool webSearchTool;
    private final ConversationContextBuilder contextBuilder;
    private final com.zsc.skills.SkillsLoader skillsLoader;
    private final ChatMemory chatMemory;

    @Value("${codemate.upload.base-path:./uploaded_projects}")
    private String uploadBasePath;

    private static final String PLAN_PROMPT = """
            你是一个资深编程架构师。根据用户的需求，输出一份分步计划（JSON 数组），告诉执行智能体如何一步步完成任务。
            
            输出格式要求（严格 JSON 数组，不要额外文字）：
            [
              {"step": 1, "title": "探查项目", "description": "调用 getRepoMap 获取项目整体结构，用 searchCode 查找相关类", "type": "SEARCH"},
              {"step": 2, "title": "读取文件", "description": "readFile 读取 XxxService.java 确认现有方法", "type": "READ"},
              ...
            ]
            
            type 可选值：SEARCH / READ / EDIT / CREATE / TEST / SHELL / OTHER
            
            规则：
            - 步骤数量 3~8 步，不要过细也不要过粗
            - 第 1 步必须是探查项目（SEARCH）
            - 如果涉及修改，修改步骤后必须紧跟一个 TEST 步骤
            - 最后一步通常是运行测试（TEST）
            - title 不超过 10 个字
            - description 要具体：提到具体的文件名、方法名、改什么
            
            用户需求：
            %s
            """;

    /**
     * 生成分步计划（同步调用 LLM）
     */
    public List<PlanStep> generatePlan(String projectId, String userTask, String model) {
        log.info("[planner] 生成计划, projectId={}, task={}", projectId, userTask);

        // 先做意图分类：KNOWLEDGE 类型不需要生成多步计划
        String intent = intentService.classifyIntent(userTask);
        log.info("[planner] 意图分类={}", intent);
        if ("QUERY".equals(intent)) {
            // QUERY 类型：代码库问答，走 ReactAgent 调用 grepSearch 精确搜索
            String queryDesc = "用 grepSearch 精确搜索关键词（从用户问题中提取核心词如注解名、类名、方法名），不要用 getRepoMap 猜测。";
            return List.of(new PlanStep(1, "代码问答", queryDesc, "QUERY"));
        }
        if ("KNOWLEDGE".equals(intent)) {
            // KNOWLEDGE 类型：通用技术问答，直接走 Web 搜索，不走 ReactAgent
            return List.of(new PlanStep(1, "技术问答", userTask, "KNOWLEDGE"));
        }

        String prompt = PLAN_PROMPT.formatted(userTask) + skillsLoader.buildSkillsPrompt();
        // 调用 LLM 返回 JSON 数组 输出格式要求（严格 JSON 数组，不要额外文字）
        String response = chatClient.prompt().user(prompt).call().content();
        log.info("[planner] LLM 返回: {}", response);

        // 提取 JSON 数组（兼容 LLM 可能返回 markdown 代码块）
        String json = extractJsonArray(response);
        try {
            List<PlanStep> steps = objectMapper.readValue(json, new TypeReference<>() {});
            log.info("[planner] 解析出 {} 个步骤", steps.size());
            return steps;
        } catch (Exception e) {
            log.error("[planner] JSON 解析失败, raw={}", json, e);
            // 降级：返回单步
            return List.of(new PlanStep(1, "直接执行", userTask, "OTHER"));
        }
    }

    /**
     * 逐步执行计划（SSE 流式）。
     * 每一步视为一次 ReactAgent 子任务，步与步之间发 plan_step_start / plan_step_done 事件。
     */
    public Flux<ServerSentEvent<String>> executePlan(String sessionId, String conversationId,
                                                      String projectId, List<PlanStep> steps,
                                                      String originalTask, String model) {
        //Flux.create 这个意思就是创建一个流，这个流是一个发送器，这个发送器可以发送消息给前端
        return Flux.create(sink -> {
            // [JDK 21 虚拟线程] 开启一个虚拟线程执行计划，不占用平台线程
            Thread thread = Thread.ofVirtual()
                    .name("plan-executor-" + sessionId)
                    .start(() -> {
                        try {
                            executePlanBlocking(sink, sessionId, conversationId, projectId, steps, originalTask, model);
                        } catch (Exception e) {
                            log.error("[planner] executePlan 异常", e);
                            sink.next(buildEvent("error", "{\"message\":\"" + e.getMessage() + "\"}"));
                            sink.complete();
                        }
                    });
            // 取消时中断线程
            sink.onDispose(() -> thread.interrupt());
        });
    }

    private void executePlanBlocking(reactor.core.publisher.FluxSink<ServerSentEvent<String>> sink,
                                     String sessionId, String conversationId,
                                     String projectId, List<PlanStep> steps,
                                     String originalTask, String model) throws Exception {
        // 初始化工作区
        Path workspace = workspaceManager.createWorkspace(sessionId);
        // 初始化 Java 项目环境
        workspaceManager.initJavaProject(workspace, projectId, uploadBasePath);
        String workspacePath = workspace.toString();
        // 创建 agent
        ReactAgent agent = agentConfig.buildCodingAgent(workspacePath, projectId, uploadBasePath, sessionId, model);

        // 加载历史消息（窗口裁剪：保留最近 N 条 + 旧消息摘要，防止 prompt 超长）
        List<Message> history = memoryRepository.findByConversationId(conversationId);
        List<Message> windowedHistory = contextBuilder.buildWindowedHistory(history);
        String historyContext = buildHistoryContext(windowedHistory);
        //把 AI 说的所有话记下来
        StringBuilder fullAssistant = new StringBuilder();
        // 性能监控
        final Instant startTime = Instant.now();
        final AtomicInteger toolCallCount = new AtomicInteger(0);

        for (int i = 0; i < steps.size(); i++) {
            PlanStep step = steps.get(i);

            // 发 plan_step_start 转成JSON 例如{"step": 1, "title": "探查项目", "description": "调用 getRepoMap 获取项目整体结构，用 searchCode 查找相关类", "type": "SEARCH"}
            String stepJson = writeJson(Map.of(
                    "step", step.step(),
                    "title", step.title(),
                    "description", step.description(),
                    "type", step.type(),
                    "total", steps.size()
            ));
            //给前端发送信息执行到哪一步
            sink.next(buildEvent("plan_step_start", stepJson));

            // 构造子任务 prompt（首步附带对话历史上下文）
            String subPrompt = buildStepPrompt(step, originalTask, i == 0, i == 0 ? historyContext : null);

            // 流式执行该步
            UserMessage msg = new UserMessage(subPrompt);
            //准备一个本子，记录AI这一步输出的内容
            StringBuilder stepOutput = new StringBuilder();

            // QUERY 类型：走 ReactAgent，让 Agent 调用 grepSearch/readFile 等工具
            // 不走 handleQueryStep（那是 Assistant 模式的快速路径）

            // KNOWLEDGE 类型：直接走 Web 搜索，不走 ReactAgent
            if ("KNOWLEDGE".equalsIgnoreCase(step.type())) {
                handleKnowledgeStep(sink, conversationId, originalTask, stepOutput, model);
                fullAssistant.append(stepOutput);
                sink.next(buildEvent("plan_step_done", writeJson(Map.of(
                        "step", step.step(), "title", step.title(), "status", "done"
                ))));
                continue;
            }

            agent.stream(msg)
                    .filter(o -> o instanceof StreamingOutput)
                    .cast(StreamingOutput.class)
                    .doOnNext(so -> {
                        //获取输出
                        Message m = so.message();
                        //判断是否为AI的输出
                        if (m instanceof AssistantMessage am) {
                            if (am.hasToolCalls()) {
                                for (AssistantMessage.ToolCall call : am.getToolCalls()) {
                                    // 推送中文进度提示
                                    String progress = getToolProgress(call.name());
                                    if (progress != null) {
                                        sink.next(buildEvent("progress", progress));
                                    }
                                    Map<String, Object> payload = new LinkedHashMap<>();
                                    payload.put("id", call.id() == null ? "" : call.id());
                                    payload.put("tool", call.name() == null ? "" : call.name());
                                    payload.put("params", call.arguments() == null ? "" : call.arguments());
                                    sink.next(buildEvent("tool_call", writeJson(payload)));
                                    toolCallCount.incrementAndGet();
                                }
                            }
                            String text = am.getText();
                            if (text != null && !text.isEmpty()) {
                                // 去重：检查新文本是否已被已发送内容覆盖（子串匹配）
                                String trimmed = text.trim();
                                boolean isDuplicate = false;
                                if (trimmed.length() > 20) {
                                    String fingerprint = trimmed.substring(0, Math.min(40, trimmed.length()));
                                    if (stepOutput.toString().contains(fingerprint)) {
                                        log.debug("[planner] 跳过重复文本片段 (指纹匹配, {} 字符)", trimmed.length());
                                        isDuplicate = true;
                                    }
                                }
                                if (!isDuplicate) {
                                    stepOutput.append(text);
                                    sink.next(ServerSentEvent.<String>builder().data(text).build());
                                }
                            }
                        } else if (m instanceof ToolResponseMessage tm) {
                            for (ToolResponseMessage.ToolResponse resp : tm.getResponses()) {
                                Map<String, Object> payload = new LinkedHashMap<>();
                                payload.put("id", resp.id() == null ? "" : resp.id());
                                payload.put("tool", resp.name() == null ? "" : resp.name());
                                payload.put("result", resp.responseData() == null ? "" : resp.responseData());
                                sink.next(buildEvent("tool_result", writeJson(payload)));
                            }
                        }
                    })
                    .onErrorResume(e -> {
                        // LLM 生成畸形 JSON 工具调用时不崩溃
                        String rootMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                        if (rootMsg == null) rootMsg = e.getClass().getSimpleName();
                        if (rootMsg.contains("Conversion from JSON") || rootMsg.contains("JsonParse")
                                || rootMsg.contains("JsonMapping") || rootMsg.contains("must be in JSON format")) {
                            log.warn("[planner] LLM 生成畸形 JSON 工具调用，跳过: {}", rootMsg);
                            String errMsg = "\n\n[警告] 模型生成了格式错误的工具调用参数，已自动跳过。\n";
                            stepOutput.append(errMsg);
                            sink.next(ServerSentEvent.<String>builder().data(errMsg).build());
                            return Flux.empty();
                        }
                        return Flux.error(e);
                    })
                    .blockLast(); // 等待这一步**完全执行完**，再走下一步

            fullAssistant.append(stepOutput);

            // 发 plan_step_done 转成JSON 例如{"step": 1, "title": "探查项目", "status": "done"}告诉前端这一步执行完了
            sink.next(buildEvent("plan_step_done", writeJson(Map.of(
                    "step", step.step(),
                    "title", step.title(),
                    "status", "done"
            ))));
        }

        // 等所用的步骤执行完，追加写入本轮对话（只 INSERT 新消息）
        persistTurn(conversationId, originalTask, fullAssistant.toString());

        // 发送性能指标事件
        long durationMs = Duration.between(startTime, Instant.now()).toMillis();
        int estimatedTokens = fullAssistant.length() / 4;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("durationMs", durationMs);
        metrics.put("durationSec", String.format("%.1f", durationMs / 1000.0));
        metrics.put("toolCalls", toolCallCount.get());
        metrics.put("estimatedTokens", estimatedTokens);
        metrics.put("outputChars", fullAssistant.length());
        log.info("[性能监控-Planner] 耗时={}ms, 工具调用={}次, 预估tokens={}", durationMs, toolCallCount.get(), estimatedTokens);
        sink.next(buildEvent("metrics", writeJson(metrics)));

        sink.complete();
    }

    /**
     * 将窗口裁剪后的历史消息拼成上下文提示，供 Agent 首步参考。
     */
    private String buildHistoryContext(List<Message> windowedHistory) {
        if (windowedHistory == null || windowedHistory.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("以下是之前的对话记录，供你了解上下文背景：\n");
        for (Message msg : windowedHistory) {
            String role = msg instanceof UserMessage ? "用户" : "助手";
            String text = msg.getText();
            // 对助手输出做截断，避免单条消息过长占用 prompt token
            if (text != null && text.length() > 600) {
                text = text.substring(0, 600) + "...(已截断)";
            }
            sb.append(role).append(": ").append(text).append("\n");
        }
        sb.append("请基于上述对话历史继续执行当前任务，如需修改之前生成的代码，请直接生成完整的新版本。");
        return sb.toString();
    }

    private String buildStepPrompt(PlanStep step, String originalTask, boolean isFirst, String historyContext) {
        StringBuilder sb = new StringBuilder();
        // 首步附带对话历史上下文，让 Agent 知道之前聊了什么
        if (historyContext != null && !historyContext.isBlank()) {
            sb.append(historyContext).append("\n\n");
        }
        sb.append("当前任务总目标：").append(originalTask).append("\n\n");
        sb.append("当前正在执行第 ").append(step.step()).append(" 步：").append(step.title()).append("\n");
        sb.append("要求：").append(step.description()).append("\n\n");
        if (isFirst && !"QUERY".equalsIgnoreCase(step.type())) {
            sb.append("这是第一步，请先用 getRepoMap / searchCode 探查项目。\n");
        }
        // QUERY 步骤：根据问题类型选择合适的工具
        if ("QUERY".equalsIgnoreCase(step.type())) {
            sb.append("⚠️ 这是代码问答步骤，必须按以下流程执行：\n");
            sb.append("1. 先用 grepSearch 精确搜索关键词（类名/方法名/注解名），定位文件位置\n");
            sb.append("2. **必须立即用 readFile 读取搜到的目标文件完整内容**（不能只报告文件位置就停止）\n");
            sb.append("3. 基于 readFile 返回的完整代码，给出具体详细的回答（包含方法签名、逻辑流程、行号引用）\n");
            sb.append("❌ 错误：grepSearch 搜到文件位置后说\"下一步将读取\"然后停止\n");
            sb.append("✅ 正确：grepSearch 搜到文件 → 立即 readFile 读完整内容 → 基于代码给出详细回答\n");
            sb.append("你可以在同一步内调用多个工具（grepSearch + readFile），不要只调一次就停下来！\n");
            sb.append("用户原始问题：").append(originalTask).append("\n");
        }
        // TEST 步骤：必须自动修复失败的测试，不要等用户指令
        if ("TEST".equalsIgnoreCase(step.type())) {
            sb.append("⚠️ 这是测试步骤，运行 mvn test 后如果失败，你必须**立即自动**进行根因分析并修复（最多重复 3 次）：\n");
            sb.append("① 解析 Maven 输出：提取失败的类名.方法名、异常类型、堆栈中的文件名.java:行号\n");
            sb.append("② 用 readFile 读取堆栈指出的具体文件\n");
            sb.append("③ 用 editFile 精准修复出错的几行\n");
            sb.append("④ 重新 mvn test\n");
            sb.append("不要只说「等待下一步指令」——你应该自动进入修复循环！\n");
            sb.append("3 次修复仍失败则如实报告具体错误，让用户决策。");
        } else {
            sb.append("完成这一步后，简要报告结果（不要做其他步骤的工作）。");
        }
        return sb.toString();
    }

    /**
     * QUERY 类型专用处理：走 CRAG 质检 + Web 搜索兜底 + 拒答三分支路由。
     */
    private void handleQueryStep(reactor.core.publisher.FluxSink<ServerSentEvent<String>> sink,
                                  String conversationId, String projectId,
                                  String userTask, StringBuilder stepOutput) {
        // 1. 混合检索
        HybridRetrievalService.HybridResult result;
        try {
            result = hybridRetrievalService.search(userTask, projectId, 25);
        } catch (Exception e) {
            log.error("[planner-QUERY] 检索失败", e);
            result = new HybridRetrievalService.HybridResult(Collections.emptyList(), 0, 0, Collections.emptyMap());
        }

        String finalPrompt;

        // 2. CRAG 质检
        if (result.documents.isEmpty() || result.lowConfidence) {
            log.info("[planner-QUERY] CRAG 质检未通过: docs={}, lowConfidence={}, avgScore={}",
                    result.documents.size(), result.lowConfidence, String.format("%.4f", result.avgRerankScore));

            // 2.1 Web 搜索兜底
            String webResult = null;
            try {
                WebSearchTool.Request webReq = new WebSearchTool.Request();
                webReq.setQuery(userTask);
                webResult = webSearchTool.apply(webReq);
            } catch (Exception e) {
                log.warn("[planner-QUERY] Web 搜索兜底失败: {}", e.getMessage());
            }

            // 2.2 Web 搜到了相关内容 → 基于 Web 结果回答
            if (webResult != null && !webResult.isBlank()
                    && !webResult.contains("未找到") && !webResult.contains("搜索失败")
                    && isWebResultRelevant(userTask, webResult)) {
                log.info("[planner-QUERY] Web 搜索兜底成功");
                finalPrompt = "内部代码库检索未找到相关内容，以下是从互联网搜索到的参考资料：\n" +
                        webResult + "\n\n用户问题：\n" + userTask +
                        "\n\n请基于上述参考资料回答，如果参考资料不足以回答，请如实说明。";
            } else {
                // 2.3 Web 也没搜到 → 拒答
                log.info("[planner-QUERY] Web 搜索兜底也未找到相关内容，拒答");
                String refuseMsg = "内部代码库和互联网检索均未找到与问题相关的信息，建议：\n" +
                        "1. 提供更具体的类名、方法名或文件路径\n" +
                        "2. 换一个角度描述你的问题\n\n" +
                        String.format("（检索诊断：内部召回 %d 条，Rerank 均分 %.4f）",
                                result.documents.size(), result.avgRerankScore);
                stepOutput.append(refuseMsg);
                sink.next(ServerSentEvent.<String>builder().data(refuseMsg).build());
                return;
            }
        } else {
            // 3. 质检通过 → 基于代码上下文回答
            log.info("[planner-QUERY] CRAG 质检通过: docs={}, avgScore={}",
                    result.documents.size(), String.format("%.4f", result.avgRerankScore));
            StringBuilder ctx = new StringBuilder("以下是从项目代码库中检索到的相关片段：\n");
            int idx = 1;
            for (org.springframework.ai.document.Document d : result.documents) {
                Map<String, Object> meta = d.getMetadata() == null ? Map.of() : d.getMetadata();
                ctx.append("--- 片段 ").append(idx++).append(" ---\n");
                ctx.append("[路径: ").append(meta.getOrDefault("file_path", "unknown"))
                        .append(" L").append(meta.getOrDefault("start_line", "?"))
                        .append("-L").append(meta.getOrDefault("end_line", "?")).append("]\n");
                ctx.append(d.getText()).append("\n");
            }
            finalPrompt = ctx + "\n用户问题：\n" + userTask;
        }

        // 4. 流式调 LLM
        chatClient.prompt().user(finalPrompt)
                .advisors(advisor -> advisor.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, conversationId))
                .stream().content()
                .doOnNext(chunk -> {
                    stepOutput.append(chunk);
                    sink.next(ServerSentEvent.<String>builder().data(chunk).build());
                })
                .blockLast();
    }

    /**
     * KNOWLEDGE 类型专用处理：跳过代码检索，直接 Web 搜索 + LLM 回答。
     * 自指性问题（如"你是谁"）跳过 Web 搜索，直接用身份 prompt 回答。
     */
    private void handleKnowledgeStep(reactor.core.publisher.FluxSink<ServerSentEvent<String>> sink,
                                      String conversationId, String userTask,
                                      StringBuilder stepOutput, String model) {
        // 根据用户选择的模型构建 ChatClient（非默认模型时动态创建）
        ChatClient client = buildKnowledgeClient(model);

        // 自指性问题：跳过 Web 搜索，直接用身份信息回答
        if (isSelfReferential(userTask)) {
            log.info("[planner-KNOWLEDGE] 自指性问题，跳过 Web 搜索: {}", userTask);
            String identityPrompt = buildIdentityPrompt(userTask, model);
            client.prompt().user(identityPrompt)
                    .advisors(advisor -> advisor.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, conversationId))
                    .stream().content()
                    .doOnNext(chunk -> {
                        stepOutput.append(chunk);
                        sink.next(ServerSentEvent.<String>builder().data(chunk).build());
                    })
                    .blockLast();
            return;
        }

        log.info("[planner-KNOWLEDGE] 直接走 Web 搜索: {}", userTask);

        String webResult = null;
        try {
            WebSearchTool.Request webReq = new WebSearchTool.Request();
            webReq.setQuery(userTask);
            webResult = webSearchTool.apply(webReq);
        } catch (Exception e) {
            log.warn("[planner-KNOWLEDGE] Web 搜索失败: {}", e.getMessage());
        }

        String finalPrompt;
        if (webResult != null && !webResult.isBlank()
                && !webResult.contains("未找到") && !webResult.contains("搜索失败")) {
            log.info("[planner-KNOWLEDGE] Web 搜索成功");
            finalPrompt = "以下是从互联网搜索到的参考资料：\n" + webResult +
                    "\n\n用户问题：\n" + userTask +
                    "\n\n请基于上述参考资料回答，如果参考资料不足以回答，请结合你的知识补充。";
        } else {
            log.info("[planner-KNOWLEDGE] Web 搜索无结果，基于 LLM 自身知识回答");
            finalPrompt = userTask;
        }

        client.prompt().user(finalPrompt)
                .advisors(advisor -> advisor.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, conversationId))
                .stream().content()
                .doOnNext(chunk -> {
                    stepOutput.append(chunk);
                    sink.next(ServerSentEvent.<String>builder().data(chunk).build());
                })
                .blockLast();
    }

    /**
     * 判断是否为自指性问题（询问 AI 自身身份、模型等）。
     */
    private boolean isSelfReferential(String userTask) {
        if (userTask == null) return false;
        String lower = userTask.toLowerCase().replaceAll("\\s+", "");
        return lower.contains("你是谁") || lower.contains("你是什么")
                || lower.contains("你的模型") || lower.contains("你用的模型")
                || lower.contains("你叫什么") || lower.contains("你的名字")
                || lower.contains("你用的哪个") || lower.contains("你用哪个")
                || lower.contains("你基于") || lower.contains("你背后")
                || lower.contains("whoareyou") || lower.contains("whatareyou")
                || lower.contains("whatmodel");
    }

    /**
     * 构建自指性问题的身份 prompt。
     */
    private String buildIdentityPrompt(String userTask, String model) {
        String modelLabel = switch (model != null ? model : "") {
            case "qwen-max3.7", "qwen-max" -> "通义千问 Qwen-Max（阿里百炼平台）";
            case "deepseek-v4-pro" -> "DeepSeek-V4 Pro（通过阿里百炼平台调用）";
            case "deepseek-v4-flash" -> "DeepSeek-V4 Flash（通过阿里百炼平台调用）";
            default -> "通义千问 Qwen-Plus（阿里百炼平台）";
        };
        return String.format("""
                你是 CodeMate，一个 AI 编程智能体。请用以下身份信息回答用户：
                - 你的名字：CodeMate
                - 当前使用的模型：%s
                - 你的能力：项目代码检索、代码生成与修改、单元测试编写、技术问答、Web 搜索
                - 平台：基于阿里百炼大模型平台
                \n用户问题：%s
                \n请用友好、简洁的中文回答。如果用户问"你是谁"，请直接介绍上述身份。""",
                modelLabel, userTask);
    }

    /**
     * 根据用户选择的模型构建 ChatClient。
     */
    private ChatClient buildKnowledgeClient(String model) {
        if (model != null && !model.isBlank() && !"qwen-plus".equals(model)) {
            var selectedChatModel = agentConfig.getModel(model);
            var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
            log.info("[planner-KNOWLEDGE] 使用用户选择的模型: {}", model);
            return ChatClient.builder(selectedChatModel)
                    .defaultSystem("你是一个智能编程助手。请用中文回答，并提供清晰、准确的代码示例。")
                    .defaultOptions(DashScopeChatOptions.builder()
                            .withTemperature(0.7)
                            .withTopP(0.9)
                            .build())
                    .defaultAdvisors(memoryAdvisor)
                    .build();
        }
        return chatClient;
    }

    /**
     * 校验 Web 搜索结果是否与用户问题相关。
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
        log.info("[planner-QUERY] Web 相关性校验: queryTerms={}, matches={}", terms, matchCount);
        return matchCount >= 1;
    }

    private void persistTurn(String conversationId, String userTask, String assistantText) {
        try {
            List<Message> newMessages = new ArrayList<>();
            newMessages.add(new UserMessage(userTask));
            if (assistantText != null && !assistantText.isEmpty()) {
                newMessages.add(new AssistantMessage(assistantText));
            }
            memoryRepository.appendTurn(conversationId, newMessages);
        } catch (Exception e) {
            log.error("[planner] 持久化失败 conversationId={}", conversationId, e);
        }
    }

    private ServerSentEvent<String> buildEvent(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
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

    private static String extractJsonArray(String raw) {
        if (raw == null) return "[]";
        // 去掉 markdown 代码块 去掉首尾空白
        String s = raw.strip();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first > 0 && last > first) {
                s = s.substring(first + 1, last).strip();
            }
        }
        // 找第一个 [ 到最后一个 ]
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }
}
