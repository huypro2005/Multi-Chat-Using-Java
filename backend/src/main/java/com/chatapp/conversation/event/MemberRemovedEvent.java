package com.chatapp.conversation.event;

import java.util.UUID;

/**
 * Event fire AFTER_COMMIT cho DELETE /members/{userId} (KICKED) và POST /leave (LEFT).
 *
 * Field:
 *  - removedByUserId: actor khi KICKED; NULL khi LEFT (self-leave).
 *  - reason: "KICKED" | "LEFT".
 *
 * Broadcaster bắn:
 *  1) `/topic/conv.{convId}` — MEMBER_REMOVED envelope cho tất cả.
 *  2) CHỈ KHI KICKED: `/user/{removedUserId}/queue/conv-removed` minimal payload.
 *
 * Xem SOCKET_EVENTS.md §3.8.
 */
public record MemberRemovedEvent(
        UUID conversationId,
        UUID removedUserId,
        UUID removedByUserId, // null khi LEFT
        String reason          // "KICKED" | "LEFT"
) {}
