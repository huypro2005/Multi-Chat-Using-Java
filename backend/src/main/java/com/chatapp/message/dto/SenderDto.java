package com.chatapp.message.dto;

import java.util.UUID;

/**
 * Thông tin tóm tắt về người gửi tin nhắn.
 * Shallow — không embed thêm nested objects.
 */
public record SenderDto(
        UUID id,
        String username,
        String fullName,
        String avatarUrl
) {}
