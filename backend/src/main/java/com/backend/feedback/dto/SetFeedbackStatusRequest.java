package com.backend.feedback.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 管理员改反馈状态。取值在 Service 里按白名单校验（见 {@code FeedbackService.STATUSES}），
 * 不在这里用 {@code @Pattern}——状态码集合是会变的，写在注解里改一处漏一处，
 * 而白名单在 Service 里还能顺带给出「有哪些合法取值」的报错。
 */
public record SetFeedbackStatusRequest(
        @NotBlank(message = "缺少状态") String status
) {
}
