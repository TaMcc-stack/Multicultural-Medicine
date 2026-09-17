package com.backend.feedback.entity;

import java.time.LocalDateTime;

/**
 * 一条用户反馈（表 user_feedback）。
 *
 * <p>{@code userId} 为 {@code null} 表示**未登录的匿名发布**——本表是全站唯一允许匿名写入的，
 * 因为留言板页面公开可访问。有 id 的帖子照常显示昵称与头像。</p>
 */
public class UserFeedback {

    private Long id;
    /** 发布者ID；null = 匿名发布 */
    private Long userId;
    /** 反馈原文（保留换行） */
    private String content;
    /** pending 待补充 / processing 处理中 / done 已补充 */
    private String status;
    /** 转为知识缺口后指向它；null = 还没转 */
    private Long gapId;
    /** 点赞数（由 user_feedback_like 重算出来，不是自增维护的） */
    private int likeCount;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getGapId() { return gapId; }
    public void setGapId(Long gapId) { this.gapId = gapId; }
    public int getLikeCount() { return likeCount; }
    public void setLikeCount(int likeCount) { this.likeCount = likeCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
