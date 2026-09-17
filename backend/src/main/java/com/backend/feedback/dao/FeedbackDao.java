package com.backend.feedback.dao;

import com.backend.feedback.entity.UserFeedback;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 用户反馈留言板的读写。
 *
 * <p>点赞计数一律**以明细表为准重算**，不在这一列上自增：见 {@link #refreshLikeCount}。</p>
 */
@Repository
public class FeedbackDao {

    private static final String COLS =
            "SELECT id, user_id, content, status, gap_id, like_count, created_at FROM user_feedback ";

    private static final RowMapper<UserFeedback> ROW_MAPPER = (rs, rowNum) -> {
        UserFeedback f = new UserFeedback();
        f.setId(rs.getLong("id"));
        // user_id 可空（匿名发布）。getLong 对 NULL 返回 0，必须靠 wasNull 区分
        long uid = rs.getLong("user_id");
        f.setUserId(rs.wasNull() ? null : uid);
        f.setContent(rs.getString("content"));
        f.setStatus(rs.getString("status"));
        long gid = rs.getLong("gap_id");
        f.setGapId(rs.wasNull() ? null : gid);
        f.setLikeCount(rs.getInt("like_count"));
        Timestamp ca = rs.getTimestamp("created_at");
        f.setCreatedAt(ca == null ? null : ca.toLocalDateTime());
        return f;
    };

    private final JdbcTemplate jdbcTemplate;

    public FeedbackDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 发布一条反馈。
     *
     * @param userId 发布者；**传 null 表示匿名发布**（未登录）。这里用 setNull 而不是 setObject：
     *               setObject(null) 的类型是未知的，H2 在 MODE=MySQL 下会拒。
     */
    public UserFeedback insert(Long userId, String content) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO user_feedback (user_id, content, status, like_count, created_at) "
                            + "VALUES (?, ?, 'pending', 0, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            if (userId == null) {
                ps.setNull(1, Types.BIGINT);
            } else {
                ps.setLong(1, userId);
            }
            ps.setString(2, content);
            return ps;
        }, keyHolder);
        return findById(extractGeneratedKey(keyHolder))
                .orElseThrow(() -> new IllegalStateException("发布反馈后回读失败"));
    }

    public Optional<UserFeedback> findById(Long id) {
        return jdbcTemplate.query(COLS + "WHERE id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    /**
     * 留言板列表：按点赞数倒序。
     *
     * <p>第二排序键 {@code id DESC} 不是可有可无的：所有新帖都是 0 赞，只按 like_count 排的话
     * 它们的先后由数据库决定，同一份数据两次查询可能给出不同顺序；加上 id 之后，
     * 同为 0 赞时**新的在前**——「刚发布就靠前」这件事因此不需要特殊处理。</p>
     */
    public List<UserFeedback> list(int limit) {
        return jdbcTemplate.query(
                COLS + "ORDER BY like_count DESC, id DESC LIMIT ?", ROW_MAPPER, limit);
    }

    // ---------- 点赞 ----------

    /**
     * 点一次赞。
     *
     * @return true = 这次确实新增了；false = 该用户此前已点过（唯一索引挡住）。
     *         靠捕获重复键而不是先查一次：先查再插在并发下会双双通过检查，其中一个抛异常。
     */
    public boolean addLike(Long feedbackId, Long userId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO user_feedback_like (feedback_id, user_id) VALUES (?, ?)",
                    feedbackId, userId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 取消点赞。行不存在也算成功（不校验影响行数），与收藏、缺口反馈一致。 */
    public void removeLike(Long feedbackId, Long userId) {
        jdbcTemplate.update(
                "DELETE FROM user_feedback_like WHERE feedback_id = ? AND user_id = ?",
                feedbackId, userId);
    }

    /**
     * 按明细表重算点赞数。
     *
     * <p>不用 {@code like_count = like_count + 1}：那样任何一处漏掉（半路失败、并发、以后新增
     * 一条写路径）都会让计数永久偏掉，而且没有自愈的机会。重算的代价只是一条带索引的 COUNT。</p>
     */
    public void refreshLikeCount(Long feedbackId) {
        jdbcTemplate.update(
                "UPDATE user_feedback SET like_count = "
                        + "(SELECT COUNT(*) FROM user_feedback_like WHERE feedback_id = ?) WHERE id = ?",
                feedbackId, feedbackId);
    }

    /** 当前用户在给定这批反馈里点过赞的那些（列表页回显「已点赞」） */
    public Set<Long> likedIdsOf(Long userId, List<Long> feedbackIds) {
        if (userId == null || feedbackIds == null || feedbackIds.isEmpty()) {
            return Collections.emptySet();
        }
        String placeholders = String.join(",", Collections.nCopies(feedbackIds.size(), "?"));
        List<Object> args = new ArrayList<>(feedbackIds.size() + 1);
        args.add(userId);
        args.addAll(feedbackIds);
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT feedback_id FROM user_feedback_like WHERE user_id = ? AND feedback_id IN ("
                        + placeholders + ")",
                Long.class, args.toArray()));
    }

    // ---------- 管理员操作 ----------

    public void updateStatus(Long id, String status) {
        jdbcTemplate.update("UPDATE user_feedback SET status = ? WHERE id = ?", status, id);
    }

    /** 记下这条反馈转成了哪条缺口（供后台回显与跳转） */
    public void setGapId(Long id, Long gapId) {
        jdbcTemplate.update("UPDATE user_feedback SET gap_id = ? WHERE id = ?", gapId, id);
    }

    // ---------- 删除 ----------

    /**
     * 删掉这条反馈的点赞明细。
     *
     * <p>与「收藏」「缺口反馈」不同，这里**要连明细一起清**：那两处留着孤儿行是因为父行还有
     * 复活的可能（软删的动态、可能被补录的缺口），而反馈是**硬删**，父行都没了，
     * 明细行就只是永远查不到也解释不了的垃圾。</p>
     */
    public void deleteLikes(Long feedbackId) {
        jdbcTemplate.update("DELETE FROM user_feedback_like WHERE feedback_id = ?", feedbackId);
    }

    /** 硬删一条反馈。权限判定在 Service 层（要看是不是本人或管理员），这里只认 id。 */
    public void delete(Long id) {
        jdbcTemplate.update("DELETE FROM user_feedback WHERE id = ?", id);
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
