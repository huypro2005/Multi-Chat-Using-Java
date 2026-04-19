package com.chatapp.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body cho POST /api/auth/logout.
 * refreshToken: token cần xóa khỏi Redis khi logout.
 */
public record LogoutRequest(
        @NotBlank(message = "Refresh token không được để trống")
        String refreshToken
) {}
