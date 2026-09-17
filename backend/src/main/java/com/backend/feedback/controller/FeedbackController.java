package com.backend.feedback.controller;

import com.backend.auth.AuthController;
import com.backend.common.ApiResponse;
import com.backend.common.BizException;
import com.backend.feedback.dto.CreateFeedbackRequest;
import com.backend.feedback.dto.FeedbackDto;
import com.backend.feedback.dto.SetFeedbackStatusRequest;
import com.backend.feedback.dto.ToGapRequest;
import com.backend.feedback.service.FeedbackService;
import com.backend.kb.KbService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 用户反馈留言板接口。
 *
 * <p>列表与发布**允许匿名**（这是全站唯一这样的写接口）：页面公开，未登录也能看和发。
 * 实现上靠 {@code app.auth.optional-paths=/api/feedback} —— 拦截器仍会解析 token，
 * 带了有效 token 就注入 userId，没带就放行当匿名，所以这里两个方法都用
 * {@code required = false} 接收，匿名时拿到 null。</p>
 *
 * <p>注意模式只匹配 {@code /api/feedback} 本身：下面点赞 / 改状态 / 转缺口都是更深一层的路径，
 * 不在可选登录范围内，仍然强制登录。点赞必须有身份——「同一用户只能点一次」没有身份就无从去重。</p>
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final KbService kbService;

    public FeedbackController(FeedbackService feedbackService, KbService kbService) {
        this.feedbackService = feedbackService;
        this.kbService = kbService;
    }

    /**
     * 留言板列表，按点赞数倒序。
     *
     * <p>匿名请求也能拿到全部内容，只是 {@code liked} 恒为 false —— 未登录没有「我点过没有」可言。</p>
     */
    @GetMapping
    public ApiResponse<List<FeedbackDto>> list(
            @RequestAttribute(value = AuthController.ATTR_USER_ID, required = false) Long userId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(feedbackService.list(userId, limit));
    }

    /** 发布一条反馈。未登录时记匿名（user_id 存 NULL），登录则记到本人名下。 */
    @PostMapping
    public ApiResponse<FeedbackDto> create(
            @RequestAttribute(value = AuthController.ATTR_USER_ID, required = false) Long userId,
            @Valid @RequestBody CreateFeedbackRequest request) {
        return ApiResponse.ok(feedbackService.create(userId, request));
    }

    /** 点赞（幂等，重复点不会重复计数） */
    @PostMapping("/{id}/like")
    public ApiResponse<FeedbackDto> like(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        return ApiResponse.ok(feedbackService.like(userId, id));
    }

    /** 取消点赞 */
    @DeleteMapping("/{id}/like")
    public ApiResponse<FeedbackDto> unlike(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        return ApiResponse.ok(feedbackService.unlike(userId, id));
    }

    /**
     * 删除一条反馈（发布者本人或管理员）。
     *
     * <p>路径 {@code /{id}} 比可选登录白名单里的 {@code /api/feedback} 深一层，不在放行范围内，
     * 所以**一定要求登录**——删除必须有身份。</p>
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        feedbackService.delete(userId, id, kbService.isOfficial(userId));
        return ApiResponse.ok();
    }

    /** 改反馈状态（管理员） */
    @PostMapping("/{id}/status")
    public ApiResponse<FeedbackDto> setStatus(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id,
            @Valid @RequestBody SetFeedbackStatusRequest request) {
        requireOfficial(userId);
        return ApiResponse.ok(feedbackService.setStatus(id, request.status()));
    }

    /**
     * 一键转为知识缺口（管理员）。
     *
     * <p>三个槽位由前端从反馈原文里识别后传上来（见 {@link ToGapRequest} 的说明）。</p>
     */
    @PostMapping("/{id}/to-gap")
    public ApiResponse<FeedbackDto> toGap(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id,
            @Valid @RequestBody ToGapRequest request) {
        requireOfficial(userId);
        return ApiResponse.ok(feedbackService.toGap(id, request));
    }

    /** 复用知识库那套「官方账号白名单」（application.properties 的 app.kb.official-user-ids） */
    private void requireOfficial(Long userId) {
        if (!kbService.isOfficial(userId)) {
            throw new BizException(403, "仅管理员可管理用户反馈");
        }
    }
}
