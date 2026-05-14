-- V13: Message Reactions (W8-D1)
-- 1 row per (user, message) — UNIQUE enforces invariant "1 user 1 emoji per message".
-- Toggle semantics (BE service): INSERT (ADDED) / DELETE (REMOVED same) / UPDATE (CHANGED different).

CREATE TABLE message_reactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    emoji VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_message_reactions_msg_user UNIQUE (message_id, user_id)
);

CREATE INDEX idx_reactions_message ON message_reactions(message_id);
CREATE INDEX idx_reactions_user ON message_reactions(user_id);
