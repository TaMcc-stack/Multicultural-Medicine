package com.backend.qa.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 问答请求：question 必填；
 * intent 为用户在澄清后选择的查询意图（prevalence/risk/diet/genetics/overview），
 * 首次提问时可空；
 * ethnicity / disease 为用户在「信息不足」选项中选择的民族 / 疾病，显式指定时优先于 NLU 识别结果；
 * conversationId 为会话上下文（继续对话时传入，为空则后端自动新建会话）。
 */
public record AskRequest(
        @NotBlank(message = "问题不能为空") String question,
        String intent,
        String ethnicity,
        String disease,
        Long conversationId
) {
}
