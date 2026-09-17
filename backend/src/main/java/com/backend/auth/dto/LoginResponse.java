package com.backend.auth.dto;

/**
 * 登录成功返回：token + 用户基本信息
 */
public class LoginResponse {

    private final String token;
    private final UserInfo user;

    public LoginResponse(String token, UserInfo user) {
        this.token = token;
        this.user = user;
    }

    public String getToken() {
        return token;
    }

    public UserInfo getUser() {
        return user;
    }
}
