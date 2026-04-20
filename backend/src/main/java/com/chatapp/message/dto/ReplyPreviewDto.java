package com.chatapp.message.dto;

import java.util.UUID;

/**
 * Preview của tin nhắn được reply (1 level only — không recursive).
 * contentPreview: cắt tối đa 100 ký tự từ content gốc. null nếu source đã bị xóa.
 * deletedAt: ISO8601 string nếu source đã bị soft delete, null nếu chưa bị xóa.
 */
public record ReplyPreviewDto(
        UUID id,
        String senderName,
        String contentPreview,  // null nếu source deleted
        String deletedAt        // ISO8601 | null
) {}
