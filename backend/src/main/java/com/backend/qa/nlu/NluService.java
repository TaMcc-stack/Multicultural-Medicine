package com.backend.qa.nlu;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 问题理解（NLU）服务 —— POC 阶段采用规则/词典方式：
 * 1. 民族识别：民族词典最长匹配
 * 2. 疾病识别：疾病词典最长匹配
 * 3. 意图识别：关键词规则（患病情况/危险因素/饮食生活方式/遗传/研究总体）
 * 4. 服务边界：诊断、治疗、处方类问题直接拦截
 * 后续 MVP 可将本服务替换为 LLM 抽取，接口保持不变。
 */
@Service
public class NluService {

    /** 民族词典（按长度倒序匹配，优先命中多字民族） */
    private static final List<String> ETHNICITIES = List.of(
            "维吾尔族", "哈萨克族", "傈僳族", "景颇族", "布朗族", "阿昌族", "德昂族", "独龙族", "基诺族",
            "白族", "彝族", "哈尼族", "傣族", "纳西族", "壮族", "苗族", "回族", "藏族", "拉祜族", "佤族",
            "瑶族", "普米族", "怒族", "水族", "蒙古族", "满族", "侗族", "布依族", "土家族", "黎族");

    /** 疾病词典 */
    private static final List<String> DISEASES = List.of(
            "糖尿病", "高血压", "冠心病", "地中海贫血", "心脑血管疾病", "结核病", "痛风",
            "贫血", "乳腺癌", "宫颈癌", "包虫病", "乙肝", "高血脂", "肥胖症");

    /** 服务边界关键词：个体诊断 / 治疗 / 处方 */
    private static final List<String> BOUNDARY_KEYWORDS = List.of(
            "诊断", "确诊", "怎么治", "如何治疗", "怎么治疗", "治疗方案", "能不能治好", "治好",
            "吃药", "用药", "服药", "吃什么药", "剂量", "吃多少", "处方", "开药", "挂什么科", "注射");

    /** 意图关键词表（顺序即优先级） */
    private static final Map<String, List<String>> INTENT_KEYWORDS = Map.of(
            "prevalence", List.of("患病率", "患病情况", "发病率", "流行现状", "流行病学", "多少人患", "多少人得", "得了糖尿病"),
            "risk", List.of("危险因素", "风险因素", "诱因", "病因", "为什么得", "为什么患", "影响因素", "容易得", "高危"),
            "diet", List.of("饮食", "膳食", "营养", "生活方式", "生活习惯", "运动", "作息", "吸烟", "饮酒", "吃什么好", "忌口"),
            "genetics", List.of("遗传", "基因", "易感", "多态性", "家族史", "体质"),
            "overview", List.of("研究总体", "总体情况", "综述", "都有哪些研究", "研究概况", "研究进展"));

    /** 模糊意图标志：提到"研究/发现"但未指向具体方面时，触发选择式澄清 */
    private static final List<String> AMBIGUOUS_MARKERS = List.of(
            "研究情况", "哪些发现", "相关研究", "什么情况", "怎么样", "如何", "啥情况");

    public QuestionUnderstanding parse(String question) {
        String q = question == null ? "" : question.trim();

        boolean boundary = BOUNDARY_KEYWORDS.stream().anyMatch(q::contains);
        String ethnicity = matchLongest(q, ETHNICITIES);
        String disease = matchLongest(q, DISEASES);

        String intent = detectIntent(q);
        boolean ambiguous = intent == null
                && AMBIGUOUS_MARKERS.stream().anyMatch(q::contains);

        return new QuestionUnderstanding(ethnicity, disease, intent, boundary, ambiguous);
    }

    private String matchLongest(String text, List<String> dict) {
        String best = null;
        for (String w : dict) {
            if (text.contains(w) && (best == null || w.length() > best.length())) {
                best = w;
            }
        }
        return best;
    }

    private String detectIntent(String q) {
        for (Map.Entry<String, List<String>> e : INTENT_KEYWORDS.entrySet()) {
            for (String kw : e.getValue()) {
                if (q.contains(kw)) {
                    return e.getKey();
                }
            }
        }
        return null;
    }
}
