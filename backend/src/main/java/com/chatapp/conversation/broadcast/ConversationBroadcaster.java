package com.chatapp.conversation.broadcast;

import com.chatapp.conversation.dto.ConversationSummaryDto;
import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.event.ConversationUpdatedEvent;
import com.chatapp.conversation.event.GroupDeletedEvent;
import com.chatapp.conversation.event.MemberAddedEvent;
import com.chatapp.conversation.event.MemberRemovedEvent;
import com.chatapp.conversation.event.OwnerTransferredEvent;
import com.chatapp.conversation.event.RoleChangedEvent;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Broadcast WebSocket events cho Group Chat lifecycle (W7-D1, W7-D2).
 *
 * Listen AFTER_COMMIT để đảm bảo transaction đã persist; nếu rollback thì không fire.
 * try-catch toàn bộ — broadcast fail KHÔNG được propagate (REST đã trả response trước đó).
 *
 * W7-D2 thêm 4 listener:
 *  - MEMBER_ADDED → /topic/conv.{id} + /user/queue/conv-added (per added user)
 *  - MEMBER_REMOVED → /topic/conv.{id} + /user/queue/conv-removed (CHỈ KHI KICKED)
 *  - ROLE_CHANGED → /topic/conv.{id}
 *  - OWNER_TRANSFERRED → /topic/conv.{id}
 *
 * Xem SOCKET_EVENTS.md §3.7-§3.10.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;

    // =========================================================================
    // W7-D1: CONVERSATION_UPDATED, GROUP_DELETED
    // =========================================================================

    /**
     * Broadcast CONVERSATION_UPDATED khi rename group hoặc đổi avatar.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConversationUpdated(ConversationUpdatedEvent event) {
        try {
            String destination = "/topic/conv." + event.conversationId();

            // Sử dụng LinkedHashMap để cho phép null values (Map.of throws NPE với null).
            Map<String, Object> updatedBy = new LinkedHashMap<>();
            updatedBy.put("userId", event.updatedByUserId().toString());
            updatedBy.put("fullName", event.updatedByFullName());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", event.conversationId().toString());
            payload.put("changes", event.changes()); // changes map có thể chứa avatarUrl: null (remove)
            payload.put("updatedBy", updatedBy);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "CONVERSATION_UPDATED");
            envelope.put("payload", payload);

            messagingTemplate.convertAndSend(destination, envelope);
            log.debug("Broadcasted CONVERSATION_UPDATED conv={} changes={}",
                    event.conversationId(), event.changes().keySet());
        } catch (Exception e) {
            log.error("Failed to broadcast CONVERSATION_UPDATED for conv {}",
                    event.conversationId(), e);
        }
    }

    /**
     * Broadcast GROUP_DELETED khi OWNER soft-delete group.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroupDeleted(GroupDeletedEvent event) {
        try {
            String destination = "/topic/conv." + event.conversationId();

            Map<String, Object> deletedBy = new LinkedHashMap<>();
            deletedBy.put("userId", event.deletedByUserId().toString());
            deletedBy.put("fullName", event.deletedByFullName());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", event.conversationId().toString());
            payload.put("deletedBy", deletedBy);
            payload.put("deletedAt", event.deletedAt().toString());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "GROUP_DELETED");
            envelope.put("payload", payload);

            messagingTemplate.convertAndSend(destination, envelope);
            log.debug("Broadcasted GROUP_DELETED conv={}", event.conversationId());
        } catch (Exception e) {
            log.error("Failed to broadcast GROUP_DELETED for conv {}",
                    event.conversationId(), e);
        }
    }

    // =========================================================================
    // W7-D2: MEMBER_ADDED
    // =========================================================================

    /**
     * Broadcast MEMBER_ADDED (W7-D2):
     *  1) Topic `/topic/conv.{id}` — members hiện hữu nhận.
     *  2) User-specific `/user/{addedUserId}/queue/conv-added` — user vừa add nhận ConversationSummaryDto.
     *
     * Chạy trong REQUIRES_NEW transaction (readOnly) để load member/conv data mà không
     * reuse transaction cũ (đã commit). Dùng cho listener AFTER_COMMIT cần DB access.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberAdded(MemberAddedEvent event) {
        try {
            // Load target member + actor user
            ConversationMember member = memberRepository
                    .findByConversation_IdAndUser_Id(event.conversationId(), event.addedUserId())
                    .orElse(null);
            if (member == null) {
                log.warn("MEMBER_ADDED: member not found conv={} user={}",
                        event.conversationId(), event.addedUserId());
                return;
            }

            User actor = userRepository.findById(event.actorUserId()).orElse(null);
            User addedUser = member.getUser();

            // 1) Topic broadcast — members hiện hữu
            String destination = "/topic/conv." + event.conversationId();

            Map<String, Object> memberPayload = new LinkedHashMap<>();
            memberPayload.put("userId", addedUser.getId().toString());
            memberPayload.put("username", addedUser.getUsername());
            memberPayload.put("fullName", addedUser.getFullName());
            memberPayload.put("avatarUrl", addedUser.getAvatarUrl());
            memberPayload.put("role", member.getRole().name());
            memberPayload.put("joinedAt", member.getJoinedAt().toInstant().toString());

            Map<String, Object> addedByPayload = new LinkedHashMap<>();
            if (actor != null) {
                addedByPayload.put("userId", actor.getId().toString());
                addedByPayload.put("username", actor.getUsername());
                addedByPayload.put("fullName", actor.getFullName());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", event.conversationId().toString());
            payload.put("member", memberPayload);
            payload.put("addedBy", addedByPayload);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "MEMBER_ADDED");
            envelope.put("payload", payload);

            messagingTemplate.convertAndSend(destination, envelope);

            // 2) User-specific notification for added user
            ConversationSummaryDto summary = buildSummaryForUser(event.conversationId(), event.addedUserId());
            if (summary != null) {
                messagingTemplate.convertAndSendToUser(
                        event.addedUserId().toString(),
                        "/queue/conv-added",
                        summary
                );
            }

            log.debug("Broadcasted MEMBER_ADDED conv={} addedUser={}",
                    event.conversationId(), event.addedUserId());
        } catch (Exception e) {
            log.error("Failed to broadcast MEMBER_ADDED for conv {} user {}",
                    event.conversationId(), event.addedUserId(), e);
        }
    }

    // =========================================================================
    // W7-D2: MEMBER_REMOVED
    // =========================================================================

    /**
     * Broadcast MEMBER_REMOVED (W7-D2):
     *  1) Topic `/topic/conv.{id}` — member list được notify.
     *  2) CHỈ KHI KICKED: `/user/{removedUserId}/queue/conv-removed` minimal payload.
     *
     * LEFT → removedBy = null trong topic envelope; không user-queue notify (user tự bấm leave).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberRemoved(MemberRemovedEvent event) {
        try {
            Map<String, Object> removedByPayload = null;
            if (event.removedByUserId() != null) {
                User actor = userRepository.findById(event.removedByUserId()).orElse(null);
                if (actor != null) {
                    removedByPayload = new LinkedHashMap<>();
                    removedByPayload.put("userId", actor.getId().toString());
                    removedByPayload.put("username", actor.getUsername());
                    removedByPayload.put("fullName", actor.getFullName());
                }
            }

            String destination = "/topic/conv." + event.conversationId();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", event.conversationId().toString());
            payload.put("userId", event.removedUserId().toString());
            payload.put("removedBy", removedByPayload); // null khi LEFT
            payload.put("reason", event.reason());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "MEMBER_REMOVED");
            envelope.put("payload", payload);

            messagingTemplate.convertAndSend(destination, envelope);

            // User-specific notify — CHỈ khi KICKED
            if ("KICKED".equals(event.reason())) {
                Map<String, Object> convRemovedPayload = new LinkedHashMap<>();
                convRemovedPayload.put("conversationId", event.conversationId().toString());
                convRemovedPayload.put("reason", "KICKED");

                messagingTemplate.convertAndSendToUser(
                        event.removedUserId().toString(),
                        "/queue/conv-removed",
                        convRemovedPayload
                );
            }

            log.debug("Broadcasted MEMBER_REMOVED conv={} user={} reason={}",
                    event.conversationId(), event.removedUserId(), event.reason());
        } catch (Exception e) {
            log.error("Failed to broadcast MEMBER_REMOVED for conv {} user {}",
                    event.conversationId(), event.removedUserId(), e);
        }
    }

    // =========================================================================
    // W7-D2: ROLE_CHANGED
    // =========================================================================

    /**
     * Broadcast ROLE_CHANGED (W7-D2) — fire từ PATCH /role khi oldRole != newRole.
     * No-op (same role) KHÔNG publish event → không nhận ở đây.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoleChanged(RoleChangedEvent event) {
        try {
            User changedBy = userRepository.findById(event.changedByUserId()).orElse(null);

            Map<String, Object> changedByPayload = new LinkedHashMap<>();
            if (changedBy != null) {
                changedByPayload.put("userId", changedBy.getId().toString());
                changedByPayload.put("username", changedBy.getUsername());
                changedByPayload.put("fullName", changedBy.getFullName());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", event.conversationId().toString());
            payload.put("userId", event.targetUserId().toString());
            payload.put("oldRole", event.oldRole().name());
            payload.put("newRole", event.newRole().name());
            payload.put("changedBy", changedByPayload);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "ROLE_CHANGED");
            envelope.put("payload", payload);

            String destination = "/topic/conv." + event.conversationId();
            messagingTemplate.convertAndSend(destination, envelope);

            log.debug("Broadcasted ROLE_CHANGED conv={} target={} {}→{}",
                    event.conversationId(), event.targetUserId(),
                    event.oldRole(), event.newRole());
        } catch (Exception e) {
            log.error("Failed to broadcast ROLE_CHANGED for conv {} target {}",
                    event.conversationId(), event.targetUserId(), e);
        }
    }

    // =========================================================================
    // W7-D2: OWNER_TRANSFERRED
    // =========================================================================

    /**
     * Broadcast OWNER_TRANSFERRED (W7-D2) — cho cả /transfer-owner (autoTransferred=false)
     * và auto-transfer khi OWNER leave (autoTransferred=true).
     *
     * Single atomic event — tránh "2 OWNER" flicker khi dùng 2 ROLE_CHANGED riêng.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOwnerTransferred(OwnerTransferredEvent event) {
        try {
            User prev = userRepository.findById(event.previousOwnerUserId()).orElse(null);
            User next = userRepository.findById(event.newOwnerUserId()).orElse(null);

            // previousOwner shape minimal: {userId, username}
            Map<String, Object> prevPayload = new LinkedHashMap<>();
            if (prev != null) {
                prevPayload.put("userId", prev.getId().toString());
                prevPayload.put("username", prev.getUsername());
            }

            // newOwner shape fuller: {userId, username, fullName}
            Map<String, Object> nextPayload = new LinkedHashMap<>();
            if (next != null) {
                nextPayload.put("userId", next.getId().toString());
                nextPayload.put("username", next.getUsername());
                nextPayload.put("fullName", next.getFullName());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", event.conversationId().toString());
            payload.put("previousOwner", prevPayload);
            payload.put("newOwner", nextPayload);
            payload.put("autoTransferred", event.autoTransferred());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "OWNER_TRANSFERRED");
            envelope.put("payload", payload);

            String destination = "/topic/conv." + event.conversationId();
            messagingTemplate.convertAndSend(destination, envelope);

            log.debug("Broadcasted OWNER_TRANSFERRED conv={} prev={} next={} auto={}",
                    event.conversationId(), event.previousOwnerUserId(),
                    event.newOwnerUserId(), event.autoTransferred());
        } catch (Exception e) {
            log.error("Failed to broadcast OWNER_TRANSFERRED for conv {}",
                    event.conversationId(), e);
        }
    }

    // =========================================================================
    // Helper: build ConversationSummaryDto cho conv-added user queue
    // =========================================================================

    /**
     * Build ConversationSummaryDto cho user vừa được add (destination /user/queue/conv-added).
     * Shape đồng bộ với GET /api/conversations list item.
     */
    private ConversationSummaryDto buildSummaryForUser(UUID convId, UUID userId) {
        Conversation conv = conversationRepository.findActiveByIdWithMembers(convId).orElse(null);
        if (conv == null) return null;

        List<ConversationMember> members = conv.getMembers();
        int memberCount = members.size();

        // Compute displayName / displayAvatarUrl (cho group: dùng name/avatar; cho 1-1 dùng other member).
        String displayName = conv.getName();
        String displayAvatarUrl = null;
        if (conv.getAvatarFileId() != null) {
            displayAvatarUrl = "/api/files/" + conv.getAvatarFileId();
        } else if (conv.getAvatarUrl() != null) {
            displayAvatarUrl = conv.getAvatarUrl();
        }

        if (conv.getType() == ConversationType.ONE_ON_ONE) {
            // Unlikely cho MEMBER_ADDED (chỉ GROUP có add members) nhưng defensive.
            for (ConversationMember m : members) {
                if (!userId.equals(m.getUser().getId())) {
                    displayName = m.getUser().getFullName();
                    displayAvatarUrl = m.getUser().getAvatarUrl();
                    break;
                }
            }
        }

        // mutedUntil cho user này
        java.time.Instant mutedUntil = null;
        for (ConversationMember m : members) {
            if (userId.equals(m.getUser().getId()) && m.getMutedUntil() != null) {
                mutedUntil = m.getMutedUntil().toInstant();
                break;
            }
        }

        return new ConversationSummaryDto(
                conv.getId(),
                conv.getType(),
                conv.getName(),
                conv.getAvatarFileId() != null ? "/api/files/" + conv.getAvatarFileId() : conv.getAvatarUrl(),
                displayName,
                displayAvatarUrl,
                memberCount,
                conv.getLastMessageAt() != null ? conv.getLastMessageAt().toInstant() : null,
                0, // unreadCount V1 placeholder
                mutedUntil
        );
    }
}
