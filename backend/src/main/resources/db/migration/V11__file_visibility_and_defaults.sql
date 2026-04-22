-- ============================================================
-- V11: Hybrid File Visibility (ADR-021) + Default Avatars
--
-- SCOPE:
--  - Thêm files.is_public BOOLEAN cho hybrid visibility.
--  - Cho phép files.uploader_id NULL (default/system files).
--  - Backfill: existing avatars (user + conv) → is_public=true.
--  - Seed 2 default avatar records (fixed UUIDs).
--
-- CONTEXT:
--  - ADR-021 Model 4: hybrid public/private per-file flag.
--  - Public files dùng endpoint /api/files/{id}/public (no auth).
--  - Default avatars (users chưa upload, group chưa set) trỏ tới fixed UUIDs.
--  - Cleanup job SKIP 2 UUIDs này; expires_at = 9999-12-31 safeguard kép.
--
-- PHYSICAL FILES:
--  - default/avatar_default.jpg và default/group_default.jpg PHẢI copy tay
--    vào ${STORAGE_PATH}/default/ sau deploy. Migration chỉ tạo DB pointer.
--  - BE startup log WARN (soft check) nếu disk thiếu.
-- ============================================================

-- -----------------------------------------------------------
-- 1) uploader_id NULLABLE — system/default files không có uploader.
-- -----------------------------------------------------------
ALTER TABLE files
    ALTER COLUMN uploader_id DROP NOT NULL;

-- -----------------------------------------------------------
-- 2) is_public flag — default FALSE để safe (existing rows = attachments).
-- -----------------------------------------------------------
ALTER TABLE files
    ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN files.is_public IS 'ADR-021: true = public endpoint (/public, no auth), false = private (JWT required).';

-- -----------------------------------------------------------
-- 3) Backfill existing avatars → is_public = TRUE
--    (user avatars trong users.avatar_file_id — nếu column tồn tại;
--    group avatars trong conversations.avatar_file_id).
--    Wrap trong DO block để tolerant nếu users.avatar_file_id chưa có
--    (V2 schema chỉ có avatar_url String — chưa có file_id).
-- -----------------------------------------------------------
DO $$
BEGIN
    -- Group avatars (conversations.avatar_file_id — V9 đã add)
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'conversations' AND column_name = 'avatar_file_id'
    ) THEN
        UPDATE files SET is_public = TRUE
        WHERE id IN (
            SELECT avatar_file_id FROM conversations WHERE avatar_file_id IS NOT NULL
        );
    END IF;

    -- User avatars (users.avatar_file_id — chưa có, future schema)
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'avatar_file_id'
    ) THEN
        UPDATE files SET is_public = TRUE
        WHERE id IN (
            SELECT avatar_file_id FROM users WHERE avatar_file_id IS NOT NULL
        );
    END IF;
END $$;

-- -----------------------------------------------------------
-- 4) Seed 2 default avatar records (fixed UUIDs — never expire, never delete).
--    expires_at = 9999-12-31 để cleanup job không scan (double-safeguard
--    nếu code quên skip UUID).
-- -----------------------------------------------------------
INSERT INTO files (
    id, uploader_id, original_name, mime, size_bytes, storage_path,
    created_at, expires_at, expired, attached_at, is_public
) VALUES
    ('00000000-0000-0000-0000-000000000001',
     NULL,
     'avatar_default.jpg',
     'image/jpeg',
     0,
     'default/avatar_default.jpg',
     NOW(),
     TIMESTAMPTZ '9999-12-31 23:59:59+00',
     FALSE,
     NOW(),
     TRUE),
    ('00000000-0000-0000-0000-000000000002',
     NULL,
     'group_default.jpg',
     'image/jpeg',
     0,
     'default/group_default.jpg',
     NOW(),
     TIMESTAMPTZ '9999-12-31 23:59:59+00',
     FALSE,
     NOW(),
     TRUE)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------------
-- 5) Partial index cho public file lookups (hot path — /public endpoint).
-- -----------------------------------------------------------
CREATE INDEX idx_files_public ON files(id) WHERE is_public = TRUE;
