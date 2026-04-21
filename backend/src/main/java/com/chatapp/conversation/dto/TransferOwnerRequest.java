package com.chatapp.conversation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body cho POST /api/conversations/{id}/transfer-owner (W7-D2).
 *
 * Shape (contract v1.1.0-w7):
 *  - targetUserId: UUID của member được promote thành OWNER. Bắt buộc.
 *
 * Ghi chú: field rename `newOwnerId` → `targetUserId` (nhất quán với path /members/{userId}).
 */
public record TransferOwnerRequest(
        @NotNull(message = "targetUserId là bắt buộc")
        UUID targetUserId
) {}
