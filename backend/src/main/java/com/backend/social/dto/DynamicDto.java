package com.backend.social.dto;

import java.time.LocalDateTime;

/**
 * 动态列表 / 详情返回结构。
 * owner / favorited 相对「当前登录用户」计算：是否为本人发布、是否已收藏。
 */
public record DynamicDto(
        Long id,
        Long userId,
        String username,
        String nickname,
        /** 作者的头像配色 key（前端按 key 取渐变色画「首字头像」）；用户不存在时为 null */
        String avatar,
        Long sourceConversationId,
        Long sourceMessageId,
        String title,
        String content,
        /** 结构化回答 JSON（详情页还原展示用） */
        String detailJson,
        /** 动态类型：dialogue 多轮对话 / qa 一问一答。历史数据（列为 NULL）一律按 dialogue 处理 */
        String kind,
        /** 问答卡的规范化键「民族|疾病|意图」；对话类为 null */
        String qaKey,
        int favoriteCount,
        boolean favorited,
        boolean owner,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /** 展示用作者名：昵称优先，其次用户名 */
    public String authorName() {
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        return username == null ? "" : username;
    }
}
