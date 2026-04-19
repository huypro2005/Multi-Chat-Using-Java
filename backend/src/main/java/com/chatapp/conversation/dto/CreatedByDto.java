package com.chatapp.conversation.dto;

import com.chatapp.user.entity.User;

import java.util.UUID;

/**
 * DTO đại diện creator của conversation.
 * Dùng trong ConversationDto.createdBy.
 */
public record CreatedByDto(
        UUID id,
        String username,
        String fullName,
        String avatarUrl
) {
    public static CreatedByDto from(User user) {
        if (user == null) return null;
        return new CreatedByDto(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getAvatarUrl()
        );
    }
}
