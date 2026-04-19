package com.chatapp.auth.dto.response;

import com.chatapp.user.entity.User;

/**
 * DTO đại diện user info trong auth response.
 * Không bao giờ expose User entity trực tiếp ra API.
 */
public record UserDto(
        String id,
        String username,
        String email,
        String fullName,
        String avatarUrl
) {

    /**
     * Factory method convert từ User entity.
     * avatarUrl có thể null (chưa upload avatar).
     */
    public static UserDto from(User user) {
        return new UserDto(
                user.getId().toString(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getAvatarUrl()
        );
    }
}
