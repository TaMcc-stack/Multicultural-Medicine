package com.backend.qa;

import com.backend.auth.AuthController;
import com.backend.chat.service.ConversationService;
import com.backend.common.ApiResponse;
import com.backend.qa.dto.AskRequest;
import com.backend.qa.dto.AskResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问答接口
 */
@RestController
@RequestMapping("/api/qa")
public class QaController {

    private final QaService qaService;
    private final ConversationService conversationService;

    public QaController(QaService qaService, ConversationService conversationService) {
        this.qaService = qaService;
        this.conversationService = conversationService;
    }

    /**
     * 提问（单接口承载完整链路）：
     * - 首次提问：question 必填，intent 可空，conversationId 为空 → 后端自动新建会话
     * - 澄清后重问：带上澄清返回的 intent 选项 key
     * - 继续对话：带上 conversationId，指代式追问自动复用上文民族/疾病
     * 每轮问答都会落库（用户问题 + assistant 响应），响应中回填 conversationId。
     */
    @PostMapping("/ask")
    public ApiResponse<AskResponse> ask(@Valid @RequestBody AskRequest request,
                                        @RequestAttribute(AuthController.ATTR_USER_ID) Long userId) {
        AskResponse.Understanding context = null;
        if (request.conversationId() != null) {
            context = conversationService.lastContext(userId, request.conversationId()).orElse(null);
        }
        AskResponse response = qaService.ask(request, context);
        Long conversationId = conversationService.recordTurn(userId, request, response);
        return ApiResponse.ok(response.withConversation(conversationId));
    }
}
