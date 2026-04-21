package com.chatapp.file.exception;

/**
 * 500 STORAGE_FAILED — lỗi I/O khi ghi/đọc disk (permission denied, disk full, ...).
 *
 * Log chi tiết server-side, client chỉ nhận generic "Storage failed" — không leak
 * thông tin filesystem (path, disk layout, errno).
 */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
