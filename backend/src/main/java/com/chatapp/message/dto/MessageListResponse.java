package com.chatapp.message.dto;

import java.util.List;

/**
 * Response cho GET /api/conversations/{convId}/messages.
 *
 * items: sorted cũ → mới (ascending createdAt).
 * hasMore: true nếu còn page tiếp theo.
 * nextCursor: ISO8601 OffsetDateTime string — createdAt của item cũ nhất trong page.
 *             null khi hasMore=false.
 */
public record MessageListResponse(
        List<MessageDto> items,
        boolean hasMore,
        String nextCursor
) {}
