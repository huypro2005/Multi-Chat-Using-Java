package com.chatapp.conversation.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Event fire sau khi DELETE /api/conversations/{id} commit thành công (W7-D1).
 *
 * Broadcaster listen AFTER_COMMIT rồi fire GROUP_DELETED qua /topic/conv.{id}
 * TRƯỚC khi hard-delete rows conversation_members (xem ConversationService.deleteGroup).
 *
 * Xem SOCKET_EVENTS.md §3.11.
 */
public record GroupDeletedEvent(
        UUID conversationId,
        UUID deletedByUserId,
        String deletedByFullName,
        Instant deletedAt
) {}
