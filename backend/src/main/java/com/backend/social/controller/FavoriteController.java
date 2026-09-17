package com.backend.social.controller;

import com.backend.auth.AuthController;
import com.backend.chat.dto.ConversationDto;
import com.backend.common.ApiResponse;
import com.backend.social.dto.ConversationFavoriteRequest;
import com.backend.social.dto.DynamicDto;
import com.backend.social.dto.FavoriteRequest;
import com.backend.social.service.SocialService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收藏接口：收藏关系按用户隔离，未登录由 AuthInterceptor 直接拦截（401）。
 */
@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    private final SocialService socialService;

    public FavoriteController(SocialService socialService) {
        this.socialService = socialService;
    }

    /** 收藏动态（幂等，重复收藏不会产生重复记录） */
    @PostMapping
    public ApiResponse<DynamicDto> favorite(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @Valid @RequestBody FavoriteRequest request) {
        return ApiResponse.ok(socialService.favorite(userId, request.dynamicId()));
    }

    /** 取消收藏（只删除收藏关系，不影响原动态） */
    @DeleteMapping("/{dynamicId}")
    public ApiResponse<Void> unfavorite(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("dynamicId") Long dynamicId) {
        socialService.unfavorite(userId, dynamicId);
        return ApiResponse.ok();
    }

    /** 我的收藏（动态列表，按收藏时间倒序） */
    @GetMapping
    public ApiResponse<List<DynamicDto>> myFavorites(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId) {
        return ApiResponse.ok(socialService.myFavorites(userId));
    }

    /** 我收藏过的动态ID（列表页「已收藏」状态回显） */
    @GetMapping("/ids")
    public ApiResponse<List<Long>> myFavoriteIds(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId) {
        return ApiResponse.ok(socialService.myFavoriteIds(userId));
    }

    // ---------- 会话收藏（私有书签） ----------
    // 路径刻意与上面的「动态收藏」分开：收藏对象不同，语义也不同
    // （动态收藏 = 收藏别人公开分享的内容；会话收藏 = 收藏自己的对话，不公开）。

    /** 收藏一段对话。只写私有书签，**不会**创建动态 */
    @PostMapping("/conversations")
    public ApiResponse<Void> favoriteConversation(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @Valid @RequestBody ConversationFavoriteRequest request) {
        socialService.favoriteConversation(userId, request.conversationId());
        return ApiResponse.ok();
    }

    /** 取消收藏一段对话 */
    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> unfavoriteConversation(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("conversationId") Long conversationId) {
        socialService.unfavoriteConversation(userId, conversationId);
        return ApiResponse.ok();
    }

    /** 我收藏的对话（会话摘要列表，按收藏时间倒序） */
    @GetMapping("/conversations")
    public ApiResponse<List<ConversationDto>> myConversationFavorites(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId) {
        return ApiResponse.ok(socialService.conversationFavorites(userId));
    }

    /** 我收藏的会话ID（对话页「已收藏」状态回显） */
    @GetMapping("/conversations/ids")
    public ApiResponse<List<Long>> myConversationFavoriteIds(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId) {
        return ApiResponse.ok(socialService.conversationFavoriteIds(userId));
    }
}
