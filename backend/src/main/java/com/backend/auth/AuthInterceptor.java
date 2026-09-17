package com.backend.auth;

import com.backend.common.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;

/**
 * 登录鉴权拦截器：
 * - /api/auth/login、/api/auth/register、/api/health 为公开接口（见 WebConfig.excludePathPatterns）
 * - 其余 /api/** 需要 Authorization: Bearer &lt;token&gt;
 * - **可选登录路径**（{@code app.auth.optional-paths}）例外：带了有效 token 就照常注入 userId，
 *   没带或已失效则放行且不注入，当作匿名
 *
 * <p>「可选登录」与 {@code excludePathPatterns} 的区别是这一条存在的理由：被 exclude 的路径
 * **根本不进拦截器**，token 不会被人解析，于是 {@code @RequestAttribute(ATTR_USER_ID)} 永远是
 * null——对留言板这种「匿名可用、登录则更好」的接口，那等于把已登录用户也降级成匿名
 * （发的帖显示不出昵称头像，列表也回显不了「我点没点过赞」）。可选登录是「解析但不强制」。</p>
 *
 * <p>失效 token 在可选路径上按匿名处理而不是 401：公开页面上因为 token 过期就被弹回登录页
 * 很恼人，而这条路径本来就有匿名能力，降级是合理的行为。</p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final TokenManager tokenManager;
    /** 可选登录的路径模式（逗号分隔的 Ant 模式），配置项为空则没有可选路径 */
    private final List<String> optionalPaths;

    public AuthInterceptor(TokenManager tokenManager,
                           @Value("${app.auth.optional-paths:}") String optionalPaths) {
        this.tokenManager = tokenManager;
        this.optionalPaths = split(optionalPaths);
    }

    /** 解析 "a,b" 形式的模式列表；空白项直接丢掉，配置写错不该阻断启动 */
    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private boolean isOptional(String path) {
        for (String pattern : optionalPaths) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = AuthController.extractToken(request.getHeader("Authorization"));
        Long userId = tokenManager.validate(token).orElse(null);
        if (userId != null) {
            request.setAttribute(AuthController.ATTR_USER_ID, userId);
            return true;
        }
        // 没登录（或 token 失效）：可选路径放行当匿名，其余一律 401
        if (isOptional(request.getRequestURI())) {
            return true;
        }
        throw new GlobalExceptionHandler.UnauthorizedException("未登录或登录状态已失效，请重新登录");
    }
}
