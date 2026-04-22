-- V12: Read Receipt — last_read_message_id per member (W7-D5)
-- Tracks which message each member last read for unread count + tick ticks.
--
-- NOTE: ConversationMember entity already has lastReadMessageId field mapped to this column.
-- This migration adds the FK constraint + index.

ALTER TABLE conversation_members
  ADD COLUMN IF NOT EXISTS last_read_message_id UUID
    REFERENCES messages(id) ON DELETE SET NULL;

-- Composite index: unread count query + readBy per message lookup
CREATE INDEX IF NOT EXISTS idx_conv_members_last_read
  ON conversation_members(conversation_id, last_read_message_id);
