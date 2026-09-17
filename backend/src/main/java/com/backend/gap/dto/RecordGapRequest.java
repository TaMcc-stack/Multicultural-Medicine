package com.backend.gap.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登记一次未命中。三个槽位由高级检索页的下拉直接给出，所以这里只做长度与空值校验，
 * 不校验取值——意图码的合法性由前端保证（下拉是固定 10 档），服务端多一张白名单表
 * 只会多一个漂移点。
 *
 * <p>{@code query} 是「原话」：高级检索没有一句自由提问，但三个槽位拼成的组合串
 * （如「白族+高血压+患病率」）足够让管理员在榜单上一眼看懂用户在查什么组合。
 * 它可空：老前端或别的调用方不带也能登记，只是榜单上少一行原始提问。</p>
 */
public record RecordGapRequest(
        @NotBlank(message = "缺口缺少民族") @Size(max = 50, message = "民族名过长") String ethnicity,
        @NotBlank(message = "缺口缺少疾病") @Size(max = 50, message = "疾病名过长") String disease,
        @NotBlank(message = "缺口缺少检索方面") @Size(max = 30, message = "检索方面过长") String intent,
        @Size(max = 500, message = "原问题过长") String query
) {}
