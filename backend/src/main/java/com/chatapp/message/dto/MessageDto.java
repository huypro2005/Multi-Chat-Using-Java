package com.chatapp.message.dto;

import com.chatapp.message.enums.MessageType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO trả về thông tin một tin nhắn.
 *
 * replyToMessage: null nếu không có reply.
 * editedAt: null nếu chưa bao giờ sửa.
 */
public record MessageDto(
        UUID id,
        UUID conversationId,
        SenderDto sender,
        MessageType type,
        String content,
        ReplyPreviewDto replyToMessage,
        OffsetDateTime editedAt,
        OffsetDateTime createdAt
) {}
