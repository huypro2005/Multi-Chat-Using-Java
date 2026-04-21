package com.chatapp.conversation.dto;

import com.chatapp.conversation.enums.ConversationType;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Request body cho POST /api/conversations.
 *
 * Shape W7 (v1.0.0-w7):
 * - ONE_ON_ONE: {type, targetUserId} — shape mới; hoặc {type, memberIds:[uuid]} — backward-compat.
 * - GROUP:      {type, name, memberIds:[uuid, uuid, ...], avatarFileId?}.
 *
 * Validation cơ bản (@NotNull cho type) ở đây — business validation (ONE_ON_ONE vs GROUP rules)
 * ở ConversationService.
 */
public record CreateConversationRequest(
        @NotNull(message = "type là bắt buộc")
        ConversationType type,

        String name,

        /**
         * W7 new field for ONE_ON_ONE. Singular UUID — shape rõ hơn array 1-phần-tử.
         * Nullable; nếu gửi cho GROUP → VALIDATION_FAILED.
         * Backward-compat: nếu null VÀ type=ONE_ON_ONE VÀ memberIds có 1 phần tử → dùng memberIds[0].
         */
        UUID targetUserId,

        /**
         * Group members (không bao gồm caller). Bắt buộc cho GROUP (≥ 2 unique).
         * ONE_ON_ONE backward-compat: nếu có 1 phần tử và targetUserId null → dùng làm targetUserId.
         */
        List<UUID> memberIds,

        /**
         * Optional group avatar — file đã upload qua POST /api/files/upload. Chỉ valid cho GROUP.
         * Validate: exists + uploader=caller + MIME image + chưa expire.
         */
        UUID avatarFileId
) {}
