package com.backend.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 把一条反馈转为知识缺口。
 *
 * <p>三个槽位由**前端**从反馈原文里识别后传上来（复用前端的 {@code extractTags} 词表），
 * 服务端不重复实现识别：Java 侧没有那份民族/疾病词表，再写一份就是第四份拷贝——
 * 这个项目已经因为「同一份词表多处维护、互不一致」吃过亏。</p>
 *
 * <p>服务端只做长度与空值校验，不校验取值是否真在词表里：词表是前端的概念，
 * 管理员手工订正过的值也不该被一张服务端白名单挡回去。</p>
 */
public record ToGapRequest(
        @NotBlank(message = "缺少民族") @Size(max = 50, message = "民族名过长") String ethnicity,
        @NotBlank(message = "缺少疾病") @Size(max = 50, message = "疾病名过长") String disease,
        @NotBlank(message = "缺少想了解的方面") @Size(max = 30, message = "检索方面过长") String intent
) {
}
