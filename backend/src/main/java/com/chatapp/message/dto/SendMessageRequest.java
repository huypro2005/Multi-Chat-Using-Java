package com.chatapp.message.dto;

import com.chatapp.message.enums.MessageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body cho POST /api/conversations/{convId}/messages.
 *
 * type: optional, default TEXT nếu null.
 * replyToMessageId: optional, null nếu không reply.
 */
public record SendMessageRequest(
        @NotBlank(message = "Nội dung tin nhắn không được để trống")
        @Size(min = 1, max = 5000, message = "Nội dung tin nhắn phải từ 1 đến 5000 ký tự")
        String content,

        MessageType type,

        UUID replyToMessageId
) {}
