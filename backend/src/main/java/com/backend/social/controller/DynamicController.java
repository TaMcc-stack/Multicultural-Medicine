package com.backend.social.controller;

import com.backend.auth.AuthController;
import com.backend.common.ApiResponse;
import com.backend.social.dto.CreateDynamicRequest;
import com.backend.social.dto.DynamicDto;
import com.backend.social.dto.PublishQaCardRequest;
import com.backend.social.service.SocialService;
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
 * 动态 / 分享接口：所有接口均需登录（AuthInterceptor 统一鉴权）。
 * 动态对所有登录用户公开可见，但只有发布者本人可以删除。
 */
@RestController
@RequestMapping("/api/dynamics")
public class DynamicController {

    private final SocialService socialService;

    public DynamicController(SocialService socialService) {
        this.socialService = socialService;
    }

    /**
     * 新增动态：把某条 AI 回答转存为公开分享。
     * 同一条 AI 回答同一用户重复添加时幂等返回已有动态。
     */
    @PostMapping
    public ApiResponse<DynamicDto> create(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @Valid @RequestBody CreateDynamicRequest request) {
        return ApiResponse.ok(socialService.create(userId, request));
    }

    /**
     * 发布 / 刷新一张「一问一答」卡片（高级检索页分享出来）。
     *
     * <p>路径是两段（/qa），与 /{id} 的详情、/{id} 的删除都不冲突——后者只匹配数字段。</p>
     * <p>幂等键 `qaKey`（「民族|疾病|意图」）**全局唯一**：同一组合重复分享是刷新内容，不新增卡片。</p>
     */
    @PostMapping("/qa")
    public ApiResponse<DynamicDto> publishQaCard(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @Valid @RequestBody PublishQaCardRequest request) {
        return ApiResponse.ok(socialService.publishQaCard(userId, request));
    }

    /**
     * 公开动态列表（按发布时间倒序，含收藏状态与收藏数；`before` 为游标，传上一页最后一条的 id）。
     * `kind` 过滤动态类型：不传 = 全部（对话 + 问答），传 qa 只看一问一答卡。
     */
    @GetMapping
    public ApiResponse<List<DynamicDto>> list(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "before", required = false) Long before,
            @RequestParam(value = "kind", required = false) String kind) {
        return ApiResponse.ok(socialService.list(userId, limit, before, kind));
    }

    /** 我的分享 */
    @GetMapping("/mine")
    public ApiResponse<List<DynamicDto>> mine(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(socialService.mine(userId, limit));
    }

    /**
     * 指定用户的公开分享（个人主页查看他人时用）。
     *
     * 路径是两段（/user/{userId}），与下面的 /{id} 不冲突——后者只匹配单段路径。
     * 动态本身对所有登录用户公开，这里只是换一个「按作者聚合」的入口。
     */
    @GetMapping("/user/{userId}")
    public ApiResponse<List<DynamicDto>> byUser(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long currentUserId,
            @PathVariable("userId") Long userId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(socialService.listByAuthor(userId, currentUserId, limit));
    }

    /** 当前用户已添加为动态的来源消息ID（前端「已添加」状态回显） */
    @GetMapping("/added")
    public ApiResponse<List<Long>> added(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @RequestParam(value = "conversationId", required = false) Long conversationId) {
        return ApiResponse.ok(socialService.addedMessageIds(userId, conversationId));
    }

    /** 动态详情 */
    @GetMapping("/{id}")
    public ApiResponse<DynamicDto> detail(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        return ApiResponse.ok(socialService.detail(userId, id));
    }

    /** 删除动态（仅发布者本人） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        socialService.delete(userId, id);
        return ApiResponse.ok();
    }
}
