package com.chatapp.file.exception;

import lombok.Getter;

import java.util.Set;

/**
 * 415 FILE_TYPE_NOT_ALLOWED — MIME detect (qua Tika magic bytes) không thuộc whitelist.
 *
 * details.allowedMimes + details.actualMime cho client biết lý do cụ thể.
 */
@Getter
public class FileTypeNotAllowedException extends RuntimeException {
    private final Set<String> allowedMimes;
    private final String actualMime;

    public FileTypeNotAllowedException(Set<String> allowedMimes, String actualMime) {
        super("Loại file không được phép: " + actualMime);
        this.allowedMimes = allowedMimes;
        this.actualMime = actualMime;
    }
}
