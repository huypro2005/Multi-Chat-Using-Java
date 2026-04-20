package com.chatapp.message.service;

import com.chatapp.message.dto.MessageDto;
import com.chatapp.message.dto.ReplyPreviewDto;
import com.chatapp.message.dto.SenderDto;
import com.chatapp.message.entity.Message;
import com.chatapp.user.entity.User;
import org.springframework.stereotype.Component;

/**
 * Tách DTO mapping khỏi MessageService để cả broadcaster và service có thể reuse.
 *
 * Design: @Component singleton, không có state → thread-safe.
 *
 * Content strip (W5-D3): nếu message đã bị soft delete (deletedAt != null),
 * content sẽ bị set = null trong DTO để tránh leak nội dung đã xóa.
 * Rule này áp dụng TẤT CẢ path: REST list, REST create response, WS broadcast, WS ACK.
 *
 * ReplyPreviewDto (W5-D4): thêm field deletedAt — null nếu source chưa bị xóa,
 * ISO8601 string nếu source đã soft-delete. contentPreview = null khi source deleted.
 */
@Component
public class MessageMapper {

    private static final int CONTENT_PREVIEW_MAX_LENGTH = 100;

    public MessageDto toDto(Message message) {
        SenderDto senderDto = null;
        if (message.getSender() != null) {
            User sender = message.getSender();
            senderDto = new SenderDto(
                    sender.getId(),
                    sender.getUsername(),
                    sender.getFullName(),
                    sender.getAvatarUrl()
            );
        }

        ReplyPreviewDto replyPreview = null;
        if (message.getReplyToMessage() != null) {
            replyPreview = toReplyPreview(message.getReplyToMessage());
        }

        // Strip content when soft-deleted — tránh leak nội dung đã bị xóa
        boolean isDeleted = message.getDeletedAt() != null;
        String content = isDeleted ? null : message.getContent();
        String deletedBy = message.getDeletedBy() != null ? message.getDeletedBy().toString() : null;

        return new MessageDto(
                message.getId(),
                message.getConversation().getId(),
                senderDto,
                message.getType(),
                content,
                replyPreview,
                message.getEditedAt(),
                message.getCreatedAt(),
                message.getDeletedAt(),
                deletedBy
        );
    }

    /**
     * Map a source Message into a ReplyPreviewDto (1-level shallow).
     *
     * - If source is soft-deleted: contentPreview = null, deletedAt = ISO8601 string.
     * - If source is not deleted: contentPreview = trimmed to 100 chars, deletedAt = null.
     * - Quoting a deleted source is allowed (per W5-D4 spec).
     */
    public ReplyPreviewDto toReplyPreview(Message source) {
        String senderName = source.getSender() != null
                ? source.getSender().getFullName()
                : "Deleted User";

        if (source.getDeletedAt() != null) {
            return new ReplyPreviewDto(
                    source.getId(),
                    senderName,
                    null,
                    source.getDeletedAt().toString()
            );
        }

        String preview = source.getContent();
        if (preview != null && preview.length() > CONTENT_PREVIEW_MAX_LENGTH) {
            preview = preview.substring(0, CONTENT_PREVIEW_MAX_LENGTH) + "...";
        }
        return new ReplyPreviewDto(source.getId(), senderName, preview, null);
    }
}
