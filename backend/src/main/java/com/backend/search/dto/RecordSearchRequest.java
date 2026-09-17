package com.backend.search.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 记录 / 刷新一条高级检索历史。
 *
 * <p>幂等键是「用户 + 三个槽位」，**不接受客户端自报 id**——同一个组合重复检索是刷新
 * 时间与快照，由服务端按 (user_id, ethnicity, disease, intent) 判定，客户端无从选择新增还是覆盖。</p>
 *
 * <p>也不收展示标题：那是前端用三个槽位拼出来的，服务端存一份就是两个可能不一致的真相源。</p>
 *
 * <p>{@code detail} 是那一次检索的结果快照，形状由前端决定（见 {@code SearchSnapshot}）。
 * 这里不做结构校验：服务端不解析它、也不据它做任何判断，只是原样存档。</p>
 */
public record RecordSearchRequest(
        @NotBlank(message = "检索记录缺少民族") @Size(max = 50, message = "民族名过长") String ethnicity,
        @NotBlank(message = "检索记录缺少疾病") @Size(max = 50, message = "疾病名过长") String disease,
        @NotBlank(message = "检索记录缺少检索方面") @Size(max = 30, message = "检索方面过长") String intent,
        Object detail
) {
}
