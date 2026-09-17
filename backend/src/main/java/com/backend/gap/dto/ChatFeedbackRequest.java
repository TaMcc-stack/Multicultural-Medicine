package com.backend.gap.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 智能对话里用户点【反馈此问题】时提交的内容。
 *
 * <p>三个槽位来自**这一轮提问已经跑过的 NLU 结果**（`understand` 拆出来的民族/疾病/意图），
 * 由前端原样带上，服务端不重新识别——再写一份识别逻辑就是第二份词表，这个项目已经因为
 * 「同一份词表多处维护、互不一致」吃过亏。服务端只做长度与空值校验。</p>
 *
 * <p>{@code disease} **允许为空**：用户问的病 NLU 词表里没有时（例如「CKM」，Python 那 7 个
 * 疾病里就没有），识别结果本来就是空的，而这种情况恰恰最该能反馈——那正是知识库可能缺的东西。
 * 服务端会把它替换成 {@code GapService} 里的占位值，见那边的说明。</p>
 *
 * <p>{@code question} 是用户的**原话**，存进 {@code knowledge_gap.original_query}，
 * 让管理员在榜单上看到「到底是谁在问什么」，而不是只看到三个槽位。
 * 它可空：老前端或别的调用方不带也能登记，只是榜单上少一句原始提问。</p>
 */
public record ChatFeedbackRequest(
        @NotBlank(message = "缺少民族") @Size(max = 50, message = "民族名过长") String ethnicity,
        @Size(max = 50, message = "疾病名过长") String disease,
        @Size(max = 30, message = "检索方面过长") String intent,
        @Size(max = 500, message = "原问题过长") String question
) {
}
