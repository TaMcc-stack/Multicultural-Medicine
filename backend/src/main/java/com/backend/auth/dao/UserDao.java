package com.backend.auth.dao;

import com.backend.auth.entity.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 用户 DAO（JdbcTemplate 实现，POC 阶段不引入 ORM）
 */
@Repository
public class UserDao {

    private final JdbcTemplate jdbcTemplate;

    private static final String COLS = "id, username, password, nickname, avatar, bio, created_at";

    private static final RowMapper<User> USER_ROW_MAPPER = (rs, rowNum) -> {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setPassword(rs.getString("password"));
        u.setNickname(rs.getString("nickname"));
        u.setAvatar(rs.getString("avatar"));
        u.setBio(rs.getString("bio"));
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        u.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return u;
    };

    public UserDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<User> findByUsername(String username) {
        List<User> list = jdbcTemplate.query(
                "SELECT " + COLS + " FROM app_user WHERE username = ?",
                USER_ROW_MAPPER, username);
        return list.stream().findFirst();
    }

    public Optional<User> findById(Long id) {
        List<User> list = jdbcTemplate.query(
                "SELECT " + COLS + " FROM app_user WHERE id = ?",
                USER_ROW_MAPPER, id);
        return list.stream().findFirst();
    }

    /** 更新个人资料（昵称 / 简介 / 头像配色）。只更新这三列，不碰用户名与密码。 */
    public void updateProfile(Long id, String nickname, String bio, String avatar) {
        jdbcTemplate.update(
                "UPDATE app_user SET nickname = ?, bio = ?, avatar = ? WHERE id = ?",
                nickname, bio, avatar, id);
    }

    /** 只更新头像列（上传自定义头像时用，避免顺带覆盖昵称/简介） */
    public void updateAvatar(Long id, String avatar) {
        jdbcTemplate.update("UPDATE app_user SET avatar = ? WHERE id = ?", avatar, id);
    }

    public User insert(User user) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_user (username, password, nickname) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getPassword());
            ps.setString(3, user.getNickname());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKeyList().isEmpty() ? null : (Number) keyHolder.getKeyList().get(0).get("id");
        if (key == null && !keyHolder.getKeyList().isEmpty()) {
            // 兜底：部分驱动返回的键列名可能为 ID（大写）
            Object v = keyHolder.getKeyList().get(0).values().iterator().next();
            if (v instanceof Number n) {
                key = n;
            }
        }
        if (key != null) {
            user.setId(key.longValue());
        }
        return user;
    }
}
