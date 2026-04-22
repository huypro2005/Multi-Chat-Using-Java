-- V10: Add SYSTEM message support (W7-D4)
-- Adds 2 nullable columns to messages table. Existing rows: both NULL (TEXT/IMAGE/FILE).

ALTER TABLE messages
  ADD COLUMN system_event_type VARCHAR(50),
  ADD COLUMN system_metadata JSONB;

-- Relax sender_id nullable for SYSTEM messages (server-generated, no user actor in sender slot).
-- Existing NOT NULL constraint on sender_id drop -> allow NULL.
ALTER TABLE messages
  ALTER COLUMN sender_id DROP NOT NULL;

-- CHECK constraint: type='SYSTEM' requires system_event_type non-null;
-- type != 'SYSTEM' requires system_event_type IS NULL (prevent dirty data).
ALTER TABLE messages
  ADD CONSTRAINT chk_message_system_consistency
  CHECK (
    (type = 'SYSTEM' AND system_event_type IS NOT NULL AND sender_id IS NULL)
    OR
    (type != 'SYSTEM' AND system_event_type IS NULL AND system_metadata IS NULL)
  );

-- Index for "system events in conv" (V2 may need -- V1 does not query separately).
-- CREATE INDEX idx_messages_system_type ON messages (conversation_id, system_event_type)
--   WHERE type = 'SYSTEM';
-- V1 commented out -- add later if query pattern emerges.
