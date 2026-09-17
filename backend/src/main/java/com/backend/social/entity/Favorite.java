package com.backend.social.entity;

import java.time.LocalDateTime;

/**
 * 收藏实体（表 user_favorite）：用户与动态的多对多关系，唯一约束保证不重复收藏。
 */
public class Favorite {

    private Long id;
    private Long userId;
    private Long dynamicId;
    private LocalDateTime createdAt;

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

    public Long getDynamicId() {
        return dynamicId;
    }

    public void setDynamicId(Long dynamicId) {
        this.dynamicId = dynamicId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
