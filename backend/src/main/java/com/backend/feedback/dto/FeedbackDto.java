package com.backend.feedback.dto;

import java.time.LocalDateTime;

/**
 * 一条用户反馈（对外展示用）。
 *
 * <p>{@code userName} / {@code nickname} / {@code avatar} 在**匿名帖**上全为 null——
 * 匿名发布的 {@code userId} 就是 null，查不到用户。展示名由前端兜底成「匿名用户」，
 * 与动态那边的 {@code DynamicDto.authorName()} 同一思路（那边是「昵称优先、其次用户名」）。</p>
 */
public record FeedbackDto(
        Long id,
        /** 发布者ID；null = 匿名 */
        Long userId,
        String userName,
        String nickname,
        String avatar,
        String content,
        /** pending 待补充 / processing 处理中 / done 已补充 */
        String status,
        /** 已转为知识缺口时指向那条缺口，供后台给出跳转 */
        Long gapId,
        int likeCount,
        /** 当前登录用户是否已点赞；匿名请求恒为 false */
        boolean liked,
        /** 是否为当前登录用户发布（决定要不要显示「我发布的」标记）；匿名请求恒为 false */
        boolean owner,
        LocalDateTime createdAt
) {
}
