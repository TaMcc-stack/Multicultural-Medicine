package com.backend.qa.retrieval;

import com.backend.qa.kb.Evidence;
import com.backend.qa.kb.KbDao;
import com.backend.qa.kb.Paper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索服务：结构化筛选（民族+疾病）→ 证据召回与排序
 *
 * POC 阶段语义检索以「主题匹配 + 关键词重合度」近似打分，
 * MVP 阶段替换为 Chroma 向量检索，打分接口保持不变。
 */
@Service
public class RetrievalService {

    private static final int MAX_EVIDENCE = 5;

    private final KbDao kbDao;

    public RetrievalService(KbDao kbDao) {
        this.kbDao = kbDao;
    }

    /** 检索结果 */
    public record RetrievalResult(List<Paper> papers, List<Evidence> evidences) {
    }

    public RetrievalResult retrieve(String ethnicity, String disease, String intent, String question) {
        List<Paper> papers = kbDao.findPapers(ethnicity, disease);
        if (papers.isEmpty()) {
            return new RetrievalResult(List.of(), List.of());
        }

        List<Long> paperIds = papers.stream().map(Paper::id).toList();
        List<Evidence> all = kbDao.findEvidenceByPaperIds(paperIds);

        // 打分：主题命中权重最高，再叠加问题关键词与证据内容的重合度（近似语义相关度）
        Map<Long, Paper> paperMap = new HashMap<>();
        papers.forEach(p -> paperMap.put(p.id(), p));

        List<Scored> scored = new ArrayList<>();
        for (Evidence e : all) {
            double score = 0;
            if (intent != null && !intent.equals("overview") && intent.equals(e.topic())) {
                score += 5;
            }
            if (intent != null && intent.equals("overview") && "overview".equals(e.topic())) {
                score += 3;
            }
            score += keywordOverlap(question, e.content());
            score += keywordOverlap(question, paperMap.get(e.paperId()).title()) * 0.5;
            scored.add(new Scored(e, score));
        }

        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());

        // 明确意图时只保留同主题证据（该主题无命中再回退全量），避免回答掺入无关主题
        List<Scored> filtered = scored;
        if (intent != null && !intent.equals("overview")) {
            List<Scored> sameTopic = scored.stream()
                    .filter(s -> intent.equals(s.evidence().topic()))
                    .toList();
            if (!sameTopic.isEmpty()) {
                filtered = sameTopic;
            }
        }

        List<Evidence> top = filtered.stream()
                .limit(MAX_EVIDENCE)
                .map(s -> s.evidence)
                .toList();

        // 保证主题命中的证据优先入选后，仍按 evidence_id 稳定排序输出
        List<Evidence> sorted = top.stream()
                .sorted(Comparator.comparingLong(Evidence::id))
                .toList();

        return new RetrievalResult(papers, sorted);
    }

    /** 问题与文本的二元组重合度（简易中文关键词匹配） */
    private double keywordOverlap(String question, String text) {
        if (question == null || text == null || question.length() < 2) {
            return 0;
        }
        double hits = 0;
        for (int i = 0; i + 2 <= question.length(); i++) {
            String gram = question.substring(i, i + 2);
            if (text.contains(gram)) {
                hits += 1;
            }
        }
        return Math.min(hits * 0.3, 2.0);
    }

    private record Scored(Evidence evidence, double score) {
    }
}
