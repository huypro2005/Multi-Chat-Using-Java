package com.chatapp.message.dto;

/**
 * Unified ACK payload gửi về sender qua /user/queue/acks (ADR-017).
 *
 * operation: discriminator "SEND" | "EDIT" | "DELETE" — FE dùng switch(operation) để route.
 * clientId:  UUID v4 client sinh — tempId cho SEND, clientEditId cho EDIT.
 * message:   MessageDto đầy đủ (identical với REST response shape).
 *
 * Breaking change từ AckPayload cũ {tempId, message} → {operation, clientId, message}.
 * BE + FE phải deploy đồng bộ khi migrate.
 */
public record AckPayload(
        String operation,
        String clientId,
        MessageDto message
) {}
