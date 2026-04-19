package com.chatapp.auth.dto.response;

/**
 * Response shape chuẩn cho mọi auth endpoint trả token.
 * Theo contract: accessToken, refreshToken, tokenType, expiresIn, user.
 *
 * expiresIn: luôn là 3600 (giây) — thời gian sống của accessToken.
 * tokenType: luôn là "Bearer".
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        int expiresIn,
        UserDto user
) {}
