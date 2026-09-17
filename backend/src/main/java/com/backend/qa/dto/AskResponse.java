package com.backend.qa.dto;

import com.backend.qa.kb.Evidence;
import com.backend.qa.kb.Paper;
import com.backend.qa.nlu.QuestionUnderstanding;

import java.util.List;

/**
 * 问答响应，按 type 分四种：
 *  - clarify     问题过于宽泛，返回选择式澄清选项
 *  - answer      AI 基于证据生成的回答 + 引用（evidence_id 溯源）
 *  - boundary    诊断/治疗/处方类问题，超出产品服务边界
 *  - insufficient 知识库证据不足，无法形成可靠结论
 */
public record AskResponse(
        String type,
        String message,
        String clarifyQuestion,
        Understanding understanding,
        List<OptionItem> options,
        /** 需要用户补充的槽位：disease / ethnicity（仅 insufficient 类型使用，其余为 null） */
        String slot,
        AnswerBody answer,
        List<Citation> citations,
        String engine,
        Long conversationId
) {

    /** 携带会话 ID 返回（由问答接口在落库后回填） */
    public AskResponse withConversation(Long conversationId) {
        return new AskResponse(type, message, clarifyQuestion, understanding,
                options, slot, answer, citations, engine, conversationId);
    }

    public static AskResponse clarify(Understanding u, String question) {
        return new AskResponse("clarify", null,
                "您希望了解" + u.disease() + "的哪方面信息？", u,
                QuestionUnderstanding.INTENT_OPTIONS.stream()
                        .map(o -> new OptionItem(o.key(), o.label()))
                        .toList(),
                null, null, null, null, null);
    }

    public static AskResponse boundary(String message) {
        return new AskResponse("boundary", message, null, null, null, null, null, null, null, null);
    }

    /** 信息不足（无可用选项） */
    public static AskResponse insufficient(String message, Understanding u) {
        return insufficient(message, u, null, null);
    }

    /**
     * 信息不足 + 需要用户补充的槽位选项
     *
     * @param slot    需补充的槽位：disease（选疾病）/ ethnicity（选民族）
     * @param options 可选项（取自知识库实际覆盖范围）
     */
    public static AskResponse insufficient(String message, Understanding u,
                                           String slot, List<OptionItem> options) {
        return new AskResponse("insufficient", message, null, u, options, slot, null, null, null, null);
    }

    public static AskResponse answer(Understanding u, AnswerBody answer,
                                     List<Evidence> evidences, List<Paper> papers, String engine) {
        List<Citation> citations = new java.util.ArrayList<>();
        for (int i = 0; i < evidences.size(); i++) {
            Evidence e = evidences.get(i);
            Paper p = papers.stream().filter(x -> x.id().equals(e.paperId())).findFirst().orElse(null);
            citations.add(new Citation(i + 1, e.id(), e.content(), p));
        }
        return new AskResponse("answer", null, null, u, null, null, answer, citations, engine, null);
    }

    // ---------- 嵌套结构 ----------

    public record Understanding(String ethnicity, String disease, String intent) {
    }

    public record OptionItem(String key, String label) {
    }

    /** 引用：index 为回答中的角标 [n]，evidenceId 用于溯源到原始证据 */
    public record Citation(int index, Long evidenceId, String quote, Paper paper) {
    }

    /** 回答主体：核心结论 / 分节内容（含 [n] 引用标记）/ 适用范围 / 注意事项 */
    public record AnswerBody(String conclusion, List<Section> sections, String applicable, String cautions) {
    }

    public record Section(String title, String content) {
    }
}
