package com.chatapp.conversation.service;

import com.chatapp.conversation.dto.*;
import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.MemberRole;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.exception.AppException;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    // =========================================================================
    // createConversation
    // =========================================================================

    @Transactional
    public ConversationDto createConversation(UUID currentUserId, CreateConversationRequest req) {
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Người dùng không tồn tại"));

        if (req.type() == ConversationType.ONE_ON_ONE) {
            return createOneOnOne(currentUser, req);
        } else {
            return createGroup(currentUser, req);
        }
    }

    private ConversationDto createOneOnOne(User currentUser, CreateConversationRequest req) {
        // Validate: phải đúng 1 member
        if (req.memberIds() == null || req.memberIds().size() != 1) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "ONE_ON_ONE phải có đúng 1 memberIds",
                    Map.of("fields", Map.of("memberIds", "ONE_ON_ONE phải có đúng 1 thành viên")));
        }

        UUID targetUserId = req.memberIds().get(0);

        // Validate: không được chat với chính mình
        if (currentUser.getId().equals(targetUserId)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Không thể tạo conversation với chính mình",
                    Map.of("fields", Map.of("memberIds", "memberIds không được chứa ID của chính bạn")));
        }

        // Validate: target user tồn tại
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "CONV_MEMBER_NOT_FOUND",
                        "Người dùng không tồn tại",
                        Map.of("missingIds", List.of(targetUserId))));

        // Check existing ONE_ON_ONE (native query returns String to handle H2/PG UUID differences)
        Optional<String> existing = conversationRepository.findExistingOneOnOne(
                currentUser.getId().toString(), targetUserId.toString());
        if (existing.isPresent()) {
            throw new AppException(HttpStatus.CONFLICT, "CONV_ONE_ON_ONE_EXISTS",
                    "Đã tồn tại cuộc trò chuyện 1-1 giữa hai người dùng này",
                    Map.of("conversationId", existing.get()));
        }

        // Tạo conversation mới
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
        return ConversationDto.from(saved);
    }

    private ConversationDto createGroup(User currentUser, CreateConversationRequest req) {
        // Validate: name required 1-100 chars
        String name = req.name();
        if (name == null || name.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
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
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
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
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "GROUP phải có ít nhất 2 memberIds",
                    Map.of("fields", Map.of("memberIds", "GROUP cần ít nhất 2 thành viên (không tính bạn)")));
        }

        // Validate: max 49 other members (total = 50 including caller)
        if (uniqueMemberIds.size() > 49) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Group cannot have more than 50 members",
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
            throw new AppException(HttpStatus.NOT_FOUND, "CONV_MEMBER_NOT_FOUND",
                    "Một hoặc nhiều memberIds không tồn tại",
                    Map.of("missingIds", missingIds));
        }

        // Tạo conversation
        Conversation conversation = Conversation.builder()
                .type(ConversationType.GROUP)
                .name(name)
                .createdBy(currentUser)
                .build();
        conversation = conversationRepository.save(conversation);

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
        return ConversationDto.from(saved);
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
        String avatarUrl = row[3] != null ? row[3].toString() : null;
        // last_message_at (row[4]) and created_at (row[5])
        java.time.Instant lastMessageAt = null;
        if (row[4] != null) {
            // JDBC returns java.sql.Timestamp or OffsetDateTime
            Object rawTs = row[4];
            if (rawTs instanceof java.sql.Timestamp ts) {
                lastMessageAt = ts.toInstant();
            } else if (rawTs instanceof java.time.OffsetDateTime odt) {
                lastMessageAt = odt.toInstant();
            } else {
                lastMessageAt = java.time.Instant.parse(rawTs.toString());
            }
        }
        long memberCount = ((Number) row[6]).longValue();

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

        return new ConversationSummaryDto(
                convId,
                type,
                name,
                avatarUrl,
                displayName,
                displayAvatarUrl,
                (int) memberCount,
                lastMessageAt,
                0, // V1 placeholder
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

        Conversation conversation = conversationRepository.findByIdWithMembers(conversationId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "CONV_NOT_FOUND",
                        "Conversation không tồn tại"));

        return ConversationDto.from(conversation);
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
}
