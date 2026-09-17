package com.backend.qa.nlu;

import java.util.List;

/**
 * 问题理解结果：民族 / 疾病 / 查询意图
 */
public record QuestionUnderstanding(
        String ethnicity,
        String disease,
        String intent,
        boolean boundary,           // 是否为诊疗/处方类越界问题
        boolean intentAmbiguous     // 意图是否模糊（需要选择式澄清）
) {

    public static final List<String> INTENT_KEYS = List.of(
            "prevalence", "risk", "diet", "genetics", "overview");

    public static final List<IntentOption> INTENT_OPTIONS = List.of(
            new IntentOption("prevalence", "患病情况"),
            new IntentOption("risk", "危险因素"),
            new IntentOption("diet", "饮食与生活方式"),
            new IntentOption("genetics", "遗传相关研究"),
            new IntentOption("overview", "研究总体情况"));

    public record IntentOption(String key, String label) {
    }
}
