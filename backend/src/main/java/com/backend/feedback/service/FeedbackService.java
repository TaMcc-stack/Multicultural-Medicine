package com.backend.feedback.service;

import com.backend.auth.dao.UserDao;
import com.backend.auth.entity.User;
import com.backend.common.BizException;
import com.backend.feedback.dao.FeedbackDao;
import com.backend.feedback.dto.CreateFeedbackRequest;
import com.backend.feedback.dto.FeedbackDto;
import com.backend.feedback.dto.ToGapRequest;
import com.backend.feedback.entity.UserFeedback;
import com.backend.gap.dao.GapDao;
import com.backend.gap.dao.GapOrigin;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 用户反馈留言板。
 *
 * <p>权限约定：**读与写对所有人开放，包括未登录**（页面公开，见 {@code app.auth.optional-paths}）；
 * 点赞需要登录（去重要有身份）；改状态与转缺口是管理员操作。</p>
 */
@Service
public class FeedbackService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 300;

    /** 合法状态。以白名单为准而不是注解，改一处即可，报错里也能顺带把合法取值说清楚。 */
    private static final Set<String> STATUSES = Set.of("pending", "processing", "done");
    private static final String STATUS_HINT = "pending（待补充）/ processing（处理中）/ done（已补充）";

    /** 转成缺口时的默认「想了解的方面」。反馈是自由文本，说不出用户想问哪个方面。 */
    private static final String DEFAULT_INTENT = "all";

    private final FeedbackDao feedbackDao;
    private final UserDao userDao;
    // 转缺口只用到 gap 的 DAO，不注入 GapService：与 KbService 依赖 gap.dao 的先例一致，
    // 避免 service → service 的横向依赖
    private final GapDao gapDao;

    public FeedbackService(FeedbackDao feedbackDao, UserDao userDao, GapDao gapDao) {
        this.feedbackDao = feedbackDao;
        this.userDao = userDao;
        this.gapDao = gapDao;
    }

    /** 留言板列表（点赞数倒序）。{@code currentUserId} 为 null（匿名访客）时不回显点赞状态。 */
    public List<FeedbackDto> list(Long currentUserId, Integer limit) {
        List<UserFeedback> rows = feedbackDao.list(normalizeLimit(limit));
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = rows.stream().map(UserFeedback::getId).toList();
        Set<Long> liked = feedbackDao.likedIdsOf(currentUserId, ids);
        List<FeedbackDto> out = new ArrayList<>(rows.size());
        for (UserFeedback f : rows) {
            out.add(toDto(f, currentUserId, liked.contains(f.getId())));
        }
        return out;
    }

    /**
     * 发布一条反馈。{@code userId} 为 null 表示匿名发布。
     *
     * <p>{@code @NotBlank} 只挡 null 与空串，挡不住纯空白串，所以这里再 trim 一次兜底——
     * 与 {@code SocialService.create} 同一处理。</p>
     */
    public FeedbackDto create(Long userId, CreateFeedbackRequest request) {
        String content = request.content() == null ? "" : request.content().trim();
        if (content.isBlank()) {
            throw new BizException(400, "反馈内容不能为空");
        }
        return toDto(feedbackDao.insert(userId, content), userId, false);
    }

    /** 点赞（幂等：已点过再点不报错，也不重复计数） */
    public FeedbackDto like(Long userId, Long feedbackId) {
        requireExists(feedbackId);
        if (feedbackDao.addLike(feedbackId, userId)) {
            feedbackDao.refreshLikeCount(feedbackId);
        }
        // 无论是否新增都回读：前端据此把按钮切成「已点赞」，不必自己猜
        return read(feedbackId, userId);
    }

    /** 取消点赞（行不存在也静默成功） */
    public FeedbackDto unlike(Long userId, Long feedbackId) {
        requireExists(feedbackId);
        feedbackDao.removeLike(feedbackId, userId);
        feedbackDao.refreshLikeCount(feedbackId);
        return read(feedbackId, userId);
    }

    /**
     * 删除一条反馈：**发布者本人或管理员**。
     *
     * <p>匿名帖（{@code userId} 为 null）没有本人可言，所以只有管理员能删——这正是需要的：
     * 否则匿名发出去的垃圾帖谁都能认领然后删掉别人的。</p>
     *
     * <p>是否管理员由调用方（Controller）判好传进来，而不是在这里注入 KbService：
     * 管理员是「官方账号白名单」这个知识库概念的判定，与反馈本身的规则不是一回事，
     * 让这一层只关心「谁能删自己发的」这一条规则，判据也更少一处漂移的机会。</p>
     */
    public void delete(Long requesterId, Long feedbackId, boolean admin) {
        UserFeedback f = feedbackDao.findById(feedbackId)
                .orElseThrow(() -> new BizException(404, "这条反馈不存在"));
        boolean owner = f.getUserId() != null && f.getUserId().equals(requesterId);
        if (!owner && !admin) {
            throw new BizException(403, "无权限删除这条反馈");
        }
        // 先清明细再删主行：反过来的话，中途失败就会留下再也对不上的点赞记录
        feedbackDao.deleteLikes(feedbackId);
        feedbackDao.delete(feedbackId);
    }

    /** 管理员改状态 */
    public FeedbackDto setStatus(Long feedbackId, String status) {        requireExists(feedbackId);
        String s = status == null ? "" : status.trim();
        if (!STATUSES.contains(s)) {
            throw new BizException(400, "状态只能是 " + STATUS_HINT);
        }
        feedbackDao.updateStatus(feedbackId, s);
        return read(feedbackId, null);
    }

    /**
     * 管理员把一条反馈转为知识缺口。
     *
     * <p>{@code touchAsk} 本身就是幂等的（{@code ON DUPLICATE KEY UPDATE ask_count + 1}），
     * 所以同一条反馈重复转不会建出第二个缺口，只是那条缺口的「被问次数」再 +1——
     * 这在语义上也说得通：又一个人想要这个。</p>
     *
     * <p>转完把反馈状态置为「处理中」：它确实进入了补录流程。管理员仍可再手改。</p>
     */
    public FeedbackDto toGap(Long feedbackId, ToGapRequest request) {
        requireExists(feedbackId);
        String ethnicity = request.ethnicity().trim();
        String disease = request.disease().trim();
        // 需求里「一键转」只需要前两个槽位，意图统一落到兜底档（不指向某个方面）
        String intent = request.intent() == null || request.intent().isBlank()
                ? DEFAULT_INTENT : request.intent().trim();

        gapDao.touchAsk(ethnicity, disease, intent, GapOrigin.BOARD, null);
        Long gapId = gapDao.findBySlots(ethnicity, disease, intent)
                .orElseThrow(() -> new BizException(500, "转为知识缺口失败，请重试"))
                .getId();

        feedbackDao.setGapId(feedbackId, gapId);
        feedbackDao.updateStatus(feedbackId, "processing");
        return read(feedbackId, null);
    }

    // ---------- 内部 ----------

    private void requireExists(Long feedbackId) {
        if (feedbackDao.findById(feedbackId).isEmpty()) {
            throw new BizException(404, "这条反馈不存在");
        }
    }

    /** 回读一条并组装 DTO（点赞状态按传入的用户算） */
    private FeedbackDto read(Long feedbackId, Long currentUserId) {
        UserFeedback f = feedbackDao.findById(feedbackId)
                .orElseThrow(() -> new BizException(404, "这条反馈不存在"));
        boolean liked = currentUserId != null && feedbackDao.likedIdsOf(currentUserId, List.of(feedbackId))
                .contains(feedbackId);
        return toDto(f, currentUserId, liked);
    }

    /**
     * 组装 DTO。
     *
     * <p>作者信息逐条查用户表（N+1）。与 {@code SocialService.toDto} 同一取舍：本项目是 POC，
     * 列表上限也就几百条，为它加一条 join 或批量查不值得。匿名帖（userId 为 null）三项留空，
     * 由前端显示「匿名用户」。</p>
     */
    private FeedbackDto toDto(UserFeedback f, Long currentUserId, boolean liked) {
        String username = null;
        String nickname = null;
        String avatar = null;
        if (f.getUserId() != null) {
            Optional<User> u = userDao.findById(f.getUserId());
            if (u.isPresent()) {
                username = u.get().getUsername();
                nickname = u.get().getNickname();
                avatar = u.get().getAvatar();
            }
        }
        return new FeedbackDto(
                f.getId(),
                f.getUserId(),
                username,
                nickname,
                avatar,
                f.getContent(),
                f.getStatus() == null || f.getStatus().isBlank() ? "pending" : f.getStatus(),
                f.getGapId(),
                f.getLikeCount(),
                liked,
                currentUserId != null && currentUserId.equals(f.getUserId()),
                f.getCreatedAt());
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
