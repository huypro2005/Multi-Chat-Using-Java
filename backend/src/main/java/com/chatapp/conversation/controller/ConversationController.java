package com.chatapp.conversation.controller;

import com.chatapp.conversation.dto.ConversationDto;
import com.chatapp.conversation.dto.ConversationListResponse;
import com.chatapp.conversation.dto.CreateConversationRequest;
import com.chatapp.conversation.dto.UpdateGroupRequest;
import com.chatapp.conversation.service.ConversationService;
import com.chatapp.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller cho conversation endpoints.
 *
 * POST   /api/conversations        → tạo conversation mới (201) — ONE_ON_ONE or GROUP
 * GET    /api/conversations        → danh sách conversations của caller (200)
 * GET    /api/conversations/{id}   → chi tiết conversation (200)
 * PATCH  /api/conversations/{id}   → W7-D1: rename group / đổi avatar (200)
 * DELETE /api/conversations/{id}   → W7-D1: OWNER soft-delete group (204)
 *
 * Auth required: tất cả. JWT được validate bởi JwtAuthFilter trước khi vào đây.
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationDto createConversation(
            @Valid @RequestBody CreateConversationRequest req,
            @AuthenticationPrincipal User currentUser
    ) {
        return conversationService.createConversation(currentUser.getId(), req);
    }

    @GetMapping
    public ConversationListResponse listConversations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal User currentUser
    ) {
        return conversationService.listConversations(currentUser.getId(), page, size);
    }

    @GetMapping("/{id}")
    public ConversationDto getConversation(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser
    ) {
        return conversationService.getConversation(currentUser.getId(), id);
    }

    /**
     * PATCH /api/conversations/{id} (W7-D1).
     * Rename group hoặc đổi avatar. Authorization: OWNER hoặc ADMIN.
     * Body có tristate cho avatarFileId: absent / null (remove) / uuid.
     */
    @PatchMapping("/{id}")
    public ConversationDto updateGroupInfo(
            @PathVariable UUID id,
            @RequestBody UpdateGroupRequest req,
            @AuthenticationPrincipal User currentUser
    ) {
        return conversationService.updateGroupInfo(currentUser.getId(), id, req);
    }

    /**
     * DELETE /api/conversations/{id} (W7-D1).
     * OWNER soft-delete group. Trả 204 No Content.
     * Side effects: deleted_at set, conversation_members hard-deleted, avatar detached,
     * GROUP_DELETED broadcast qua WebSocket.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser
    ) {
        conversationService.deleteGroup(currentUser.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
