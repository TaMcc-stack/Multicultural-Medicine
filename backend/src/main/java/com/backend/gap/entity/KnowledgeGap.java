package com.backend.gap.entity;

import java.time.LocalDateTime;

/**
 * 一条知识缺口：「某个民族 × 某个疾病 × 某个方面」在知识库里没有资料。
 *
 * <p>全库唯一——同一组合只有一行，被问次数跨用户累加（见 {@code GapDao.touchAsk}）。
 * 这与 {@code SearchHistory} 的「每个用户自己一行」是相反的取向，因为两者回答的问题不同：
 * 检索历史回答「我查过什么」，缺口回答「知识库缺什么」。</p>
 */
public class KnowledgeGap {

    private Long id;
    private String ethnicity;
    private String disease;
    private String intent;
    /** 被问次数：每次高级检索未命中 +1 */
    private int askCount;
    /** 反馈人数：按用户去重，不是点击次数 */
    private int feedbackCount;
    /** open 待补充 / filled 已补充 */
    private String status;
    /** 补录并审核通过（且索引同步成功）的那份文档 */
    private String filledDocId;
    /** 来源之一：高级检索未命中自动登记过（存量的行都是 1） */
    private boolean fromSearch;
    /** 来源之一：用户在智能对话里主动点过「反馈此问题」 */
    private boolean fromChat;
    /** 首次触发这条缺口的原始提问；高级检索来源没有原话，为 null */
    private String originalQuery;
    private LocalDateTime createdAt;
    private LocalDateTime lastAskedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEthnicity() {
        return ethnicity;
    }

    public void setEthnicity(String ethnicity) {
        this.ethnicity = ethnicity;
    }

    public String getDisease() {
        return disease;
    }

    public void setDisease(String disease) {
        this.disease = disease;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public int getAskCount() {
        return askCount;
    }

    public void setAskCount(int askCount) {
        this.askCount = askCount;
    }

    public int getFeedbackCount() {
        return feedbackCount;
    }

    public void setFeedbackCount(int feedbackCount) {
        this.feedbackCount = feedbackCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFilledDocId() {
        return filledDocId;
    }

    public void setFilledDocId(String filledDocId) {
        this.filledDocId = filledDocId;
    }

    public boolean isFromSearch() {
        return fromSearch;
    }

    public void setFromSearch(boolean fromSearch) {
        this.fromSearch = fromSearch;
    }

    public boolean isFromChat() {
        return fromChat;
    }

    public void setFromChat(boolean fromChat) {
        this.fromChat = fromChat;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public void setOriginalQuery(String originalQuery) {
        this.originalQuery = originalQuery;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastAskedAt() {
        return lastAskedAt;
    }

    public void setLastAskedAt(LocalDateTime lastAskedAt) {
        this.lastAskedAt = lastAskedAt;
    }
}
