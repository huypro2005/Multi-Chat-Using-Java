package com.chatapp.conversation.dto;

import com.chatapp.conversation.enums.MemberRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Response 200 cho PATCH /api/conversations/{id}/members/{userId}/role (W7-D2).
 *
 * Shape (contract v1.1.0-w7):
 *  - userId: target user.
 *  - role: new role (hoặc role hiện tại nếu no-op).
 *  - changedAt: timestamp thao tác.
 *  - changedBy: actor minimal {userId, username}.
 *
 * Ghi chú v1.1.0-w7: bỏ `oldRole` (FE tự biết từ cache), thêm `changedBy`.
 * Broadcast §3.9 vẫn giữ oldRole vì receiver không có context.
 */
public record RoleChangeResponse(
        UUID userId,
        MemberRole role,
        Instant changedAt,
        ActorSummaryDto changedBy
) {}
