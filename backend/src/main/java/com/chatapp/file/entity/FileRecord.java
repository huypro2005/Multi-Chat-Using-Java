package com.chatapp.file.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Entity mapping bảng `files` — metadata của file đã upload.
 *
 * UUID PK: @PrePersist generate nếu null (pattern W3-BE-1, khớp Message).
 * Timestamps chuẩn UTC để tránh H2 timezone stripping trong test.
 *
 * storage_path là internal filesystem path — KHÔNG bao giờ expose ra API response.
 * Client chỉ biết `/api/files/{id}` và `/api/files/{id}/thumb`.
 *
 * uploader_id là UUID (users.id là UUID, xem V2 migration). Task spec viết BIGINT
 * nhưng schema đã UUID → chỉnh về đúng FK type.
 */
@Entity
@Table(name = "files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileRecord {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * User đã upload file. ON DELETE CASCADE ở DB — nếu user bị xoá cứng thì file cũng đi.
     * Nullable=false ở entity vì upload luôn yêu cầu auth.
     */
    @Column(name = "uploader_id", nullable = false)
    private UUID uploaderId;

    /** Tên gốc đã sanitize — hiển thị cho user khi download (Content-Disposition). */
    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    /** MIME đã verify qua Tika magic bytes, thuộc whitelist `[image/jpeg, image/png, image/webp, image/gif, application/pdf]`. */
    @Column(name = "mime", nullable = false, length = 127)
    private String mime;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Internal path: `{base}/{yyyy}/{mm}/{uuid}.{ext}`. Không expose. */
    @Column(name = "storage_path", nullable = false, length = 1024)
    private String storagePath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** createdAt + 30 ngày. Cleanup job xoá disk file + set expired=true sau mốc này. */
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** TRUE sau khi cleanup job đã xoá file khỏi disk. Query vẫn filter expired=false trước khi serve. */
    @Column(name = "expired", nullable = false)
    @Builder.Default
    private boolean expired = false;

    /**
     * NULL = orphan (chưa attach vào message nào).
     * Non-null = đã attach; orphan cleanup job (1h) skip record này.
     */
    @Column(name = "attached_at")
    private OffsetDateTime attachedAt;

    /**
     * Internal path tới thumbnail 200x200 (W6-D2). NULL cho PDF/non-image hoặc thumbnail generation failed.
     * Không expose ra API — URL thumbnail luôn là /api/files/{id}/thumb (server resolve path).
     */
    @Column(name = "thumbnail_internal_path", length = 1024)
    private String thumbnailInternalPath;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        // expiresAt được set từ service (createdAt + 30d) trước khi save — không default ở đây.
    }

    // --- Domain behavior ---

    /** Đánh dấu file đã được attach vào một message (gọi từ MessageService khi send với attachments). */
    public void markAttached() {
        if (this.attachedAt == null) {
            this.attachedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public boolean isImage() {
        return mime != null && mime.startsWith("image/");
    }
}
