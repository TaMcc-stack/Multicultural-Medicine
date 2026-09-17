package com.backend.qa.llm;

import com.backend.qa.kb.Evidence;
import com.backend.qa.kb.Paper;
import com.backend.qa.nlu.QuestionUnderstanding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回答生成服务：
 * - 配置了 app.qwen.api-key 时调用 Qwen（DashScope OpenAI 兼容接口）基于证据生成回答；
 * - 未配置或调用失败时回退到模板生成，保证 POC 演示链路始终可用。
 * 两种引擎输出结构一致，均带 [n] 引用角标，n 对应证据序号（evidence_id）。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private static final Map<String, String> TOPIC_LABELS = Map.of(
            "prevalence", "患病情况",
            "risk", "危险因素",
            "diet", "饮食与生活方式",
            "genetics", "遗传相关研究",
            "overview", "研究总体情况");

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;

    public LlmService(@Value("${app.qwen.api-key:}") String apiKey,
                      @Value("${app.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                      @Value("${app.qwen.model:qwen-plus}") String model) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl;
        this.model = model;
        this.restClient = RestClient.create();
    }

    public record GeneratedAnswer(
            String conclusion,
            List<AskSection> sections,
            String applicable,
            String cautions,
            String engine) {
    }

    public record AskSection(String title, String content) {
    }

    public GeneratedAnswer generate(String question,
                                    QuestionUnderstanding u,
                                    List<Evidence> evidences,
                                    List<Paper> papers) {
        if (!apiKey.isEmpty()) {
            try {
                GeneratedAnswer ans = callQwen(question, u, evidences, papers);
                if (ans != null) {
                    return ans;
                }
            } catch (Exception e) {
                log.warn("Qwen 调用失败，回退模板生成: {}", e.getMessage());
            }
        }
        return templateGenerate(question, u, evidences, papers);
    }

    // ---------------- Qwen（DashScope OpenAI 兼容接口） ----------------

    private GeneratedAnswer callQwen(String question, QuestionUnderstanding u,
                                     List<Evidence> evidences, List<Paper> papers) {
        String systemPrompt = """
                你是面向普通大众的多民族健康知识科普助手。请严格遵守：
                1. 只能依据给定的证据片段回答，不得编造数据或结论；证据中没有的信息不要回答。
                2. 回答通俗易懂，关键事实后用 [n] 标注引用（n 为证据编号）。
                3. 只输出一个 JSON 对象，不要输出其他文字，格式：
                   {"conclusion":"核心结论（1-2句）","sections":[{"title":"小节标题","content":"内容，含[n]引用"}],
                    "applicable":"适用范围说明","cautions":"注意事项"}
                4. 不提供个体诊断、治疗或处方建议。
                """;

        StringBuilder user = new StringBuilder();
        user.append("用户问题：").append(question).append("\n\n");
        user.append("已识别条件——民族：").append(u.ethnicity())
                .append("；疾病：").append(u.disease())
                .append("；查询意图：").append(TOPIC_LABELS.getOrDefault(u.intent(), "综合")).append("\n\n");
        user.append("证据片段：\n");
        Map<Long, Paper> paperMap = new LinkedHashMap<>();
        papers.forEach(p -> paperMap.put(p.id(), p));
        for (int i = 0; i < evidences.size(); i++) {
            Evidence e = evidences.get(i);
            Paper p = paperMap.get(e.paperId());
            user.append("[").append(i + 1).append("] 来源论文《").append(p != null ? p.title() : "")
                    .append("》：").append(e.content()).append("\n");
        }

        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.3,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", user.toString())));

        String resp = restClient.post()
                .uri(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        return parseQwenJson(resp);
    }

    private GeneratedAnswer parseQwenJson(String resp) {
        if (resp == null || resp.isBlank()) {
            return null;
        }
        try {
            return doParseQwenJson(resp);
        } catch (Exception e) {
            log.warn("解析 Qwen 返回内容失败，回退模板生成: {}", e.getMessage());
            return null;
        }
    }

    private GeneratedAnswer doParseQwenJson(String resp) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = objectMapper.readTree(resp);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            return null;
        }
        String text = content.asText().trim();
        // 去掉可能的 markdown 代码块包裹
        if (text.startsWith("```")) {
            text = text.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        }
        JsonNode json = objectMapper.readTree(text);
        List<AskSection> sections = new ArrayList<>();
        for (JsonNode s : json.path("sections")) {
            sections.add(new AskSection(s.path("title").asText(), s.path("content").asText()));
        }
        if (sections.isEmpty()) {
            return null;
        }
        return new GeneratedAnswer(
                json.path("conclusion").asText(),
                sections,
                json.path("applicable").asText(""),
                json.path("cautions").asText(""),
                "qwen:" + model);
    }

    // ---------------- 模板生成（回退 / 未配置 API Key） ----------------

    private GeneratedAnswer templateGenerate(String question, QuestionUnderstanding u,
                                             List<Evidence> evidences, List<Paper> papers) {
        Map<Long, Paper> paperMap = new LinkedHashMap<>();
        papers.forEach(p -> paperMap.put(p.id(), p));

        // 按主题分节，证据带 [n] 角标
        Map<String, StringBuilder> byTopic = new LinkedHashMap<>();
        Map<String, Integer> byTopicCount = new LinkedHashMap<>();
        for (int i = 0; i < evidences.size(); i++) {
            Evidence e = evidences.get(i);
            byTopic.computeIfAbsent(e.topic(), k -> new StringBuilder())
                    .append(e.content()).append(" [").append(i + 1).append("]\n");
            byTopicCount.merge(e.topic(), 1, Integer::sum);
        }

        List<AskSection> sections = new ArrayList<>();
        byTopic.forEach((topic, sb) -> sections.add(
                new AskSection(TOPIC_LABELS.getOrDefault(topic, "研究发现"), sb.toString().trim())));

        // 核心结论：取意图最相关证据的第一条 + 论文数量
        String first = evidences.isEmpty() ? "" : evidences.get(0).content();
        String conclusion = String.format(
                "关于%s人群%s的%s，当前知识库共纳入 %d 篇相关研究。主要发现：%s",
                u.ethnicity(), u.disease(),
                TOPIC_LABELS.getOrDefault(u.intent(), "研究情况"),
                papers.size(), first);

        StringBuilder applicable = new StringBuilder();
        applicable.append("以上结论来自").append(u.ethnicity()).append("人群")
                .append(u.disease()).append("相关研究（");
        for (int i = 0; i < papers.size(); i++) {
            if (i > 0) {
                applicable.append("、");
            }
            applicable.append(papers.get(i).studyYear() == null ? "" : papers.get(i).studyYear() + "年");
        }
        applicable.append("），反映该人群总体研究情况，不代表任何个体的患病风险或健康结论。");

        StringBuilder cautions = new StringBuilder();
        papers.stream().map(Paper::limitation).filter(l -> l != null && !l.isBlank())
                .distinct().limit(3).forEach(l -> cautions.append("· ").append(l).append("\n"));
        cautions.append("· 本回答为健康知识科普，不构成诊断、治疗或用药建议；如有健康问题请及时就医。");

        return new GeneratedAnswer(conclusion, sections, applicable.toString(), cautions.toString(), "template");
    }
}
