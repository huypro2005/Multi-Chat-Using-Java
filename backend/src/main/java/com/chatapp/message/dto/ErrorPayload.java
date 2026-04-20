package com.chatapp.message.dto;

/**
 * Unified ERROR payload gửi về sender qua /user/queue/errors (ADR-017).
 *
 * operation: discriminator "SEND" | "EDIT" — FE dùng để route handler xử lý đúng.
 * clientId:  UUID v4 client sinh — tempId cho SEND, clientEditId cho EDIT.
 * error:     human-readable message.
 * code:      machine-readable error code theo contract SOCKET_EVENTS.md.
 */
public record ErrorPayload(
        String operation,
        String clientId,
        String error,
        String code
) {}
