package com.backend.auth.dto;

/**
 * 对外暴露的用户信息（不含密码）
 */
public class UserInfo {

    private final Long id;
    private final String username;
    private final String nickname;
    /** 头像配色 key（sage / clay / amber / ...），前端据此渲染「昵称首字 + 渐变底」 */
    private final String avatar;
    /** 个人简介 */
    private final String bio;

    public UserInfo(Long id, String username, String nickname, String avatar, String bio) {
        this.id = id;
        this.username = username;
        this.nickname = nickname;
        this.avatar = avatar;
        this.bio = bio;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getNickname() {
        return nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public String getBio() {
        return bio;
    }
}
