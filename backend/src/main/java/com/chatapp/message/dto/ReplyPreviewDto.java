package com.chatapp.message.dto;

import java.util.UUID;

/**
 * Preview của tin nhắn được reply (1 level only — không recursive).
 * contentPreview: cắt tối đa 100 ký tự từ content gốc.
 */
public record ReplyPreviewDto(
        UUID id,
        String senderName,
        String contentPreview
) {}
