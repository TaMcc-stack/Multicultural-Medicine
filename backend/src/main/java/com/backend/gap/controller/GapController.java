package com.backend.gap.controller;

import com.backend.auth.AuthController;
import com.backend.common.ApiResponse;
import com.backend.common.BizException;
import com.backend.gap.dto.ChatFeedbackRequest;
import com.backend.gap.dto.GapDto;
import com.backend.gap.dto.RecordGapRequest;
import com.backend.gap.service.GapService;
import com.backend.kb.KbService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识缺口接口。
 *
 * <p>登记与反馈对所有登录用户开放（每个人都是需求来源）；榜单是运营视角，只对管理员开放。
 * 整段路径受 {@code AuthInterceptor} 默认拦截，未登录一律 401，这里不必再声明。</p>
 */
@RestController
@RequestMapping("/api/gaps")
public class GapController {

    private final GapService gapService;
    private final KbService kbService;

    public GapController(GapService gapService, KbService kbService) {
        this.gapService = gapService;
        this.kbService = kbService;
    }

    /** 高级检索未命中时登记一条需求（重复组合只是次数 +1） */
    @PostMapping
    public ApiResponse<GapDto> record(@Valid @RequestBody RecordGapRequest request) {
        return ApiResponse.ok(gapService.record(request));
    }

    /** 「反馈缺文献」：按用户去重，重复点不会再加 */
    @PostMapping("/{id}/feedback")
    public ApiResponse<GapDto> feedback(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        return ApiResponse.ok(gapService.feedback(userId, id));
    }

    // ---------- 智能对话来源 ----------

    /**
     * 智能对话未命中时，用户点【反馈此问题】。
     *
     * <p>与 {@code POST /api/gaps} 的区别：那条是高级检索**自动**登记（只记次数，用户要不要
     * 反馈是下一步的事）；这条是用户**主动**反馈，登记与反馈一次做完，并打上「来自对话」的
     * 来源标记。</p>
     *
     * <p>路径是两段字面量，与 {@code GET /{id}}（一段）和 {@code POST /{id}/feedback}
     * （方法也不同）都不冲突。</p>
     */
    @PostMapping("/chat-feedback")
    public ApiResponse<GapDto> chatFeedback(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @Valid @RequestBody ChatFeedbackRequest request) {
        return ApiResponse.ok(gapService.chatFeedback(userId, request));
    }

    /**
     * 我在对话里反馈过的缺口（对话页据此回显「已反馈」）。
     *
     * <p>返回的是**自己**的反馈记录，不是运营数据，所以不设管理员闸门——与上面那个
     * 「登记即可，人人可为」是同一取向。</p>
     */
    @GetMapping("/feedback/mine")
    public ApiResponse<List<GapDto>> myFeedback(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId) {
        return ApiResponse.ok(gapService.myFeedback(userId));
    }

    /**
     * 人工标记「已解决」（管理员）。
     *
     * <p>与「补充文献 → 审核通过后自动结掉」是两条路：这条是管理员判定「不用补了」时手动收口，
     * 不关联任何文档。</p>
     */
    @PostMapping("/{id}/resolve")
    public ApiResponse<GapDto> resolve(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        requireOfficial(userId);
        return ApiResponse.ok(gapService.resolve(id));
    }

    /** 单条缺口：文献补录工作台据此显示「在补哪个缺口」并预填关键词 */
    @GetMapping("/{id}")
    public ApiResponse<GapDto> get(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        requireOfficial(userId);
        return ApiResponse.ok(gapService.get(id));
    }

    /**
     * 榜单。
     *
     * @param sort ask（默认，热门话题）/ feedback（未解决话题）
     */
    @GetMapping
    public ApiResponse<List<GapDto>> list(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "limit", required = false) Integer limit) {
        requireOfficial(userId);
        return ApiResponse.ok(gapService.list(sort, limit));
    }

    /** 复用知识库那套「官方账号白名单」（application.properties 的 app.kb.official-user-ids） */
    private void requireOfficial(Long userId) {
        if (!kbService.isOfficial(userId)) {
            throw new BizException(403, "仅管理员可查看需求分析");
        }
    }
}
