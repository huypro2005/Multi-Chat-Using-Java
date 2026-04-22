package com.chatapp.message.dto;

import java.util.UUID;

/**
 * Payload từ client khi đánh dấu đã đọc tin nhắn.
 *
 * Destination: /app/conv.{convId}.read
 * convId lấy từ path variable, KHÔNG từ payload.
 *
 * Contract: SOCKET_EVENTS.md §3f.1 (W7-D5).
 */
public record ReadReceiptPayload(UUID messageId) {}
