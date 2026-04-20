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
            Message replyMsg = message.getReplyToMessage();
            String senderName = replyMsg.getSender() != null
                    ? replyMsg.getSender().getFullName()
                    : "Deleted User";
            // Snapshot: do NOT strip replyToMessage contentPreview even if original is deleted.
            // V1 acceptable — V2 may add deleted: boolean flag.
            String contentPreview = replyMsg.getContent();
            if (contentPreview != null && contentPreview.length() > CONTENT_PREVIEW_MAX_LENGTH) {
                contentPreview = contentPreview.substring(0, CONTENT_PREVIEW_MAX_LENGTH) + "...";
            }
            replyPreview = new ReplyPreviewDto(replyMsg.getId(), senderName, contentPreview);
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
}
