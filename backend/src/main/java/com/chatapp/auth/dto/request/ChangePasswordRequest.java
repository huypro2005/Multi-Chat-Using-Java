package com.chatapp.auth.dto.request;

public record ChangePasswordRequest(
        String currentPassword,
        String newPassword,
        String confirmPassword
) {
}
