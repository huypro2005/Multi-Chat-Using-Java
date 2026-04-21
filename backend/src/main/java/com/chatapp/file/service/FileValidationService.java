package com.chatapp.file.service;

import com.chatapp.file.exception.FileEmptyException;
import com.chatapp.file.exception.FileTooLargeException;
import com.chatapp.file.exception.FileTypeNotAllowedException;
import com.chatapp.file.exception.MimeMismatchException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * Validation layer cho uploaded file — enforce 4 rule:
 *  1. Non-empty (size > 0).
 *  2. Size <= 20MB (defense-in-depth ngoài Spring multipart max-file-size).
 *  3. MIME detected (qua Tika magic bytes) thuộc whitelist.
 *  4. Declared MIME (header Content-Type) khớp detected MIME — chống spoofing.
 *
 * Tika pitfall: Tika.detect(InputStream) chỉ đọc ~8KB đầu (peek), KHÔNG consume
 * toàn stream. Nhưng vẫn MARK stream trước khi gọi, RESET sau đó nếu caller cần
 * đọc lại. Ở đây ta gọi trên `MultipartFile.getInputStream()` — mỗi lần gọi
 * trả về stream mới, nên không cần mark/reset.
 */
@Slf4j
@Service
public class FileValidationService {

    // Whitelist MIME được phép upload (V1) — ADR-019, API_CONTRACT.md.
    public static final Set<String> ALLOWED_MIMES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "application/pdf"
    );

    public static final long MAX_SIZE_BYTES = 20L * 1024 * 1024; // 20MB

    /**
     * Mapping MIME → file extension CỐ ĐỊNH — không đọc từ originalName (path traversal).
     * Bao phủ đúng whitelist ALLOWED_MIMES.
     */
    private static final Map<String, String> MIME_TO_EXT = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif",
            "application/pdf", "pdf"
    );

    private final Tika tika = new Tika();

    /**
     * Validate file đầy đủ theo thứ tự: empty → size → MIME whitelist → MIME match.
     *
     * @return detected MIME (để FileService dùng làm giá trị lưu DB).
     * @throws FileEmptyException         nếu file null hoặc size = 0.
     * @throws FileTooLargeException      nếu size > 20MB.
     * @throws FileTypeNotAllowedException nếu detected MIME không trong whitelist.
     * @throws MimeMismatchException      nếu declared MIME khác detected MIME.
     */
    public String validate(MultipartFile file) {
        // 1. Empty check
        if (file == null || file.isEmpty() || file.getSize() == 0L) {
            throw new FileEmptyException();
        }

        // 2. Size check (defense-in-depth; Spring đã reject ở HTTP nếu > max-file-size)
        long size = file.getSize();
        if (size > MAX_SIZE_BYTES) {
            throw new FileTooLargeException(MAX_SIZE_BYTES, size);
        }

        // 3. Detect MIME qua magic bytes
        String detectedMime;
        try (InputStream in = file.getInputStream()) {
            detectedMime = tika.detect(in);
        } catch (IOException e) {
            log.warn("Tika detect IOException: {}", e.getMessage());
            // I/O khi detect → treat như file không hợp lệ (không leak chi tiết ra client)
            throw new FileTypeNotAllowedException(ALLOWED_MIMES, "unknown");
        }

        if (detectedMime == null || !ALLOWED_MIMES.contains(detectedMime)) {
            throw new FileTypeNotAllowedException(ALLOWED_MIMES,
                    detectedMime != null ? detectedMime : "unknown");
        }

        // 4. Compare với declared MIME (Content-Type header) — chống spoofing
        String declared = file.getContentType();
        if (declared != null && !declared.equalsIgnoreCase(detectedMime)) {
            // Một số browser gửi "image/jpg" thay vì "image/jpeg" — chuẩn hoá alias trước khi reject
            String normalizedDeclared = normalizeMime(declared);
            if (!normalizedDeclared.equalsIgnoreCase(detectedMime)) {
                throw new MimeMismatchException(declared, detectedMime);
            }
        }

        return detectedMime;
    }

    /**
     * Lấy file extension (không kèm dấu chấm) từ detected MIME.
     * Throw IllegalStateException nếu MIME không trong map — chỉ gọi sau validate() nên luôn hợp lệ.
     */
    public String extensionFromMime(String mime) {
        String ext = MIME_TO_EXT.get(mime);
        if (ext == null) {
            throw new IllegalStateException("Không có extension mapping cho MIME: " + mime);
        }
        return ext;
    }

    /**
     * Chuẩn hoá MIME alias phổ biến.
     * Hiện tại handle image/jpg → image/jpeg (Firefox cũ, vài browser exotic).
     */
    private String normalizeMime(String mime) {
        if ("image/jpg".equalsIgnoreCase(mime)) return "image/jpeg";
        return mime;
    }
}
