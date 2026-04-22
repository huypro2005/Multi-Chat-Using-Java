package com.chatapp.conversation.dto;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.file.constant.FileConstants;
import com.chatapp.user.entity.User;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Full conversation response — dùng cho POST /api/conversations (201), PATCH /{id} (200),
 * và GET /api/conversations/{id} (200).
 *
 * Shape theo contract v1.0.0-w7:
 *  - `name`, `avatarUrl`: null cho ONE_ON_ONE.
 *  - `owner`: null cho ONE_ON_ONE hoặc khi OWNER đã bị xoá; có cho GROUP.
 *  - `members`: sort theo role DESC (OWNER đầu), rồi joinedAt ASC (cũ nhất trước).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConversationDto(
        UUID id,
        ConversationType type,
        String name,
        String avatarUrl,
        OwnerDto owner,
        CreatedByDto createdBy,
        List<MemberDto> members,
        Instant createdAt,
        Instant lastMessageAt
) {
    /**
     * Build từ Conversation entity (phải đã JOIN FETCH members và members.user).
     *
     * @param conv entity đã load members
     * @param ownerResolver function load User entity theo UUID cho owner (có thể trả null).
     *                      Cho phép caller inject UserRepository.findById::orElse(null).
     */
    public static ConversationDto from(Conversation conv, Function<UUID, User> ownerResolver) {
        // Sort members: role DESC (OWNER, ADMIN, MEMBER), joinedAt ASC (cũ nhất trước).
        // Enum ordering — OWNER=0, ADMIN=1, MEMBER=2 → dùng role.ordinal() ASC để OWNER đầu.
        List<MemberDto> memberDtos = conv.getMembers().stream()
                .sorted(Comparator
                        .comparing((com.chatapp.conversation.entity.ConversationMember m) ->
                                m.getRole() != null ? m.getRole().ordinal() : Integer.MAX_VALUE)
                        .thenComparing(m -> m.getJoinedAt() != null
                                ? m.getJoinedAt().toInstant()
                                : Instant.MAX))
                .map(MemberDto::from)
                .toList();

        // Owner resolution — chỉ cho GROUP và khi ownerId non-null.
        OwnerDto owner = null;
        if (conv.getType() == ConversationType.GROUP && conv.getOwnerId() != null) {
            User ownerUser = ownerResolver != null ? ownerResolver.apply(conv.getOwnerId()) : null;
            owner = OwnerDto.from(ownerUser);
        }

        // avatarUrl (W7-D4-fix, ADR-021): dùng /public endpoint — native <img src> load OK.
        String avatarUrl = null;
        if (conv.getAvatarFileId() != null) {
            avatarUrl = FileConstants.publicUrl(conv.getAvatarFileId());
        } else if (conv.getAvatarUrl() != null) {
            avatarUrl = conv.getAvatarUrl();
        }

        return new ConversationDto(
                conv.getId(),
                conv.getType(),
                conv.getName(),
                avatarUrl,
                owner,
                CreatedByDto.from(conv.getCreatedBy()),
                memberDtos,
                conv.getCreatedAt() != null ? conv.getCreatedAt().toInstant() : null,
                conv.getLastMessageAt() != null ? conv.getLastMessageAt().toInstant() : null
        );
    }

    /**
     * Backward-compat overload — khi không cần resolve owner (ONE_ON_ONE hoặc test đơn giản).
     * Pass null resolver → owner luôn null.
     */
    public static ConversationDto from(Conversation conv) {
        return from(conv, null);
    }
}
