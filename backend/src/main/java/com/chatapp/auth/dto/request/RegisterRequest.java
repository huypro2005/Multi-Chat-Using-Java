package com.chatapp.auth.dto.request;

import jakarta.validation.constraints.*;

/**
 * Request body cho POST /api/auth/register.
 *
 * Validation rules theo contract:
 * - email: đúng định dạng RFC 5322, max 255 ký tự.
 * - username: 3–50 ký tự, chỉ [a-zA-Z0-9_], không bắt đầu bằng số.
 * - password: 8–128 ký tự, ít nhất 1 chữ hoa (A-Z) và 1 chữ số (0-9).
 * - fullName: 1–100 ký tự, không trống.
 */
public record RegisterRequest(

        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 255, message = "Email không được dài quá 255 ký tự")
        String email,

        @NotBlank(message = "Username không được để trống")
        @Size(min = 3, max = 50, message = "Username phải từ 3 đến 50 ký tự")
        @Pattern(
                regexp = "^[a-zA-Z_][a-zA-Z0-9_]{2,49}$",
                message = "Username phải bắt đầu bằng chữ cái hoặc dấu gạch dưới, chỉ chứa chữ cái, số, dấu gạch dưới"
        )
        String username,

        @NotBlank(message = "Password không được để trống")
        @Size(min = 8, max = 128, message = "Password phải từ 8 đến 128 ký tự")
        @Pattern(
                regexp = "^(?=.*[A-Z])(?=.*[0-9]).+$",
                message = "Password phải có ít nhất 1 chữ hoa và 1 chữ số"
        )
        String password,

        @NotBlank(message = "Họ tên không được để trống")
        @Size(min = 1, max = 100, message = "Họ tên không được dài quá 100 ký tự")
        String fullName

) {}
