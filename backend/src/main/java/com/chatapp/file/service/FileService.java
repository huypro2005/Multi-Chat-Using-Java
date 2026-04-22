package com.chatapp.file.service;

import com.chatapp.file.constant.FileConstants;
import com.chatapp.file.dto.FileDto;
import com.chatapp.file.entity.FileRecord;
import com.chatapp.file.exception.FileRateLimitedException;
import com.chatapp.file.exception.StorageException;
import com.chatapp.file.repository.FileRecordRepository;
import com.chatapp.file.storage.StorageService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Business logic cho file upload + DTO mapping.
 *
 * Flow upload:
 *  1. Validate (empty, size, MIME whitelist, MIME match — FileValidationService).
 *  2. Rate limit: Redis INCR `rate:file-upload:{userId}` TTL 60s, max 20.
 *  3. Generate UUID v4 + lookup extension từ MIME whitelist (KHÔNG từ filename — path traversal).
 *  4. Delegate storage layer (StorageService.store) — trả về storagePath để lưu DB.
 *  5. Persist FileRecord với expiresAt = createdAt + 30 ngày.
 *  6. Map sang FileDto trả về (url/thumbUrl computed).
 *
 * GET /api/files/{id} (W6-D1 stub): chỉ uploader mới download được. W6-D2 sẽ
 * mở rộng authorization cho member của conversation chứa attachment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private static final int UPLOAD_RATE_LIMIT_PER_MINUTE = 20;
    private static final long EXPIRY_DAYS = 30L;

    private final FileValidationService validationService;
    private final StorageService storageService;
    private final ThumbnailService thumbnailService;
    private final FileRecordRepository fileRecordRepository;
    private final StringRedisTemplate redisTemplate;

    // =========================================================================
    // upload
    // =========================================================================

    /** Backward-compat overload — default isPublic=false (private attachment). */
    @Transactional
    public FileDto upload(MultipartFile file, UUID userId) {
        return upload(file, userId, false);
    }

    /**
     * Upload flow với hybrid visibility (ADR-021, W7-D4-fix).
     *
     * @param isPublic true = avatar/public endpoint; false = message attachment (private).
     */
    @Transactional
    public FileDto upload(MultipartFile file, UUID userId, boolean isPublic) {
        // Step 1: Validate (throws FileEmpty/FileTooLarge/FileTypeNotAllowed/MimeMismatch)
        String detectedMime = validationService.validate(file);

        // Step 2: Rate limit (fail-open khi Redis down)
        checkUploadRateLimit(userId);

        // Step 3: Generate ID + extension
        UUID fileId = UUID.randomUUID();
        String ext = validationService.extensionFromMime(detectedMime);

        // Step 4: Store via storage layer
        String storagePath;
        try (InputStream in = file.getInputStream()) {
            storagePath = storageService.store(in, fileId.toString(), ext);
        } catch (IOException e) {
            log.error("Storage I/O error khi upload file (userId={}, fileId={}): {}",
                    userId, fileId, e.getMessage(), e);
            throw new StorageException("Không thể lưu file", e);
        } catch (IllegalArgumentException e) {
            // Path traversal hoặc args invalid — log WARN vì đây là bug programming, không phải attack runtime
            log.warn("Storage rejected store() args (userId={}, fileId={}): {}", userId, fileId, e.getMessage());
            throw new StorageException("Không thể lưu file", e);
        }

        // Step 5: Persist DB
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        FileRecord record = FileRecord.builder()
                .id(fileId)
                .uploaderId(userId)
                .originalName(sanitizeFilename(file.getOriginalFilename()))
                .mime(detectedMime)
                .sizeBytes(file.getSize())
                .storagePath(storagePath)
                .createdAt(now)
                .expiresAt(now.plusDays(EXPIRY_DAYS))
                .expired(false)
                .attachedAt(null)
                .isPublic(isPublic)
                .build();
        record = fileRecordRepository.save(record);

        log.info("File uploaded: id={}, uploader={}, mime={}, size={}B, isPublic={}, storagePath={}",
                record.getId(), userId, detectedMime, file.getSize(), isPublic, storagePath);

        // Step 6: Generate thumbnail (fail-open — thumbnail failure không fail upload).
        if (thumbnailService.supportsThumbnail(detectedMime)) {
            try {
                String thumbPath = thumbnailService.generate(record.getStoragePath(), detectedMime);
                record.setThumbnailInternalPath(thumbPath);
                record = fileRecordRepository.save(record);
                log.debug("Thumbnail persisted for fileId={}, thumbPath={}", record.getId(), thumbPath);
            } catch (Exception e) {
                // Fail-open: upload đã succeed, chỉ thumb lỗi → DB field vẫn null, GET /thumb trả 404.
                log.warn("Failed to generate thumbnail for file {} (mime={}): {}",
                        record.getId(), detectedMime, e.getMessage());
            }
        }

        // Step 7: DTO
        return toDto(record);
    }

    // =========================================================================
    // download (W6-D1 stub authorization)
    // =========================================================================

    /**
     * Load FileRecord cho download.
     *
     * W6-D1 scope: chỉ uploader được download. W6-D2 sẽ mở rộng:
     *  - uploader OR
     *  - member của conversation chứa message có attachment = fileId.
     *
     * Anti-enumeration: mọi case (not-found, not-owner, expired) đều merge → null
     * để controller trả 404 NOT_FOUND.
     */
    @Transactional(readOnly = true)
    public FileRecord loadForDownload(UUID fileId, UUID callerUserId) {
        FileRecord record = fileRecordRepository.findByIdAndExpiredFalse(fileId).orElse(null);
        if (record == null) {
            return null;
        }

        // W6-D1 stub: chỉ uploader mới xem được. W6-D2 sẽ thêm conv-member check.
        if (!record.getUploaderId().equals(callerUserId)) {
            return null;
        }

        // Double-check expiry time (column expires_at) phòng trường hợp cleanup job chưa chạy
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (record.getExpiresAt() != null && record.getExpiresAt().isBefore(now)) {
            return null;
        }

        return record;
    }

    /**
     * Load public file cho GET /api/files/{id}/public (ADR-021, W7-D4-fix).
     *
     * Authorization: CHỈ is_public = true. Private files trả null → controller 404.
     * Anti-enumeration: mọi case (not-found, is_public=false, expired) đều merge → null.
     */
    @Transactional(readOnly = true)
    public FileRecord loadForPublicDownload(UUID fileId) {
        FileRecord record = fileRecordRepository.findById(fileId).orElse(null);
        if (record == null) {
            return null;
        }
        if (!record.isPublic()) {
            return null; // anti-enum
        }
        if (record.isExpired()) {
            return null;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (record.getExpiresAt() != null && record.getExpiresAt().isBefore(now)) {
            return null;
        }
        return record;
    }

    /**
     * Mở InputStream từ storage — caller (controller) có trách nhiệm close/stream-out.
     * Throw StorageException nếu I/O lỗi.
     */
    public InputStream openStream(FileRecord record) {
        try {
            return storageService.retrieve(record.getStoragePath());
        } catch (IOException e) {
            log.error("Storage I/O error khi đọc file (id={}, path={}): {}",
                    record.getId(), record.getStoragePath(), e.getMessage(), e);
            throw new StorageException("Không thể đọc file", e);
        }
    }

    /**
     * Mở InputStream cho thumbnail của file (W6-D2). Caller đã check
     * {@code record.getThumbnailInternalPath() != null}.
     */
    public InputStream openThumbnailStream(FileRecord record) {
        String thumbPath = record.getThumbnailInternalPath();
        if (thumbPath == null) {
            throw new IllegalStateException("File không có thumbnail: " + record.getId());
        }
        try {
            return storageService.retrieve(thumbPath);
        } catch (IOException e) {
            log.error("Storage I/O error khi đọc thumbnail (id={}, path={}): {}",
                    record.getId(), thumbPath, e.getMessage(), e);
            throw new StorageException("Không thể đọc thumbnail", e);
        }
    }

    // =========================================================================
    // Startup checks (ADR-021, W7-D4-fix) — default avatar physical files
    // =========================================================================

    /**
     * Soft check lúc startup: log WARN nếu 2 file physical default avatar thiếu trên disk.
     *
     * Migration V11 chỉ tạo DB record (pointer) — physical files PHẢI copy tay vào
     * {@code ${STORAGE_PATH}/default/} post-deploy (runbook). Nếu thiếu, user/group
     * dùng default sẽ nhận 404 khi load avatar.
     *
     * KHÔNG fail startup (deploy pipeline có thể chưa sync physical). Wrap trong
     * try/catch để non-local storage (S3 V2) không làm fail.
     */
    @PostConstruct
    public void validateDefaultAvatars() {
        try {
            checkDefaultExists(FileConstants.DEFAULT_USER_AVATAR_PATH, "DEFAULT_USER_AVATAR");
            checkDefaultExists(FileConstants.DEFAULT_GROUP_AVATAR_PATH, "DEFAULT_GROUP_AVATAR");
        } catch (Exception e) {
            log.debug("[FileService] Default avatar check skipped: {}", e.getMessage());
        }
    }

    private void checkDefaultExists(String relativePath, String label) {
        try {
            Path full = storageService.resolveAbsolute(relativePath);
            if (!Files.exists(full)) {
                log.warn("[FileService] Default {} MISSING on disk: {} — users/groups using default sẽ nhận 404 cho tới khi file được copy.",
                        label, full);
                log.warn("[FileService] Runbook: cp default-avatars/*.jpg {}", full.getParent());
            } else {
                log.info("[FileService] Default {} OK: {}", label, full);
            }
        } catch (SecurityException se) {
            log.warn("[FileService] Default {} path resolution failed: {}", label, se.getMessage());
        }
    }

    // =========================================================================
    // Mapping
    // =========================================================================

    /**
     * Build FileDto (ADR-021 hybrid shape).
     *  - Public file  → url = /api/files/{id}/public, publicUrl = url, thumbUrl = null.
     *  - Private file → url = /api/files/{id},       publicUrl = null, thumbUrl theo record.
     *
     * Thumbnail CHỈ áp cho private image (public avatars không có thumbnail V1).
     */
    public FileDto toDto(FileRecord record) {
        boolean pub = record.isPublic();
        String url = pub
                ? FileConstants.publicUrl(record.getId())
                : FileConstants.privateUrl(record.getId());
        // W7-D4-fix: public files KHÔNG expose thumbnail endpoint (FE resize CSS).
        String thumbUrl = (!pub && record.getThumbnailInternalPath() != null)
                ? FileConstants.privateUrl(record.getId()) + "/thumb"
                : null;
        String publicUrl = pub ? url : null;
        return new FileDto(
                record.getId(),
                record.getMime(),
                record.getOriginalName(),
                record.getSizeBytes(),
                url,
                thumbUrl,
                resolveIconType(record.getMime()),
                record.getExpiresAt(),
                pub,
                publicUrl
        );
    }

    /**
     * Tính iconType từ MIME — server-computed, FE dùng để chọn icon phù hợp.
     * Map: IMAGE | PDF | WORD | EXCEL | POWERPOINT | TEXT | ARCHIVE | GENERIC.
     * W6-D4-extend.
     */
    private static String resolveIconType(String mime) {
        if (mime == null) return "GENERIC";
        if (mime.startsWith("image/")) return "IMAGE";
        if ("application/pdf".equals(mime)) return "PDF";
        if (mime.contains("wordprocessingml") || "application/msword".equals(mime)) return "WORD";
        if (mime.contains("spreadsheetml") || "application/vnd.ms-excel".equals(mime)) return "EXCEL";
        if (mime.contains("presentationml") || "application/vnd.ms-powerpoint".equals(mime)) return "POWERPOINT";
        if ("text/plain".equals(mime)) return "TEXT";
        if (mime.contains("zip") || mime.contains("7z")) return "ARCHIVE";
        return "GENERIC";
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void checkUploadRateLimit(UUID userId) {
        String rateKey = "rate:file-upload:" + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(rateKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(rateKey, 60, TimeUnit.SECONDS);
            }
            if (count != null && count > UPLOAD_RATE_LIMIT_PER_MINUTE) {
                long retryAfter = 60;
                try {
                    Long ttl = redisTemplate.getExpire(rateKey, TimeUnit.SECONDS);
                    if (ttl != null && ttl > 0) retryAfter = ttl;
                } catch (Exception ignored) { /* best-effort */ }
                throw new FileRateLimitedException(retryAfter);
            }
        } catch (FileRateLimitedException e) {
            throw e;
        } catch (DataAccessException e) {
            // Fail-open: Redis down → vẫn cho upload để không block user
            log.warn("Redis unavailable for upload rate limit check (key={}), fail-open", rateKey);
        }
    }

    /**
     * Sanitize tên file: strip path separator + control chars, truncate 255 chars.
     * Giữ Unicode để user Việt Nam thấy đúng tên file gốc khi download.
     */
    private String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) {
            return "file";
        }
        // Strip path components (Windows `\`, Unix `/`), control chars, null bytes.
        String base = Paths.get(raw).getFileName() != null
                ? Paths.get(raw).getFileName().toString()
                : raw;
        // Defensive: Paths.get có thể không strip nếu input khác OS — fallback regex
        String cleaned = base
                .replaceAll("[\\x00-\\x1F\\x7F]", "")   // control chars
                .replaceAll("[\\\\/]", "_");            // path separators
        if (cleaned.isBlank()) cleaned = "file";
        if (cleaned.length() > 255) {
            cleaned = cleaned.substring(0, 255);
        }
        return cleaned;
    }
}
