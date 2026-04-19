package com.chatapp.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Business exception dùng chung trong toàn project.
 * GlobalExceptionHandler sẽ bắt và convert sang ErrorResponse.
 *
 * Cách dùng:
 *   throw new AppException(HttpStatus.CONFLICT, "AUTH_EMAIL_TAKEN", "Email đã được sử dụng");
 */
@Getter
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public AppException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }
}
