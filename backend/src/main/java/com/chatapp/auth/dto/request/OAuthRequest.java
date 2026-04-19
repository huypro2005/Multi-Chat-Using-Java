package com.chatapp.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body cho POST /api/auth/oauth.
 * firebaseIdToken: JWT phát bởi Firebase sau khi user đăng nhập Google phía client.
 */
public record OAuthRequest(
        @NotBlank(message = "Firebase ID token không được để trống")
        String firebaseIdToken
) {}
