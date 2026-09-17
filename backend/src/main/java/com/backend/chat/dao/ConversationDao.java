package com.backend.chat.dao;

import com.backend.chat.entity.ChatMessage;
import com.backend.chat.entity.Conversation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 会话 / 消息 DAO（JdbcTemplate 实现，与 UserDao 保持一致的轻量风格）
 */
@Repository
public class ConversationDao {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Conversation> CONV_ROW_MAPPER = (rs, rowNum) -> {
        Conversation c = new Conversation();
        c.setId(rs.getLong("id"));
        c.setUserId(rs.getLong("user_id"));
        c.setTitle(rs.getString("title"));
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        c.setCreatedAt(ca == null ? null : ca.toLocalDateTime());
        c.setUpdatedAt(ua == null ? null : ua.toLocalDateTime());
        return c;
    };

    private static final RowMapper<ChatMessage> MSG_ROW_MAPPER = (rs, rowNum) -> {
        ChatMessage m = new ChatMessage();
        m.setId(rs.getLong("id"));
        m.setConversationId(rs.getLong("conversation_id"));
        m.setRole(rs.getString("role"));
        m.setKind(rs.getString("kind"));
        m.setContent(rs.getString("content"));
        m.setDetailJson(rs.getString("detail_json"));
        Timestamp ts = rs.getTimestamp("created_at");
        m.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return m;
    };

    public ConversationDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Conversation insertConversation(Long userId, String title) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO conversation (user_id, title, created_at, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, title);
            return ps;
        }, keyHolder);
        Long id = extractGeneratedKey(keyHolder);
        return findConversationById(id).orElseThrow();
    }

    public Optional<Conversation> findConversationById(Long id) {
        List<Conversation> list = jdbcTemplate.query(
                "SELECT id, user_id, title, created_at, updated_at FROM conversation WHERE id = ?",
                CONV_ROW_MAPPER, id);
        return list.stream().findFirst();
    }

    /** 用户自己的会话列表，按最后活跃时间倒序 */
    public List<Conversation> listByUser(Long userId) {
        return jdbcTemplate.query(
                "SELECT id, user_id, title, created_at, updated_at FROM conversation WHERE user_id = ? ORDER BY updated_at DESC, id DESC",
                CONV_ROW_MAPPER, userId);
    }

    public void rename(Long id, String title) {
        jdbcTemplate.update(
                "UPDATE conversation SET title = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", title, id);
    }

    /** 会话活跃时间刷新（追加新消息后调用） */
    public void touch(Long id) {
        jdbcTemplate.update("UPDATE conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public void deleteConversation(Long id) {
        jdbcTemplate.update("DELETE FROM chat_message WHERE conversation_id = ?", id);
        jdbcTemplate.update("DELETE FROM conversation WHERE id = ?", id);
    }

    /** 会话内消息数（列表页展示用） */
    public int countMessages(Long conversationId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE conversation_id = ?", Integer.class, conversationId);
        return n == null ? 0 : n;
    }

    // ---------- 消息 ----------

    public ChatMessage insertMessage(Long conversationId, String role, String kind, String content, String detailJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO chat_message (conversation_id, role, kind, content, detail_json, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, conversationId);
            ps.setString(2, role);
            ps.setString(3, kind);
            ps.setString(4, content);
            ps.setString(5, detailJson);
            return ps;
        }, keyHolder);
        Long id = extractGeneratedKey(keyHolder);
        return findMessageById(id).orElseThrow();
    }

    public Optional<ChatMessage> findMessageById(Long id) {
        List<ChatMessage> list = jdbcTemplate.query(
                "SELECT id, conversation_id, role, kind, content, detail_json, created_at FROM chat_message WHERE id = ?",
                MSG_ROW_MAPPER, id);
        return list.stream().findFirst();
    }

    public List<ChatMessage> listMessages(Long conversationId) {
        return jdbcTemplate.query(
                "SELECT id, conversation_id, role, kind, content, detail_json, created_at FROM chat_message "
                        + "WHERE conversation_id = ? ORDER BY id ASC",
                MSG_ROW_MAPPER, conversationId);
    }

    // ---------- 工具 ----------

    private Long extractGeneratedKey(KeyHolder keyHolder) {
        if (!keyHolder.getKeyList().isEmpty()) {
            Object v = keyHolder.getKeyList().get(0).get("id");
            if (v == null && !keyHolder.getKeyList().get(0).isEmpty()) {
                // 兜底：部分驱动返回的键列名可能为 ID（大写）
                v = keyHolder.getKeyList().get(0).values().iterator().next();
            }
            if (v instanceof Number n) {
                return n.longValue();
            }
        }
        throw new IllegalStateException("无法获取生成的主键");
    }
}
