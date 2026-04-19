package com.chatapp.conversation.dto;

import com.chatapp.conversation.enums.ConversationType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body cho POST /api/conversations.
 *
 * Validation cơ bản ở đây — business validation (ONE_ON_ONE vs GROUP rules) ở ConversationService.
 */
public record CreateConversationRequest(
        @NotNull(message = "type là bắt buộc")
        ConversationType type,

        String name,

        @NotNull(message = "memberIds là bắt buộc")
        @Size(min = 1, message = "memberIds phải có ít nhất 1 phần tử")
        List<UUID> memberIds
) {}
