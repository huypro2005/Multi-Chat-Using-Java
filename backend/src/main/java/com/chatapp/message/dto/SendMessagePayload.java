package com.chatapp.message.dto;

import java.util.UUID;

/**
 * STOMP inbound payload cho Path B (ADR-016).
 *
 * Client gửi frame tới /app/conv.{convId}.message với body này.
 * Validation được thực hiện ở tầng service (sendViaStomp).
 *
 * tempId: client-generated UUID v4 — dùng để dedup và route ACK/ERROR.
 * content: nội dung tin nhắn, 1..5000 chars sau trim.
 * type: chỉ "TEXT" ở Path B; IMAGE/FILE vẫn đi qua REST.
 * replyToMessageId: UUID của tin nhắn được reply (nullable). Phải thuộc cùng conversation.
 *   Cho phép reply vào tin nhắn đã bị soft delete (quoting deleted source OK per ADR).
 */
public record SendMessagePayload(
        String tempId,
        String content,
        String type,
        UUID replyToMessageId   // nullable
) {}
