package com.zsc.controller;


import com.zsc.service.IntentService;
import com.zsc.service.ManualRagChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * <p>
 *  前端控制器（RAG 助手）
 * </p>
 *
 * @author 周书超
 * @since 2026-05-11
 */
@RestController
@RequestMapping("/chat-message")
@Slf4j
public class ChatMessageController {

    private final IntentService intentService;
    private final ManualRagChatService manualRagChatService;

    public ChatMessageController(IntentService intentService,
                                 ManualRagChatService manualRagChatService) {
        this.intentService = intentService;
        this.manualRagChatService = manualRagChatService;
    }

    /**
     * RAG 模式聊天（SSE 多事件）：
     *  - event: retrieved_docs   首先发，data 为 JSON {vector_hits, bm25_hits, docs:[...]}
     *  - event: message          多次，data 为 LLM 文本片段
     *  - event: done             结束
     * 若意图为 ADD/MODIFY，仅发一条 message 引导用户切到智能体页面 + done。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestParam String conversationId,
                                              @RequestParam String projectId,
                                              @RequestParam String message) {
        // 1. 意图分类
        String intent = intentService.classifyIntent(message);
        if ("ADD".equals(intent) || "MODIFY".equals(intent)) {
            String guide = "您想要" + ("ADD".equals(intent) ? "新增" : "修改")
                    + "代码。请切换到【编程智能体】页面（/agent），我可以自动生成代码并运行测试。";
            return Flux.just(
                    ServerSentEvent.<String>builder().event("message").data(guide).build(),
                    ServerSentEvent.<String>builder().event("done").data("ok").build()
            );
        }

        // 2. 走手工 RAG（先发检索片段事件，再流式 LLM）
        log.info("[chat] projectId={}, message={}", projectId, message);
        return manualRagChatService.chat(conversationId, projectId, message);
    }
}
