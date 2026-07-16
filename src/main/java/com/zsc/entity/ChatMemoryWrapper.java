package com.zsc.entity;



import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Redis 存储的对话记忆包装类，携带最后更新时间戳用于乐观锁一致性检查
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMemoryWrapper {
    private List<StoredMessage> messages;   // 全量消息（窗口截取由上层负责）
    private long lastUpdated;         // 毫秒时间戳
}