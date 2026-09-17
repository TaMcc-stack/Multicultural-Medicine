package com.backend.chat.controller;

import com.backend.auth.AuthController;
import com.backend.chat.dto.ChatMessageDto;
import com.backend.chat.dto.ConversationDetail;
import com.backend.chat.dto.ConversationDto;
import com.backend.chat.dto.CreateConversationRequest;
import com.backend.chat.dto.RenameConversationRequest;
import com.backend.chat.dto.RecordMessageRequest;
import com.backend.chat.service.ConversationService;
import com.backend.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话接口：创建 / 列表 / 详情 / 重命名 / 删除
 * 统一由 AuthInterceptor 鉴权，userId 从请求属性注入。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** 创建会话（title 可空，为空时按首问自动生成） */
    @PostMapping
    public ApiResponse<ConversationDto> create(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @Valid @RequestBody(required = false) CreateConversationRequest request) {
        String title = request == null ? null : request.title();
        return ApiResponse.ok(conversationService.create(userId, title));
    }

    /** 会话列表（按最后活跃倒序） */
    @GetMapping
    public ApiResponse<List<ConversationDto>> list(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId) {
        return ApiResponse.ok(conversationService.list(userId));
    }

    /**
     * 最近活跃的会话（含全部历史消息）。
     * 刷新页面后前端据此恢复上一次的连续对话；没有任何会话时 data 为 null。
     * 注意：必须声明在 {@code /{id}} 之前，否则 "last" 会被当成会话 id 解析。
     */
    @GetMapping("/last")
    public ApiResponse<ConversationDetail> last(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId) {
        return ApiResponse.ok(conversationService.lastDetail(userId));
    }

    /** 会话详情（含全部历史消息，用于继续对话 / 回放） */
    @GetMapping("/{id}")
    public ApiResponse<ConversationDetail> detail(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        return ApiResponse.ok(conversationService.detail(userId, id));
    }

    /** 重命名 */
    @PutMapping("/{id}")
    public ApiResponse<ConversationDto> rename(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id,
            @Valid @RequestBody RenameConversationRequest request) {
        return ApiResponse.ok(conversationService.rename(userId, id, request.title()));
    }

    /**
     * 追加一条消息（仅持久化，不生成）：前端本地生成回答后落库，供历史回放。
     * 返回落库后的消息（含自增 id），前端据此做「添加到动态」的来源溯源。
     */
    @PostMapping("/{id}/messages")
    public ApiResponse<ChatMessageDto> recordMessage(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id,
            @Valid @RequestBody RecordMessageRequest request) {
        return ApiResponse.ok(conversationService.recordMessage(
                userId, id, request.role(), request.kind(), request.content(), request.detail()));
    }

    /** 删除会话（连同历史消息） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @RequestAttribute(AuthController.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        conversationService.delete(userId, id);
        return ApiResponse.ok();
    }
}
