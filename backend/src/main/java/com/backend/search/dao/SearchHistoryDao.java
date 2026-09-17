package com.backend.search.dao;

import com.backend.search.entity.SearchHistory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 高级检索历史 DAO，JdbcTemplate 实现，与 SocialDao / ConversationDao 风格一致。
 */
@Repository
public class SearchHistoryDao {

    /**
     * 列清单抽成常量，理由同 SocialDao.DYNAMIC_COLS：分散在多处 SELECT 里的话，
     * 加一列就要改好几个地方，漏掉一处只在特定接口上表现为「字段莫名是 null」。
     */
    private static final String COLS =
            "SELECT id, user_id, ethnicity, disease, intent, detail_json, created_at, updated_at "
                    + "FROM user_search_history ";

    private static final RowMapper<SearchHistory> ROW_MAPPER = (rs, rowNum) -> {
        SearchHistory h = new SearchHistory();
        h.setId(rs.getLong("id"));
        h.setUserId(rs.getLong("user_id"));
        h.setEthnicity(rs.getString("ethnicity"));
        h.setDisease(rs.getString("disease"));
        h.setIntent(rs.getString("intent"));
        h.setDetailJson(rs.getString("detail_json"));
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        h.setCreatedAt(ca == null ? null : ca.toLocalDateTime());
        h.setUpdatedAt(ua == null ? null : ua.toLocalDateTime());
        return h;
    };

    private final JdbcTemplate jdbcTemplate;

    public SearchHistoryDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按「用户 + 三个槽位」找那条记录。
     *
     * <p>唯一约束 {@code uk_search_user_slots} 正是建在这四列上，所以最多命中一条，
     * 这里不必也不能像动态那样查「含已删除」——检索历史没有软删除，删除就是真删。</p>
     */
    public Optional<SearchHistory> findBySlots(Long userId, String ethnicity, String disease, String intent) {
        return jdbcTemplate.query(
                COLS + "WHERE user_id = ? AND ethnicity = ? AND disease = ? AND intent = ?",
                ROW_MAPPER, userId, ethnicity, disease, intent).stream().findFirst();
    }

    public Optional<SearchHistory> findById(Long id) {
        return jdbcTemplate.query(COLS + "WHERE id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    /**
     * 我的检索历史，按最后一次检索时间倒序。
     *
     * <p>排序键用 {@code updated_at} 而不是 {@code created_at}：重复检索同一组合是刷新，
     * 语义上「刚点过的排最前」，与对话侧栏里刚聊过的会话排最前一致。</p>
     *
     * <p>二级键 {@code id DESC} 是为了让同一时刻（同一秒内连点）写入的记录顺序稳定——
     * 只按 timestamp 排时相等行的先后由数据库决定，翻页会看到顺序抖动。</p>
     */
    public List<SearchHistory> listByUser(Long userId, int limit) {
        return jdbcTemplate.query(
                COLS + "WHERE user_id = ? ORDER BY updated_at DESC, id DESC LIMIT ?",
                ROW_MAPPER, userId, limit);
    }

    public SearchHistory insert(Long userId, String ethnicity, String disease, String intent, String detailJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO user_search_history (user_id, ethnicity, disease, intent, detail_json, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, ethnicity);
            ps.setString(3, disease);
            ps.setString(4, intent);
            ps.setString(5, detailJson);
            return ps;
        }, keyHolder);
        return findById(extractGeneratedKey(keyHolder)).orElseThrow();
    }

    /** 刷新已存在记录的快照，并把 updated_at 顶到当前时间（列表据此重排） */
    public void updateSnapshot(Long id, String detailJson) {
        jdbcTemplate.update(
                "UPDATE user_search_history SET detail_json = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                detailJson, id);
    }

    /**
     * 删除一条，**带 user_id 条件**。
     *
     * <p>不先查归属再删，而是把 user_id 写进 WHERE：这样越权删除影响 0 行，
     * 不依赖调用方记得做校验，也少一次查询。返回值即实际删除的行数，供上层判断是否 404。</p>
     */
    public int delete(Long userId, Long id) {
        return jdbcTemplate.update(
                "DELETE FROM user_search_history WHERE id = ? AND user_id = ?", id, userId);
    }

    /**
     * 这条检索记录是否属于该用户。
     *
     * <p>收藏前要用它校验归属：`{id}` 是路径参数，不校验的话任何人都能把别人的记录 id
     * 收进自己的收藏夹——虽然列表组装时按 user_id 过滤后那条会取不到，但收藏关系已经落库了。</p>
     */
    public boolean existsForUser(Long userId, Long id) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_search_history WHERE id = ? AND user_id = ?",
                Integer.class, id, userId);
        return n != null && n > 0;
    }

    /** 按 id 集合取记录（只取属于该用户的），收藏列表用 */
    public List<SearchHistory> listByIds(Long userId, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        // 占位符按实际个数拼：IN 子句的项数只有运行时才知道，没法写死在常量里
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids.size() + 1);
        args.add(userId);
        args.addAll(ids);
        return jdbcTemplate.query(
                COLS + "WHERE user_id = ? AND id IN (" + placeholders + ")",
                ROW_MAPPER, args.toArray());
    }

    // ---------- 检索记录收藏（私有书签，形状与 SocialDao 的会话收藏一致） ----------

    public boolean existsFavorite(Long userId, Long searchHistoryId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_search_favorite WHERE user_id = ? AND search_history_id = ?",
                Integer.class, userId, searchHistoryId);
        return n != null && n > 0;
    }

    /** 收藏一条检索记录（幂等）。只写 user_search_favorite，不碰 user_search_history。 */
    public void addFavorite(Long userId, Long searchHistoryId) {
        if (existsFavorite(userId, searchHistoryId)) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO user_search_favorite (user_id, search_history_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                userId, searchHistoryId);
    }

    /** 取消收藏。行不存在也算成功——与 SocialDao 的会话收藏一致，不校验影响行数。 */
    public void removeFavorite(Long userId, Long searchHistoryId) {
        jdbcTemplate.update(
                "DELETE FROM user_search_favorite WHERE user_id = ? AND search_history_id = ?",
                userId, searchHistoryId);
    }

    /** 我收藏的检索记录ID，按收藏时间倒序（id 自增，故 order by id 即按收藏先后） */
    public List<Long> favoriteIdsOf(Long userId) {
        return jdbcTemplate.queryForList(
                "SELECT search_history_id FROM user_search_favorite WHERE user_id = ? ORDER BY id DESC",
                Long.class, userId);
    }

    // ---------- 工具 ----------

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
