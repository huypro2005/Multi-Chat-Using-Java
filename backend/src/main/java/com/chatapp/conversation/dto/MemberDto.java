package com.chatapp.conversation.dto;

import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.MemberRole;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO đại diện một thành viên trong conversation.
 * Dùng trong ConversationDto.members[].
 *
 * lastReadMessageId (v1.4.0-w7-read): UUID của message cuối cùng member đã đánh dấu đọc.
 * null = chưa bao giờ đánh dấu. Update trigger: STOMP /app/conv.{id}.read (§3f).
 */
public record MemberDto(
        UUID userId,
        String username,
        String fullName,
        String avatarUrl,
        MemberRole role,
        Instant joinedAt,
        UUID lastReadMessageId
) {
    public static MemberDto from(ConversationMember member) {
        return new MemberDto(
                member.getUser().getId(),
                member.getUser().getUsername(),
                member.getUser().getFullName(),
                member.getUser().getAvatarUrl(),
                member.getRole(),
                member.getJoinedAt().toInstant(),
                member.getLastReadMessageId()
        );
    }
}
