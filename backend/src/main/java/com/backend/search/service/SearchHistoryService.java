package com.backend.search.service;

import com.backend.common.BizException;
import com.backend.search.dao.SearchHistoryDao;
import com.backend.search.dto.RecordSearchRequest;
import com.backend.search.dto.SearchHistoryDto;
import com.backend.search.entity.SearchHistory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 高级检索历史服务。
 *
 * <p>权限约定：检索历史是**私有**的——只能看到 / 删除自己的记录。列表不带 userId 参数，
 * 一律取登录态里的那个，从接口形状上就不给越权留口子（对比「动态」是有意公开的）。</p>
 *
 * <p>写入是**幂等 upsert**：键为「用户 + 民族 + 疾病 + 方面」，重复检索同一组合只刷新
 * 时间与结果快照。所以客户端不需要、也拿不到「新增还是覆盖」的选择权。</p>
 */
@Service
public class SearchHistoryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final SearchHistoryDao searchHistoryDao;
    // Boot 4 下 ObjectMapper 不自动注册为 Bean（仅 MVC 转换器可用），此处自建实例，同 SocialService
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SearchHistoryService(SearchHistoryDao searchHistoryDao) {
        this.searchHistoryDao = searchHistoryDao;
    }

    /**
     * 记录一次检索（同一组合则刷新）。
     *
     * <p>空值校验交给 {@link RecordSearchRequest} 上的 {@code @NotBlank}——这里只做 trim，
     * 不再重复判空：注解已经拦在入口，再写一遍就是永远不会走到的分支。</p>
     */
    public SearchHistoryDto record(Long userId, RecordSearchRequest request) {
        String ethnicity = request.ethnicity().trim();
        String disease = request.disease().trim();
        String intent = request.intent().trim();
        String detailJson = toJson(request.detail());

        Optional<SearchHistory> existed = searchHistoryDao.findBySlots(userId, ethnicity, disease, intent);
        if (existed.isPresent()) {
            return refresh(existed.get().getId(), detailJson);
        }

        try {
            return toDto(searchHistoryDao.insert(userId, ethnicity, disease, intent, detailJson));
        } catch (DuplicateKeyException e) {
            // 并发写入撞上唯一约束：另一个请求刚建好了，改为刷新那一条（同 SocialService.create 的兜底思路）。
            // 前端在一次检索结束时只发一个请求，这里是数据库层的最后一道防线。
            SearchHistory h = searchHistoryDao.findBySlots(userId, ethnicity, disease, intent)
                    .orElseThrow(() -> new BizException(500, "保存检索记录失败，请重试"));
            return refresh(h.getId(), detailJson);
        }
    }

    /** 我的检索历史（按最后一次检索时间倒序） */
    public List<SearchHistoryDto> list(Long userId, Integer limit) {
        List<SearchHistory> rows = searchHistoryDao.listByUser(userId, normalizeLimit(limit));
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<SearchHistoryDto> out = new ArrayList<>(rows.size());
        for (SearchHistory h : rows) {
            out.add(toDto(h));
        }
        return out;
    }

    /** 删除一条自己的记录；不是自己的（或已不存在）一律 404，不区分这两种情况 */
    public void delete(Long userId, Long id) {
        if (searchHistoryDao.delete(userId, id) == 0) {
            throw new BizException(404, "检索记录不存在");
        }
    }

    // ---------- 检索记录收藏（私有书签） ----------
    // 与「会话收藏」是同一套语义：只写书签，不公开发布。

    /** 收藏一条检索记录（幂等）。先校验归属，避免把别人的记录 id 收进自己的收藏夹。 */
    public void favorite(Long userId, Long searchHistoryId) {
        if (!searchHistoryDao.existsForUser(userId, searchHistoryId)) {
            throw new BizException(404, "检索记录不存在");
        }
        searchHistoryDao.addFavorite(userId, searchHistoryId);
    }

    /** 取消收藏（只删收藏关系，不动检索记录本身） */
    public void unfavorite(Long userId, Long searchHistoryId) {
        searchHistoryDao.removeFavorite(userId, searchHistoryId);
    }

    /** 我收藏的检索记录ID，供列表页按钮回显 */
    public List<Long> favoriteIds(Long userId) {
        return searchHistoryDao.favoriteIdsOf(userId);
    }

    /**
     * 我收藏的检索记录（「个人中心 - 我的收藏」用），按**收藏时间**倒序。
     *
     * <p>刻意不复用 {@link #list}：那是按 updated_at 排的（最后一次检索时间），
     * 与「什么时候收藏的」是两条不同的时间线，拿它排会让刚收藏的旧记录沉在下面。</p>
     *
     * <p>用 listByIds 而不是「拉全部再过滤」：那只在记录数不超过分页上限时才对，
     * 记录攒多了以后，收藏得早的那条会莫名从收藏夹里消失。</p>
     */
    public List<SearchHistoryDto> favorites(Long userId) {
        List<Long> ids = searchHistoryDao.favoriteIdsOf(userId);
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, SearchHistory> byId = new HashMap<>();
        for (SearchHistory h : searchHistoryDao.listByIds(userId, ids)) {
            byId.put(h.getId(), h);
        }
        List<SearchHistoryDto> out = new ArrayList<>(ids.size());
        for (Long id : ids) {              // 按收藏时间倒序
            SearchHistory h = byId.get(id);
            if (h != null) {               // 记录已被删除 → 跳过（收藏关系留着无害）
                out.add(toDto(h));
            }
        }
        return out;
    }

    /**
     * 刷新快照并把 updated_at 顶到当前时间。
     *
     * <p>写完**回读一次**再返回：updated_at 是数据库的 CURRENT_TIMESTAMP 写的，
     * 在内存里自己猜一个 now() 会与实际落库的值有毫秒级偏差，而侧栏正是按这个字段排序——
     * 返回一个偏早的时间，前端拿它插入列表就可能没被顶到最前，表现为「刚搜过的记录沉下去了」。</p>
     */
    private SearchHistoryDto refresh(Long id, String detailJson) {
        searchHistoryDao.updateSnapshot(id, detailJson);
        return toDto(searchHistoryDao.findById(id)
                .orElseThrow(() -> new BizException(500, "保存检索记录失败，请重试")));
    }

    private SearchHistoryDto toDto(SearchHistory h) {
        return new SearchHistoryDto(
                h.getId(),
                h.getEthnicity(),
                h.getDisease(),
                h.getIntent(),
                h.getDetailJson(),
                h.getCreatedAt(),
                h.getUpdatedAt());
    }

    private String toJson(Object detail) {
        if (detail == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
