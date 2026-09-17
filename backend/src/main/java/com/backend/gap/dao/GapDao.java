package com.backend.gap.dao;

import com.backend.gap.entity.KnowledgeGap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 知识缺口的读写。
 *
 * <p><b>计数一律用 SQL 原子语句，不要「先查再改」</b>：被问次数是并发最高的写（每次未命中
 * 都写），两个用户同时提问时先查再改会双双读到同一个旧值，其中一次的提问被静默吞掉。
 * 已实测 H2（MySQL 模式）支持 {@code ON DUPLICATE KEY UPDATE}，一条语句即可完成
 * 「不存在则建、存在则 +1」。</p>
 */
@Repository
public class GapDao {

    /**
     * 列清单抽成常量，理由同 SearchHistoryDao.COLS：分散在多处 SELECT 里的话，
     * 加一列就要改好几个地方，漏掉一处只在特定接口上表现为「字段莫名是 null」。
     *
     * <p>表起了别名 {@code g} 并给每列加了前缀，是为了让 {@link #listByUserFeedback} 那条
     * join 能直接复用这一份清单——不加前缀的话 {@code id} 在两张表之间是有歧义的。
     * 只查一张表的那些查询不受影响：不加前缀的列名在单表里照样解析得到。</p>
     */
    private static final String COLS =
            "SELECT g.id, g.ethnicity, g.disease, g.intent, g.ask_count, g.feedback_count, g.status, "
                    + "g.filled_doc_id, g.from_search, g.from_chat, g.original_query, "
                    + "g.created_at, g.last_asked_at FROM knowledge_gap g ";

    private static final RowMapper<KnowledgeGap> ROW_MAPPER = (rs, rowNum) -> {
        KnowledgeGap g = new KnowledgeGap();
        g.setId(rs.getLong("id"));
        g.setEthnicity(rs.getString("ethnicity"));
        g.setDisease(rs.getString("disease"));
        g.setIntent(rs.getString("intent"));
        g.setAskCount(rs.getInt("ask_count"));
        g.setFeedbackCount(rs.getInt("feedback_count"));
        g.setStatus(rs.getString("status"));
        g.setFilledDocId(rs.getString("filled_doc_id"));
        // TINYINT 取成 int 再判，比 getBoolean 稳：两种数据库对 0/1 → boolean 的映射规则不完全一致
        g.setFromSearch(rs.getInt("from_search") == 1);
        g.setFromChat(rs.getInt("from_chat") == 1);
        g.setOriginalQuery(rs.getString("original_query"));
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) g.setCreatedAt(created.toLocalDateTime());
        Timestamp asked = rs.getTimestamp("last_asked_at");
        if (asked != null) g.setLastAskedAt(asked.toLocalDateTime());
        return g;
    };

    private final JdbcTemplate jdbcTemplate;

    public GapDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记一次未命中：同一组合全库只有一行，重复出现是次数 +1 而不是新增一行。
     *
     * @param origin  哪个入口记的这一笔。它决定把哪一列来源标记置 1——**标记只增不减**，
     *                所以同一组合先被检索记过、再被对话反馈过之后，两列都会是 1。
     * @param query   原始提问；只有对话 / 留言板来源才有，高级检索传 null。
     *                UPDATE 分支里用 COALESCE 保留**第一句**原话——后面再被问到同一组合时
     *                不该把管理员已经在看的那句话换掉，否则榜单上的文案会随每次提问跳动。
     */
    public void touchAsk(String ethnicity, String disease, String intent, GapOrigin origin, String query) {
        int fromSearch = origin == GapOrigin.SEARCH ? 1 : 0;
        int fromChat = origin == GapOrigin.CHAT ? 1 : 0;
        jdbcTemplate.update(
                "INSERT INTO knowledge_gap "
                        + "(ethnicity, disease, intent, ask_count, from_search, from_chat, original_query, last_asked_at) "
                        + "VALUES (?, ?, ?, 1, ?, ?, ?, CURRENT_TIMESTAMP) "
                        // 不用 VALUES(col)：MySQL 8.0.20 起已废弃它，H2 的支持也不完全一致，
                        // 直接把参数再传一遍最稳（CASE WHEN 里那两组问号）
                        + "ON DUPLICATE KEY UPDATE "
                        + "ask_count = ask_count + 1, "
                        + "from_search = CASE WHEN ? = 1 THEN 1 ELSE from_search END, "
                        + "from_chat = CASE WHEN ? = 1 THEN 1 ELSE from_chat END, "
                        + "original_query = COALESCE(original_query, ?), "
                        + "last_asked_at = CURRENT_TIMESTAMP",
                ethnicity, disease, intent, fromSearch, fromChat, query,
                fromSearch, fromChat, query);
    }

    public Optional<KnowledgeGap> findBySlots(String ethnicity, String disease, String intent) {
        List<KnowledgeGap> list = jdbcTemplate.query(
                COLS + "WHERE ethnicity = ? AND disease = ? AND intent = ?", ROW_MAPPER,
                ethnicity, disease, intent);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<KnowledgeGap> findById(Long id) {
        List<KnowledgeGap> list = jdbcTemplate.query(COLS + "WHERE id = ?", ROW_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 记一条反馈。
     *
     * @return true = 这次确实新增了；false = 该用户此前已反馈过（唯一索引挡住）。
     *         靠捕获重复键而不是先查一次：先查再插在并发下会同时通过检查、其中一个抛异常。
     */
    public boolean addFeedback(Long gapId, Long userId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO knowledge_gap_feedback (gap_id, user_id) VALUES (?, ?)", gapId, userId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 人数以明细表为准重算——比在 knowledge_gap 上自增更耐错（任何一处漏掉都能自愈） */
    public void refreshFeedbackCount(Long gapId) {
        jdbcTemplate.update(
                "UPDATE knowledge_gap SET feedback_count = "
                        + "(SELECT COUNT(*) FROM knowledge_gap_feedback WHERE gap_id = ?) WHERE id = ?",
                gapId, gapId);
    }

    /** 热门榜：按被问次数倒序 */
    public List<KnowledgeGap> listByAsk(int limit) {
        return jdbcTemplate.query(
                COLS + "ORDER BY ask_count DESC, last_asked_at DESC LIMIT ?", ROW_MAPPER, limit);
    }

    /** 未解决榜：只列待补充的，按反馈人数倒序 */
    public List<KnowledgeGap> listOpenByFeedback(int limit) {
        return jdbcTemplate.query(
                COLS + "WHERE status = 'open' ORDER BY feedback_count DESC, ask_count DESC LIMIT ?",
                ROW_MAPPER, limit);
    }

    /**
     * 某个用户反馈过（点过「我需要这个」或对话里点过【反馈此问题】）的缺口。
     *
     * <p>给对话页回显「已反馈」用。**按槽位而不是按回合**：服务端本来就按
     * (缺口, 用户) 去重，同一组合再点一次是空操作，所以同一组合的所有回合一律显示已反馈，
     * 反而避免了「点了没反应」的困惑。</p>
     *
     * <p>不加 LIMIT：一个人反馈过的组合数量天然有限（要回答问题就得先有知识缺口），
     * 而且前端要拿它做全量比对，截断了就会漏判。</p>
     */
    public List<KnowledgeGap> listByUserFeedback(Long userId) {
        return jdbcTemplate.query(
                COLS + "JOIN knowledge_gap_feedback f ON f.gap_id = g.id "
                        + "WHERE f.user_id = ? ORDER BY f.id DESC",
                ROW_MAPPER, userId);
    }

    /**
     * 已解决历史。
     *
     * <p>两类都算：`filled`（补录入库、审核通过结掉的）与 `resolved`（管理员人工标记的）。
     * 按最后一次被问时间倒序——这张表没有「解决时间」列，而最近还在被问的通常最值得回看。</p>
     */
    public List<KnowledgeGap> listResolved(int limit) {
        return jdbcTemplate.query(
                COLS + "WHERE status <> 'open' ORDER BY last_asked_at DESC LIMIT ?",
                ROW_MAPPER, limit);
    }

    /** 人工标记已解决（不是靠补录入库，由管理员判定「不用补了」） */
    public void markResolved(Long gapId) {        jdbcTemplate.update("UPDATE knowledge_gap SET status = 'resolved' WHERE id = ?", gapId);
    }

    public void markFilled(Long gapId, String docId) {
        jdbcTemplate.update(
                "UPDATE knowledge_gap SET status = 'filled', filled_doc_id = ? WHERE id = ?", docId, gapId);
    }

    /** 补录的那份文档被删掉时把缺口退回待补充——否则榜上会留一条"已补充"的假记录 */
    public void reopen(Long gapId) {
        jdbcTemplate.update(
                "UPDATE knowledge_gap SET status = 'open', filled_doc_id = NULL WHERE id = ?", gapId);
    }
}
