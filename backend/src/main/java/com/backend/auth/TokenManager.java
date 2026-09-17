package com.backend.auth;

import com.backend.auth.dao.TokenDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * Token 管理：token 持久化到 auth_token 表（H2 / MySQL 通用），
 * 后端重启后登录态不丢失；生产亦可平滑替换为 Redis。
 * token 为随机 UUID（去连字符），过期时间默认 7 天；过期 token 惰性清理。
 */
@Component
public class TokenManager {

    private final TokenDao tokenDao;
    private final long expireMillis;

    public TokenManager(TokenDao tokenDao,
                        @Value("${app.auth.token-expire-hours:168}") long expireHours) {
        this.tokenDao = tokenDao;
        this.expireMillis = expireHours * 3600_000L;
    }

    /** 为用户签发新 token（持久化入库） */
    public String issue(Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokenDao.insert(token, userId, new Timestamp(System.currentTimeMillis() + expireMillis));
        return token;
    }

    /** 校验 token，返回用户ID；无效或过期返回 empty */
    public Optional<Long> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<TokenDao.TokenRow> row = tokenDao.findByToken(token);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() > row.get().expiresAtMillis()) {
            tokenDao.delete(token);
            return Optional.empty();
        }
        return Optional.of(row.get().userId());
    }

    /** 注销 token（删除记录） */
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            tokenDao.delete(token);
        }
    }
}
