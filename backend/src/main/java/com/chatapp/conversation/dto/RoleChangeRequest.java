package com.chatapp.conversation.dto;

import com.chatapp.conversation.enums.MemberRole;
import jakarta.validation.constraints.NotNull;

/**
 * Request body cho PATCH /api/conversations/{id}/members/{userId}/role (W7-D2).
 *
 * Validation rules (contract v1.1.0-w7):
 *  - role: enum strict "ADMIN" | "MEMBER". Truyền "OWNER" → INVALID_ROLE (400).
 *  - role null/missing → VALIDATION_FAILED (400).
 */
public record RoleChangeRequest(
        @NotNull(message = "role là bắt buộc")
        MemberRole role
) {}
