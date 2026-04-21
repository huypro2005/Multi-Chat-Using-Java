package com.chatapp.conversation.dto;

import java.util.UUID;

/**
 * Một entry trong AddMembersResponse.skipped — user bị skip với lý do.
 *
 * reason enum (contract v1.1.0-w7):
 *  - ALREADY_MEMBER: userId đã là member của conv.
 *  - USER_NOT_FOUND: userId không tồn tại / status != 'active' (anti-enum merge).
 *  - BLOCKED: user-blocks relation (V1 chưa wire — reserved forward-compat).
 */
public record SkippedMemberDto(
        UUID userId,
        String reason
) {}
