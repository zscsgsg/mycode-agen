package com.zsc.service.impl;



import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zsc.vo.ConversationVO;
import com.zsc.entity.ChatMessage;
import com.zsc.entity.Conversation;
import com.zsc.mapper.ConversationMapper;
import com.zsc.memory.TwoLevelChatMemoryRepository;
import com.zsc.service.IChatMessageService;
import com.zsc.service.IConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation> implements IConversationService {

    private final IChatMessageService chatMessageService;
    private final TwoLevelChatMemoryRepository memoryRepository;

    @Override
    @Transactional
    public Conversation createConversation(String mode, String title) {
        Conversation conv = new Conversation();
        conv.setMode(mode);
        conv.setTitle(title != null && !title.isBlank() ? title : "新对话");
        conv.setUserId("default");   // 单用户固定
        conv.setCreatedAt(OffsetDateTime.now());
        conv.setUpdatedAt(OffsetDateTime.now());
        conv.setLastMessageAt(OffsetDateTime.now());
        save(conv);
        return conv;
    }

    @Override
    public List<ConversationVO> listConversations(String mode) {
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getMode, mode)
                .orderByDesc(Conversation::getLastMessageAt);
        List<Conversation> list = list(wrapper);
        return list.stream().map(conv -> {
            ConversationVO vo = new ConversationVO();
            BeanUtils.copyProperties(conv, vo);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public boolean updateConversationTitle(String id, String title) {
        Conversation conv = getById(id);
        if (conv == null) return false;
        conv.setTitle(title);
        conv.setUpdatedAt(OffsetDateTime.now());
        return updateById(conv);
    }

    @Override
    @Transactional
    public boolean deleteConversation(String id) {
        // 1. 删除所有消息（调用已有的 TwoLevelChatMemoryRepository）
        memoryRepository.deleteByConversationId(id);
        // 2. 删除会话记录
        return removeById(id);
    }

    @Override
    public List<ChatMessage> getMessages(String conversationId) {
        return chatMessageService.lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getCreatedAt)
                .list();
    }
}