package com.backend.social.service;

import com.backend.auth.dao.UserDao;
import com.backend.auth.entity.User;
import com.backend.chat.dto.ConversationDto;
import com.backend.chat.service.ConversationService;
import com.backend.common.BizException;
import com.backend.social.dao.SocialDao;
import com.backend.social.dto.CreateDynamicRequest;
import com.backend.social.dto.DynamicDto;
import com.backend.social.dto.PublishQaCardRequest;
import com.backend.social.entity.Dynamic;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 动态（分享）/ 收藏服务。
 *
 * <p>权限约定：
 * <ul>
 *   <li>动态对所有登录用户公开（查看 / 收藏）；</li>
 *   <li>只有发布者本人可以删除自己的动态；</li>
 *   <li>每个用户只能看到 / 操作自己的收藏关系。</li>
 * </ul>
 */
@Service
public class SocialService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int TITLE_MAX = 60;

    private final SocialDao socialDao;
    private final UserDao userDao;
    private final ConversationService conversationService;
    // Boot 4 下 ObjectMapper 不自动注册为 Bean（仅 MVC 转换器可用），此处自建实例
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SocialService(SocialDao socialDao, UserDao userDao, ConversationService conversationService) {
        this.socialDao = socialDao;
        this.userDao = userDao;
        this.conversationService = conversationService;
    }

    // ---------- 动态 ----------

    /**
     * 新增动态：由某条 AI 回答转存而来。
     * 幂等 —— 同一用户对同一条 AI 回答重复添加时，直接返回已有动态（不会产生重复数据）。
     */
    public DynamicDto create(Long userId, CreateDynamicRequest request) {
        String content = request.content() == null ? "" : request.content().trim();
        if (content.isBlank()) {
            throw new BizException(400, "分享内容不能为空");
        }
        // 来源会话必须属于当前用户，避免越权引用他人会话
        if (request.conversationId() != null) {
            conversationService.assertOwned(userId, request.conversationId());
        }
        String title = buildTitle(request.title(), content);
        String detailJson = toJson(request.detail());

        // 整段对话打包分享：幂等键是**会话**（此时没有单条 messageId）。
        // 查「含已删除」的记录——唯一约束 (user_id, source_conversation_id) 连软删除的行一起占位，
        // 所以重新发布同一会话时不能再插一条，要把它复活（updateDynamic 会把 status 置回 normal）。
        // 重复发布时刷新内容而不是原样返回：用户分享后往往还会继续追问，
        // 不刷新的话公开出去的动态会永远停在第一次分享那一刻。
        if (request.messageId() == null && request.conversationId() != null) {
            Optional<Dynamic> existed = socialDao.findAnyByUserAndConversation(userId, request.conversationId());
            if (existed.isPresent()) {
                Dynamic d = existed.get();
                socialDao.updateDynamic(d.getId(), title, content, detailJson);
                d.setTitle(title);
                d.setContent(content);
                d.setDetailJson(detailJson);
                d.setStatus(SocialDao.STATUS_NORMAL);
                return toDto(d, userId, false, 0);
            }
        }

        // 单条回答分享（旧路径，保留兼容）
        Long messageId = request.messageId();
        if (messageId != null) {
            Optional<Dynamic> existed = socialDao.findByUserAndMessage(userId, messageId);
            if (existed.isPresent()) {
                return toDto(existed.get(), userId, false, 0);
            }
        }

        try {
            Dynamic d = socialDao.insertDynamic(userId, request.conversationId(), messageId,
                    SocialDao.KIND_DIALOGUE, null, title, content, detailJson);
            return toDto(d, userId, false, 0);
        } catch (DuplicateKeyException e) {
            // 并发插入撞上唯一约束：另一个请求刚建好了，改为更新那一条。
            // 前端已用加载锁挡住连点，这里是数据库层的最后一道防线。
            if (request.conversationId() != null) {
                Dynamic d = socialDao.findAnyByUserAndConversation(userId, request.conversationId())
                        .orElseThrow(() -> new BizException(500, "发布失败，请重试"));
                socialDao.updateDynamic(d.getId(), title, content, detailJson);
                d.setTitle(title);
                d.setContent(content);
                d.setDetailJson(detailJson);
                d.setStatus(SocialDao.STATUS_NORMAL);
                return toDto(d, userId, false, 0);
            }
            throw new BizException(500, "发布失败，请重试");
        }
    }

    /** 公开动态列表（所有用户发布的正常状态动态，按发布时间倒序） */
    /** 公开动态列表（游标分页，按发布时间倒序） */
    public List<DynamicDto> list(Long userId, Integer limit, Long beforeId, String kind) {
        return toDtos(socialDao.listNormal(normalizeLimit(limit), beforeId, normalizeKind(kind)), userId);
    }

    /**
     * 发布 / 刷新一张「一问一答」卡片。
     *
     * <p>幂等键是 {@code qaKey}（「民族|疾病|意图」），且**跨用户全局唯一**：同一组合谁先分享谁占位，
     * 后来者只刷新标题与内容，不新增第二张卡。与 {@link #create} 的「用户 + 会话」幂等是两套键，
     * 所以这里用 {@code findByQaKey} 而不是带 user_id 的查询。</p>
     *
     * <p>{@code user_id} 保持首次分享者不变：卡片内容来自同一套知识库，换个人分享就改归属
     * 只会让「谁分享的」这个信息莫名其妙地跳。</p>
     */
    public DynamicDto publishQaCard(Long userId, PublishQaCardRequest request) {
        String qaKey = request.qaKey() == null ? "" : request.qaKey().trim();
        if (qaKey.isBlank()) {
            throw new BizException(400, "问答卡缺少标识");
        }
        String content = request.content() == null ? "" : request.content().trim();
        if (content.isBlank()) {
            throw new BizException(400, "分享内容不能为空");
        }
        String title = buildTitle(request.title(), content);
        String detailJson = toJson(request.detail());

        // 查「含已删除」：唯一约束 uk_dynamic_qa 连软删除的行一起占位，
        // 重新分享同一组合时不能再插一条，要把旧的那条复活（updateDynamic 会把 status 置回 normal）。
        Optional<Dynamic> existed = socialDao.findByQaKey(qaKey);
        if (existed.isPresent()) {
            return refreshQaCard(existed.get(), userId, title, content, detailJson);
        }

        try {
            Dynamic d = socialDao.insertDynamic(userId, null, null,
                    SocialDao.KIND_QA, qaKey, title, content, detailJson);
            return toDto(d, userId, false, 0);
        } catch (DuplicateKeyException e) {
            // 并发插入撞上唯一约束：另一个请求刚建好了，改为刷新那一条（同 create 的兜底思路）
            Dynamic d = socialDao.findByQaKey(qaKey)
                    .orElseThrow(() -> new BizException(500, "发布失败，请重试"));
            return refreshQaCard(d, userId, title, content, detailJson);
        }
    }

    /** 刷新问答卡的标题 / 内容 / 明细，并复活的软删除状态；返回带真实收藏状态的 DTO */
    private DynamicDto refreshQaCard(Dynamic d, Long userId, String title, String content, String detailJson) {
        socialDao.updateDynamic(d.getId(), title, content, detailJson);
        d.setTitle(title);
        d.setContent(content);
        d.setDetailJson(detailJson);
        d.setStatus(SocialDao.STATUS_NORMAL);
        return toDto(d, userId,
                socialDao.existsFavorite(userId, d.getId()),
                socialDao.countFavorites(List.of(d.getId())).getOrDefault(d.getId(), 0));
    }

    /** 我的分享 */
    public List<DynamicDto> mine(Long userId, Integer limit) {
        return toDtos(socialDao.listByUser(userId, normalizeLimit(limit)), userId);
    }

    /**
     * 某个用户的公开分享（个人主页展示他人时用）。
     *
     * 注意两个 userId 不是一回事：`targetUserId` 是**被查看的人**，
     * `currentUserId` 是**当前登录的人**——owner / favorited 必须相对后者算，
     * 否则会把别人的动态误判成「我的」，或让收藏状态张冠李戴。
     */
    public List<DynamicDto> listByAuthor(Long targetUserId, Long currentUserId, Integer limit) {
        return toDtos(socialDao.listByUser(targetUserId, normalizeLimit(limit)), currentUserId);
    }

    /** 动态详情（所有登录用户可查看） */
    public DynamicDto detail(Long userId, Long id) {
        Dynamic d = socialDao.findDynamicById(id)
                .orElseThrow(() -> new BizException(404, "动态不存在或已删除"));
        if (!SocialDao.STATUS_NORMAL.equals(d.getStatus())) {
            throw new BizException(404, "动态不存在或已删除");
        }
        return toDto(d, userId, socialDao.existsFavorite(userId, id), socialDao.countFavorites(List.of(id)).getOrDefault(id, 0));
    }

    /** 删除动态（仅发布者本人） */
    public void delete(Long userId, Long id) {
        Dynamic d = socialDao.findDynamicById(id)
                .orElseThrow(() -> new BizException(404, "动态不存在"));
        if (!d.getUserId().equals(userId)) {
            throw new BizException(403, "只能删除自己发布的动态");
        }
        socialDao.softDelete(id);
    }

    /** 当前用户已添加为动态的来源消息ID（用于前端「已添加」状态回显） */
    public List<Long> addedMessageIds(Long userId, Long conversationId) {
        return socialDao.addedMessageIds(userId, conversationId);
    }

    // ---------- 会话收藏（私有书签） ----------
    // 与上面的「动态收藏」刻意分开：
    //   动态收藏 = 收藏**别人公开分享的动态**（user_favorite）
    //   会话收藏 = 收藏**自己的某段对话**，不公开发布（user_conversation_favorite）
    // 早先没有后者，收藏对话只能先转存成动态再收藏，于是「点收藏」会把内容公开出去。

    /** 收藏一段对话（幂等）。只写 user_conversation_favorite，不创建动态。 */
    public void favoriteConversation(Long userId, Long conversationId) {
        conversationService.assertOwned(userId, conversationId);
        socialDao.addConversationFavorite(userId, conversationId);
    }

    /** 取消收藏一段对话（只删收藏关系，不动会话本身） */
    public void unfavoriteConversation(Long userId, Long conversationId) {
        socialDao.removeConversationFavorite(userId, conversationId);
    }

    /** 当前用户收藏的会话ID */
    public List<Long> conversationFavoriteIds(Long userId) {
        return socialDao.conversationFavoriteIdsOf(userId);
    }

    /**
     * 当前用户收藏的会话摘要（「个人中心 - 我的收藏」用）。
     * 复用 conversationService.list 再按收藏的 id 过滤——收藏量小，不值得单开一条 join 查询。
     */
    public List<ConversationDto> conversationFavorites(Long userId) {
        List<Long> ids = socialDao.conversationFavoriteIdsOf(userId);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, ConversationDto> all = new java.util.HashMap<>();
        for (ConversationDto c : conversationService.list(userId)) {
            all.put(c.id(), c);
        }
        List<ConversationDto> out = new ArrayList<>();
        for (Long id : ids) {           // 按收藏时间倒序
            ConversationDto c = all.get(id);
            if (c != null) {            // 会话已被删除 → 跳过（收藏关系留着无害）
                out.add(c);
            }
        }
        return out;
    }

    // ---------- 收藏 ----------

    /** 收藏动态（幂等：重复收藏不会产生重复记录） */
    public DynamicDto favorite(Long userId, Long dynamicId) {
        requireVisible(dynamicId);
        socialDao.addFavorite(userId, dynamicId);
        return detail(userId, dynamicId);
    }

    /** 取消收藏（只删除收藏关系，不删除原动态） */
    public void unfavorite(Long userId, Long dynamicId) {
        socialDao.removeFavorite(userId, dynamicId);
    }

    /** 我的收藏（按收藏时间倒序） */
    public List<DynamicDto> myFavorites(Long userId) {
        List<Long> ids = socialDao.favoriteIdsOf(userId);
        List<Dynamic> list = new ArrayList<>();
        for (Long id : ids) {
            socialDao.findDynamicById(id).ifPresent(d -> {
                if (SocialDao.STATUS_NORMAL.equals(d.getStatus())) {
                    list.add(d);
                }
            });
        }
        return toDtos(list, userId);
    }

    /** 当前用户已收藏的动态ID（列表页「已收藏」状态回显） */
    public List<Long> myFavoriteIds(Long userId) {
        return socialDao.favoriteIdsOf(userId);
    }

    // ---------- 内部方法 ----------

    private void requireVisible(Long dynamicId) {
        Dynamic d = socialDao.findDynamicById(dynamicId)
                .orElseThrow(() -> new BizException(404, "动态不存在或已删除"));
        if (!SocialDao.STATUS_NORMAL.equals(d.getStatus())) {
            throw new BizException(404, "动态不存在或已删除");
        }
    }

    private List<DynamicDto> toDtos(List<Dynamic> list, Long currentUserId) {
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = list.stream().map(Dynamic::getId).toList();
        Map<Long, Integer> counts = socialDao.countFavorites(ids);
        Set<Long> faved = socialDao.favoriteIdsOf(currentUserId, ids);
        List<DynamicDto> out = new ArrayList<>(list.size());
        for (Dynamic d : list) {
            out.add(toDto(d, currentUserId, faved.contains(d.getId()), counts.getOrDefault(d.getId(), 0)));
        }
        return out;
    }

    private DynamicDto toDto(Dynamic d, Long currentUserId, boolean favorited, int favoriteCount) {
        String username = null;
        String nickname = null;
        String avatar = null;
        Optional<User> u = userDao.findById(d.getUserId());
        if (u.isPresent()) {
            username = u.get().getUsername();
            nickname = u.get().getNickname();
            avatar = u.get().getAvatar();
        }
        return new DynamicDto(
                d.getId(),
                d.getUserId(),
                username,
                nickname,
                avatar,
                d.getSourceConversationId(),
                d.getSourceMessageId(),
                d.getTitle(),
                d.getContent(),
                d.getDetailJson(),
                // 列是后加的，历史行可能为 NULL——对外一律呈现成 dialogue，前端不必再兜底
                d.getKind() == null || d.getKind().isBlank() ? SocialDao.KIND_DIALOGUE : d.getKind(),
                d.getQaKey(),
                favoriteCount,
                favorited,
                currentUserId != null && currentUserId.equals(d.getUserId()),
                d.getCreatedAt(),
                d.getUpdatedAt());
    }

    /**
     * 只认 dialogue / qa 两个值，其余（含 null）一律当「不筛」。
     * 传个拼错的 kind 返回全部，比返回空列表或报 400 都更不容易让人卡住。
     */
    private String normalizeKind(String kind) {
        if (SocialDao.KIND_QA.equals(kind) || SocialDao.KIND_DIALOGUE.equals(kind)) {
            return kind;
        }
        return null;
    }

    private String buildTitle(String title, String content) {
        if (title != null && !title.isBlank()) {
            String t = title.trim().replaceAll("\\s+", " ");
            return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX) + "…";
        }
        String c = content.trim().replaceAll("\\s+", " ");
        return c.length() <= TITLE_MAX ? c : c.substring(0, TITLE_MAX) + "…";
    }

    private String toJson(Object detail) {
        if (detail == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            return detail.toString();
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
