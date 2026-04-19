package com.chatapp.conversation.dto;

import com.chatapp.user.entity.User;

import java.util.UUID;

/**
 * DTO cho GET /api/users/search response.
 * KHÔNG bao gồm email — email là PII, chỉ owner xem được qua self-profile.
 */
public record UserSearchDto(
        UUID id,
        String username,
        String fullName,
        String avatarUrl
) {
    public static UserSearchDto from(User user) {
        return new UserSearchDto(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getAvatarUrl()
        );
    }
}
