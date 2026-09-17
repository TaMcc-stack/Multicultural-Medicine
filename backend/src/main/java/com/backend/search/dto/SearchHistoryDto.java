package com.backend.search.dto;

import java.time.LocalDateTime;

/**
 * 高级检索历史的一条记录（列表与写入后回显共用）。
 *
 * <p>不带展示标题：侧栏要显示的「白族 + 糖尿病 + 患病情况」由前端用三个槽位 +
 * {@code INTENT_LABELS} 拼出。服务端拼不了——意图只存了英文码，中文标签在前端，
 * 而 Java 侧那两张 intent 标签表只有 5 档（不含高级检索新增的 5 档），用它必然漏。
 * 也正因为不落标题，将来改标签时旧记录会跟着一起变，不会出现新旧记录文案不一致。</p>
 *
 * <p>{@code detailJson} 与动态一样是**未解析的 JSON 字符串**，由前端自行 {@code JSON.parse}——
 * 保持与 {@code DynamicDto} 一致，前端那套「从 detailJson 里抽展示字段」的写法可以直接照搬。</p>
 */
public record SearchHistoryDto(
        Long id,
        String ethnicity,
        String disease,
        /** 想了解的方面（意图码）；前端用 INTENT_LABELS 换成中文 */
        String intent,
        /** 结果快照 JSON（答案 + 证据 + 未命中原因） */
        String detailJson,
        /** 首次检索时间 */
        LocalDateTime createdAt,
        /** 最后一次检索时间 */
        LocalDateTime updatedAt
) {
}
