package com.zsc.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoredMessage {
    private String role;    // "user" 或 "assistant"
    private String content;
}