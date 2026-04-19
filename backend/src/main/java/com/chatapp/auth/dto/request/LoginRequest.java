package com.chatapp.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body cho POST /api/auth/login.
 *
 * Lưu ý: server KHÔNG validate format username/password ở đây để tránh lộ thông tin
 * "username không tồn tại" — security requirement chống user enumeration attack.
 */
public record LoginRequest(

        @NotBlank(message = "Username không được để trống")
        @Size(max = 50, message = "Username không hợp lệ")
        String username,

        @NotBlank(message = "Password không được để trống")
        @Size(max = 128, message = "Password không hợp lệ")
        String password

) {}
