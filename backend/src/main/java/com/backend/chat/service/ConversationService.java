package com.backend.chat.service;

import com.backend.chat.dao.ConversationDao;
import com.backend.chat.dto.ChatMessageDto;
import com.backend.chat.dto.ConversationDetail;
import com.backend.chat.dto.ConversationDto;
import com.backend.chat.entity.ChatMessage;
import com.backend.chat.entity.Conversation;
import com.backend.common.BizException;
import com.backend.qa.dto.AskRequest;
import com.backend.qa.dto.AskResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 会话服务：创建 / 列表 / 详情 / 重命名 / 删除 / 问答回合落库。
 * 所有操作都校验会话归属，用户只能访问自己的会话。
 */
@Service
public class ConversationService {

    private static final int TITLE_MAX = 24;

    private final ConversationDao conversationDao;
    // Boot 4 下 ObjectMapper 不自动注册为 Bean（仅 MVC 转换器可用），此处自建实例。
    // 关闭「未知字段报错」：detail 是前端持续演进的载荷，严格模式下多一个字段就会
    // 让整条历史消息反序列化失败（静默丢数据），这里一律宽容处理。
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ConversationService(ConversationDao conversationDao) {
        this.conversationDao = conversationDao;
    }

    public ConversationDto create(Long userId, String title) {
        String t = (title == null || title.isBlank()) ? "新对话" : title.trim();
        return toDto(conversationDao.insertConversation(userId, t));
    }

    public List<ConversationDto> list(Long userId) {
        return conversationDao.listByUser(userId).stream().map(this::toDto).toList();
    }

    public ConversationDetail detail(Long userId, Long conversationId) {
        Conversation c = requireOwned(userId, conversationId);
        List<ChatMessageDto> messages = conversationDao.listMessages(conversationId).stream()
                .map(this::toMessageDto)
                .toList();
        return new ConversationDetail(toDto(c), messages);
    }

    public ConversationDto rename(Long userId, Long conversationId, String title) {
        requireOwned(userId, conversationId);
        conversationDao.rename(conversationId, title.trim());
        return toDto(conversationDao.findConversationById(conversationId).orElseThrow());
    }

    public void delete(Long userId, Long conversationId) {
        requireOwned(userId, conversationId);
        conversationDao.deleteConversation(conversationId);
    }

    /**
     * 追加一条消息（由前端在生成回答后调用，仅持久化，不重新生成）。
     * 用于把本地生成的 user 问题 + assistant 完整响应写入 chat_message，
     * 使历史会话可完整回放。
     *
     * @return 落库后的消息（含自增 id），供「添加到动态」按来源消息溯源
     */
    public ChatMessageDto recordMessage(Long userId, Long conversationId, String role, String kind, String content, Object detail) {
        requireOwned(userId, conversationId);
        String detailJson = null;
        if (detail != null) {
            try {
                detailJson = objectMapper.writeValueAsString(detail);
            } catch (JsonProcessingException e) {
                detailJson = detail.toString();
            }
        }
        ChatMessage m = conversationDao.insertMessage(conversationId, role, kind, content, detailJson);
        conversationDao.touch(conversationId);
        return toMessageDto(m);
    }

    /** 校验会话归属（对外暴露，供动态等模块复用同一套归属校验，避免越权引用他人会话） */
    public void assertOwned(Long userId, Long conversationId) {
        requireOwned(userId, conversationId);
    }

    /**
     * 用户最近活跃的会话详情（含全部历史消息）。
     *
     * <p>供前端「刷新页面 / 重新进入对话页后恢复上一次的连续对话」使用。
     * {@code listByUser} 已按 updated_at 倒序，取第一条即最近活跃的那个。
     * 没有会话时返回 null。</p>
     */
    public ConversationDetail lastDetail(Long userId) {
        List<Conversation> list = conversationDao.listByUser(userId);
        if (list.isEmpty()) {
            return null;
        }
        return detail(userId, list.get(0).getId());
    }

    /**
     * 记录一轮问答：
     * - conversationId 为空时新建会话（标题取首问前若干字）
     * - 追加用户问题 + assistant 响应两条消息，刷新会话活跃时间
     * 返回最终会话 ID。
     */
    public Long recordTurn(Long userId, AskRequest request, AskResponse response) {
        Long conversationId = request.conversationId();
        if (conversationId == null) {
            Conversation c = conversationDao.insertConversation(userId, buildTitle(request.question()));
            conversationId = c.getId();
        } else {
            requireOwned(userId, conversationId);
        }

        conversationDao.insertMessage(conversationId, "user", "text", request.question().trim(), null);
        conversationDao.insertMessage(conversationId, "assistant", response.type(),
                assistantContent(response), toJson(response));
        conversationDao.touch(conversationId);
        return conversationId;
    }

    /**
     * 最近一次可用的问答上下文（最后一条带民族/疾病的 assistant 响应的 understanding）。
     * 用于指代式追问（如「那遗传相关研究呢？」）补全民族/疾病。
     */
    public java.util.Optional<AskResponse.Understanding> lastContext(Long userId, Long conversationId) {
        requireOwned(userId, conversationId);
        List<ChatMessage> msgs = conversationDao.listMessages(conversationId);
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ChatMessage m = msgs.get(i);
            if (!"assistant".equals(m.getRole()) || m.getDetailJson() == null || m.getDetailJson().isBlank()) {
                continue;
            }
            try {
                AskResponse r = objectMapper.readValue(m.getDetailJson(), AskResponse.class);
                AskResponse.Understanding u = r.understanding();
                if (u != null && u.ethnicity() != null && u.disease() != null) {
                    return java.util.Optional.of(u);
                }
            } catch (JsonProcessingException ignored) {
                // 跳过解析失败的历史消息
            }
        }
        return java.util.Optional.empty();
    }

    /** assistant 消息的纯文本内容（用于列表/无障碍展示） */
    private String assistantContent(AskResponse response) {
        if (response.type().equals("answer") && response.answer() != null) {
            return response.answer().conclusion();
        }
        return response.message() != null ? response.message()
                : (response.clarifyQuestion() != null ? response.clarifyQuestion() : "");
    }

    private String buildTitle(String question) {
        String q = question.trim().replaceAll("\\s+", " ");
        return q.length() <= TITLE_MAX ? q : q.substring(0, TITLE_MAX) + "…";
    }

    private String toJson(AskResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new BizException(500, "消息序列化失败");
        }
    }

    private ChatMessageDto toMessageDto(ChatMessage m) {
        // 解析成 Map 而不是 JsonNode：本项目存在两套 Jackson —— 代码里是 Jackson 2
        // （com.fasterxml），而 Boot 4 的 MVC 转换器用 Jackson 3。Jackson 3 不认识
        // Jackson 2 的 JsonNode，会把它当普通 POJO 序列化，响应里冒出一堆
        // nodeType/bigDecimal 之类的内部属性，真实内容全丢。Map 两套都认。
        // 也不用绑定具体 record：detail 是前端定义的、会持续演进的载荷，
        // 绑定后多一个未知字段就会反序列化失败、整条 detail 变 null（静默丢历史）。
        Object detail = null;
        if (m.getDetailJson() != null && !m.getDetailJson().isBlank()) {
            try {
                detail = objectMapper.readValue(m.getDetailJson(),
                        new TypeReference<Map<String, Object>>() {});
            } catch (JsonProcessingException e) {
                // 历史数据解析失败时保留 null，前端仍可按 content 兜底展示
            }
        }
        return new ChatMessageDto(m.getId(), m.getRole(), m.getKind(), m.getContent(), detail, m.getCreatedAt());
    }

    private Conversation requireOwned(Long userId, Long conversationId) {
        Conversation c = conversationDao.findConversationById(conversationId)
                .orElseThrow(() -> new BizException(404, "会话不存在"));
        if (!c.getUserId().equals(userId)) {
            throw new BizException(403, "无权访问该会话");
        }
        return c;
    }

    private ConversationDto toDto(Conversation c) {
        return new ConversationDto(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt(),
                conversationDao.countMessages(c.getId()));
    }
}
