package com.chatapp.message.dto;

import com.chatapp.file.dto.FileDto;
import com.chatapp.message.enums.MessageType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO trả về thông tin một tin nhắn.
 *
 * replyToMessage: null nếu không có reply.
 * editedAt: null nếu chưa bao giờ sửa.
 * deletedAt: null nếu chưa bị xóa. Non-null = đã bị soft delete.
 * deletedBy: null nếu chưa bị xóa. UUID string của user xóa.
 * content: null khi deletedAt != null (stripped tại MessageMapper để tránh leak).
 * attachments: W6-D1. LUÔN là List (có thể rỗng, KHÔNG null) — FE không phải check null.
 *   Khi message bị soft-delete, mapper strip thành empty list (không leak file URL sau delete).
 */
public record MessageDto(
        UUID id,
        UUID conversationId,
        SenderDto sender,
        MessageType type,
        String content,
        List<FileDto> attachments,
        ReplyPreviewDto replyToMessage,
        OffsetDateTime editedAt,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt,
        String deletedBy
) {}
