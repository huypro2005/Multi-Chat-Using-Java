package com.chatapp.message.dto;

import java.util.UUID;

/**
 * Inbound STOMP payload cho edit message — /app/conv.{convId}.edit.
 *
 * clientEditId: UUID v4 client-generated, dùng để dedup + route ACK.
 * messageId:    real server-generated message ID của message cần sửa.
 * newContent:   nội dung mới (1–5000 chars sau trim).
 */
public record EditMessagePayload(
        String clientEditId,
        UUID messageId,
        String newContent
) {}
