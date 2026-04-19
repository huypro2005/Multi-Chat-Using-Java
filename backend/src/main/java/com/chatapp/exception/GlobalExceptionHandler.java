package com.chatapp.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global error handler — bắt mọi exception từ controller, convert sang ErrorResponse chuẩn.
 *
 * Nguyên tắc: KHÔNG bao giờ leak stack trace ra response. Log server-side, trả message friendly.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * AppException — business error do service layer throw.
     * Ví dụ: AUTH_EMAIL_TAKEN (409), AUTH_INVALID_CREDENTIALS (401), ...
     */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(AppException ex) {
        log.debug("AppException: {} - {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity
                .status(ex.getStatus())
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    /**
     * MethodArgumentNotValidException — @Valid trên @RequestBody thất bại.
     * Trả về details.fields với từng field lỗi.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err ->
                fieldErrors.put(err.getField(), err.getDefaultMessage())
        );

        Map<String, Object> details = Map.of("fields", fieldErrors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", "Dữ liệu không hợp lệ", details));
    }

    /**
     * ConstraintViolationException — @Validated trên @RequestParam / path variable thất bại.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(cv -> {
            // Path dạng "methodName.paramName" → lấy phần cuối
            String path = cv.getPropertyPath().toString();
            String fieldName = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fieldErrors.put(fieldName, cv.getMessage());
        });

        Map<String, Object> details = Map.of("fields", fieldErrors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", "Dữ liệu không hợp lệ", details));
    }

    /**
     * Catch-all — mọi exception không được handle ở trên.
     * Log full stack trace server-side, trả về generic message.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "Lỗi server không xác định, vui lòng thử lại"));
    }
}
