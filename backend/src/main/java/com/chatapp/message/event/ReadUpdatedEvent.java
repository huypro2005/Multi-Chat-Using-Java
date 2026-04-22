package com.chatapp.message.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published sau khi conversation_members.last_read_message_id được cập nhật.
 *
 * @param convId    UUID của conversation.
 * @param userId    UUID của user vừa đánh dấu đã đọc.
 * @param messageId UUID của message được đánh dấu (non-null — chỉ published khi advance forward).
 * @param readAt    Server-time tại commit transaction (UTC).
 *
 * Contract: SOCKET_EVENTS.md §3.13 (W7-D5).
 */
public record ReadUpdatedEvent(UUID convId, UUID userId, UUID messageId, Instant readAt) {}
