package com.backend.chat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 追加一条会话消息（仅持久化，不重新生成）。
 * 由前端在本地生成回答后调用，把 user 问题 + assistant 完整响应落库，供历史回放。
 */
public record RecordMessageRequest(
        @NotBlank(message = "角色不能为空") String role,
        String kind,
        @NotBlank(message = "内容不能为空") String content,
        Object detail
) {
}
