package com.chatapp.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Business exception dùng chung trong toàn project.
 * GlobalExceptionHandler sẽ bắt và convert sang ErrorResponse.
 *
 * Cách dùng:
 *   throw new AppException(HttpStatus.CONFLICT, "AUTH_EMAIL_TAKEN", "Email đã được sử dụng");
 *   throw new AppException(HttpStatus.CONFLICT, "CONV_ONE_ON_ONE_EXISTS", "...", Map.of("conversationId", id));
 */
@Getter
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    /** Optional details object serialized into error response — may be null. */
    private final Object details;

    public AppException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.details = null;
    }

    public AppException(HttpStatus status, String errorCode, String message, Object details) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.details = details;
    }
}
