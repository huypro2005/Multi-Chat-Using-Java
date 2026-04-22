package com.chatapp.message.dto;

import com.chatapp.file.dto.FileDto;
import com.chatapp.message.enums.MessageType;
import com.chatapp.reaction.dto.ReactionAggregateDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * systemEventType: W7-D4. Non-null chỉ khi type == SYSTEM. 1 trong 8 enum string.
 * systemMetadata: W7-D4. Non-null chỉ khi type == SYSTEM. JSONB object theo shape per-event-type.
 * reactions: W8-D1. LUÔN là List (không null) — FE không phải check null.
 *   Empty list [] nếu: không có reaction, message bị soft-delete, hoặc type == SYSTEM.
 *   Sorted: count DESC, emoji ASC (stable sort, consistent với contract v1.5.0-w8-reactions).
 *   currentUserReacted trong mỗi aggregate tính server-side (không cache).
 * pinnedAt: W8-D2. Instant khi được ghim, null nếu chưa ghim.
 * pinnedBy: W8-D2. {userId, userName} của người ghim, null nếu chưa ghim.
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
        String deletedBy,
        String systemEventType,
        Map<String, Object> systemMetadata,
        List<ReactionAggregateDto> reactions,
        Instant pinnedAt,
        Map<String, Object> pinnedBy
) {}
