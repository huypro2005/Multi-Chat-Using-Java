package com.chatapp.conversation.dto;

import com.chatapp.conversation.enums.ConversationType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight conversation summary — dùng cho GET /api/conversations list.
 *
 * Shape theo contract v0.5.0-conversations:
 * - displayName, displayAvatarUrl: server-computed cho FE render trực tiếp
 * - unreadCount: V1 luôn 0
 * - mutedUntil: từ conversation_members.muted_until của caller
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConversationSummaryDto(
        UUID id,
        ConversationType type,
        String name,
        String avatarUrl,
        String displayName,
        String displayAvatarUrl,
        int memberCount,
        Instant lastMessageAt,
        int unreadCount,
        Instant mutedUntil
) {}
