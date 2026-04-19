-- ============================================================
-- V2: Users, Auth Providers, User Blocks
-- Khớp với ARCHITECTURE.md mục 3.1
-- pgcrypto extension đã có sẵn (gen_random_uuid() hoạt động)
-- ============================================================

-- Bảng chính: thông tin user
COMMENT ON SCHEMA public IS 'chat-app schema';

CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(255) NOT NULL UNIQUE,
    username            VARCHAR(50)  NOT NULL UNIQUE,
    full_name           VARCHAR(100) NOT NULL,
    password_hash       VARCHAR(255),               -- NULL nếu chỉ dùng OAuth
    avatar_url          VARCHAR(500),
    status              VARCHAR(20)  NOT NULL DEFAULT 'active',  -- active, suspended, deleted
    deleted_name        VARCHAR(100),               -- lưu tên gốc khi xóa account
    username_changed_at TIMESTAMPTZ,                -- track thời gian đổi username (60 ngày/lần)
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE users IS 'Thông tin cơ bản của user trong hệ thống';
COMMENT ON COLUMN users.password_hash IS 'NULL nếu user chỉ dùng OAuth, không có password riêng';
COMMENT ON COLUMN users.status IS 'Trạng thái tài khoản: active | suspended | deleted';
COMMENT ON COLUMN users.deleted_name IS 'Lưu full_name gốc khi user xóa tài khoản (tham chiếu lịch sử tin nhắn)';
COMMENT ON COLUMN users.username_changed_at IS 'Timestamp lần đổi username gần nhất, giới hạn 60 ngày/lần';

-- OAuth providers: 1 user có thể link nhiều provider
CREATE TABLE user_auth_providers (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider     VARCHAR(50) NOT NULL,     -- 'google', 'facebook', ...
    provider_uid VARCHAR(255) NOT NULL,    -- ID từ Firebase/provider
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (provider, provider_uid)
);

COMMENT ON TABLE user_auth_providers IS 'OAuth provider được link vào tài khoản user';
COMMENT ON COLUMN user_auth_providers.provider IS 'Tên provider: google, facebook, ...';
COMMENT ON COLUMN user_auth_providers.provider_uid IS 'UID phía provider (Firebase UID, Facebook ID, ...)';

CREATE INDEX idx_auth_providers_user ON user_auth_providers(user_id);

-- Block list: 2 chiều (mỗi chiều 1 record riêng)
CREATE TABLE user_blocks (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    blocker_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (blocker_id, blocked_id)
);

COMMENT ON TABLE user_blocks IS 'Danh sách block giữa các user (2 chiều — mỗi chiều lưu 1 record)';
COMMENT ON COLUMN user_blocks.blocker_id IS 'User thực hiện hành động block';
COMMENT ON COLUMN user_blocks.blocked_id IS 'User bị block';

CREATE INDEX idx_blocks_blocker ON user_blocks(blocker_id);
CREATE INDEX idx_blocks_blocked ON user_blocks(blocked_id);
