package com.backend.chat.entity;

import java.time.LocalDateTime;

/**
 * 会话消息实体。
 * role：user / assistant；
 * kind：text（用户问题）/ clarify / answer / notice（对应 AskResponse.type）
 * detailJson：assistant 消息的完整响应 JSON，用于历史回放渲染；用户消息为 null
 */
public class ChatMessage {

    private Long id;
    private Long conversationId;
    private String role;
    private String kind;
    private String content;
    private String detailJson;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
