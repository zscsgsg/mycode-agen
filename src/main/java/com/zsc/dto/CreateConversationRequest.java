package com.zsc.dto;

import lombok.Data;


@Data
public class CreateConversationRequest {

    private String mode;   // "assistant" 或 "agent"

    private String title;  // 可选，不传默认“新对话”
}
