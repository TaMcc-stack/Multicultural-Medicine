package com.backend.social.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 收藏 / 取消收藏「一段对话」的请求。
 * 与 {@link FavoriteRequest}（收藏**已发布的动态**）是两个不同的动作：
 * 这个只写私有书签，不会把对话公开出去。
 */
public record ConversationFavoriteRequest(
        @NotNull(message = "会话ID不能为空") Long conversationId
) {
}
