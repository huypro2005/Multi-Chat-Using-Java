package com.chatapp.conversation.service;

import com.chatapp.conversation.dto.ActorSummaryDto;
import com.chatapp.conversation.dto.AddMembersRequest;
import com.chatapp.conversation.dto.AddMembersResponse;
import com.chatapp.conversation.dto.MemberDto;
import com.chatapp.conversation.dto.OwnerTransferResponse;
import com.chatapp.conversation.dto.PreviousOwnerDto;
import com.chatapp.conversation.dto.RoleChangeRequest;
import com.chatapp.conversation.dto.RoleChangeResponse;
import com.chatapp.conversation.dto.SkippedMemberDto;
import com.chatapp.conversation.dto.TransferOwnerRequest;
import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.MemberRole;
import com.chatapp.conversation.event.MemberAddedEvent;
import com.chatapp.conversation.event.MemberRemovedEvent;
import com.chatapp.conversation.event.OwnerTransferredEvent;
import com.chatapp.conversation.event.RoleChangedEvent;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.constant.SystemEventType;
import com.chatapp.message.service.SystemMessageService;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * W7-D2: Member management — Add / Remove / Leave / ChangeRole / TransferOwner.
 *
 * Tách khỏi ConversationService để giữ class size manageable. Dùng chung
 * ConversationRepository + ConversationMemberRepository + UserRepository + eventPublisher.
 *
 * Authorization matrix: xem API_CONTRACT.md Appendix (v1.1.0-w7).
 * Broadcast shapes: xem SOCKET_EVENTS.md §3.7-§3.10.
 *
 * Transaction boundary: mỗi public method là 1 @Transactional. Broadcaster fire AFTER_COMMIT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMemberService {

    private static final int GROUP_MEMBER_LIMIT = 50;

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SystemMessageService systemMessageService;

    // =========================================================================
    // POST /api/conversations/{id}/members — addMembers
    // =========================================================================

    @Transactional
    public AddMembersResponse addMembers(UUID convId, UUID actorId, AddMembersRequest req) {
        // 1. Authorization: caller phải là member + có quyền add (OWNER/ADMIN)
        ConversationMember actor = requireMembership(convId, actorId);

        // 2. Load conv + verify GROUP
        Conversation conv = conversationRepository.findActiveById(convId)
                .orElseThrow(() -> convNotFound());

        if (!conv.isGroup()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "NOT_GROUP",
                    "Chỉ GROUP mới có thể add members");
        }

        if (!actor.getRole().canAddMembers()) {
            throw new AppException(HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION",
                    "Chỉ OWNER hoặc ADMIN mới được add members");
        }

        // 3. Dedupe input userIds (contract: silent dedupe)
        List<UUID> inputIds = new ArrayList<>(new LinkedHashSet<>(req.userIds()));

        // 4. Race-safe count: SELECT rows với FOR UPDATE (lock), count ở Java.
        long currentCount = memberRepository.lockMembersForUpdate(convId.toString()).size();

        // 5. Load existing member IDs (cho already-member check)
        Set<UUID> existingIds = memberRepository.findUserIdsByConversationId(convId);

        // 6. Batch load users để check exist + active
        List<User> users = userRepository.findAllById(inputIds);
        Map<UUID, User> userMap = users.stream()
                .filter(u -> "active".equals(u.getStatus()))
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        // 7. Classify: add vs skip (USER_NOT_FOUND, ALREADY_MEMBER, BLOCKED)
        List<SkippedMemberDto> skipped = new ArrayList<>();
        List<UUID> toAdd = new ArrayList<>();

        for (UUID userId : inputIds) {
            User user = userMap.get(userId);
            if (user == null) {
                // not-exist OR non-active (anti-enum merge)
                skipped.add(new SkippedMemberDto(userId, "USER_NOT_FOUND"));
                continue;
            }
            if (existingIds.contains(userId)) {
                skipped.add(new SkippedMemberDto(userId, "ALREADY_MEMBER"));
                continue;
            }
            // BLOCKED reserved — V1 chưa wire user_blocks; không fire ở đây.
            toAdd.add(userId);
        }

        // 8. ALL-OR-NOTHING check MEMBER_LIMIT_EXCEEDED
        int validToAddCount = toAdd.size();
        if (currentCount + validToAddCount > GROUP_MEMBER_LIMIT) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("currentCount", currentCount);
            details.put("attemptedCount", validToAddCount);
            details.put("limit", GROUP_MEMBER_LIMIT);
            throw new AppException(HttpStatus.CONFLICT, "MEMBER_LIMIT_EXCEEDED",
                    "Nhóm đã đủ " + GROUP_MEMBER_LIMIT + " thành viên", details);
        }

        // 9. Insert batch + build added list
        List<MemberDto> added = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (UUID userId : toAdd) {
            User user = userMap.get(userId);
            ConversationMember newMember = ConversationMember.builder()
                    .conversation(conv)
                    .user(user)
                    .role(MemberRole.MEMBER)
                    .joinedAt(now)
                    .build();
            memberRepository.save(newMember);
            added.add(MemberDto.from(newMember));

            // 10. Publish event per added user
            eventPublisher.publishEvent(new MemberAddedEvent(convId, userId, actorId));

            // W7-D4: MEMBER_ADDED system message (1 per added user, KHÔNG cho skipped)
            Map<String, Object> addedMeta = new LinkedHashMap<>();
            addedMeta.put("targetId", userId.toString());
            addedMeta.put("targetName", user.getFullName());
            systemMessageService.createAndPublish(convId, actorId, SystemEventType.MEMBER_ADDED, addedMeta);
        }

        return new AddMembersResponse(added, skipped);
    }

    // =========================================================================
    // DELETE /api/conversations/{id}/members/{userId} — removeMember (kick)
    // =========================================================================

    @Transactional
    public void removeMember(UUID convId, UUID actorId, UUID targetUserId) {
        // 1. CANNOT_KICK_SELF (trước mọi check khác)
        if (actorId.equals(targetUserId)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "CANNOT_KICK_SELF",
                    "Không thể tự kick. Dùng POST /leave để rời nhóm");
        }

        // 2. Authorization: caller phải là member
        ConversationMember actor = requireMembership(convId, actorId);

        // 3. Load conv + verify GROUP
        Conversation conv = conversationRepository.findActiveById(convId)
                .orElseThrow(() -> convNotFound());

        if (!conv.isGroup()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "NOT_GROUP",
                    "Chỉ GROUP mới có thể kick members");
        }

        // 4. Load target member
        ConversationMember target = memberRepository
                .findByConversation_IdAndUser_Id(convId, targetUserId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND",
                        "Không tìm thấy thành viên trong nhóm"));

        // 5. Authorization matrix: OWNER can kick ADMIN/MEMBER; ADMIN only kick MEMBER
        if (!actor.getRole().canRemoveMember(target.getRole())) {
            throw new AppException(HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION",
                    "Bạn không có quyền kick thành viên này");
        }

        // W7-D4: MEMBER_REMOVED system message BEFORE hard-delete (contract ordering)
        Map<String, Object> removedMeta = new LinkedHashMap<>();
        removedMeta.put("targetId", targetUserId.toString());
        removedMeta.put("targetName", target.getUser().getFullName());
        systemMessageService.createAndPublish(convId, actorId, SystemEventType.MEMBER_REMOVED, removedMeta);

        // 6. Hard-delete target row
        memberRepository.delete(target);

        // 7. Publish event (reason=KICKED, removedBy=actorId)
        eventPublisher.publishEvent(new MemberRemovedEvent(convId, targetUserId, actorId, "KICKED"));
    }

    // =========================================================================
    // POST /api/conversations/{id}/leave — leaveGroup (self-leave)
    // =========================================================================

    @Transactional
    public void leaveGroup(UUID convId, UUID actorId) {
        // 1. Load membership với FOR UPDATE lock — race-safe với concurrent kick.
        ConversationMember actor = memberRepository
                .findByConversationIdAndUserIdForUpdate(convId.toString(), actorId.toString())
                .orElseThrow(() -> convNotFound()); // anti-enum

        // 2. Load conv + verify GROUP
        Conversation conv = conversationRepository.findActiveById(convId)
                .orElseThrow(() -> convNotFound());

        if (!conv.isGroup()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "NOT_GROUP",
                    "Không thể leave ONE_ON_ONE conversation");
        }

        // 3. OWNER leave → auto-transfer
        if (actor.getRole() == MemberRole.OWNER) {
            List<ConversationMember> candidates = memberRepository
                    .findCandidatesForOwnerTransfer(convId.toString(), actorId.toString());

            if (candidates.isEmpty()) {
                throw new AppException(HttpStatus.BAD_REQUEST, "CANNOT_LEAVE_EMPTY_GROUP",
                        "Bạn là thành viên duy nhất. Dùng DELETE /{id} để xoá nhóm.");
            }

            // Pick first candidate (oldest ADMIN, fallback oldest MEMBER)
            ConversationMember newOwner = candidates.get(0);
            UUID newOwnerId = newOwner.getUser().getId();
            String newOwnerName = newOwner.getUser().getFullName();

            // Atomic swap: demote caller OWNER → MEMBER (trước delete),
            // promote candidate → OWNER.
            actor.setRole(MemberRole.MEMBER);
            newOwner.setRole(MemberRole.OWNER);
            memberRepository.save(actor);
            memberRepository.save(newOwner);

            // Update conversations.owner_id
            conv.setOwnerId(newOwnerId);
            conversationRepository.save(conv);

            // Publish OWNER_TRANSFERRED TRƯỚC MEMBER_REMOVED (contract §3.10)
            eventPublisher.publishEvent(new OwnerTransferredEvent(
                    convId, actorId, newOwnerId, true
            ));

            // W7-D4: OWNER_TRANSFERRED system message (autoTransferred=true) — BEFORE MEMBER_LEFT
            Map<String, Object> transferredMeta = new LinkedHashMap<>();
            transferredMeta.put("targetId", newOwnerId.toString());
            transferredMeta.put("targetName", newOwnerName);
            transferredMeta.put("autoTransferred", true);
            systemMessageService.createAndPublish(convId, actorId, SystemEventType.OWNER_TRANSFERRED, transferredMeta);
        }

        // 4. Hard-delete caller row
        memberRepository.delete(actor);

        // 5. Publish MEMBER_REMOVED (reason=LEFT, removedBy=null)
        eventPublisher.publishEvent(new MemberRemovedEvent(convId, actorId, null, "LEFT"));

        // W7-D4: MEMBER_LEFT system message (after the delete — actor is gone)
        systemMessageService.createAndPublish(convId, actorId, SystemEventType.MEMBER_LEFT, Collections.emptyMap());
    }

    // =========================================================================
    // PATCH /api/conversations/{id}/members/{userId}/role — changeRole
    // =========================================================================

    @Transactional
    public RoleChangeResponse changeRole(UUID convId, UUID actorId, UUID targetUserId,
                                         RoleChangeRequest req) {
        // 1. Validate role: KHÔNG cho OWNER trong body
        if (req.role() == MemberRole.OWNER) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_ROLE",
                    "OWNER role chỉ set qua POST /transfer-owner");
        }

        // 2. Authorization: caller phải là member + OWNER
        ConversationMember actor = requireMembership(convId, actorId);

        // 3. Load conv + verify GROUP
        Conversation conv = conversationRepository.findActiveById(convId)
                .orElseThrow(() -> convNotFound());

        if (!conv.isGroup()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "NOT_GROUP",
                    "Chỉ GROUP mới có thể đổi role");
        }

        if (!actor.getRole().canChangeRole()) {
            throw new AppException(HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION",
                    "Chỉ OWNER mới được đổi role");
        }

        // 4. Load target member
        ConversationMember target = memberRepository
                .findByConversation_IdAndUser_Id(convId, targetUserId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND",
                        "Không tìm thấy thành viên trong nhóm"));

        // 5. Target hiện là OWNER → CANNOT_CHANGE_OWNER_ROLE (403)
        if (target.getRole() == MemberRole.OWNER) {
            throw new AppException(HttpStatus.FORBIDDEN, "CANNOT_CHANGE_OWNER_ROLE",
                    "Không thể đổi role của OWNER. Dùng POST /transfer-owner để đổi chủ.");
        }

        MemberRole oldRole = target.getRole();

        // 6. No-op idempotent: trả 200 OK, KHÔNG broadcast
        User actorUser = userRepository.findById(actorId).orElse(null);
        if (oldRole == req.role()) {
            return new RoleChangeResponse(
                    targetUserId,
                    req.role(),
                    Instant.now(),
                    ActorSummaryDto.from(actorUser)
            );
        }

        // 7. Apply change
        target.setRole(req.role());
        memberRepository.save(target);

        // 8. Publish event
        eventPublisher.publishEvent(new RoleChangedEvent(
                convId, targetUserId, oldRole, req.role(), actorId
        ));

        // W7-D4: ROLE_PROMOTED / ROLE_DEMOTED system message
        String roleEventType = (req.role() == MemberRole.ADMIN)
                ? SystemEventType.ROLE_PROMOTED
                : SystemEventType.ROLE_DEMOTED;
        User targetUser2 = userRepository.findById(targetUserId).orElse(null);
        Map<String, Object> roleMeta = new LinkedHashMap<>();
        roleMeta.put("targetId", targetUserId.toString());
        roleMeta.put("targetName", targetUser2 != null ? targetUser2.getFullName() : "Unknown");
        systemMessageService.createAndPublish(convId, actorId, roleEventType, roleMeta);

        return new RoleChangeResponse(
                targetUserId,
                req.role(),
                Instant.now(),
                ActorSummaryDto.from(actorUser)
        );
    }

    // =========================================================================
    // POST /api/conversations/{id}/transfer-owner — transferOwner (explicit)
    // =========================================================================

    @Transactional
    public OwnerTransferResponse transferOwner(UUID convId, UUID actorId, TransferOwnerRequest req) {
        UUID targetUserId = req.targetUserId();

        // 1. CANNOT_TRANSFER_TO_SELF (trước mọi check khác)
        if (actorId.equals(targetUserId)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "CANNOT_TRANSFER_TO_SELF",
                    "Không thể transfer cho chính mình");
        }

        // 2. Load caller với lock — race-safe
        ConversationMember actor = memberRepository
                .findByConversationIdAndUserIdForUpdate(convId.toString(), actorId.toString())
                .orElseThrow(() -> convNotFound());

        // 3. Load conv + verify GROUP
        Conversation conv = conversationRepository.findActiveById(convId)
                .orElseThrow(() -> convNotFound());

        if (!conv.isGroup()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "NOT_GROUP",
                    "Chỉ GROUP mới có transfer owner");
        }

        if (!actor.getRole().canTransferOwnership()) {
            throw new AppException(HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION",
                    "Chỉ OWNER mới được transfer ownership");
        }

        // 4. Load target với lock
        ConversationMember target = memberRepository
                .findByConversationIdAndUserIdForUpdate(convId.toString(), targetUserId.toString())
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND",
                        "Không tìm thấy thành viên trong nhóm"));

        // 5. Atomic 2-way swap: OWNER cũ → ADMIN (giữ quyền quản lý!), target → OWNER
        actor.setRole(MemberRole.ADMIN);
        target.setRole(MemberRole.OWNER);
        memberRepository.save(actor);
        memberRepository.save(target);

        // Update conversations.owner_id
        conv.setOwnerId(targetUserId);
        conversationRepository.save(conv);

        // 6. Publish event (autoTransferred=false cho explicit)
        eventPublisher.publishEvent(new OwnerTransferredEvent(
                convId, actorId, targetUserId, false
        ));

        // 7. Build response
        User actorUser = userRepository.findById(actorId).orElse(null);
        User targetUser = userRepository.findById(targetUserId).orElse(null);

        // W7-D4: OWNER_TRANSFERRED system message (autoTransferred=false for explicit transfer)
        Map<String, Object> ownerTransferMeta = new LinkedHashMap<>();
        ownerTransferMeta.put("targetId", targetUserId.toString());
        ownerTransferMeta.put("targetName", targetUser != null ? targetUser.getFullName() : "Unknown");
        ownerTransferMeta.put("autoTransferred", false);
        systemMessageService.createAndPublish(convId, actorId, SystemEventType.OWNER_TRANSFERRED, ownerTransferMeta);

        return new OwnerTransferResponse(
                PreviousOwnerDto.fromAdmin(actorUser),
                ActorSummaryDto.from(targetUser)
        );
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Anti-enumeration: return membership for caller or throw 404 CONV_NOT_FOUND.
     * Merge "conv không tồn tại" + "không phải member" → 404 (tránh leak existence).
     */
    private ConversationMember requireMembership(UUID convId, UUID userId) {
        return memberRepository.findByConversation_IdAndUser_Id(convId, userId)
                .orElseThrow(() -> convNotFound());
    }

    private static AppException convNotFound() {
        return new AppException(HttpStatus.NOT_FOUND, "CONV_NOT_FOUND",
                "Conversation không tồn tại hoặc bạn không phải thành viên");
    }
}
