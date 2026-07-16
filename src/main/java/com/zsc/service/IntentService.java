// com.zsc.service.IntentService.java
package com.zsc.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class IntentService {
    private final ChatClient chatClient;

    public IntentService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String classifyIntent(String userTask) {
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
}