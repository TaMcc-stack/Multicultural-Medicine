package com.backend.search.entity;

import java.time.LocalDateTime;

/**
 * 高级检索历史实体（表 user_search_history）。
 *
 * <p>一条记录 = 某用户点过的「民族 × 疾病 × 想了解的方面」，外加那一次的结果快照。
 * 与「会话」是两回事：这里没有多轮消息，幂等键也不是会话 id 而是三个槽位。</p>
 */
public class SearchHistory {

    private Long id;
    private Long userId;
    private String ethnicity;
    private String disease;
    /** 想了解的方面（意图码，如 prevalence） */
    private String intent;
    /** 结果快照 JSON，供「查看当时的结果」还原 */
    private String detailJson;
    private LocalDateTime createdAt;
    /** 最后一次检索时间；列表按它倒序 */
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public String getDetailJson() {
        return detailJson;
    }

    public void setDetailJson(String detailJson) {
        this.detailJson = detailJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
