package com.zsc.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zsc.entity.ChatMemoryWrapper;
import com.zsc.entity.ChatMessage;
import com.zsc.entity.StoredMessage;
import com.zsc.service.IChatMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
@Slf4j
public class TwoLevelChatMemoryRepository implements ChatMemoryRepository {

    private final RedisTemplate<String, ChatMemoryWrapper> redisTemplate;
    private final IChatMessageService chatMessageService;

    @Value("${codemate.memory.redis.ttl-seconds:6000}")
    private long redisTtlSeconds;

    // ==================== 转换方法 ====================

    private List<StoredMessage> toStoredMessages(List<Message> messages) {
        if (messages == null) return List.of();
        return messages.stream()
                .map(msg -> new StoredMessage(
                        msg instanceof UserMessage ? "user" : "assistant",
                        msg.getText()
                ))
                .collect(Collectors.toList());
    }

    private List<Message> toMessages(List<StoredMessage> stored) {
        if (stored == null) return List.of();
        return stored.stream()
                .map(s -> {
                    if ("user".equals(s.getRole())) {
                        return new UserMessage(s.getContent());
                    } else {
                        return new AssistantMessage(s.getContent());
                    }
                })
                .collect(Collectors.toList());
    }

    // ==================== ChatMemoryRepository 接口实现 ====================

    @NotNull
    @Override
    public List<String> findConversationIds() {
        List<ChatMessage> messages = chatMessageService.lambdaQuery()
                .select(ChatMessage::getConversationId)
                .groupBy(ChatMessage::getConversationId)
                .list();
        return messages.stream()
                .map(ChatMessage::getConversationId)
                .distinct()
                .collect(Collectors.toList());
    }

    @NotNull
    @Override
    public List<Message> findByConversationId(String conversationId) {
        String redisKey = buildRedisKey(conversationId);

        // 1. 优先读 Redis
        ChatMemoryWrapper wrapper = redisTemplate.opsForValue().get(redisKey);
        if (wrapper != null && wrapper.getMessages() != null) {
            log.debug("Redis hit for conversation: {}", conversationId);
            return toMessages(wrapper.getMessages());
        }

        // 2. Redis 未命中，加载数据库
        log.debug("Redis miss for conversation: {}, loading from DB", conversationId);
        List<Message> messages = loadFromDatabase(conversationId);
        if (messages.isEmpty()) {
            return List.of();
        }

        // 3. 回填 Redis
        long now = System.currentTimeMillis();
        ChatMemoryWrapper newWrapper = new ChatMemoryWrapper(toStoredMessages(messages), now);
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, newWrapper, redisTtlSeconds, TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(success)) {
            log.debug("Redis cache filled for conversation: {}", conversationId);
            return messages;
        }

        // 4. 已存在缓存，比较时间戳
        ChatMemoryWrapper existing = redisTemplate.opsForValue().get(redisKey);
        if (existing != null && existing.getLastUpdated() > now) {
            log.debug("Redis already has newer data for conversation: {}, using it", conversationId);
            return toMessages(existing.getMessages());
        } else {
            redisTemplate.opsForValue().set(redisKey, newWrapper, redisTtlSeconds, TimeUnit.SECONDS);
            log.debug("Redis cache updated (overwritten) for conversation: {}", conversationId);
        }
        return messages;
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null) {
            messages = List.of();
        }

        // 1. 替换数据库中的消息
        chatMessageService.remove(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId));

        if (!messages.isEmpty()) {
            List<ChatMessage> entities = new ArrayList<>();
            for (Message msg : messages) {
                entities.add(convertToDatabaseEntity(conversationId, msg));
            }
            chatMessageService.saveBatch(entities);
        }

        // 2. 刷新 Redis 缓存
        refreshRedisFromDatabase(conversationId);
        log.info("Saved {} messages for conversation: {}", messages.size(), conversationId);
    }

    /**
     * 追加写入：只 INSERT 本轮新增的消息（user + assistant），不做全量替换。
     * 比 saveAll 更高效：对话 50 轮时，只插 2 条而不是删 49 轮再插 50 轮。
     *
     * @param conversationId 会话 ID
     * @param newMessages    本轮新增的消息列表（通常 2 条：1 user + 1 assistant）
     */
    @Transactional
    public void appendTurn(String conversationId, List<Message> newMessages) {
        if (newMessages == null || newMessages.isEmpty()) {
            log.warn("[appendTurn] 新消息为空，跳过, conversationId={}", conversationId);
            return;
        }

        // 1. 只插入新消息（不删旧消息）
        List<ChatMessage> entities = new ArrayList<>();
        for (Message msg : newMessages) {
            entities.add(convertToDatabaseEntity(conversationId, msg));
        }
        chatMessageService.saveBatch(entities);

        // 2. 刷新 Redis 缓存（从 DB 加载完整历史）
        refreshRedisFromDatabase(conversationId);
        log.info("[appendTurn] 追加 {} 条消息, conversationId={}", newMessages.size(), conversationId);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        chatMessageService.remove(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId));
        redisTemplate.delete(buildRedisKey(conversationId));
        log.info("Deleted conversation: {}", conversationId);
    }

    // ==================== 私有辅助方法 ====================

    private String buildRedisKey(String conversationId) {
        return "chat_memory:" + conversationId;
    }

    private List<Message> loadFromDatabase(String conversationId) {
        List<ChatMessage> records = chatMessageService.lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getCreatedAt)
                .list();
        return records.stream()
                .map(this::convertToSpringMessage)
                .collect(Collectors.toList());
    }

    private Message convertToSpringMessage(ChatMessage entity) {
        String role = entity.getRole();
        String content = entity.getContent();
        if ("user".equalsIgnoreCase(role)) {
            return new UserMessage(content);
        } else {
            return new AssistantMessage(content);
        }
    }

    private ChatMessage convertToDatabaseEntity(String conversationId, Message message) {
        ChatMessage entity = new ChatMessage();
        entity.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
        entity.setConversationId(conversationId);
        entity.setContent(message.getText());
        entity.setRole(message instanceof UserMessage ? "user" : "assistant");
        entity.setCreatedAt(OffsetDateTime.now());
        return entity;
    }

    private void refreshRedisFromDatabase(String conversationId) {
        String redisKey = buildRedisKey(conversationId);
        List<Message> latestMessages = loadFromDatabase(conversationId);
        long newTimestamp = System.currentTimeMillis();
        ChatMemoryWrapper newWrapper = new ChatMemoryWrapper(toStoredMessages(latestMessages), newTimestamp);

        for (int retry = 0; retry < 3; retry++) {
            ChatMemoryWrapper existing = redisTemplate.opsForValue().get(redisKey);
            if (existing == null || existing.getLastUpdated() < newTimestamp) {
                redisTemplate.opsForValue().set(redisKey, newWrapper, redisTtlSeconds, TimeUnit.SECONDS);
                log.debug("Redis refreshed for conversation: {}", conversationId);
                return;
            } else {
                log.debug("Redis already has newer data for conversation: {}, skip refresh", conversationId);
                return;
            }
        }
        log.warn("Failed to refresh Redis after retries for conversation: {}", conversationId);
    }
}