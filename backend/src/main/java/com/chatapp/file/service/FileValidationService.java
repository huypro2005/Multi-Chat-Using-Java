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
 *
 * ZIP→Office note: DOCX/XLSX/PPTX là ZIP container. Tika có thể detect thành
 * application/zip nếu file thiếu Office-specific metadata. Khi đó, dùng extension
 * hint (từ originalFilename) để override CHỈN KHI Tika trả application/zip —
 * không phải trong mọi trường hợp. MIME whitelist vẫn check sau override.
 *
 * Charset strip: Tika trả "text/plain; charset=UTF-8" cho text file — strip
 * phần sau ";" trước khi check whitelist.
 */
@Slf4j
@Service
public class FileValidationService {

    // Whitelist MIME được phép upload (W6-D4-extend) — ADR-019, API_CONTRACT.md.
    // Group A: Images (gallery-capable, 1–5 per message)
    // Group B: Documents & archives (1 per message)
    public static final Set<String> ALLOWED_MIMES = Set.of(
            // Group A — Images
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            // Group B — Documents & archives
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // docx
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",        // xlsx
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",// pptx
            "application/msword",            // doc (legacy)
            "application/vnd.ms-excel",      // xls (legacy)
            "application/vnd.ms-powerpoint", // ppt (legacy)
            "text/plain",
            "application/zip",
            "application/x-7z-compressed"
    );

    public static final long MAX_SIZE_BYTES = 20L * 1024 * 1024; // 20MB

    /**
     * Mapping MIME → file extension CỐ ĐỊNH — không đọc từ originalName (path traversal).
     * Bao phủ đúng whitelist ALLOWED_MIMES.
     * Dùng Map.ofEntries vì > 10 entries (Map.of() nhận tối đa 10 key-value pairs).
     */
    private static final Map<String, String> MIME_TO_EXT = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png",  "png"),
            Map.entry("image/webp", "webp"),
            Map.entry("image/gif",  "gif"),
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/vnd.ms-powerpoint", "ppt"),
            Map.entry("text/plain", "txt"),
            Map.entry("application/zip", "zip"),
            Map.entry("application/x-7z-compressed", "7z")
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
            String raw = tika.detect(in);
            // Charset strip: Tika trả "text/plain; charset=UTF-8" — strip phần charset.
            detectedMime = raw != null ? raw.split(";")[0].trim() : null;
        } catch (IOException e) {
            log.warn("Tika detect IOException: {}", e.getMessage());
            // I/O khi detect → treat như file không hợp lệ (không leak chi tiết ra client)
            throw new FileTypeNotAllowedException(ALLOWED_MIMES, "unknown");
        }

        // ZIP→Office override: DOCX/XLSX/PPTX là ZIP container — Tika có thể trả
        // application/zip nếu file thiếu Office-specific metadata. Dùng extension hint
        // CHỈ KHI Tika trả application/zip (fallback an toàn).
        if ("application/zip".equals(detectedMime)) {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename != null) {
                String ext = org.springframework.util.StringUtils.getFilenameExtension(originalFilename);
                if (ext != null) {
                    String extLower = ext.toLowerCase();
                    if ("docx".equals(extLower)) {
                        detectedMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    } else if ("xlsx".equals(extLower)) {
                        detectedMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                    } else if ("pptx".equals(extLower)) {
                        detectedMime = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                    }
                    // else: giữ nguyên application/zip (file zip thật)
                }
            }
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
