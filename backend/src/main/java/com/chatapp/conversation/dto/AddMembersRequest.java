package com.chatapp.conversation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body cho POST /api/conversations/{id}/members (W7-D2).
 *
 * Validation rules per contract v1.1.0-w7:
 * - userIds: required, 1..10 phần tử.
 * - Duplicate entries trong array → BE dedupe silently (không throw).
 */
public record AddMembersRequest(
        @NotEmpty(message = "userIds không được rỗng")
        @Size(max = 10, message = "Tối đa 10 userIds mỗi request")
        List<UUID> userIds
) {}
