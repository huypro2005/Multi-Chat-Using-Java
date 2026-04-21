package com.chatapp.file.exception;

/**
 * 400 FILE_EMPTY — multipart part `file` thiếu hoặc size = 0 bytes.
 *
 * Đây là sibling exception (không extend AppException) để FileValidationService
 * throw gọn và GlobalExceptionHandler có handler riêng. Giữ business logic độc lập
 * khỏi HttpStatus/errorCode plumbing.
 */
public class FileEmptyException extends RuntimeException {
    public FileEmptyException(String message) {
        super(message);
    }

    public FileEmptyException() {
        super("File rỗng hoặc không được cung cấp");
    }
}
