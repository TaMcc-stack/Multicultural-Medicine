package com.backend.chat.dto;

import java.time.LocalDateTime;

/**
 * 会话消息（详情页使用）。
 *
 * <p>detail 为 assistant 消息落库时保存的完整响应对象（含 understanding / answer /
 * citations / retrieval 等），前端据此完整回放历史；用户消息 detail 为 null。</p>
 *
 * <p><b>刻意用 Object 而不是 AskResponse：</b>detail 是前端定义的、会持续演进的
 * 载荷，后端只负责存取。若绑定成某个具体 record，一旦前端多存一个字段，
 * Jackson 默认会因「未知字段」抛异常，导致**整条历史消息的 detail 变成 null**——
 * 界面表现为所有历史回答的依据文献、追问胶囊全部消失，且不报任何错。
 * 用 Object + readTree 原样透出，前后端各加各的字段互不影响。</p>
 */
public record ChatMessageDto(
        Long id,
        String role,
        String kind,
        String content,
        Object detail,
        LocalDateTime createdAt
) {
}
