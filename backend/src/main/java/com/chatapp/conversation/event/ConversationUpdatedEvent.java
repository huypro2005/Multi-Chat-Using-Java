package com.chatapp.conversation.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Event fire sau khi PATCH /api/conversations/{id} commit thành công (W7-D1).
 *
 * Broadcaster listen AFTER_COMMIT rồi fire CONVERSATION_UPDATED qua /topic/conv.{id}.
 * Xem SOCKET_EVENTS.md §3.6.
 *
 * @param conversationId conv UUID
 * @param changes        map chứa các field đã đổi, ví dụ {"name": "New"} hoặc {"avatarUrl": "/api/files/uuid"}
 *                       hoặc cả hai. KHÔNG include field không đổi.
 * @param updatedByUserId actor (OWNER/ADMIN)
 * @param updatedByFullName  fullName của actor để FE render "X đã đổi ..."
 * @param occurredAt     thời điểm event, truyền sang FE nếu cần
 */
public record ConversationUpdatedEvent(
        UUID conversationId,
        Map<String, Object> changes,
        UUID updatedByUserId,
        String updatedByFullName,
        Instant occurredAt
) {}
