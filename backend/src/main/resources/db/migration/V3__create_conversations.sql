-- ============================================================
-- V3: Conversations + Conversation Members
-- Khớp với ARCHITECTURE.md mục 3.2
-- Task: W3-D1 — schema + domain model only (no service/controller)
-- ============================================================

-- Mọi cuộc trò chuyện đều là conversation (1-1 và nhóm)
CREATE TABLE conversations (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    type            VARCHAR(20) NOT NULL CHECK (type IN ('ONE_ON_ONE', 'GROUP')),
    name            VARCHAR(100),                         -- NULL cho 1-1 chat
    avatar_url      TEXT,                                  -- NULL cho 1-1 chat
    created_by      UUID        REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_message_at TIMESTAMPTZ                            -- cập nhật khi có tin mới
);

COMMENT ON TABLE conversations IS 'Cuộc trò chuyện: 1-1 (ONE_ON_ONE) hoặc nhóm (GROUP)';
COMMENT ON COLUMN conversations.type IS 'ONE_ON_ONE | GROUP';
COMMENT ON COLUMN conversations.name IS 'Tên nhóm — NULL cho cuộc trò chuyện 1-1';
COMMENT ON COLUMN conversations.avatar_url IS 'Avatar nhóm — NULL cho cuộc trò chuyện 1-1';
COMMENT ON COLUMN conversations.last_message_at IS 'Timestamp tin nhắn gần nhất, dùng để sort danh sách conversation';

CREATE INDEX idx_conversations_last_message ON conversations(last_message_at DESC NULLS LAST);
CREATE INDEX idx_conversations_created_by   ON conversations(created_by);

-- Thành viên trong conversation
CREATE TABLE conversation_members (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id      UUID        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id              UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role                 VARCHAR(20) NOT NULL DEFAULT 'MEMBER'
                                     CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    joined_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_read_message_id UUID,                             -- đánh dấu đã đọc đến đây
    muted_until          TIMESTAMPTZ,                      -- tắt thông báo đến thời điểm X

    CONSTRAINT uq_members_conv_user UNIQUE (conversation_id, user_id)
);

COMMENT ON TABLE conversation_members IS 'Thành viên của conversation';
COMMENT ON COLUMN conversation_members.role IS 'OWNER | ADMIN | MEMBER';
COMMENT ON COLUMN conversation_members.last_read_message_id IS 'ID tin nhắn cuối user đã đọc trong conversation này';
COMMENT ON COLUMN conversation_members.muted_until IS 'Tắt thông báo đến thời điểm này; NULL = không mute';

CREATE INDEX idx_members_user ON conversation_members(user_id, joined_at DESC);
CREATE INDEX idx_members_conv ON conversation_members(conversation_id);
