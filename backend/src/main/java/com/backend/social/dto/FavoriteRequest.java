package com.backend.social.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 收藏 / 取消收藏请求：只需要动态 ID
 */
public record FavoriteRequest(
        @NotNull(message = "动态ID不能为空") Long dynamicId
) {
}
