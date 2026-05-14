package com.chatapp.reaction.dto;

import java.util.List;
import java.util.UUID;

/**
 * Aggregate reaction shape cho 1 emoji trên 1 message (v1.5.0-w8-reactions).
 *
 * Xuất hiện trong MessageDto.reactions[].
 * Contract: API_CONTRACT.md § ReactionAggregateDto.
 *
 * Sort guarantee: BE MUST sort reactions[] theo count DESC, emoji ASC.
 *   - Đảm bảo FE render ReactionBar consistent.
 *   - Sort được apply trong MessageMapper.aggregateReactions().
 *
 * currentUserReacted: true nếu caller (từ SecurityContext/Principal) nằm trong userIds.
 *   Computed server-side — không cache.
 *
 * count luôn >= 1 (emoji count=0 KHÔNG xuất hiện — BE filter trước serialize).
 */
public record ReactionAggregateDto(
        String emoji,
        int count,
        List<UUID> userIds,
        boolean currentUserReacted
) {}
