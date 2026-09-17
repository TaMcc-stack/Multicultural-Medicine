package com.backend.auth.dto;

/**
 * 更新个人资料请求。
 * 只含可改的三项——用户名是登录凭据、密码有专门的流程，都不在此接口的范围内。
 */
public class UpdateProfileRequest {

    private String nickname;
    /** 头像配色 key（sage / clay / amber / ...），非法值会被服务端兜底成默认值 */
    private String avatar;
    private String bio;

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
}
