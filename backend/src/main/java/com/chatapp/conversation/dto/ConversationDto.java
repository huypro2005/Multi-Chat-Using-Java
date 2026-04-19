package com.chatapp.conversation.dto;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.enums.ConversationType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full conversation response — dùng cho POST /api/conversations (201) và GET /api/conversations/{id} (200).
 *
 * Shape theo contract v0.5.0-conversations.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConversationDto(
        UUID id,
        ConversationType type,
        String name,
        String avatarUrl,
        CreatedByDto createdBy,
        List<MemberDto> members,
        Instant createdAt,
        Instant lastMessageAt
) {
    /**
     * Build từ Conversation entity (phải đã JOIN FETCH members và members.user).
     */
    public static ConversationDto from(Conversation conv) {
        List<MemberDto> memberDtos = conv.getMembers().stream()
                .map(MemberDto::from)
                .toList();

        return new ConversationDto(
                conv.getId(),
                conv.getType(),
                conv.getName(),
                conv.getAvatarUrl(),
                CreatedByDto.from(conv.getCreatedBy()),
                memberDtos,
                conv.getCreatedAt() != null ? conv.getCreatedAt().toInstant() : null,
                conv.getLastMessageAt() != null ? conv.getLastMessageAt().toInstant() : null
        );
    }
}
