package com.zsc.service;

import com.zsc.entity.ChatMessage;
import com.zsc.entity.Conversation;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zsc.vo.ConversationVO;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 周书超
 * @since 2026-05-11
 */
public interface IConversationService extends IService<Conversation> {
    /**
     * 创建会话
     *
     * @param mode 会话模式
     * @param title 会话标题
     * @return 会话对象
     */
    Conversation createConversation(String mode, String title);
    /**
     * 获取当前用户所有会话（按模式过滤）
     *
     * @param mode 会话模式
     * @return 会话列表
     */
    List<ConversationVO> listConversations(String mode);
    /**
     * 删除会话
     *
     * @param id 会话ID
     * @return 是否成功
     */
    boolean updateConversationTitle(String id, String title);
    /**
     * 删除会话
     *
     * @param id 会话ID
     * @return 是否成功
     */
    boolean deleteConversation(String id);
    /**
     * 获取某个会话的历史消息（用于刷新页面恢复聊天）
     *
     * @param id 会话ID
     * @return 消息列表
     */
    List<ChatMessage> getMessages(String id);
}
