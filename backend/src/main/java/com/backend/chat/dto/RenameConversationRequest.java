package com.backend.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 会话重命名请求
 */
public record RenameConversationRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 100, message = "会话标题过长")
        String title
) {
}
