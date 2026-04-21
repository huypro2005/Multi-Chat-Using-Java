package com.chatapp.file.service;

import com.chatapp.file.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Generate thumbnail 200×200 cho image attachments (W6-D2).
 *
 * <p>Policy:
 * <ul>
 *   <li>Chỉ support image MIME trong {@link #IMAGE_MIMES}.</li>
 *   <li>Path layout: original {@code {base}/yyyy/MM/{uuid}.{ext}} →
 *       thumbnail {@code {base}/yyyy/MM/{uuid}_thumb.{ext}} (cùng folder).</li>
 *   <li>Fail-open: khi generate lỗi, caller log WARN và bỏ qua — upload vẫn thành công
 *       (không block user vì thumbnail miss). DB field {@code thumbnail_internal_path}
 *       sẽ là null, GET /thumb trả 404.</li>
 *   <li>KHÔNG ghi đè original file; cả original + thumbnail cùng tồn tại trên disk.</li>
 * </ul>
 *
 * <p>Thumbnail format V1: giữ extension gốc (jpg/png/webp/gif). Contract API_CONTRACT.md
 * nói "thumbnail luôn JPEG" — trong code hiện tại giữ format gốc để đơn giản + cache ETag;
 * Content-Type trả ra khớp với record.mime (đã là image/* an toàn). Nếu cần đổi sang JPEG
 * thuần, cập nhật {@code thumbExt} logic + content-type map.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailService {

    /** Kích thước max (width hoặc height) — Thumbnailator fit in box, giữ aspect ratio. */
    public static final int THUMB_SIZE = 200;

    /** Suffix cho file thumbnail — tránh va chạm với original (cùng thư mục). */
    public static final String THUMB_SUFFIX = "_thumb";

    /** JPEG output quality (0.0 - 1.0) — 0.85 là balance size/quality tốt. */
    private static final float OUTPUT_QUALITY = 0.85f;

    /** MIME hỗ trợ generate thumbnail. PDF và MIME khác → skip. */
    private static final Set<String> IMAGE_MIMES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );

    private final StorageService storageService;

    /**
     * Trả true nếu MIME hỗ trợ thumbnail (image/*).
     */
    public boolean supportsThumbnail(String mime) {
        return mime != null && IMAGE_MIMES.contains(mime);
    }

    /**
     * Generate thumbnail 200x200 cho file ảnh đã lưu. Trả về internal path tương đối
     * của thumbnail để caller lưu vào DB (FileRecord.thumbnailInternalPath).
     *
     * <p>Caller chịu trách nhiệm check {@link #supportsThumbnail(String)} trước khi gọi.
     *
     * <p>Ném {@link IOException} nếu source không đọc được, Thumbnailator fail,
     * hoặc write output lỗi. Caller trong {@link FileService} sẽ catch fail-open.
     *
     * @param originalInternalPath relative path của source file đã lưu (vd "2026/04/abc.jpg").
     * @param mime detected MIME (đã whitelist).
     * @return relative path của thumbnail (vd "2026/04/abc_thumb.jpg"), forward-slash normalized.
     */
    public String generate(String originalInternalPath, String mime) throws IOException {
        if (originalInternalPath == null || originalInternalPath.isBlank()) {
            throw new IllegalArgumentException("originalInternalPath rỗng");
        }

        // Split path thành parent + filename để build thumb tên cùng thư mục
        int lastSlash = originalInternalPath.lastIndexOf('/');
        String parentPart = lastSlash >= 0 ? originalInternalPath.substring(0, lastSlash + 1) : "";
        String fileName = lastSlash >= 0 ? originalInternalPath.substring(lastSlash + 1) : originalInternalPath;

        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx <= 0) {
            // Không có extension hoặc filename bắt đầu bằng dấu chấm — không expected
            throw new IllegalArgumentException("originalInternalPath không có extension hợp lệ: "
                    + originalInternalPath);
        }
        String baseName = fileName.substring(0, dotIdx);
        String ext = fileName.substring(dotIdx + 1);

        String thumbInternalPath = parentPart + baseName + THUMB_SUFFIX + "." + ext;

        Path source = storageService.resolveAbsolute(originalInternalPath);
        Path thumbPath = storageService.resolveAbsolute(thumbInternalPath);

        // Đảm bảo parent folder tồn tại (cùng folder với source, thường đã có nhưng defense).
        Path parent = thumbPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Thumbnails.of(source.toFile())
                .size(THUMB_SIZE, THUMB_SIZE)
                .outputQuality(OUTPUT_QUALITY)
                .toFile(thumbPath.toFile());

        log.debug("Thumbnail generated: source={}, thumb={}", originalInternalPath, thumbInternalPath);
        return thumbInternalPath;
    }
}
