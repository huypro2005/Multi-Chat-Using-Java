-- V14: Add pin message support to messages table

ALTER TABLE messages
    ADD COLUMN pinned_at TIMESTAMPTZ,
    ADD COLUMN pinned_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL;

-- Partial index: only index rows where pinned_at IS NOT NULL (fast query for pinned list)
CREATE INDEX idx_messages_pinned ON messages(conversation_id, pinned_at)
    WHERE pinned_at IS NOT NULL;
