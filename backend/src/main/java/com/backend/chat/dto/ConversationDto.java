package com.backend.chat.dto;

import java.time.LocalDateTime;

/**
 * 会话摘要（列表页使用）
 */
public record ConversationDto(
        Long id,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        int messageCount
) {
}
