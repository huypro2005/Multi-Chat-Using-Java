package com.chatapp.conversation.dto;

import java.util.List;

/**
 * Wrapper pagination cho GET /api/conversations.
 *
 * Shape theo contract v0.5.0-conversations:
 * { content, page, size, totalElements, totalPages }
 */
public record ConversationListResponse(
        List<ConversationSummaryDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
