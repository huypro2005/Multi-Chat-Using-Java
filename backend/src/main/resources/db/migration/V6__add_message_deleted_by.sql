-- ============================================================
-- V6: Add deleted_by to messages table
-- Task: W5-D3 — delete message via STOMP (soft delete)
-- ============================================================
-- NOTE: deleted_at already exists from V5.
-- Only deleted_by is new here.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS deleted_by UUID NULL REFERENCES users(id) ON DELETE SET NULL;

COMMENT ON COLUMN messages.deleted_by IS 'User who performed the soft delete. NULL = not deleted.';
