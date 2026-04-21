package com.chatapp.file.repository;

import com.chatapp.file.entity.FileRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileRecordRepository extends JpaRepository<FileRecord, UUID> {

    /**
     * Lookup file chưa expire — dùng cho GET /api/files/{id}.
     * Nếu expired=true → treat như không tồn tại (anti-enumeration 404).
     */
    Optional<FileRecord> findByIdAndExpiredFalse(UUID id);

    /**
     * Liệt kê orphan files của user (attached_at = NULL) tạo trước threshold.
     * Dùng cho orphan cleanup job (W6-D3). Threshold thường là now() - 1h.
     */
    List<FileRecord> findByUploaderIdAndAttachedAtIsNullAndCreatedAtBefore(
            UUID uploaderId, OffsetDateTime threshold);

    /**
     * Lấy batch files đã hết hạn nhưng chưa được đánh dấu expired=true.
     * Dùng cho expired cleanup job (W6-D3). Luôn query page 0 — sau mỗi batch
     * records được delete/update nên page 0 tiếp theo chứa records mới.
     */
    Page<FileRecord> findByExpiresAtBeforeAndExpiredFalse(OffsetDateTime threshold, Pageable pageable);

    /**
     * Lấy batch orphan files (attached_at IS NULL) tạo trước threshold.
     * Dùng cho orphan cleanup job (W6-D3). Threshold = now() - 1h.
     */
    Page<FileRecord> findByAttachedAtIsNullAndCreatedAtBefore(OffsetDateTime threshold, Pageable pageable);
}
