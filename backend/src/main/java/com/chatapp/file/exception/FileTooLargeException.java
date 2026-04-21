package com.chatapp.file.exception;

import lombok.Getter;

/**
 * 413 FILE_TOO_LARGE — size > 20MB (defense-in-depth check sau Spring multipart limit).
 *
 * Kèm maxBytes + actualBytes để client có thể hiển thị "2.3 MB / 20 MB".
 */
@Getter
public class FileTooLargeException extends RuntimeException {
    private final long maxBytes;
    private final long actualBytes;

    public FileTooLargeException(long maxBytes, long actualBytes) {
        super("File quá lớn (tối đa " + maxBytes + " bytes, nhận " + actualBytes + ")");
        this.maxBytes = maxBytes;
        this.actualBytes = actualBytes;
    }
}
