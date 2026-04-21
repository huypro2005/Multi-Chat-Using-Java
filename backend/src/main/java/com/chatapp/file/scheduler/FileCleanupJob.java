package com.chatapp.file.scheduler;

import com.chatapp.file.entity.FileRecord;
import com.chatapp.file.repository.FileRecordRepository;
import com.chatapp.file.repository.MessageAttachmentRepository;
import com.chatapp.file.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Scheduled jobs để dọn dẹp files trên disk và DB.
 *
 * <p>Job 1 — Expired cleanup (mặc định 3:00 AM mỗi ngày):
 *   Tìm files có expiresAt < now() và expired=false.
 *   Xóa physical file → nếu không còn attachment: xóa DB record.
 *   Nếu vẫn còn attachment: giữ DB record với expired=true (GET /files/{id} sẽ trả 404 graceful).
 *
 * <p>Job 2 — Orphan cleanup (mặc định mỗi giờ):
 *   Tìm files attachedAt=null (chưa gắn message) tạo trước now()-1h.
 *   Xóa physical file + DB record (safe vì không có FK từ message_attachments).
 *
 * <p>Disable trong test: {@code app.file-cleanup.enabled=false} (xem application-test.yml).
 *
 * <p>Multi-instance note (V1): assume single BE instance (ADR-015 SimpleBroker).
 * V2 cần distributed lock (Redis SETNX) khi scale. Xem backend-knowledge.md V2 bucket.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.file-cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class FileCleanupJob {

    private static final int BATCH_SIZE = 100;

    private final FileRecordRepository fileRecordRepository;
    private final MessageAttachmentRepository messageAttachmentRepository;
    private final StorageService storageService;

    // =========================================================================
    // Job 1: Expired files
    // =========================================================================

    /**
     * Cleanup files đã hết hạn (expiresAt < now, expired=false).
     *
     * <p>Per-record try-catch: 1 file fail không làm cả job fail.
     * Batch page 0 lặp lại: sau khi xử lý batch, records đã delete/update
     * nên page 0 tiếp theo chứa records mới (không bỏ sót).
     */
    @Scheduled(cron = "${app.file-cleanup.expired-cron:0 0 3 * * *}")
    public void cleanupExpiredFiles() {
        log.info("[FileCleanup] Expired cleanup start");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int deleted = 0, skipped = 0, errors = 0;

        while (true) {
            Page<FileRecord> batch = fileRecordRepository
                    .findByExpiresAtBeforeAndExpiredFalse(now, PageRequest.of(0, BATCH_SIZE));

            if (batch.isEmpty()) break;

            for (FileRecord file : batch) {
                try {
                    deletePhysical(file);

                    boolean stillAttached = messageAttachmentRepository.existsByIdFileId(file.getId());
                    if (stillAttached) {
                        // Physical file xóa, DB record giữ với expired=true.
                        // GET /api/files/{id} → openStream() → StorageException → 404 graceful.
                        file.setExpired(true);
                        fileRecordRepository.save(file);
                        log.warn("[FileCleanup] File {} expired but still attached — physical deleted, DB kept with expired=true",
                                file.getId());
                        skipped++;
                    } else {
                        fileRecordRepository.delete(file);
                        deleted++;
                    }
                } catch (Exception e) {
                    log.error("[FileCleanup] Failed to cleanup expired file {}", file.getId(), e);
                    errors++;
                    // Đánh dấu expired=true để tránh bị query lại vô hạn khi job chạy lần sau
                    // nhưng chỉ nếu lỗi là I/O (file vật lý có thể đã không tồn tại)
                    try {
                        file.setExpired(true);
                        fileRecordRepository.save(file);
                    } catch (Exception saveEx) {
                        log.error("[FileCleanup] Also failed to mark file {} as expired after error",
                                file.getId(), saveEx);
                    }
                }
            }

            if (batch.getNumberOfElements() < BATCH_SIZE) break;
        }

        log.info("[FileCleanup] Expired done: deleted={}, skipped={}, errors={}", deleted, skipped, errors);
    }

    // =========================================================================
    // Job 2: Orphan files
    // =========================================================================

    /**
     * Cleanup orphan files (attachedAt=null, createdAt < now-1h).
     *
     * <p>Orphan = file đã upload nhưng user chưa gắn vào message nào,
     * hoặc giao dịch attach bị rollback/abort. 1h grace period đủ thời gian
     * client đang soạn tin nhắn với file đính kèm.
     *
     * <p>Safe to delete hard: không có FK constraint từ message_attachments vì
     * attached_at=null (FileRecord.markAttached() chỉ được gọi khi attach succeed).
     */
    @Scheduled(cron = "${app.file-cleanup.orphan-cron:0 0 * * * *}")
    public void cleanupOrphanFiles() {
        log.info("[FileCleanup] Orphan cleanup start");
        OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minus(1, ChronoUnit.HOURS);
        int deleted = 0, errors = 0;

        while (true) {
            Page<FileRecord> batch = fileRecordRepository
                    .findByAttachedAtIsNullAndCreatedAtBefore(threshold, PageRequest.of(0, BATCH_SIZE));

            if (batch.isEmpty()) break;

            for (FileRecord file : batch) {
                try {
                    deletePhysical(file);
                    fileRecordRepository.delete(file);
                    deleted++;
                } catch (Exception e) {
                    log.error("[FileCleanup] Failed to cleanup orphan file {}", file.getId(), e);
                    errors++;
                    // Orphan không set expired flag — next run sẽ thử lại.
                    // Nếu file vật lý đã xóa nhưng DB delete fail, next run sẽ gọi delete()
                    // trên path không tồn tại → deletePhysical swallow IOException.
                }
            }

            if (batch.getNumberOfElements() < BATCH_SIZE) break;
        }

        log.info("[FileCleanup] Orphan done: deleted={}, errors={}", deleted, errors);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Xóa file vật lý khỏi storage (original + thumbnail nếu có).
     * Throw IOException nếu delete fail — caller quyết định cách xử lý.
     */
    private void deletePhysical(FileRecord file) throws IOException {
        storageService.delete(file.getStoragePath());
        if (file.getThumbnailInternalPath() != null) {
            storageService.delete(file.getThumbnailInternalPath());
        }
    }
}
