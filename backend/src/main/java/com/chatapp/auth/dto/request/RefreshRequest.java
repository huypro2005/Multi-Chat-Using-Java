package com.chatapp.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body cho POST /api/auth/refresh.
 * Contract: refreshToken không được để trống.
 */
public record RefreshRequest(
        @NotBlank(message = "refreshToken không được để trống")
        String refreshToken
) {}
