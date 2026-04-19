package com.chatapp.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Shape chuẩn cho mọi error response của API.
 *
 * Contract (từ API_CONTRACT.md):
 * {
 *   "error": "ERROR_CODE_STRING",
 *   "message": "Human readable message",
 *   "timestamp": "2026-04-19T10:00:00Z",
 *   "details": { ... }   // optional, null nếu không có
 * }
 *
 * @JsonInclude(NON_NULL) — không serialize field null (details thường null).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String error,
        String message,
        String timestamp,
        Object details
) {
    /** Factory method tiện lợi khi không có details. */
    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, Instant.now().toString(), null);
    }

    /** Factory method khi có details (validation fields, retryAfterSeconds...). */
    public static ErrorResponse of(String error, String message, Object details) {
        return new ErrorResponse(error, message, Instant.now().toString(), details);
    }
}
