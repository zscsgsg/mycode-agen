package com.zsc.vo;



import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ConversationVO {
    private String id;
    private String title;
    private String mode;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
//    private Integer messageCount;   // 该会话的消息条数
}
