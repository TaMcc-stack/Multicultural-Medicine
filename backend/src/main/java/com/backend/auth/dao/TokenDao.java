package com.backend.auth.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 登录令牌 DAO（JdbcTemplate 实现，与 UserDao 保持一致的轻量风格）。
 * token 持久化到 auth_token 表，后端重启后登录态不丢失。
 */
@Repository
public class TokenDao {

    /** token 行：所属用户ID + 过期时间（毫秒时间戳） */
    public record TokenRow(Long userId, long expiresAtMillis) {}

    private final JdbcTemplate jdbcTemplate;

    public TokenDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 签发时持久化一条 token 记录 */
    public void insert(String token, Long userId, Timestamp expiresAt) {
        jdbcTemplate.update(
                "INSERT INTO auth_token (token, user_id, created_at, expires_at) VALUES (?, ?, CURRENT_TIMESTAMP, ?)",
                token, userId, expiresAt);
    }

    /** 查询 token 记录（含过期时间）；不存在返回 empty */
    public Optional<TokenRow> findByToken(String token) {
        List<TokenRow> list = jdbcTemplate.query(
                "SELECT user_id, expires_at FROM auth_token WHERE token = ?",
                (rs, rowNum) -> {
                    Timestamp ts = rs.getTimestamp("expires_at");
                    long millis = ts == null ? 0L : ts.getTime();
                    return new TokenRow(rs.getLong("user_id"), millis);
                },
                token);
        return list.stream().findFirst();
    }

    /** 删除 token（注销 / 过期清理） */
    public void delete(String token) {
        jdbcTemplate.update("DELETE FROM auth_token WHERE token = ?", token);
    }
}
