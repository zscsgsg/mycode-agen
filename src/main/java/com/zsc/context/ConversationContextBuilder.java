package com.zsc.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 模式对话上下文窗口管理器。
 * <p>
 * 解决 Agent 模式加载全部历史消息导致 prompt 过长、撑爆 LLM token 上限的问题。
 * <p>
 * 策略：保留最近 N 条原始消息 + 对更早的消息用 LLM 生成摘要。
 * 摘要调用使用独立的 ChatClient（不走 Memory Advisor），无副作用。
 */
@Component
@Slf4j
public class ConversationContextBuilder {

    private final ChatClient chatClient;

    /** 上下文 token 预算（粗略估算：4 字符 ≈ 1 token） */
    @Value("${codemate.memory.agent-max-tokens:30000}")
    private int maxTokens;

    /** 粗略估算：每 4 个字符约 1 个 token */
    private static final int CHARS_PER_TOKEN = 4;

    public ConversationContextBuilder(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 对历史消息做 token 预算窗口裁剪，返回裁剪后的消息列表。
     * <p>
     * 策略：从最近的消息往前累加 token，直到超出预算，超出的部分用 LLM 生成摘要。
     * <ul>
     *   <li>总 token ≤ maxTokens：原样返回</li>
     *   <li>总 token > maxTokens：对旧消息用 LLM 生成摘要，作为一条 UserMessage 插入到窗口前</li>
     * </ul>
     */
    public List<Message> buildWindowedHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return history;
        }

        // 从后往前累加 token，找出能装入预算的最大后缀
        int totalTokens = 0;
        int cutIndex = history.size(); // 默认为全部消息都在预算内
        for (int i = history.size() - 1; i >= 0; i--) {
            String text = history.get(i).getText();
            int msgTokens = (text == null) ? 0 : text.length() / CHARS_PER_TOKEN;
            if (totalTokens + msgTokens > maxTokens) {
                cutIndex = i + 1; // 从这条开始超出预算
                break;
            }
            totalTokens += msgTokens;
            cutIndex = i;
        }

        // 全部消息都在预算内，不需要裁剪
        if (cutIndex == 0) {
            log.debug("[contextBuilder] 历史 {} 条 / ~{} tokens，未超出预算 {} tokens",
                    history.size(), totalTokens, maxTokens);
            return history;
        }

        List<Message> oldMessages = history.subList(0, cutIndex);
        List<Message> recentMessages = history.subList(cutIndex, history.size());
        int oldTokens = oldMessages.stream()
                .mapToInt(m -> (m.getText() == null ? 0 : m.getText().length() / CHARS_PER_TOKEN))
                .sum();

        log.info("[contextBuilder] 历史 {} 条 / ~{} tokens，超出预算 {} tokens，对旧 {} 条（~{} tokens）生成摘要",
                history.size(), totalTokens + oldTokens, maxTokens, oldMessages.size(), oldTokens);

        String summary = summarizeOldMessages(oldMessages);
        UserMessage summaryMsg = new UserMessage("[对话历史摘要]\n" + summary);

        ArrayList<Message> windowed = new ArrayList<>(recentMessages.size() + 1);
        windowed.add(summaryMsg);
        windowed.addAll(recentMessages);
        return windowed;
    }

    /**
     * 把旧消息序列化为文本，调 LLM 生成一段不超过 500 字的中文摘要。
     * 若 LLM 调用失败，回退为保留最近 5 条旧消息的原文。
     */
    private String summarizeOldMessages(List<Message> oldMessages) {
        StringBuilder historyText = new StringBuilder("以下是之前的对话记录（较早的部分）：\n");
        for (Message msg : oldMessages) {
            String role = msg instanceof UserMessage ? "用户" : "助手";
            String text = msg.getText();
            // 对助手输出做截断，防止摘要输入过长
            if (text != null && text.length() > 800) {
                text = text.substring(0, 800) + "...(已截断)";
            }
            historyText.append(role).append(": ").append(text).append("\n");
        }
        historyText.append("\n请用不超过 500 字总结上述对话的核心内容：\n")
                .append("- 讨论了什么主题\n")
                .append("- 完成了哪些任务（修改了哪些文件/方法）\n")
                .append("- 有哪些待处理的问题\n")
                .append("- 当前对话的状态和下一步");

        try {
            // 使用 chatClient 但通过 conversation_id 隔离，避免影响用户会话记忆
            String summary = chatClient.prompt()
                    .user(historyText.toString())
                    .advisors(advisor -> advisor.param(
                            org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID,
                            "system-internal-summary"))
                    .call()
                    .content();
            if (summary != null && !summary.isBlank()) {
                log.info("[contextBuilder] 摘要生成成功，长度={} 字符", summary.length());
                return summary.strip();
            }
        } catch (Exception e) {
            log.warn("[contextBuilder] 摘要生成失败，回退为保留最近旧消息原文: {}", e.getMessage());
        }

        // 回退：取最后 5 条旧消息的原文拼接
        int fallbackCount = Math.min(5, oldMessages.size());
        List<Message> fallback = oldMessages.subList(oldMessages.size() - fallbackCount, oldMessages.size());
        StringBuilder sb = new StringBuilder("[摘要生成失败，保留最近 ")
                .append(fallbackCount).append(" 条旧消息]\n");
        for (Message msg : fallback) {
            String role = msg instanceof UserMessage ? "用户" : "助手";
            String text = msg.getText();
            if (text != null && text.length() > 400) {
                text = text.substring(0, 400) + "...(已截断)";
            }
            sb.append(role).append(": ").append(text).append("\n");
        }
        return sb.toString();
    }
}
