package com.zsc.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.zsc.memory.TwoLevelChatMemoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AIConfig {

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    /**
     * 注册 DashScopeApi Bean，供 AgentConfig 动态创建多模型 ChatModel 使用。
     */
    @Bean
    public DashScopeApi dashScopeApi() {
        return DashScopeApi.builder().apiKey(apiKey).build();
    }


    @Bean
    public TextSplitter textSplitter() {
        return new TokenTextSplitter(800, 200, 100, 10000, true);
    }

    @Bean
    public ChatMemory chatMemory(TwoLevelChatMemoryRepository repository) {
        // 使用内置窗口策略，保留最近 20 条消息
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }


    /**
     * 裸 RAG ChatClient：不装检索/Rerank Advisor。
     * 供 ManualRagChatService、AgentExecutionService（QUERY 分流）使用——
     * 先由 HybridRetrievalService 完成检索（含查询重写+RRF+Rerank），
     * 再将结果拼入 prompt 调 LLM，避免重复检索。
     */
    @Bean("bareRagChatClient")
    public ChatClient bareRagChatClient(DashScopeChatModel chatModel, ChatMemory chatMemory) {
        MessageChatMemoryAdvisor memoryAdvisor =
                MessageChatMemoryAdvisor.builder(chatMemory).build();
        return ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是一个智能编程助手。请用中文回答，并提供清晰、准确的代码示例。
                        回答时若引用了上下文中的代码，必须在引用处末尾标注来源，
                        格式：[文件相对路径#L起始行-L结束行]，例如 [Bean/Student.java#L120-L130]。
                        来源信息已写在每段代码片段开头的 // [路径:行号] 注释中。
                        若上下文中没有相关片段，请明确告知"未检索到相关代码"，不要凭空推测。
                        """)
                .defaultOptions(
                        DashScopeChatOptions.builder()
                                .withTemperature(0.7)
                                .withTopP(0.9)
                                .build()
                )
                .defaultAdvisors(memoryAdvisor)
                .build();
    }

}
