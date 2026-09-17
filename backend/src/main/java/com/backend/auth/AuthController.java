package com.backend.auth;

import com.backend.auth.dto.LoginRequest;
import com.backend.auth.dto.LoginResponse;
import com.backend.auth.dto.RegisterRequest;
import com.backend.auth.dto.UpdateProfileRequest;
import com.backend.auth.dto.UserInfo;
import com.backend.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 登录 / 注册 / 退出 / 当前用户
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 请求属性：当前登录用户ID（由 AuthInterceptor 注入） */
    public static final String ATTR_USER_ID = "currentUserId";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(extractToken(authorization));
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<UserInfo> me(@RequestAttribute(ATTR_USER_ID) Long userId) {
        return ApiResponse.ok(authService.getUserInfo(userId));
    }

    /** 更新个人资料（昵称 / 简介 / 头像配色），返回更新后的用户信息 */
    @PutMapping("/me")
    public ApiResponse<UserInfo> updateMe(
            @RequestAttribute(ATTR_USER_ID) Long userId,
            @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(authService.updateProfile(userId, request));
    }

    /** 上传自定义头像（multipart），成功后 avatar 变为 upload:{版本号} */
    @PostMapping("/avatar")
    public ApiResponse<UserInfo> uploadAvatar(
            @RequestAttribute(ATTR_USER_ID) Long userId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(authService.saveAvatar(userId, file));
    }

    /**
     * 读取自定义头像。
     *
     * <p><b>这个接口在鉴权拦截器里被放行</b>（见 WebConfig，只放行带 id 的 GET，不含上传）——
     * 因为 {@code <img src>} 无法携带 Authorization 头。头像本身不是敏感信息，
     * 且 URL 里只有用户 ID，不暴露任何账号数据。</p>
     */
    @GetMapping("/avatar/{userId}")
    public ResponseEntity<byte[]> avatar(@PathVariable("userId") Long userId) {
        return authService.loadAvatar(userId)
                .map(a -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, a.contentType())
                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                        .body(a.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 解析 Authorization: Bearer xxx */
    static String extractToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        if (authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        return authorization.trim();
    }
}
