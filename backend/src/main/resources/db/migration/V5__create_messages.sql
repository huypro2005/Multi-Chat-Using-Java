-- ============================================================
-- V5: Messages table
-- Khớp với ARCHITECTURE.md mục 3.2
-- Task: W4-D1 — schema messages + 2 REST endpoints
-- ============================================================

CREATE TABLE messages (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id     UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id           UUID        NOT NULL REFERENCES users(id) ON DELETE SET NULL,
    type                VARCHAR(20) NOT NULL DEFAULT 'TEXT'
                                    CHECK (type IN ('TEXT', 'IMAGE', 'FILE', 'SYSTEM')),
    content             TEXT        NOT NULL,
    reply_to_message_id UUID        NULL REFERENCES messages(id) ON DELETE SET NULL,
    edited_at           TIMESTAMPTZ NULL,
    deleted_at          TIMESTAMPTZ NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE messages IS 'Tin nhắn trong conversation. Soft-delete qua deleted_at.';
COMMENT ON COLUMN messages.type IS 'TEXT | IMAGE | FILE | SYSTEM';
COMMENT ON COLUMN messages.reply_to_message_id IS 'ID tin nhắn gốc nếu đây là reply — tự tham chiếu, 1 level';
COMMENT ON COLUMN messages.deleted_at IS 'Soft delete — không xóa cứng dữ liệu';

CREATE INDEX idx_messages_conv_created ON messages(conversation_id, created_at DESC);
CREATE INDEX idx_messages_sender       ON messages(sender_id);
CREATE INDEX idx_messages_reply        ON messages(reply_to_message_id)
    WHERE reply_to_message_id IS NOT NULL;

-- Defer FK từ V3: conversation_members.last_read_message_id → messages
-- (cột đã tồn tại, chỉ thêm constraint)
ALTER TABLE conversation_members
    ADD CONSTRAINT fk_members_last_read
    FOREIGN KEY (last_read_message_id) REFERENCES messages(id) ON DELETE SET NULL;
