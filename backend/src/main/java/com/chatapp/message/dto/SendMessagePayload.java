package com.chatapp.message.dto;

import java.util.List;
import java.util.UUID;

/**
 * STOMP inbound payload cho Path B (ADR-016).
 *
 * Client gửi frame tới /app/conv.{convId}.message với body này.
 * Validation được thực hiện ở tầng service (sendViaStomp).
 *
 * tempId: client-generated UUID v4 — dùng để dedup và route ACK/ERROR.
 * content: nội dung tin nhắn. Từ W6-D1 (attachments) — nullable khi có attachmentIds non-empty;
 *   ngược lại bắt buộc 1..5000 chars sau trim.
 * type: chỉ "TEXT" ở Path B; server tự derive IMAGE/FILE khi có attachments.
 * replyToMessageId: UUID của tin nhắn được reply (nullable). Phải thuộc cùng conversation.
 *   Cho phép reply vào tin nhắn đã bị soft delete (quoting deleted source OK per ADR).
 * attachmentIds: W6-D1. Array UUID (nullable / empty OK). Validation count + mix + ownership
 *   trong MessageService.validateAndAttachFiles.
 */
public record SendMessagePayload(
        String tempId,
        String content,
        String type,
        UUID replyToMessageId,         // nullable
        List<UUID> attachmentIds       // W6-D1: nullable or empty list
) {}
