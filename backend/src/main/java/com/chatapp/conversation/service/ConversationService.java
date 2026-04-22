package com.chatapp.conversation.service;

import com.chatapp.conversation.dto.*;
import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.MemberRole;
import com.chatapp.conversation.event.ConversationUpdatedEvent;
import com.chatapp.conversation.event.GroupDeletedEvent;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.exception.AppException;
import com.chatapp.file.constant.FileConstants;
import com.chatapp.file.entity.FileRecord;
import com.chatapp.file.repository.FileRecordRepository;
import com.chatapp.message.constant.SystemEventType;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.message.service.SystemMessageService;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final FileRecordRepository fileRecordRepository;
    private final MessageRepository messageRepository;
    private final EntityManager entityManager;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final SystemMessageService systemMessageService;

    private static final Set<String> IMAGE_MIMES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );


    // =========================================================================
    // createConversation
    // =========================================================================

    @Transactional
    public ConversationDto createConversation(UUID currentUserId, CreateConversationRequest req) {
        // Rate limit: 10 creates/min per user (W3-BE-6) — fail-open nếu Redis down (ADR-011)
        String rateKey = "rate:conv_create:" + currentUserId;
        Long count = null;
        try {
            count = redisTemplate.opsForValue().increment(rateKey);
            if (count != null && count == 1) {
                redisTemplate.expire(rateKey, 60, TimeUnit.SECONDS);
            }
        } catch (DataAccessException e) {
            log.warn("Redis unavailable for rate limit check, proceeding without limit: {}", e.getMessage());
        }
        if (count != null && count > 10) {
            long ttl = 60; // default fallback
            try {
                Long redisTtl = redisTemplate.getExpire(rateKey, TimeUnit.SECONDS);
                if (redisTtl != null && redisTtl > 0) ttl = redisTtl;
            } catch (DataAccessException ignored) {}
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many conversations created. Please wait.",
                    Map.of("retryAfterSeconds", ttl));
        }

        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Người dùng không tồn tại"));

        if (req.type() == ConversationType.ONE_ON_ONE) {
            return createOneOnOne(currentUser, req);
        } else {
            return createGroup(currentUser, req);
        }
    }

    private ConversationDto createOneOnOne(User currentUser, CreateConversationRequest req) {
        // W7 backward-compat: accept targetUserId (new) hoặc memberIds[0] (legacy V3-V6).
        UUID targetUserId = req.targetUserId();
        if (targetUserId == null) {
            if (req.memberIds() == null || req.memberIds().size() != 1) {
                throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "ONE_ON_ONE yêu cầu targetUserId (mới) hoặc memberIds=[uuid] (deprecated)",
                        Map.of("fields", Map.of("targetUserId", "ONE_ON_ONE phải có targetUserId")));
            }
            targetUserId = req.memberIds().get(0);
        }

        // W7: name không được set cho ONE_ON_ONE
        if (req.name() != null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "ONE_ON_ONE không được có name",
                    Map.of("fields", Map.of("name", "ONE_ON_ONE không có tên")));
        }

        // Validate: không được chat với chính mình
        if (currentUser.getId().equals(targetUserId)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Không thể tạo conversation với chính mình",
                    Map.of("fields", Map.of("targetUserId", "targetUserId không được là chính bạn")));
        }

        // Validate: target user tồn tại
        final UUID finalTargetUserId = targetUserId;
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "CONV_MEMBER_NOT_FOUND",
                        "Người dùng không tồn tại",
                        Map.of("missingIds", List.of(finalTargetUserId))));

        // Check existing ONE_ON_ONE (native query returns String to handle H2/PG UUID differences)
        Optional<String> existing = conversationRepository.findExistingOneOnOne(
                currentUser.getId().toString(), targetUserId.toString());
        if (existing.isPresent()) {
            throw new AppException(HttpStatus.CONFLICT, "CONV_ONE_ON_ONE_EXISTS",
                    "Đã tồn tại cuộc trò chuyện 1-1 giữa hai người dùng này",
                    Map.of("conversationId", existing.get()));
        }

        // Tạo conversation mới (ONE_ON_ONE — KHÔNG set name/owner_id để pass CHECK constraint)
        Conversation conversation = Conversation.builder()
                .type(ConversationType.ONE_ON_ONE)
                .createdBy(currentUser)
                .build();
        conversation = conversationRepository.save(conversation);

        // Tạo 2 members
        ConversationMember ownerMember = ConversationMember.builder()
                .conversation(conversation)
                .user(currentUser)
                .role(MemberRole.OWNER)
                .build();
        ConversationMember targetMember = ConversationMember.builder()
                .conversation(conversation)
                .user(targetUser)
                .role(MemberRole.MEMBER)
                .build();
        memberRepository.save(ownerMember);
        memberRepository.save(targetMember);

        // Flush để persist members, clear 1st-level cache để force reload từ DB
        entityManager.flush();
        entityManager.clear();
        Conversation saved = conversationRepository.findByIdWithMembers(conversation.getId())
                .orElseThrow();
        return ConversationDto.from(saved, this::resolveUser);
    }

    private ConversationDto createGroup(User currentUser, CreateConversationRequest req) {
        // Validate: targetUserId KHÔNG được set cho GROUP
        if (req.targetUserId() != null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "GROUP không dùng targetUserId — dùng memberIds",
                    Map.of("fields", Map.of("targetUserId", "GROUP dùng memberIds")));
        }

        // Validate: name required 1-100 chars sau trim
        String name = req.name();
        if (name == null || name.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "GROUP_NAME_REQUIRED",
                    "Tên nhóm là bắt buộc cho GROUP",
                    Map.of("fields", Map.of("name", "Tên nhóm không được để trống")));
        }
        name = name.trim();
        if (name.length() > 100) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Tên nhóm quá dài",
                    Map.of("fields", Map.of("name", "Tên nhóm tối đa 100 ký tự")));
        }

        // Validate: memberIds not null
        if (req.memberIds() == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "GROUP_MEMBERS_MIN",
                    "GROUP phải có ít nhất 2 memberIds",
                    Map.of("fields", Map.of("memberIds", "GROUP cần ít nhất 2 thành viên (không tính bạn)")));
        }

        // Validate: caller không được nằm trong memberIds
        if (req.memberIds().contains(currentUser.getId())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Cannot add yourself to the member list",
                    Map.of("fields", Map.of("memberIds", "memberIds không được chứa ID của chính bạn")));
        }

        // Dedupe memberIds
        List<UUID> uniqueMemberIds = req.memberIds().stream().distinct().toList();

        // Validate: >= 2 unique other members
        if (uniqueMemberIds.size() < 2) {
            throw new AppException(HttpStatus.BAD_REQUEST, "GROUP_MEMBERS_MIN",
                    "GROUP phải có ít nhất 2 memberIds unique",
                    Map.of("fields", Map.of("memberIds", "GROUP cần ít nhất 2 thành viên (không tính bạn)")));
        }

        // Validate: max 49 other members (total = 50 including caller)
        if (uniqueMemberIds.size() > 49) {
            throw new AppException(HttpStatus.BAD_REQUEST, "GROUP_MEMBERS_MAX",
                    "Nhóm không được có quá 50 thành viên",
                    Map.of("fields", Map.of("memberIds", "Nhóm không được có quá 50 thành viên (bao gồm bạn)")));
        }

        // Validate: tất cả userIds tồn tại
        List<UUID> missingIds = new ArrayList<>();
        List<User> targetUsers = new ArrayList<>();
        for (UUID memberId : uniqueMemberIds) {
            userRepository.findById(memberId).ifPresentOrElse(
                    targetUsers::add,
                    () -> missingIds.add(memberId)
            );
        }
        if (!missingIds.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "GROUP_MEMBER_NOT_FOUND",
                    "Một hoặc nhiều memberIds không tồn tại",
                    Map.of("missingIds", missingIds));
        }

        // Validate avatar (optional) — trước khi persist conversation
        FileRecord avatarFile = null;
        if (req.avatarFileId() != null) {
            avatarFile = validateGroupAvatar(req.avatarFileId(), currentUser.getId());
        }

        // W7-D4-fix (ADR-021): group không có custom avatar → fallback DEFAULT_GROUP_AVATAR.
        // Rationale: mọi group PHẢI có avatar (default nếu không custom) → FE không null-check.
        UUID finalAvatarFileId = avatarFile != null
                ? avatarFile.getId()
                : FileConstants.DEFAULT_GROUP_AVATAR_ID;

        // Tạo conversation
        Conversation conversation = Conversation.builder()
                .type(ConversationType.GROUP)
                .name(name)
                .ownerId(currentUser.getId())
                .avatarFileId(finalAvatarFileId)
                .createdBy(currentUser)
                .build();
        conversation = conversationRepository.save(conversation);

        // Attach avatar file (mark attached_at để tránh orphan cleanup)
        if (avatarFile != null) {
            avatarFile.markAttached();
            fileRecordRepository.save(avatarFile);
        }

        // Tạo members: currentUser = OWNER
        ConversationMember ownerMember = ConversationMember.builder()
                .conversation(conversation)
                .user(currentUser)
                .role(MemberRole.OWNER)
                .build();
        memberRepository.save(ownerMember);

        for (User targetUser : targetUsers) {
            ConversationMember member = ConversationMember.builder()
                    .conversation(conversation)
                    .user(targetUser)
                    .role(MemberRole.MEMBER)
                    .build();
            memberRepository.save(member);
        }

        // Flush để persist members, clear 1st-level cache để force reload từ DB
        entityManager.flush();
        entityManager.clear();
        Conversation saved = conversationRepository.findByIdWithMembers(conversation.getId())
                .orElseThrow();

        // W7-D4: GROUP_CREATED system message — fire within same @Transactional
        systemMessageService.createAndPublish(
                conversation.getId(),
                currentUser.getId(),
                SystemEventType.GROUP_CREATED,
                Collections.emptyMap()
        );

        return ConversationDto.from(saved, this::resolveUser);
    }

    // =========================================================================
    // listConversations
    // =========================================================================

    @Transactional(readOnly = true)
    public ConversationListResponse listConversations(UUID currentUserId, int page, int pageSize) {
        if (page < 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "page phải >= 0",
                    Map.of("fields", Map.of("page", "page phải >= 0")));
        }
        if (pageSize <= 0 || pageSize > 50) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "size phải trong khoảng 1-50",
                    Map.of("fields", Map.of("size", "size phải trong khoảng 1-50")));
        }

        int offset = page * pageSize;
        long total = conversationRepository.countConversationsByUser(currentUserId.toString());
        int totalPages = (int) Math.ceil((double) total / pageSize);

        List<Object[]> rows = conversationRepository.findConversationsByUserPaginated(
                currentUserId.toString(), pageSize, offset);

        // For each conversation, build summary.
        // For ONE_ON_ONE: load other member for displayName/displayAvatarUrl.
        // To avoid N+1, batch load all conversation IDs with members.
        List<UUID> convIds = rows.stream()
                .map(row -> UUID.fromString(row[0].toString()))
                .toList();

        // Batch load memberships with user info for these conversations
        Map<UUID, List<ConversationMember>> membersByConvId = new HashMap<>();
        if (!convIds.isEmpty()) {
            // Load via findByIdWithMembers for each conv — acceptable for V1 (small list)
            for (UUID convId : convIds) {
                conversationRepository.findByIdWithMembers(convId).ifPresent(c ->
                        membersByConvId.put(c.getId(), new ArrayList<>(c.getMembers()))
                );
            }
        }

        // Load caller's membership for mutedUntil
        Map<UUID, ConversationMember> myMemberships = membersByConvId.values().stream()
                .flatMap(List::stream)
                .filter(m -> currentUserId.equals(m.getUser().getId()))
                .collect(Collectors.toMap(
                        m -> m.getConversation().getId(),
                        m -> m,
                        (a, b) -> a
                ));

        List<ConversationSummaryDto> summaries = rows.stream()
                .map(row -> buildSummary(row, currentUserId, membersByConvId, myMemberships))
                .toList();

        return new ConversationListResponse(summaries, page, pageSize, total, totalPages);
    }

    private ConversationSummaryDto buildSummary(
            Object[] row,
            UUID currentUserId,
            Map<UUID, List<ConversationMember>> membersByConvId,
            Map<UUID, ConversationMember> myMemberships
    ) {
        UUID convId = UUID.fromString(row[0].toString());
        ConversationType type = ConversationType.valueOf(row[1].toString());
        String name = row[2] != null ? row[2].toString() : null;
        // row[3] = avatar_url (legacy), row[4] = avatar_file_id (W7)
        // W7-D4-fix (ADR-021): dùng /public endpoint cho avatar_file_id.
        String legacyAvatarUrl = row[3] != null ? row[3].toString() : null;
        String avatarFileId = row[4] != null ? row[4].toString() : null;
        String avatarUrl = avatarFileId != null
                ? FileConstants.publicUrl(UUID.fromString(avatarFileId))
                : legacyAvatarUrl;
        // last_message_at (row[5]) — shifted from row[4] after adding avatar_file_id column
        java.time.Instant lastMessageAt = null;
        if (row[5] != null) {
            // JDBC returns java.sql.Timestamp or OffsetDateTime
            Object rawTs = row[5];
            if (rawTs instanceof java.sql.Timestamp ts) {
                lastMessageAt = ts.toInstant();
            } else if (rawTs instanceof java.time.OffsetDateTime odt) {
                lastMessageAt = odt.toInstant();
            } else {
                lastMessageAt = java.time.Instant.parse(rawTs.toString());
            }
        }
        long memberCount = ((Number) row[7]).longValue();

        // Compute displayName / displayAvatarUrl
        String displayName = name; // GROUP default
        String displayAvatarUrl = avatarUrl;

        if (type == ConversationType.ONE_ON_ONE) {
            // Find the OTHER member
            List<ConversationMember> members = membersByConvId.getOrDefault(convId, Collections.emptyList());
            Optional<ConversationMember> other = members.stream()
                    .filter(m -> !currentUserId.equals(m.getUser().getId()))
                    .findFirst();
            if (other.isPresent()) {
                displayName = other.get().getUser().getFullName();
                displayAvatarUrl = other.get().getUser().getAvatarUrl();
            }
        }

        // mutedUntil from caller's membership
        java.time.Instant mutedUntil = null;
        ConversationMember myMembership = myMemberships.get(convId);
        if (myMembership != null && myMembership.getMutedUntil() != null) {
            mutedUntil = myMembership.getMutedUntil().toInstant();
        }

        // unreadCount: server-computed per-caller (v1.4.0-w7-read)
        // Count non-SYSTEM, non-deleted messages after caller's lastReadMessageId.createdAt.
        // lastReadMessageId = null → count all (user has never marked read in this conv).
        int unreadCount = 0;
        try {
            UUID lastReadMsgId = myMembership != null ? myMembership.getLastReadMessageId() : null;
            String lastReadIdStr = lastReadMsgId != null ? lastReadMsgId.toString() : null;
            unreadCount = (int) messageRepository.countUnread(convId.toString(), lastReadIdStr);
        } catch (Exception e) {
            // Fail-graceful: log warn, return 0 to avoid breaking list endpoint
            log.warn("[UNREAD] Failed to compute unreadCount for convId={}: {}", convId, e.getMessage());
        }

        return new ConversationSummaryDto(
                convId,
                type,
                name,
                avatarUrl,
                displayName,
                displayAvatarUrl,
                (int) memberCount,
                lastMessageAt,
                unreadCount,
                mutedUntil
        );
    }

    // =========================================================================
    // getConversation
    // =========================================================================

    @Transactional(readOnly = true)
    public ConversationDto getConversation(UUID currentUserId, UUID conversationId) {
        // Check membership — 404 cho cả not-exist + not-member (anti-enumeration)
        memberRepository.findByConversation_IdAndUser_Id(conversationId, currentUserId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "CONV_NOT_FOUND",
                        "Conversation không tồn tại hoặc bạn không phải thành viên"));

        // Filter soft-deleted — trả 404 nếu group đã delete (anti-enumeration)
        Conversation conversation = conversationRepository.findActiveByIdWithMembers(conversationId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "CONV_NOT_FOUND",
                        "Conversation không tồn tại"));

        return ConversationDto.from(conversation, this::resolveUser);
    }

    // =========================================================================
    // W7-D1: updateGroupInfo — PATCH /api/conversations/{id}
    // =========================================================================

    @Transactional
    public ConversationDto updateGroupInfo(UUID currentUserId, UUID conversationId, UpdateGroupRequest req) {
        // Validate: phải có ít nhất 1 field
        if (req.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Cần ít nhất 1 field (name hoặc avatarFileId)",
                    Map.of("fields", Map.of("_", "Body không được rỗng")));
        }

        // Load membership — anti-enumeration (404 cho non-member hoặc conv không tồn tại)
        ConversationMember member = memberRepository
                .findByConversation_IdAndUser_Id(conversationId, currentUserId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "CONV_NOT_FOUND",
                        "Conversation không tồn tại hoặc bạn không phải thành viên"));

        // Load active conversation (filter soft-deleted)
        Conversation conv = conversationRepository.findActiveById(conversationId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "CONV_NOT_FOUND",
                        "Conversation không tồn tại"));

        // Shape check: PATCH chỉ cho GROUP
        if (!conv.isGroup()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "NOT_GROUP",
                    "PATCH chỉ áp dụng cho GROUP conversation");
        }

        // Authorization: OWNER hoặc ADMIN mới được rename/đổi avatar
        if (!member.getRole().canRename()) {
            throw new AppException(HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION",
                    "Chỉ OWNER hoặc ADMIN mới được sửa thông tin nhóm");
        }

        // Build changes map + apply updates
        Map<String, Object> changes = new LinkedHashMap<>();
        String oldGroupName = conv.getName(); // capture before any update (for GROUP_RENAMED metadata)

        if (req.hasName()) {
            String newName = req.getName();
            if (newName == null || newName.isBlank()) {
                throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "Tên nhóm không được rỗng",
                        Map.of("fields", Map.of("name", "name không được để trống")));
            }
            String trimmed = newName.trim();
            if (trimmed.length() > 100) {
                throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "Tên nhóm quá dài",
                        Map.of("fields", Map.of("name", "Tên nhóm tối đa 100 ký tự")));
            }
            if (!trimmed.equals(conv.getName())) {
                conv.setName(trimmed);
                changes.put("name", trimmed);
            }
        }

        if (req.hasAvatarFileId()) {
            if (req.isRemoveAvatar()) {
                // W7-D4-fix (ADR-021): FALLBACK về DEFAULT_GROUP_AVATAR thay vì để NULL.
                // Mọi group PHẢI có avatar (default nếu không custom) → FE không null-check.
                UUID currentAvatarId = conv.getAvatarFileId();
                if (!FileConstants.DEFAULT_GROUP_AVATAR_ID.equals(currentAvatarId)) {
                    conv.setAvatarFileId(FileConstants.DEFAULT_GROUP_AVATAR_ID);
                    changes.put("avatarUrl", FileConstants.DEFAULT_GROUP_AVATAR_URL);
                }
            } else {
                UUID newAvatarId = req.getAvatarFileId();
                if (newAvatarId == null) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                            "avatarFileId không hợp lệ",
                            Map.of("fields", Map.of("avatarFileId", "UUID không hợp lệ")));
                }
                FileRecord avatarFile = validateGroupAvatar(newAvatarId, currentUserId);
                avatarFile.markAttached();
                fileRecordRepository.save(avatarFile);

                conv.setAvatarFileId(newAvatarId);
                changes.put("avatarUrl", FileConstants.publicUrl(newAvatarId));
            }
        }

        if (changes.isEmpty()) {
            // Field có mặt nhưng giá trị trùng cũ — trả response hiện tại, không broadcast
            entityManager.flush();
            entityManager.clear();
            Conversation reloaded = conversationRepository.findActiveByIdWithMembers(conversationId)
                    .orElseThrow();
            return ConversationDto.from(reloaded, this::resolveUser);
        }

        conversationRepository.save(conv);

        // W7-D4: GROUP_RENAMED system message — only when name actually changed
        if (changes.containsKey("name")) {
            Map<String, Object> renamedMeta = new java.util.HashMap<>();
            renamedMeta.put("oldValue", oldGroupName != null ? oldGroupName : "");
            renamedMeta.put("newValue", changes.get("name").toString());
            systemMessageService.createAndPublish(
                    conversationId,
                    currentUserId,
                    SystemEventType.GROUP_RENAMED,
                    renamedMeta
            );
        }

        // Publish event — broadcaster fire AFTER_COMMIT qua /topic/conv.{id}
        User actor = userRepository.findById(currentUserId).orElse(null);
        String actorFullName = actor != null ? actor.getFullName() : "Unknown";
        eventPublisher.publishEvent(new ConversationUpdatedEvent(
                conversationId,
                changes,
                currentUserId,
                actorFullName,
                Instant.now()
        ));

        entityManager.flush();
        entityManager.clear();
        Conversation reloaded = conversationRepository.findActiveByIdWithMembers(conversationId)
                .orElseThrow();
        return ConversationDto.from(reloaded, this::resolveUser);
    }

    // =========================================================================
    // W7-D1: deleteGroup — DELETE /api/conversations/{id}
    // =========================================================================

    @Transactional
    public void deleteGroup(UUID currentUserId, UUID conversationId) {
        // Anti-enumeration: 404 cho non-member
        ConversationMember member = memberRepository
                .findByConversation_IdAndUser_Id(conversationId, currentUserId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "CONV_NOT_FOUND",
                        "Conversation không tồn tại hoặc bạn không phải thành viên"));

        Conversation conv = conversationRepository.findActiveById(conversationId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "CONV_NOT_FOUND",
                        "Conversation không tồn tại"));

        if (!conv.isGroup()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "NOT_GROUP",
                    "DELETE chỉ áp dụng cho GROUP conversation");
        }

        // Authorization: chỉ OWNER
        if (!member.getRole().canDeleteGroup()) {
            throw new AppException(HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION",
                    "Chỉ OWNER mới được xoá nhóm");
        }

        // Side effect ordering theo contract:
        // 1) Set deleted_at
        // 2) Detach avatar (conversations.avatar_file_id = NULL)
        // 3) Hard-delete conversation_members
        // 4) Publish event — broadcaster fire AFTER_COMMIT (members đã hard-deleted
        //    nhưng subscription active vẫn nhận frame cuối).
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        conv.setDeletedAt(now);
        conv.setAvatarFileId(null); // detach avatar
        conversationRepository.save(conv);

        // Hard-delete all members
        memberRepository.deleteByConversation_Id(conversationId);

        // Publish event
        User actor = userRepository.findById(currentUserId).orElse(null);
        String actorFullName = actor != null ? actor.getFullName() : "Unknown";
        eventPublisher.publishEvent(new GroupDeletedEvent(
                conversationId,
                currentUserId,
                actorFullName,
                now.toInstant()
        ));
    }

    // =========================================================================
    // getUserById
    // =========================================================================

    @Transactional(readOnly = true)
    public UserSearchDto getUserById(UUID id) {
        User user = userRepository.findById(id)
                .filter(u -> "active".equals(u.getStatus()))
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
        return UserSearchDto.from(user);
    }

    // =========================================================================
    // searchUsers
    // =========================================================================

    @Transactional(readOnly = true)
    public List<UserSearchDto> searchUsers(UUID currentUserId, String query, int limit) {
        if (query == null || query.trim().length() < 2) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Query phải có ít nhất 2 ký tự",
                    Map.of("fields", Map.of("q", "q phải có ít nhất 2 ký tự sau khi trim")));
        }
        String q = query.trim();
        if (q.length() > 50) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Query quá dài",
                    Map.of("fields", Map.of("q", "q tối đa 50 ký tự")));
        }
        if (limit < 1 || limit > 20) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "limit phải trong khoảng 1-20",
                    Map.of("fields", Map.of("limit", "limit phải trong khoảng 1-20")));
        }

        return userRepository.searchUsers(q, currentUserId, PageRequest.of(0, limit))
                .stream()
                .map(UserSearchDto::from)
                .toList();
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Validate avatar file cho GROUP (dùng chung giữa createGroup và updateGroupInfo).
     * Rule:
     *  - File tồn tại (else GROUP_AVATAR_NOT_OWNED — merge anti-enum với "not owned").
     *  - File.uploader_id == callerId (else GROUP_AVATAR_NOT_OWNED).
     *  - File MIME trong whitelist image/jpeg|png|webp|gif (else GROUP_AVATAR_NOT_IMAGE).
     *  - File chưa expired.
     *
     * @return FileRecord entity đã được verify (caller có thể set attached_at sau).
     */
    private FileRecord validateGroupAvatar(UUID avatarFileId, UUID callerId) {
        FileRecord file = fileRecordRepository.findById(avatarFileId)
                .orElseThrow(() -> new AppException(HttpStatus.FORBIDDEN, "GROUP_AVATAR_NOT_OWNED",
                        "File avatar không tồn tại hoặc bạn không có quyền sử dụng"));

        // Anti-enum: trả cùng code cho "not owned" và "not found"
        if (!callerId.equals(file.getUploaderId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "GROUP_AVATAR_NOT_OWNED",
                    "File avatar không tồn tại hoặc bạn không có quyền sử dụng");
        }

        if (file.isExpired()) {
            throw new AppException(HttpStatus.FORBIDDEN, "GROUP_AVATAR_NOT_OWNED",
                    "File avatar đã hết hạn hoặc không có quyền sử dụng");
        }

        String mime = file.getMime();
        if (mime == null || !IMAGE_MIMES.contains(mime.toLowerCase())) {
            throw new AppException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "GROUP_AVATAR_NOT_IMAGE",
                    "Avatar phải là ảnh (jpeg/png/webp/gif)",
                    Map.of("actualMime", mime != null ? mime : "unknown"));
        }

        // ADR-021: avatar files must be public so /public endpoint serves them.
        // Auto-flip any file uploaded without ?public=true (defense-in-depth).
        if (!file.isPublic()) {
            file.setPublic(true);
            fileRecordRepository.save(file);
            log.info("[AVATAR] Auto-flipped is_public=false→true for file {} (ADR-021)", file.getId());
        }

        return file;
    }

    /**
     * Resolve User entity cho ownerResolver của ConversationDto.from.
     * Trả null nếu không tìm thấy (V1 edge case khi OWNER bị xoá — owner_id = NULL sau cascade).
     */
    private User resolveUser(UUID userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).orElse(null);
    }

}
