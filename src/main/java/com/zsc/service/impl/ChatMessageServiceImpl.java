package com.zsc.service.impl;

import com.zsc.entity.ChatMessage;
import com.zsc.mapper.ChatMessageMapper;
import com.zsc.service.IChatMessageService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 周书超
 * @since 2026-05-11
 */
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements IChatMessageService {

}
