package com.chatapp.file.exception;

import lombok.Getter;

/**
 * 429 RATE_LIMITED (file upload) — vượt 20 uploads/phút/user.
 *
 * Tách khỏi exception generic để tránh xung đột tên với rate limit khác
 * (vd message RATE_LIMITED/MSG_RATE_LIMITED). Error code trả về vẫn là
 * "RATE_LIMITED" theo contract — tên class chỉ khác để tránh duplicate.
 */
@Getter
public class FileRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public FileRateLimitedException(long retryAfterSeconds) {
        super("Upload quá nhiều, thử lại sau " + retryAfterSeconds + " giây");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
