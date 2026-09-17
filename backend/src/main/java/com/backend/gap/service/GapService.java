package com.backend.gap.service;

import com.backend.common.BizException;
import com.backend.gap.dao.GapDao;
import com.backend.gap.dao.GapOrigin;
import com.backend.gap.dto.ChatFeedbackRequest;
import com.backend.gap.dto.GapDto;
import com.backend.gap.dto.RecordGapRequest;
import com.backend.gap.entity.KnowledgeGap;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识缺口：登记、反馈、榜单。
 *
 * <p>两个来源：高级检索未命中**自动**登记（三个槽位来自用户选的下拉，本身就是结构化的
 * 需求信号）；智能对话未命中则要**用户主动点**【反馈此问题】才登记（那里的槽位是 NLU
 * 猜出来的，猜错会污染榜单，所以不自动登记）。两者写同一张表、同一套唯一键，
 * 靠 {@code from_search} / {@code from_chat} 两列各自留痕。</p>
 *
 * <p>权限约定与检索历史相反：**登记与反馈人人可为**（每个用户都是需求来源），
 * 而**榜单只有管理员能看**（它是运营视角，不是个人数据）。</p>
 */
@Service
public class GapService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    /**
     * 疾病槽位的占位值：用户问的病没被识别出来（NLU 词表里没有）时用它顶上。
     *
     * <p><b>为什么用占位串而不是留空</b>：{@code knowledge_gap.disease} 是 NOT NULL，而唯一键是
     * 「民族 + 疾病 + 方面」——留空的话 SQL 里 NULL 之间互不相等，同一组合每反馈一次就会新建
     * 一行，「同一缺口全库一行」的前提就没了。占位串能正常参与去重。</p>
     *
     * <p>前端 {@code api/gap.ts} 的 {@code DISEASE_UNSPECIFIED} 是同值的一份，改这里记得两处一起改。
     * 前端正常会自己填好（它还会退回自己的词表从原话里再认一次），这里的替换是给
     * 「别的调用方传了空白」兜底。</p>
     */
    private static final String DISEASE_UNSPECIFIED = "未识别疾病";

    /** 意图缺失时的兜底档：不指向某个方面，与留言板转缺口用的是同一个值 */
    private static final String DEFAULT_INTENT = "all";

    private final GapDao gapDao;

    public GapService(GapDao gapDao) {
        this.gapDao = gapDao;
    }

    /**
     * 登记一次未命中，返回更新后的那条缺口。
     *
     * <p>返回整条而不是「成功」二字：前端要拿 id 去点反馈，也要知道它现在被问了几次。
     * 次数 +1 与读取在同一事务语义下完成（先 UPDATE/INSERT 再 SELECT），
     * 所以返回的必然是加过之后的数。</p>
     */
    public GapDto record(RecordGapRequest request) {
        String ethnicity = request.ethnicity().trim();
        String disease = request.disease().trim();
        String intent = request.intent().trim();
        // 高级检索没有一句完整的话，但三个槽位拼成的组合串（「白族+高血压+患病率」）
        // 足够当「原话」存——管理员在榜单上看到它就知道用户在查什么组合。空白退成 null。
        String query = request.query() == null || request.query().isBlank() ? null : request.query().trim();
        gapDao.touchAsk(ethnicity, disease, intent, GapOrigin.SEARCH, query);
        KnowledgeGap gap = gapDao.findBySlots(ethnicity, disease, intent)
                .orElseThrow(() -> new BizException(500, "登记知识缺口失败，请重试"));
        return toDto(gap);
    }

    /**
     * 智能对话里用户点【反馈此问题】。
     *
     * <p>与 {@link #record} 的区别不只是来源标记：这里是**登记 + 反馈一次做完**。
     * 高级检索那条链路要分两步（先 record 拿 id，用户再点一次才 feedback），因为用户可能
     * 不反馈；而对话页的按钮本身就是反馈动作，再拆两步等于让用户点两下。</p>
     *
     * <p>{@code touchAsk} 会把 {@code ask_count} +1（没这一行就建行），{@code addFeedback}
     * 按 (缺口, 用户) 去重后再把 {@code feedback_count} 重算——所以同一个人连点只算一次，
     * 换个人点才会把人数加上去。</p>
     */
    public GapDto chatFeedback(Long userId, ChatFeedbackRequest request) {
        String ethnicity = request.ethnicity().trim();
        // 疾病与方面都可能缺失，而且这恰恰是最该能反馈的情形（用户问的病 NLU 不认识），
        // 所以不拒绝，各自退到占位值 / 兜底档
        String disease = blankTo(request.disease(), DISEASE_UNSPECIFIED);
        String intent = blankTo(request.intent(), DEFAULT_INTENT);
        String question = request.question() == null ? null : request.question().trim();

        gapDao.touchAsk(ethnicity, disease, intent, GapOrigin.CHAT, question);
        KnowledgeGap gap = gapDao.findBySlots(ethnicity, disease, intent)
                .orElseThrow(() -> new BizException(500, "登记知识缺口失败，请重试"));
        if (gapDao.addFeedback(gap.getId(), userId)) {
            gapDao.refreshFeedbackCount(gap.getId());
        }
        return toDto(gapDao.findById(gap.getId()).orElse(gap));
    }

    /** null / 空白一律换成兜底值 */
    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * 当前用户反馈过的缺口（对话页据此回显「已反馈」）。
     *
     * <p>不过滤状态：一条缺口被补上之后，用户当时反馈过这件事仍然成立——他再翻回那段对话，
     * 那一轮本来就该继续显示「已反馈」，否则会让人以为反馈丢了。</p>
     */
    public List<GapDto> myFeedback(Long userId) {
        return gapDao.listByUserFeedback(userId).stream().map(GapService::toDto).toList();
    }

    /**
     * 反馈「我需要这个方面」。
     *
     * <p>同一用户重复点只算一次：反馈次数回答的是「有多少人想要」，
     * 若按点击累加，一个人连点就能把某条刷到榜首，榜单就失去意义了。</p>
     */
    public GapDto feedback(Long userId, Long gapId) {
        KnowledgeGap gap = gapDao.findById(gapId)
                .orElseThrow(() -> new BizException(404, "这条缺口不存在"));
        if (gapDao.addFeedback(gapId, userId)) {
            gapDao.refreshFeedbackCount(gapId);
        }
        // 无论是否新增都回读一次：前端据此把按钮切成「已反馈」，不必自己猜
        return toDto(gapDao.findById(gapId).orElse(gap));
    }

    public GapDto get(Long gapId) {
        return toDto(gapDao.findById(gapId)
                .orElseThrow(() -> new BizException(404, "这条缺口不存在")));
    }

    /**
     * 人工标记「已解决」——管理员判定这条缺口不用补了（或已在别处补上）。
     *
     * <p>与 {@code KbService.review} 那条自动结转是两回事：那条由补录入库触发，会记下
     * 补进来的文档 id；这条只改状态。幂等：已经补充/已标记过的再点一次不报错，回读当前状态即可
     * ——管理员连点或两个标签页同时操作都不该看到红字。</p>
     */
    public GapDto resolve(Long gapId) {
        KnowledgeGap gap = gapDao.findById(gapId)
                .orElseThrow(() -> new BizException(404, "这条缺口不存在"));
        if (!"open".equals(gap.getStatus())) {
            return toDto(gap);
        }
        gapDao.markResolved(gapId);
        return toDto(gapDao.findById(gapId).orElse(gap));
    }

    /**
     * 榜单。
     *
     * @param sort ask = 热门话题排行（按被问次数）；feedback = 未解决话题排行（按反馈人数）；
     *             resolved = 已解决历史（补录入库的 + 人工标记的）
     */
    public List<GapDto> list(String sort, Integer limit) {
        int n = normalizeLimit(limit);
        List<KnowledgeGap> rows = switch (sort == null ? "ask" : sort) {
            case "feedback" -> gapDao.listOpenByFeedback(n);
            case "resolved" -> gapDao.listResolved(n);
            default -> gapDao.listByAsk(n);
        };
        return rows.stream().map(GapService::toDto).toList();
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private static GapDto toDto(KnowledgeGap g) {
        return new GapDto(g.getId(), g.getEthnicity(), g.getDisease(), g.getIntent(),
                g.getAskCount(), g.getFeedbackCount(), g.getStatus(), g.getFilledDocId(),
                g.isFromSearch(), g.isFromChat(), g.getOriginalQuery(),
                g.getCreatedAt(), g.getLastAskedAt());
    }
}
