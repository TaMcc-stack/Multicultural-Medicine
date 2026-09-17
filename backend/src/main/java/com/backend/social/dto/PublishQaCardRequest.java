package com.backend.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发布 / 刷新一张「一问一答」卡片（高级检索页分享出来的单轮问答）。
 *
 * <p>与 {@link CreateDynamicRequest} 的区别在于幂等键：问答卡按 {@code qaKey}
 * （「民族|疾病|意图」）**全局唯一**，而不是按「用户 + 会话」。同一个组合谁先分享谁占位，
 * 后来者刷新内容但不会新增第二张卡——内容来自同一套知识库，重复的卡片只会让社区像刷屏。</p>
 */
public record PublishQaCardRequest(
        @NotBlank(message = "问答卡缺少标识") @Size(max = 300, message = "问答卡标识过长") String qaKey,
        @Size(max = 200, message = "标题过长") String title,
        @NotBlank(message = "分享内容不能为空") String content,
        Object detail
) {
}
