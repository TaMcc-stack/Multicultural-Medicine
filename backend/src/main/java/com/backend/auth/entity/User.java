package com.backend.auth.entity;

import java.time.LocalDateTime;

/**
 * 用户实体（对应表 user）
 */
public class User {

    private Long id;
    private String username;
    /** BCrypt 加密后的密码，不对外返回 */
    private String password;
    private String nickname;
    /** 头像配色 key（sage / clay / amber / ...），前端据此渲染「昵称首字 + 渐变底」 */
    private String avatar;
    /** 个人简介 */
    private String bio;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
