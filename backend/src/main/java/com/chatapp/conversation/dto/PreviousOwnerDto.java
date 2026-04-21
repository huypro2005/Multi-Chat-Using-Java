package com.chatapp.conversation.dto;

import com.chatapp.user.entity.User;

import java.util.UUID;

/**
 * Previous owner shape trong OwnerTransferResponse (W7-D2).
 *
 * Shape: {userId, username, newRole: "ADMIN"} — newRole luôn ADMIN theo contract
 * /transfer-owner semantics (OWNER cũ giữ quyền quản lý, KHÔNG về MEMBER).
 */
public record PreviousOwnerDto(
        UUID userId,
        String username,
        String newRole
) {
    public static PreviousOwnerDto fromAdmin(User user) {
        if (user == null) return null;
        return new PreviousOwnerDto(user.getId(), user.getUsername(), "ADMIN");
    }
}
