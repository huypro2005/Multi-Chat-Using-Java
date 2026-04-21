package com.chatapp.file.exception;

import lombok.Getter;

/**
 * 415 MIME_MISMATCH — declared MIME (từ Content-Type header) khác detected MIME (magic bytes).
 *
 * Đây là trường hợp attacker đổi extension/header để bypass whitelist (vd đặt .exe
 * thành .jpg với Content-Type image/jpeg). Tika đọc magic bytes và phát hiện bất khớp.
 */
@Getter
public class MimeMismatchException extends RuntimeException {
    private final String declaredMime;
    private final String detectedMime;

    public MimeMismatchException(String declaredMime, String detectedMime) {
        super("MIME không khớp: khai báo " + declaredMime + ", thực tế " + detectedMime);
        this.declaredMime = declaredMime;
        this.detectedMime = detectedMime;
    }
}
