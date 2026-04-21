package com.chatapp.conversation.dto;

import com.chatapp.user.entity.User;

import java.util.UUID;

/**
 * Minimal actor shape {userId, username} — dùng cho response RoleChangeResponse
 * và OwnerTransferResponse.previousOwner (không cần fullName).
 *
 * Khác với broadcast actor shape {userId, username, fullName} — xem ActorFullDto.
 */
public record ActorSummaryDto(
        UUID userId,
        String username
) {
    public static ActorSummaryDto from(User user) {
        if (user == null) return null;
        return new ActorSummaryDto(user.getId(), user.getUsername());
    }
}
