package com.chatapp.conversation.dto;

/**
 * Response 200 cho POST /api/conversations/{id}/transfer-owner (W7-D2).
 *
 * Shape (contract v1.1.0-w7):
 *  - previousOwner: {userId, username, newRole: "ADMIN"} — OWNER cũ (caller) giờ là ADMIN.
 *  - newOwner: {userId, username} — target user được promote.
 *
 * Broadcast §3.10 payload độc lập (có fullName + autoTransferred).
 */
public record OwnerTransferResponse(
        PreviousOwnerDto previousOwner,
        ActorSummaryDto newOwner
) {}
