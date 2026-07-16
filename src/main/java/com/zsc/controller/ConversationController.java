package com.zsc.controller;

import com.zsc.dto.CreateConversationRequest;
import com.zsc.dto.UpdateConversationRequest;
import com.zsc.entity.ApiResult;
import com.zsc.entity.ChatMessage;
import com.zsc.entity.Conversation;
import com.zsc.service.IConversationService;
import com.zsc.vo.ConversationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/conversation")
@RequiredArgsConstructor
public class ConversationController {

    private final IConversationService conversationService;

    /**
     * 新建会话
     */
    @PostMapping("/create")
    public ResponseEntity<ApiResult<Map<String, Object>>> create( @RequestBody CreateConversationRequest request) {
        Conversation conv = conversationService.createConversation(request.getMode(), request.getTitle());
        Map<String, Object> data = Map.of(
                "conversationId", conv.getId(),
                "createdAt", conv.getCreatedAt(),
                "title", conv.getTitle()
        );
        return ResponseEntity.ok(ApiResult.success(data));
    }

    /**
     * 获取当前用户的所有会话（按模式过滤）
     */
    @GetMapping(value = "/list")
    public ApiResult<List<ConversationVO>> list(@RequestParam String mode) {
        List<ConversationVO> list = conversationService.listConversations(mode);
        return ApiResult.success(list);
    }

    /**
     * 更新会话标题（手动或自动调用）
     */
    @PutMapping("/update")
    public ResponseEntity<ApiResult<String>> updateTitle(@Valid @RequestBody UpdateConversationRequest request) {
        boolean success = conversationService.updateConversationTitle(request.getId(), request.getTitle());
        if (success) {
            return ResponseEntity.ok(ApiResult.success("更新成功"));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResult.error(404, "会话不存在"));
        }
    }

    /**
     * 删除会话（同时删除所有消息和缓存）
     */
    @DeleteMapping("/delete/{id}")
    public ResponseEntity<ApiResult<Void>> delete(@PathVariable String id) {
        boolean success = conversationService.deleteConversation(id);
        if (success) {
            return ResponseEntity.ok(ApiResult.success());
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResult.error(404, "会话不存在"));
        }
    }

    /**
     * 获取某个会话的历史消息（用于刷新页面恢复聊天）
     */
    @GetMapping("/{id}/messages")
    public ResponseEntity<ApiResult<List<ChatMessage>>> getMessages(@PathVariable String id) {
        List<ChatMessage> messages = conversationService.getMessages(id);
        return ResponseEntity.ok(ApiResult.success(messages));
    }
}