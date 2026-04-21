package com.chatapp.exception;

import com.chatapp.file.exception.FileEmptyException;
import com.chatapp.file.exception.FileRateLimitedException;
import com.chatapp.file.exception.FileTooLargeException;
import com.chatapp.file.exception.FileTypeNotAllowedException;
import com.chatapp.file.exception.MimeMismatchException;
import com.chatapp.file.exception.StorageException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getDetails()));
    }

    /**
     * Không có handler cho URL/method (Spring 6 ném NoResourceFoundException thay vì 404 mặc định).
     * Thường gặp khi gọi GET thay vì POST, hoặc sai path — tránh 500 INTERNAL_ERROR gây hiểu nhầm.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        String path = ex.getResourcePath();
        log.debug("No handler for resource path: {}", path);
        String hint = "Kiểm tra HTTP method và đường dẫn (ví dụ POST /api/auth/refresh với JSON body).";
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", "Không tìm thấy tài nguyên: " + path + ". " + hint));
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
     * MethodArgumentTypeMismatchException — @PathVariable hoặc @RequestParam không convert được.
     * Ví dụ: GET /api/conversations/not-a-uuid → UUID parse fail → 400 thay vì 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String field = ex.getName();
        log.debug("Type mismatch for parameter '{}': {}", field, ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                        "VALIDATION_FAILED",
                        "Invalid format for parameter: " + field,
                        Map.of("field", field, "error", "Định dạng ID không hợp lệ")
                ));
    }

    // =========================================================================
    // W6-D1 — File upload exceptions
    // =========================================================================

    /**
     * 400 FILE_EMPTY — multipart field `file` thiếu hoặc size = 0.
     */
    @ExceptionHandler(FileEmptyException.class)
    public ResponseEntity<ErrorResponse> handleFileEmpty(FileEmptyException ex) {
        log.debug("FileEmptyException: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("FILE_EMPTY", ex.getMessage()));
    }

    /**
     * 413 FILE_TOO_LARGE — size > 20MB (service-level check).
     * details: maxBytes + actualBytes.
     */
    @ExceptionHandler(FileTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleFileTooLarge(FileTooLargeException ex) {
        log.debug("FileTooLargeException: max={}, actual={}", ex.getMaxBytes(), ex.getActualBytes());
        Map<String, Object> details = Map.of(
                "maxBytes", ex.getMaxBytes(),
                "actualBytes", ex.getActualBytes()
        );
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of("FILE_TOO_LARGE", ex.getMessage(), details));
    }

    /**
     * 413 FILE_TOO_LARGE — Spring multipart HTTP-layer limit vượt (trước khi vào service).
     * Không có actualBytes chính xác ở tầng này.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        log.debug("MaxUploadSizeExceededException: {}", ex.getMessage());
        Map<String, Object> details = Map.of(
                "maxBytes", ex.getMaxUploadSize()
        );
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of("FILE_TOO_LARGE",
                        "File vượt giới hạn kích thước cho phép", details));
    }

    /**
     * 415 FILE_TYPE_NOT_ALLOWED — MIME detect không trong whitelist.
     */
    @ExceptionHandler(FileTypeNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleFileTypeNotAllowed(FileTypeNotAllowedException ex) {
        log.debug("FileTypeNotAllowedException: actual={}, allowed={}", ex.getActualMime(), ex.getAllowedMimes());
        Map<String, Object> details = Map.of(
                "allowedMimes", ex.getAllowedMimes(),
                "actualMime", ex.getActualMime()
        );
        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ErrorResponse.of("FILE_TYPE_NOT_ALLOWED", ex.getMessage(), details));
    }

    /**
     * 415 MIME_MISMATCH — declared Content-Type khác MIME detect qua magic bytes.
     */
    @ExceptionHandler(MimeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMimeMismatch(MimeMismatchException ex) {
        log.warn("MimeMismatchException (potential spoofing): declared={}, detected={}",
                ex.getDeclaredMime(), ex.getDetectedMime());
        Map<String, Object> details = Map.of(
                "declaredMime", ex.getDeclaredMime(),
                "detectedMime", ex.getDetectedMime()
        );
        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ErrorResponse.of("MIME_MISMATCH", ex.getMessage(), details));
    }

    /**
     * 429 RATE_LIMITED — vượt 20 uploads/phút/user.
     */
    @ExceptionHandler(FileRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleFileRateLimited(FileRateLimitedException ex) {
        log.debug("FileRateLimitedException: retryAfter={}s", ex.getRetryAfterSeconds());
        Map<String, Object> details = Map.of(
                "retryAfterSeconds", ex.getRetryAfterSeconds()
        );
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of("RATE_LIMITED", ex.getMessage(), details));
    }

    /**
     * 500 STORAGE_FAILED — I/O lỗi khi ghi/đọc disk. Log chi tiết, trả generic.
     */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> handleStorageException(StorageException ex) {
        log.error("StorageException: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("STORAGE_FAILED", "Không thể xử lý file, vui lòng thử lại"));
    }

    /**
     * 400 FILE_EMPTY — missing multipart part (client POST không có field "file").
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException ex) {
        log.debug("MissingServletRequestPartException: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("FILE_EMPTY",
                        "Thiếu multipart field: " + ex.getRequestPartName()));
    }

    /**
     * 400 VALIDATION_FAILED — missing @RequestParam (ví dụ thiếu "file" khi Content-Type khác multipart).
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        log.debug("MissingServletRequestParameterException: {}", ex.getMessage());
        // Nếu param tên "file" → map về FILE_EMPTY để FE xử lý thống nhất
        if ("file".equals(ex.getParameterName())) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of("FILE_EMPTY",
                            "Thiếu field: file"));
        }
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED",
                        "Thiếu tham số: " + ex.getParameterName(),
                        Map.of("field", ex.getParameterName())));
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
