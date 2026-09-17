package com.backend.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发布一条反馈。
 *
 * <p>长度上限 500：这是全站唯一**不需要登录**的写接口，一条超长正文就够把列表撑爆。
 * 上限只是第一道闸（挡单个请求），并不挡「反复发很多条」——那需要按 IP 限流，本轮未做。</p>
 *
 * <p>发布者是登录态决定的，所以请求体里**没有** userId 字段：接口形状上就不给冒名留口子，
 * 与检索历史那几个端点同一约定。</p>
 */
public record CreateFeedbackRequest(
        @NotBlank(message = "反馈内容不能为空")
        @Size(max = 500, message = "反馈内容最多 500 字")
        String content
) {
}
