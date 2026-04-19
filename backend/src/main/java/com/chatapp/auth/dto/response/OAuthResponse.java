package com.chatapp.auth.dto.response;

/**
 * Response cho POST /api/auth/oauth.
 * Extends token shape chuẩn với field isNewUser bổ sung.
 *
 * isNewUser: true nếu user vừa được tạo mới, false nếu đã tồn tại.
 * FE dùng field này để quyết định redirect onboarding hay vào thẳng chat.
 */
public record OAuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        int expiresIn,
        boolean isNewUser,
        UserDto user
) {}
