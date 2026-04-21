package com.chatapp.conversation.controller;

import com.chatapp.conversation.dto.AddMembersRequest;
import com.chatapp.conversation.dto.AddMembersResponse;
import com.chatapp.conversation.dto.ConversationDto;
import com.chatapp.conversation.dto.ConversationListResponse;
import com.chatapp.conversation.dto.CreateConversationRequest;
import com.chatapp.conversation.dto.OwnerTransferResponse;
import com.chatapp.conversation.dto.RoleChangeRequest;
import com.chatapp.conversation.dto.RoleChangeResponse;
import com.chatapp.conversation.dto.TransferOwnerRequest;
import com.chatapp.conversation.dto.UpdateGroupRequest;
import com.chatapp.conversation.service.ConversationMemberService;
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
    private final ConversationMemberService memberService;

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

    // =========================================================================
    // W7-D2: Member management endpoints
    // =========================================================================

    /**
     * POST /api/conversations/{id}/members (W7-D2).
     * Batch add 1-10 users. OWNER/ADMIN only. Partial-success {added, skipped}.
     * 201 Created. Broadcasts MEMBER_ADDED per user + /queue/conv-added.
     */
    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public AddMembersResponse addMembers(
            @PathVariable UUID id,
            @Valid @RequestBody AddMembersRequest req,
            @AuthenticationPrincipal User currentUser
    ) {
        return memberService.addMembers(id, currentUser.getId(), req);
    }

    /**
     * DELETE /api/conversations/{id}/members/{userId} (W7-D2).
     * Kick member. OWNER kick ADMIN/MEMBER, ADMIN kick MEMBER. 204 No Content.
     * CANNOT_KICK_SELF khi self-remove. Broadcasts MEMBER_REMOVED (KICKED) + /queue/conv-removed.
     */
    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable UUID id,
            @PathVariable UUID userId,
            @AuthenticationPrincipal User currentUser
    ) {
        memberService.removeMember(id, currentUser.getId(), userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/conversations/{id}/leave (W7-D2).
     * Self-leave. Bất kỳ role nào. OWNER leave → auto-transfer (oldest ADMIN → oldest MEMBER).
     * 204 No Content. Broadcasts MEMBER_REMOVED (LEFT) + (nếu OWNER) OWNER_TRANSFERRED trước.
     * CANNOT_LEAVE_EMPTY_GROUP nếu OWNER là member duy nhất.
     */
    @PostMapping("/{id}/leave")
    public ResponseEntity<Void> leaveGroup(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser
    ) {
        memberService.leaveGroup(id, currentUser.getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * PATCH /api/conversations/{id}/members/{userId}/role (W7-D2).
     * Đổi role ADMIN↔MEMBER. OWNER only. INVALID_ROLE khi body role=OWNER.
     * No-op idempotent (oldRole==newRole) → 200 OK, KHÔNG broadcast.
     * Broadcasts ROLE_CHANGED khi thực sự đổi.
     */
    @PatchMapping("/{id}/members/{userId}/role")
    public RoleChangeResponse changeRole(
            @PathVariable UUID id,
            @PathVariable UUID userId,
            @Valid @RequestBody RoleChangeRequest req,
            @AuthenticationPrincipal User currentUser
    ) {
        return memberService.changeRole(id, currentUser.getId(), userId, req);
    }

    /**
     * POST /api/conversations/{id}/transfer-owner (W7-D2).
     * OWNER chuyển quyền cho 1 member khác. Atomic 2-way swap (OWNER cũ → ADMIN, target → OWNER).
     * CANNOT_TRANSFER_TO_SELF khi target==actor.
     * Broadcasts OWNER_TRANSFERRED (autoTransferred=false).
     */
    @PostMapping("/{id}/transfer-owner")
    public OwnerTransferResponse transferOwner(
            @PathVariable UUID id,
            @Valid @RequestBody TransferOwnerRequest req,
            @AuthenticationPrincipal User currentUser
    ) {
        return memberService.transferOwner(id, currentUser.getId(), req);
    }
}
