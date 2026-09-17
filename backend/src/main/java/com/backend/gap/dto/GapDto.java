package com.backend.gap.dto;

import java.time.LocalDateTime;

/**
 * 一条知识缺口（对外展示用）。
 *
 * <p>{@code intent} 是意图码（如 {@code genetics}）而不是中文——中文名由前端用自己的
 * {@code INTENT_LABELS} 换。刻意不在服务端存/拼展示名：Java 侧那两张意图标签表只有 5 档，
 * 不含高级检索新增的 5 档，服务端拼出来的名字会与界面上显示的不一致（这个坑在检索历史
 * 那张表上已经踩过一次，见 {@code SearchHistoryDto} 的说明）。</p>
 */
public record GapDto(
        Long id,
        String ethnicity,
        String disease,
        /** 想了解的方面（意图码） */
        String intent,
        /** 被问次数（全用户累加） */
        int askCount,
        /** 反馈人数（按用户去重） */
        int feedbackCount,
        /** open 待补充 / filled 已补充 */
        String status,
        /** 已补充时指向那份文档，供「看看补了什么」跳转 */
        String filledDocId,
        /**
         * 来源之一：高级检索未命中自动登记过。
         * 与 {@code fromChat} **不互斥**——同一组合可以被两个入口都碰过，界面上两个徽标同时显示。
         */
        boolean fromSearch,
        /** 来源之一：用户在智能对话里主动点过「反馈此问题」 */
        boolean fromChat,
        /** 首次触发这条缺口的原始提问；高级检索来源没有原话，为 null */
        String originalQuery,
        LocalDateTime createdAt,
        LocalDateTime lastAskedAt
) {}
