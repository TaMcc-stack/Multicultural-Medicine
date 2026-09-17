package com.backend.social.dao;

import com.backend.social.entity.Dynamic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动态（user_dynamic）/ 收藏（user_favorite）DAO，JdbcTemplate 实现，与 UserDao / ConversationDao 风格一致。
 */
@Repository
public class SocialDao {

    public static final String STATUS_NORMAL = "normal";
    public static final String STATUS_DELETED = "deleted";

    /** 多轮对话分享（原有形态，也是历史数据的默认类型） */
    public static final String KIND_DIALOGUE = "dialogue";
    /** 一问一答卡片（高级检索页分享出来的单轮问答） */
    public static final String KIND_QA = "qa";

    /**
     * 动态的列清单。抽成常量是因为它原本在 5 处 SELECT 里各抄了一遍——
     * 加一列就要改 5 个地方，漏掉一处只在特定接口上表现为「字段莫名是 null」。
     */
    private static final String DYNAMIC_COLS =
            "SELECT id, user_id, source_conversation_id, source_message_id, title, content, detail_json, kind, qa_key, status, created_at, updated_at "
                    + "FROM user_dynamic ";

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Dynamic> DYNAMIC_ROW_MAPPER = (rs, rowNum) -> {
        Dynamic d = new Dynamic();
        d.setId(rs.getLong("id"));
        d.setUserId(rs.getLong("user_id"));
        long conv = rs.getLong("source_conversation_id");
        d.setSourceConversationId(rs.wasNull() ? null : conv);
        long msg = rs.getLong("source_message_id");
        d.setSourceMessageId(rs.wasNull() ? null : msg);
        d.setTitle(rs.getString("title"));
        d.setContent(rs.getString("content"));
        d.setDetailJson(rs.getString("detail_json"));
        String kind = rs.getString("kind");
        // 列是后加的，历史行的值取决于数据库是否按 DEFAULT 回填；两种情况都归一成 dialogue，
        // 免得前端拿到 null 还要自己兜底
        d.setKind(kind == null || kind.isBlank() ? KIND_DIALOGUE : kind);
        d.setQaKey(rs.getString("qa_key"));
        d.setStatus(rs.getString("status"));
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        d.setCreatedAt(ca == null ? null : ca.toLocalDateTime());
        d.setUpdatedAt(ua == null ? null : ua.toLocalDateTime());
        return d;
    };

    public SocialDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------- 动态 ----------

    /**
     * 该用户在这个会话下的动态，**含已软删除的**。
     *
     * <p>唯一约束 (user_id, source_conversation_id) 连软删除的行一起占位，所以重新发布
     * 同一会话时不能再插一条——要把之前那条「复活」（status 改回 normal）而不是插入。</p>
     */
    public java.util.Optional<Dynamic> findAnyByUserAndConversation(Long userId, Long conversationId) {
        return jdbcTemplate.query(
                DYNAMIC_COLS + "WHERE user_id = ? AND source_conversation_id = ? ORDER BY id LIMIT 1",
                DYNAMIC_ROW_MAPPER, userId, conversationId).stream().findFirst();
    }

    /**
     * 按规范化键找问答卡，**含已软删除的**。
     *
     * <p>与 {@link #findAnyByUserAndConversation} 同理：唯一约束 {@code uk_dynamic_qa} 连软删除的
     * 行一起占位，所以重复分享同一个「民族+疾病+意图」时不能再插一条，要把旧的那条复活。</p>
     *
     * <p>注意是**跨用户**查——问答卡的唯一性是全局的（内容来自同一套知识库，与谁分享无关），
     * 所以这里不能像其他查询那样带上 user_id。</p>
     */
    public java.util.Optional<Dynamic> findByQaKey(String qaKey) {
        return jdbcTemplate.query(
                DYNAMIC_COLS + "WHERE qa_key = ? ORDER BY id LIMIT 1",
                DYNAMIC_ROW_MAPPER, qaKey).stream().findFirst();
    }

    /** 刷新已发布动态的内容；顺带把 status 置回 normal（重新发布可复活被删的动态） */
    public void updateDynamic(Long id, String title, String content, String detailJson) {
        jdbcTemplate.update(
                "UPDATE user_dynamic SET title = ?, content = ?, detail_json = ?, status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                title, content, detailJson, STATUS_NORMAL, id);
    }

    public Dynamic insertDynamic(Long userId, Long sourceConversationId, Long sourceMessageId,
                                 String kind, String qaKey,
                                 String title, String content, String detailJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO user_dynamic (user_id, source_conversation_id, source_message_id, kind, qa_key, title, content, detail_json, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, userId);
            setNullableLong(ps, 2, sourceConversationId);
            setNullableLong(ps, 3, sourceMessageId);
            ps.setString(4, kind);
            ps.setString(5, qaKey);
            ps.setString(6, title);
            ps.setString(7, content);
            ps.setString(8, detailJson);
            ps.setString(9, STATUS_NORMAL);
            return ps;
        }, keyHolder);
        return findDynamicById(extractGeneratedKey(keyHolder)).orElseThrow();
    }

    public java.util.Optional<Dynamic> findDynamicById(Long id) {
        return jdbcTemplate.query(DYNAMIC_COLS + "WHERE id = ?",
                DYNAMIC_ROW_MAPPER, id).stream().findFirst();
    }

    /** 同一用户对同一条 AI 回答是否已添加过（用于幂等去重） */
    public java.util.Optional<Dynamic> findByUserAndMessage(Long userId, Long messageId) {
        return jdbcTemplate.query(
                DYNAMIC_COLS + "WHERE user_id = ? AND source_message_id = ? AND status = ?",
                DYNAMIC_ROW_MAPPER, userId, messageId, STATUS_NORMAL).stream().findFirst();
    }

    /**
     * 公开动态列表，游标分页。
     *
     * <p>用 {@code id < beforeId} 而不是 {@code OFFSET}：offset 分页在列表头部有新内容插入时
     * 会漏掉或重复条目，而且越翻越慢。id 是自增的，与发布顺序一致，直接拿它当游标。</p>
     *
     * @param beforeId 上一页最后一条的 id；null 表示取第一页
     * @param kind     只取该类动态（dialogue / qa）；null 表示两类都要。
     *                 用 {@code COALESCE} 兜住 kind 为 NULL 的历史行——它们都是对话分享
     */
    public List<Dynamic> listNormal(int limit, Long beforeId, String kind) {
        StringBuilder sql = new StringBuilder(DYNAMIC_COLS + "WHERE status = ? ");
        List<Object> args = new java.util.ArrayList<>();
        args.add(STATUS_NORMAL);
        if (kind != null && !kind.isBlank()) {
            sql.append("AND COALESCE(kind, ?) = ? ");
            args.add(KIND_DIALOGUE);
            args.add(kind);
        }
        if (beforeId != null) {
            sql.append("AND id < ? ");
            args.add(beforeId);
        }
        sql.append("ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), DYNAMIC_ROW_MAPPER, args.toArray());
    }

    /** 我的动态（按发布时间倒序） */
    public List<Dynamic> listByUser(Long userId, int limit) {
        return jdbcTemplate.query(
                DYNAMIC_COLS + "WHERE user_id = ? AND status = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                DYNAMIC_ROW_MAPPER, userId, STATUS_NORMAL, limit);
    }

    /** 软删除（保留数据，列表不再展示） */
    public void softDelete(Long id) {
        jdbcTemplate.update(
                "UPDATE user_dynamic SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                STATUS_DELETED, id);
    }

    /** 当前用户已添加为动态的来源消息ID（用于前端「已添加」状态） */
    public List<Long> addedMessageIds(Long userId, Long conversationId) {
        if (conversationId != null) {
            return jdbcTemplate.queryForList(
                    "SELECT source_message_id FROM user_dynamic WHERE user_id = ? AND status = ? AND source_conversation_id = ? "
                            + "AND source_message_id IS NOT NULL",
                    Long.class, userId, STATUS_NORMAL, conversationId);
        }
        return jdbcTemplate.queryForList(
                "SELECT source_message_id FROM user_dynamic WHERE user_id = ? AND status = ? AND source_message_id IS NOT NULL",
                Long.class, userId, STATUS_NORMAL);
    }

    // ---------- 收藏 ----------

    /** 收藏（唯一约束 + 先查后插，保证幂等：重复收藏不会产生第二条记录） */
    public void addFavorite(Long userId, Long dynamicId) {
        if (existsFavorite(userId, dynamicId)) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO user_favorite (user_id, dynamic_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                userId, dynamicId);
    }

    public void removeFavorite(Long userId, Long dynamicId) {
        jdbcTemplate.update("DELETE FROM user_favorite WHERE user_id = ? AND dynamic_id = ?", userId, dynamicId);
    }

    public boolean existsFavorite(Long userId, Long dynamicId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_favorite WHERE user_id = ? AND dynamic_id = ?",
                Integer.class, userId, dynamicId);
        return n != null && n > 0;
    }

    /** 当前用户收藏的动态ID（限定在给定集合内，用于列表页「已收藏」状态） */
    public Set<Long> favoriteIdsOf(Long userId, List<Long> dynamicIds) {
        if (dynamicIds == null || dynamicIds.isEmpty()) {
            return Collections.emptySet();
        }
        String ph = String.join(",", Collections.nCopies(dynamicIds.size(), "?"));
        List<Long> args = new java.util.ArrayList<>(dynamicIds);
        args.add(0, userId);
        List<Long> hit = jdbcTemplate.queryForList(
                "SELECT dynamic_id FROM user_favorite WHERE user_id = ? AND dynamic_id IN (" + ph + ")",
                Long.class, args.toArray());
        return new HashSet<>(hit);
    }

    /** 当前用户收藏的全部动态ID（个人主页「我的收藏」用） */
    public List<Long> favoriteIdsOf(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT dynamic_id FROM user_favorite WHERE user_id = ? ORDER BY id DESC", Long.class, userId);
    }

    // ---------- 会话收藏（私有书签，与上面的「动态收藏」是两回事） ----------

    /** 收藏一段对话——只写 user_conversation_favorite，**不碰 user_dynamic** */
    public void addConversationFavorite(Long userId, Long conversationId) {
        if (existsConversationFavorite(userId, conversationId)) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO user_conversation_favorite (user_id, conversation_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                userId, conversationId);
    }

    public void removeConversationFavorite(Long userId, Long conversationId) {
        jdbcTemplate.update(
                "DELETE FROM user_conversation_favorite WHERE user_id = ? AND conversation_id = ?",
                userId, conversationId);
    }

    public boolean existsConversationFavorite(Long userId, Long conversationId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_conversation_favorite WHERE user_id = ? AND conversation_id = ?",
                Integer.class, userId, conversationId);
        return n != null && n > 0;
    }

    /** 当前用户收藏的会话ID，按收藏时间倒序 */
    public List<Long> conversationFavoriteIdsOf(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT conversation_id FROM user_conversation_favorite WHERE user_id = ? ORDER BY id DESC",
                Long.class, userId);
    }

    /** 批量统计收藏数 */
    public Map<Long, Integer> countFavorites(List<Long> dynamicIds) {
        if (dynamicIds == null || dynamicIds.isEmpty()) {
            return Collections.emptyMap();
        }
        String ph = String.join(",", Collections.nCopies(dynamicIds.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT dynamic_id, COUNT(*) AS c FROM user_favorite WHERE dynamic_id IN (" + ph + ") GROUP BY dynamic_id",
                dynamicIds.toArray());
        Map<Long, Integer> out = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Object k = r.get("dynamic_id");
            Object v = r.get("c");
            if (k instanceof Number a && v instanceof Number b) {
                out.put(a.longValue(), b.intValue());
            }
        }
        return out;
    }

    // ---------- 工具 ----------

    private static void setNullableLong(PreparedStatement ps, int idx, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(idx, java.sql.Types.BIGINT);
        } else {
            ps.setLong(idx, value);
        }
    }

    private Long extractGeneratedKey(KeyHolder keyHolder) {
        if (!keyHolder.getKeyList().isEmpty()) {
            Object v = keyHolder.getKeyList().get(0).get("id");
            if (v == null && !keyHolder.getKeyList().get(0).isEmpty()) {
                v = keyHolder.getKeyList().get(0).values().iterator().next();
            }
            if (v instanceof Number n) {
                return n.longValue();
            }
        }
        throw new IllegalStateException("无法获取生成的主键");
    }
}
