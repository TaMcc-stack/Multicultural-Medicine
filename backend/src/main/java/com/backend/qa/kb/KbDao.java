package com.backend.qa.kb;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 知识库 DAO：论文 + 证据片段
 * POC 阶段用 JdbcTemplate 结构化筛选（对应 MySQL 筛选层），
 * 语义检索层（Chroma）后续接入，当前以关键词/主题匹配近似。
 */
@Repository
public class KbDao {

    private static final String PAPER_COLS =
            "id, title, ethnicity, disease, population, study_year, findings, limitation, source";

    private final JdbcTemplate jdbcTemplate;

    public KbDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static Paper mapPaper(ResultSet rs, int rowNum) throws SQLException {
        return new Paper(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("ethnicity"),
                rs.getString("disease"),
                rs.getString("population"),
                rs.getString("study_year"),
                rs.getString("findings"),
                rs.getString("limitation"),
                rs.getString("source"));
    }

    private static Evidence mapEvidence(ResultSet rs, int rowNum) throws SQLException {
        return new Evidence(
                rs.getLong("id"),
                rs.getLong("paper_id"),
                rs.getString("topic"),
                rs.getString("content"));
    }

    /** 按民族 + 疾病筛选论文（结构化条件检索） */
    public List<Paper> findPapers(String ethnicity, String disease) {
        return jdbcTemplate.query(
                "SELECT " + PAPER_COLS + " FROM paper WHERE ethnicity = ? AND disease = ? ORDER BY id",
                KbDao::mapPaper, ethnicity, disease);
    }

    /** 知识库覆盖的疾病（去重，用于问题信息不足时给出可选项） */
    public List<String> findDiseases() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT disease FROM paper WHERE disease IS NOT NULL ORDER BY disease", String.class);
    }

    /** 知识库覆盖的民族（去重，用于问题信息不足时给出可选项） */
    public List<String> findEthnicities() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT ethnicity FROM paper WHERE ethnicity IS NOT NULL ORDER BY ethnicity", String.class);
    }

    /** 论文总数（演示数据初始化判断用） */
    public long paperCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM paper", Long.class);
        return count == null ? 0 : count;
    }

    /** 取指定论文集合下的全部证据片段 */
    public List<Evidence> findEvidenceByPaperIds(List<Long> paperIds) {
        if (paperIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(paperIds.size(), "?"));
        return jdbcTemplate.query(
                "SELECT id, paper_id, topic, content FROM evidence WHERE paper_id IN (" + placeholders + ") ORDER BY id",
                KbDao::mapEvidence, paperIds.toArray());
    }

    public void insertPaper(Paper p) {
        jdbcTemplate.update(
                "INSERT INTO paper (title, ethnicity, disease, population, study_year, findings, limitation, source) VALUES (?,?,?,?,?,?,?,?)",
                p.title(), p.ethnicity(), p.disease(), p.population(), p.studyYear(), p.findings(), p.limitation(), p.source());
    }

    public void insertEvidence(Evidence e) {
        jdbcTemplate.update(
                "INSERT INTO evidence (paper_id, topic, content) VALUES (?,?,?)",
                e.paperId(), e.topic(), e.content());
    }
}
