package com.backend.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增动态请求：
 * 由「某条 AI 回答」转存而来，必须带上来源会话与来源消息，便于溯源与去重。
 */
public record CreateDynamicRequest(
        Long conversationId,
        Long messageId,
        @Size(max = 200, message = "标题过长") String title,
        @NotBlank(message = "分享内容不能为空") String content,
        Object detail
) {
}
