package com.chatapp.conversation.event;

import com.chatapp.conversation.enums.MemberRole;

import java.util.UUID;

/**
 * Event fire AFTER_COMMIT khi PATCH /members/{userId}/role thay đổi role (W7-D2).
 * CHỈ fire khi oldRole != newRole (no-op idempotent không broadcast).
 *
 * Broadcaster bắn `/topic/conv.{convId}` envelope ROLE_CHANGED {oldRole, newRole, changedBy}.
 *
 * Giá trị enum CHỈ ADMIN | MEMBER trong V1 — OWNER changes đi qua OWNER_TRANSFERRED.
 *
 * Xem SOCKET_EVENTS.md §3.9.
 */
public record RoleChangedEvent(
        UUID conversationId,
        UUID targetUserId,
        MemberRole oldRole,
        MemberRole newRole,
        UUID changedByUserId
) {}
