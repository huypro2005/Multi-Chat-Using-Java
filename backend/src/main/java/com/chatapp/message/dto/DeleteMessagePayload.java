package com.chatapp.message.dto;

import java.util.UUID;

/**
 * Inbound STOMP payload cho delete message — /app/conv.{convId}.delete.
 *
 * clientDeleteId: UUID v4 client-generated, dùng để dedup + route ACK.
 * messageId:      real server-generated message ID của message cần xóa.
 */
public record DeleteMessagePayload(
        String clientDeleteId,
        UUID messageId
) {}
