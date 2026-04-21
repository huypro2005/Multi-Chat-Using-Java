-- ============================================================
-- V9: Group Chat metadata (W7-D1, ADR-020)
--
-- CONTEXT:
--  - V3 đã tạo `conversations` với cột `name`, `avatar_url` (TEXT), `created_by`
--    và `conversation_members` với `role VARCHAR(20) CHECK IN ('OWNER','ADMIN','MEMBER')`
--    + `joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`.
--  - Vì vậy V9 CHỈ ADD cột mới cho group management + CHECK constraint.
--  - KHÔNG tạo ENUM `member_role` (giữ VARCHAR cho tương thích H2 test).
--  - V7 đã được dùng cho `files` table (W6-D1). V9 được chọn cho group chat
--    (naming drift so với docs — xem changelog API_CONTRACT v1.0.0-w7).
--
-- SCOPE:
--  - Thêm `owner_id`, `avatar_file_id`, `deleted_at` vào `conversations`.
--  - Thêm CHECK constraint shape invariant: ONE_ON_ONE phải có name+owner_id NULL;
--    GROUP phải có cả name+owner_id non-null.
--  - Index soft-delete filter + owner lookup.
--
-- BACKFILL:
--  - V1-W6 chưa có GROUP conversation trong DB → CHECK constraint apply an toàn.
--  - ONE_ON_ONE rows có `name`/`avatar_url` non-null sẽ vi phạm CHECK — KHÔNG
--    fire trong dev (V3 insert không set name cho ONE_ON_ONE). Nếu có drift data
--    thì backfill `UPDATE conversations SET name=NULL, avatar_url=NULL WHERE type='ONE_ON_ONE'`
--    trước khi apply V9 ở production.
-- ============================================================

-- -----------------------------------------------------------
-- 1) conversations.owner_id — OWNER của group (NULL cho ONE_ON_ONE).
--    ON DELETE SET NULL: OWNER bị xoá account → group vẫn tồn tại, owner_id=NULL.
-- -----------------------------------------------------------
ALTER TABLE conversations
    ADD COLUMN owner_id UUID REFERENCES users(id) ON DELETE SET NULL;

COMMENT ON COLUMN conversations.owner_id IS 'OWNER của GROUP. NULL cho ONE_ON_ONE hoặc khi OWNER bị xoá account.';

-- -----------------------------------------------------------
-- 2) conversations.avatar_file_id — FK tới files(id) cho group avatar.
--    V3 đã có cột `avatar_url TEXT` — giữ lại cho backward-compat nhưng W7+ dùng
--    avatar_file_id làm source of truth. avatarUrl trong response compute từ file_id.
--    ON DELETE SET NULL: file bị xoá cứng → avatar bị reset.
-- -----------------------------------------------------------
ALTER TABLE conversations
    ADD COLUMN avatar_file_id UUID REFERENCES files(id) ON DELETE SET NULL;

COMMENT ON COLUMN conversations.avatar_file_id IS 'File avatar nhóm (W7+); NULL cho ONE_ON_ONE hoặc group chưa có avatar.';

-- -----------------------------------------------------------
-- 3) conversations.deleted_at — soft-delete timestamp (OWNER delete group).
--    NULL = active. Query list/read PHẢI filter deleted_at IS NULL.
-- -----------------------------------------------------------
ALTER TABLE conversations
    ADD COLUMN deleted_at TIMESTAMPTZ;

COMMENT ON COLUMN conversations.deleted_at IS 'Soft-delete timestamp (W7). NULL = active.';

-- -----------------------------------------------------------
-- 4) CHECK constraint shape invariant:
--    ONE_ON_ONE: name NULL AND owner_id NULL.
--    GROUP: name NOT NULL AND owner_id NOT NULL (trừ khi OWNER bị xoá → owner_id=NULL tạm thời).
--    LƯU Ý: owner_id có thể NULL cho GROUP khi user OWNER bị xoá (ON DELETE SET NULL),
--    nên constraint dùng check mềm: (type='GROUP' AND name IS NOT NULL).
--    V1 spec yêu cầu strict — nhưng deletion edge case phải được handle. Chọn
--    soft check: owner_id có thể NULL sau deletion, nhưng khi CREATE group BE
--    enforce non-null ở service layer.
-- -----------------------------------------------------------
ALTER TABLE conversations
    ADD CONSTRAINT chk_group_metadata
    CHECK (
        (type = 'ONE_ON_ONE' AND name IS NULL AND owner_id IS NULL) OR
        (type = 'GROUP' AND name IS NOT NULL)
    );

-- -----------------------------------------------------------
-- 5) Indexes
-- -----------------------------------------------------------
-- Owner lookup (ADMIN dashboard, cleanup job)
CREATE INDEX idx_conversations_owner
    ON conversations(owner_id)
    WHERE owner_id IS NOT NULL;

-- Soft-delete filter hot path. Partial index — chỉ index rows đã xoá (thiểu số) để scan audit nhanh.
CREATE INDEX idx_conversations_deleted
    ON conversations(deleted_at)
    WHERE deleted_at IS NOT NULL;

-- conversation_members đã có idx_members_conv (V3). Thêm index (conv_id, role) để query
-- member theo role (OWNER promote, ADMIN list).
CREATE INDEX idx_conversation_members_conv_role
    ON conversation_members(conversation_id, role);

-- (conv_id, joined_at) cho sort member list theo joined_at ASC (hiển thị GET /{id})
CREATE INDEX idx_conversation_members_conv_joined
    ON conversation_members(conversation_id, joined_at);
