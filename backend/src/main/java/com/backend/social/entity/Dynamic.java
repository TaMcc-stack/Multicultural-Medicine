package com.backend.social.entity;

import java.time.LocalDateTime;

/**
 * 动态 / 分享实体（表 user_dynamic）。
 * 一条动态由「某次会话中的某条 AI 回答」转存而来，属于发布它的用户，对所有登录用户公开可见。
 */
public class Dynamic {

    private Long id;
    private Long userId;
    private Long sourceConversationId;
    private Long sourceMessageId;
    private String title;
    private String content;
    private String detailJson;
    /** 动态类型：{@code dialogue} 多轮对话分享 / {@code qa} 一问一答卡片 */
    private String kind;
    /** 问答卡的规范化键「民族|疾病|意图」（全局唯一）；对话类为 null */
    private String qaKey;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSourceConversationId() {
        return sourceConversationId;
    }

    public void setSourceConversationId(Long sourceConversationId) {
        this.sourceConversationId = sourceConversationId;
    }

    public Long getSourceMessageId() {
        return sourceMessageId;
    }

    public void setSourceMessageId(Long sourceMessageId) {
        this.sourceMessageId = sourceMessageId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public void setDetailJson(String detailJson) {
        this.detailJson = detailJson;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getQaKey() {
        return qaKey;
    }

    public void setQaKey(String qaKey) {
        this.qaKey = qaKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
