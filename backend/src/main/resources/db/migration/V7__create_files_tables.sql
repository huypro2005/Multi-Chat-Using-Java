-- ============================================================
-- V7: Files + message attachments
-- Khớp với API_CONTRACT.md mục "Files Management (v0.9.0-files — W6-D1)"
-- Và ARCHITECTURE.md §7.7 (StorageService + local disk V1).
-- ============================================================
--
-- Quy ước:
--  - uploader_id UUID FK users(id). users.id là UUID (xem V2), KHÔNG phải BIGINT.
--  - message_id  UUID FK messages(id). messages.id là UUID (xem V5).
--  - id (files)  UUID server-generated via gen_random_uuid() (pgcrypto đã tạo ở V2).
--  - expires_at  = created_at + 30 ngày (V1 expiry policy).
--  - expired flag: toggled bởi cleanup job (ADR-019, W6-D3). NULL -> chưa chạm.
--  - attached_at: NULL = orphan (chưa attach vào message); non-null = đã dùng.
--
-- Indexes:
--  - uploader_id    → query user's uploads (future profile view)
--  - expires_at (partial) → cleanup job scan files chưa expire
--  - attached_at (partial) → orphan cleanup scan (NULL) — pattern partial filter
--  - message_attachments(file_id) → reverse lookup "message nào đang dùng file này"
-- ============================================================

CREATE TABLE files (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    uploader_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    original_name   VARCHAR(255) NOT NULL,
    mime            VARCHAR(127) NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    storage_path    VARCHAR(1024) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    expired         BOOLEAN      NOT NULL DEFAULT FALSE,
    attached_at     TIMESTAMPTZ  NULL
);

COMMENT ON TABLE files IS 'Uploaded files; local-disk storage V1, S3 V2. Expire sau 30 ngày.';
COMMENT ON COLUMN files.storage_path IS 'Internal filesystem path — KHÔNG bao giờ expose ra API response.';
COMMENT ON COLUMN files.original_name IS 'Tên gốc đã sanitize, dùng cho Content-Disposition khi download.';
COMMENT ON COLUMN files.attached_at IS 'Timestamp lần attach vào message; NULL = orphan (cleanup sau 1h).';
COMMENT ON COLUMN files.expired IS 'Cleanup job set TRUE khi đã xoá file disk sau expiry.';

CREATE INDEX idx_files_uploader     ON files(uploader_id);
CREATE INDEX idx_files_expires_at   ON files(expires_at) WHERE expired = FALSE;
CREATE INDEX idx_files_attached_at  ON files(attached_at) WHERE attached_at IS NULL;

-- ============================================================
-- message_attachments: M2M messages ↔ files (composite key)
-- ============================================================

CREATE TABLE message_attachments (
    message_id     UUID     NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    file_id        UUID     NOT NULL REFERENCES files(id),
    display_order  SMALLINT NOT NULL DEFAULT 0,
    PRIMARY KEY (message_id, file_id)
);

COMMENT ON TABLE message_attachments IS 'Many-to-many giữa messages và files; display_order giữ thứ tự hiển thị trong bubble.';

CREATE INDEX idx_msg_attach_file ON message_attachments(file_id);
