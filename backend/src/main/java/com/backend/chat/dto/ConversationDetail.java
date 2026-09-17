package com.backend.chat.dto;

import java.util.List;

/**
 * 会话详情：会话信息 + 全部消息
 */
public record ConversationDetail(
        ConversationDto conversation,
        List<ChatMessageDto> messages
) {
}
