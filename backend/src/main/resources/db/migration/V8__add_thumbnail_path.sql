-- ============================================================
-- V8: Thumbnail path for image files (W6-D2)
-- ============================================================
--
-- thumbnail_internal_path: internal storage path to 200x200 thumbnail JPEG.
--  - NULL = không có thumbnail (PDF / non-image, hoặc thumbnail generation failed fail-open).
--  - Non-null = path relative tới storage basePath, dùng để stream qua GET /api/files/{id}/thumb.
--
-- Path layout: {base}/{yyyy}/{mm}/{uuid}_thumb.{ext} (cùng thư mục với original).
-- Không expose ra API response — chỉ lưu nội bộ; response trả URL `/api/files/{id}/thumb`.
-- ============================================================

ALTER TABLE files
    ADD COLUMN thumbnail_internal_path VARCHAR(1024) NULL;

COMMENT ON COLUMN files.thumbnail_internal_path IS
    'Internal path tới thumbnail 200x200 (NULL cho PDF/non-image hoặc nếu generate thumbnail fail-open). KHÔNG expose ra API.';
