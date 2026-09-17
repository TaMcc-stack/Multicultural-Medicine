package com.backend.chat.dto;

import jakarta.validation.constraints.Size;

/**
 * 创建会话请求：title 可空，为空时后端按首问自动生成
 */
public record CreateConversationRequest(
        @Size(max = 100, message = "会话标题过长") String title
) {
}
