package com.backend.search.controller;

import com.backend.auth.AuthController;
import com.backend.common.ApiResponse;
import com.backend.search.dto.RecordSearchRequest;
import com.backend.search.dto.SearchHistoryDto;
import com.backend.search.service.SearchHistoryService;
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
 * 高级检索历史接口。所有接口均需登录（AuthInterceptor 统一鉴权），且**只作用于当前登录用户**——
 * 三个方法都不收 userId 参数，用户身份只从注入的登录态里取，接口形状上就不给越权留口子。
 *
 * <p>与「动态」的差别是有意的：动态是公开内容（谁都能看），检索历史是私有痕迹（只有自己能看），
 * 所以这里没有「按 userId 查看他人」的入口。</p>
 */
@RestController
@RequestMapping("/api/search-history")
public class SearchHistoryController {

    private final SearchHistoryService searchHistoryService;

    public SearchHistoryController(SearchHistoryService searchHistoryService) {
        this.searchHistoryService = searchHistoryService;
    }

    /**
     * 记录 / 刷新一条检索历史（幂等）。
     *
     * <p>前端在检索结束时调用，把「民族 + 疾病 + 方面 + 结果快照」一起提交。
     * 同一个组合重复检索只刷新时间与快照，不会在侧栏里堆出多条。</p>
     */
    @PostMapping
    public ApiResponse<SearchHistoryDto> record(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @Valid @RequestBody RecordSearchRequest request) {
        return ApiResponse.ok(searchHistoryService.record(userId, request));
    }

    /** 我的检索历史（按最后一次检索时间倒序） */
    @GetMapping
    public ApiResponse<List<SearchHistoryDto>> list(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(searchHistoryService.list(userId, limit));
    }

    /** 删除一条自己的检索历史 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        searchHistoryService.delete(userId, id);
        return ApiResponse.ok();
    }

    // ---------- 检索记录收藏（私有书签） ----------
    // 路径刻意与「收藏动态」(/api/favorites) 分开：收藏对象不同，语义也不同。
    // 放在本控制器而不是 FavoriteController，是因为被收藏的对象与返回类型都是 SearchHistoryDto，
    // 挂到 social 包下会让 SocialService 反向依赖 search 包。

    /** 收藏一条检索记录（幂等） */
    @PostMapping("/{id}/favorite")
    public ApiResponse<Void> favorite(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        searchHistoryService.favorite(userId, id);
        return ApiResponse.ok();
    }

    /** 取消收藏一条检索记录 */
    @DeleteMapping("/{id}/favorite")
    public ApiResponse<Void> unfavorite(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        searchHistoryService.unfavorite(userId, id);
        return ApiResponse.ok();
    }

    /**
     * 我收藏的检索记录（按收藏时间倒序）。
     *
     * <p>注意这条要看得比 {@code /{id}} 更具体——Spring 的路径匹配中 {@code /favorites}
     * 会优先命中字面量而不是 {@code /{id}} 模板，所以不存在把 "favorites" 当 id 解析的问题。</p>
     */
    @GetMapping("/favorites")
    public ApiResponse<List<SearchHistoryDto>> favorites(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId) {
        return ApiResponse.ok(searchHistoryService.favorites(userId));
    }

    /** 我收藏的检索记录ID（列表页「已收藏」状态回显） */
    @GetMapping("/favorites/ids")
    public ApiResponse<List<Long>> favoriteIds(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId) {
        return ApiResponse.ok(searchHistoryService.favoriteIds(userId));
    }
}
