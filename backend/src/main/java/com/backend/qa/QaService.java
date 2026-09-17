package com.backend.qa;

import com.backend.qa.dto.AskRequest;
import com.backend.qa.dto.AskResponse;
import com.backend.qa.dto.AskResponse.OptionItem;
import com.backend.qa.kb.KbDao;
import com.backend.qa.llm.LlmService;
import com.backend.qa.nlu.NluService;
import com.backend.qa.nlu.QuestionUnderstanding;
import com.backend.qa.retrieval.RetrievalService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 问答编排服务：问题理解 → （边界拦截）→ （槽位补全）→ （选择式澄清）→ 检索 → AI 生成 → 证据关联
 */
@Service
public class QaService {

    private static final Set<String> VALID_INTENTS = Set.of(
            "prevalence", "risk", "diet", "genetics", "overview");

    private final NluService nluService;
    private final RetrievalService retrievalService;
    private final KbDao kbDao;
    private final LlmService llmService;

    public QaService(NluService nluService, RetrievalService retrievalService,
                     KbDao kbDao, LlmService llmService) {
        this.nluService = nluService;
        this.retrievalService = retrievalService;
        this.kbDao = kbDao;
        this.llmService = llmService;
    }

    public AskResponse ask(AskRequest request) {
        return ask(request, null);
    }

    /**
     * 问答编排：问题理解 → （边界拦截）→ （选择式澄清）→ 检索 → AI 生成 → 证据关联
     *
     * @param context 会话上下文（上一条有效回答的民族/疾病），用于指代式追问补全；可为 null
     */
    public AskResponse ask(AskRequest request, AskResponse.Understanding context) {
        String question = request.question().trim();

        // 1. 问题理解
        QuestionUnderstanding u = nluService.parse(question);

        // 2. 服务边界：诊断 / 治疗 / 处方类问题直接提示
        if (u.boundary()) {
            return AskResponse.boundary(
                    "您的问题涉及诊断、治疗或用药建议，超出了本产品的服务边界。"
                            + "本产品仅提供基于研究资料的健康知识科普，不提供个体诊断、治疗或处方建议。"
                            + "如有健康问题，请及时前往正规医疗机构就诊。");
        }

        // 3. 民族 / 疾病：用户在选项中显式指定 > NLU 识别 > 会话上文（如「那遗传相关研究呢？」）
        String ethnicity = firstPresent(request.ethnicity(), u.ethnicity(),
                context != null ? context.ethnicity() : null);
        String disease = firstPresent(request.disease(), u.disease(),
                context != null ? context.disease() : null);

        // 4. 槽位缺失：按知识库实际覆盖范围给出可选项，引导用户点选补齐
        if (ethnicity == null || disease == null) {
            return slotMissing(ethnicity, disease, u);
        }

        // 5. 意图：优先用户澄清后的选择，其次 NLU 识别；均无则触发选择式澄清
        String intent = normalizeIntent(request.intent()) != null ? normalizeIntent(request.intent()) : u.intent();
        QuestionUnderstanding effective = new QuestionUnderstanding(
                ethnicity, disease, intent, false, u.intentAmbiguous());

        if (intent == null) {
            return AskResponse.clarify(toDto(effective), question);
        }

        // 6. 检索
        RetrievalService.RetrievalResult result =
                retrievalService.retrieve(ethnicity, disease, intent, question);
        if (result.papers().isEmpty() || result.evidences().isEmpty()) {
            return AskResponse.insufficient(
                    "当前纳入资料不足，无法形成可靠结论。"
                            + "知识库中暂无关于" + ethnicity + "人群" + disease
                            + "的" + "充分研究证据（POC 阶段覆盖：白族 + 糖尿病）。",
                    toDto(effective));
        }

        // 7. AI 基于证据生成回答（Qwen 或模板回退）+ 证据关联
        LlmService.GeneratedAnswer generated = llmService.generate(question, effective,
                result.evidences(), result.papers());

        AskResponse.AnswerBody body = new AskResponse.AnswerBody(
                generated.conclusion(),
                generated.sections().stream()
                        .map(s -> new AskResponse.Section(s.title(), s.content()))
                        .toList(),
                generated.applicable(),
                generated.cautions());

        QuestionUnderstanding finalU = new QuestionUnderstanding(
                ethnicity, disease, intent, false, false);
        return AskResponse.answer(toDto(finalU), body, result.evidences(), result.papers(), generated.engine());
    }

    /**
     * 民族 / 疾病槽位缺失：按「知识库实际覆盖范围」给出可选项，引导用户点选补齐。
     * 优先补齐缺失的那一项；两者都缺失时先补民族，再补疾病（分步选择，避免一次给太多选项）。
     */
    private AskResponse slotMissing(String ethnicity, String disease, QuestionUnderstanding u) {
        AskResponse.Understanding dto =
                toDto(new QuestionUnderstanding(ethnicity, disease, u.intent(), false, u.intentAmbiguous()));

        boolean needEthnicity = ethnicity == null;
        boolean needDisease = disease == null;

        // 民族已识别、疾病未识别：给出知识库覆盖的疾病选项
        if (!needEthnicity && needDisease) {
            List<String> diseases = kbDao.findDiseases();
            if (!diseases.isEmpty()) {
                return AskResponse.insufficient(
                        "已识别到您关注「" + ethnicity + "」人群，但还没有确认具体疾病。"
                                + "请选择想了解的疾病，系统会据此检索研究资料并给出可追溯来源的回答。",
                        dto, "disease", toOptions(diseases));
            }
            return AskResponse.insufficient(
                    "已识别到您关注「" + ethnicity + "」人群，但还没有确认具体疾病。"
                            + "请在问题中补充疾病信息，例如：「" + ethnicity + "人群糖尿病患病情况如何？」。", dto);
        }

        // 疾病已识别、民族未识别：给出知识库覆盖的民族选项
        if (needEthnicity && !needDisease) {
            List<String> ethnicities = kbDao.findEthnicities();
            if (!ethnicities.isEmpty()) {
                return AskResponse.insufficient(
                        "已识别到您关注「" + disease + "」，但还没有确认具体民族。"
                                + "请选择想了解的民族人群。",
                        dto, "ethnicity", toOptions(ethnicities));
            }
            return AskResponse.insufficient(
                    "已识别到您关注「" + disease + "」，但还没有确认具体民族。"
                            + "请在问题中补充民族信息，例如：「白族人群" + disease + "患病情况如何？」。", dto);
        }

        // 两者都未识别：先补民族
        List<String> ethnicities = kbDao.findEthnicities();
        if (!ethnicities.isEmpty()) {
            return AskResponse.insufficient(
                    "未能从问题中识别出「民族 + 疾病」信息。请先选择想了解的民族人群，"
                            + "下一步再选择具体疾病。",
                    dto, "ethnicity", toOptions(ethnicities));
        }
        return AskResponse.insufficient(
                "未能从问题中识别出完整的「民族 + 疾病」信息。"
                        + "请补充后重试，例如：「白族人群糖尿病患病情况如何？」。", dto);
    }

    private static List<OptionItem> toOptions(List<String> values) {
        return values.stream().map(v -> new OptionItem(v, v)).toList();
    }

    /** 取第一个非空（null / 空白均视为空）的值 */
    private static String firstPresent(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private String normalizeIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return null;
        }
        String t = intent.trim();
        return VALID_INTENTS.contains(t) ? t : null;
    }

    private AskResponse.Understanding toDto(QuestionUnderstanding u) {
        return new AskResponse.Understanding(u.ethnicity(), u.disease(), u.intent());
    }

}
