package com.chatapp.conversation.dto;

import com.chatapp.user.entity.User;

import java.util.UUID;

/**
 * Minimal DTO cho owner của GROUP conversation (W7-D1).
 *
 * Shape: {userId, username, fullName} — KHÔNG expose avatarUrl (FE đã có full info trong members[]).
 * Lý do: tránh duplicate data cho sidebar/title bar, reduce payload size.
 *
 * Trả NULL khi ONE_ON_ONE hoặc khi OWNER đã bị xoá account (owner_id = NULL sau cascade).
 */
public record OwnerDto(
        UUID userId,
        String username,
        String fullName
) {
    public static OwnerDto from(User user) {
        if (user == null) return null;
        return new OwnerDto(user.getId(), user.getUsername(), user.getFullName());
    }
}
