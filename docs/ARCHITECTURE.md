# Tài liệu thiết kế hệ thống: Web Chat Application V1

## Tổng quan quyết định

| Hạng mục | Quyết định V1 |
|----------|---------------|
| Quy mô | < 1,000 users, < 1,000 concurrent, ~4,000 tin/ngày |
| Nhóm | Tối đa 50 thành viên, không giới hạn số nhóm/user |
| Backend | Spring Boot + Spring WebSocket (STOMP) |
| Database | PostgreSQL (single instance) |
| Cache/Realtime | Redis (online status, badge count, JWT blacklist, pub/sub) |
| Auth | Firebase Auth (OAuth2 Google) → Backend phát JWT riêng |
| File Storage V1 | Upload lên server, lưu local → V2 chuyển S3 |
| Deploy | 1 server Singapore |

---

## 1. Kiến trúc tổng thể

```
┌─────────────┐     HTTPS/WSS      ┌──────────────────────────────┐
│   Browser    │◄──────────────────►│     Spring Boot Server       │
│  (React/Vue) │                    │                              │
└─────────────┘                    │  ┌──────────────────────┐     │
                                   │  │  REST Controllers     │     │
                                   │  │  (Auth, User, File,   │     │
                                   │  │   Conversation CRUD)  │     │
                                   │  └──────────┬───────────┘     │
                                   │             │                 │
                                   │  ┌──────────▼───────────┐     │
                                   │  │  WebSocket/STOMP      │     │
                                   │  │  (Gửi + Nhận tin nhắn │     │
                                   │  │   text, typing, ACK)  │     │
                                   │  └──────────┬───────────┘     │
                                   │             │                 │
                                   │  ┌──────────▼───────────┐     │
                                   │  │  MessageService       │     │
                                   │  │  (Validate, Save, Fan │     │
                                   │  │   out, ACK/Error)     │     │
                                   │  └──────────┬───────────┘     │
                                   └─────────────┼─────────────────┘
                                                 │
                          ┌──────────────────────┼──────────────────────┐
                          │                      │                      │
                   ┌──────▼──────┐       ┌───────▼──────┐      ┌───────▼──────┐
                   │ PostgreSQL  │       │    Redis      │      │ File Storage │
                   │ (data)      │       │ (cache/pubsub)│      │ (local → S3) │
                   └─────────────┘       └──────────────┘      └──────────────┘
```

### Phân chia trách nhiệm: Socket vs REST

```
┌─────────────────────────────────────────────────────────────────────┐
│  QUA WEBSOCKET/STOMP (realtime, low latency)                       │
│                                                                     │
│  • Gửi tin nhắn text           → client SEND, server ACK/ERROR     │
│  • Nhận tin nhắn mới           → server push MESSAGE                │
│  • Typing indicator            → client SEND, server broadcast      │
│  • Online/offline status       → server push PRESENCE               │
│  • Reactions                   → client SEND, server broadcast      │
│  • Message edited/deleted      → server push MESSAGE_UPDATE         │
│  • Đã đọc                     → client SEND, server broadcast      │
│  • Conversation updates        → server push CONVERSATION_UPDATE    │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  QUA REST/HTTP (request-response, binary upload)                    │
│                                                                     │
│  • Auth (login, register, OAuth, refresh, logout)                   │
│  • User CRUD (profile, avatar, block, search)                       │
│  • Conversation CRUD (tạo, list, thêm/xóa member, cài đặt)        │
│  • Upload file/ảnh (multipart → lưu xong → gửi message qua socket) │
│  • Lấy lịch sử tin nhắn (phân trang)                               │
│  • Tìm kiếm tin nhắn                                               │
│  • Tin nhắn ghim                                                    │
└─────────────────────────────────────────────────────────────────────┘
```

**Tại sao text qua Socket, file qua REST?**

Text messages rất nhẹ (vài KB) và cần latency thấp nhất — socket là kênh tự nhiên. Nhưng file/ảnh (lên đến 20MB) không phù hợp với STOMP vì STOMP frame không được thiết kế cho binary lớn, không có progress bar, và nếu upload thất bại giữa chừng, socket connection có thể bị hỏng luôn. Vì vậy file upload qua REST (multipart), upload xong server tạo message rồi push qua socket cho tất cả members.

### Flow gửi tin nhắn TEXT (qua Socket với ACK)

```
┌────────┐                          ┌─────────────┐         ┌────────┐  ┌───────┐
│Client A│                          │   Server     │         │  DB    │  │ Redis │
└───┬────┘                          └──────┬───────┘         └───┬────┘  └───┬───┘
    │                                      │                     │           │
    │── SEND /app/chat.send ─────────────►│                     │           │
    │   { tempId, convId, content,         │                     │           │
    │     type:"text", replyTo? }          │                     │           │
    │                                      │                     │           │
    │                                      │── Validate ────────►│           │
    │                                      │   (member? block?   │           │
    │                                      │    rate limit?)     │           │
    │                                      │                     │           │
    │                               [Nếu FAIL]                   │           │
    │◄── ERROR to /user/queue/errors ─────│                     │           │
    │   { tempId, code, message }          │                     │           │
    │   (Client hiển thị "Gửi thất bại")   │                     │           │
    │                                      │                     │           │
    │                               [Nếu OK]                     │           │
    │                                      │── INSERT message ──►│           │
    │                                      │◄── saved (with id) ─│           │
    │                                      │                     │           │
    │◄── ACK to /user/queue/acks ─────────│                     │           │
    │   { tempId, messageId,               │                     │           │
    │     createdAt, status:"saved" }      │                     │           │
    │   (Client thay tempId bằng real id)  │                     │           │
    │                                      │                     │           │
    │                                      │── Publish ─────────►│──────────►│
    │                                      │   "conv:{id}"       │           │
    │                                      │                     │           │
    │                                      │── Push MESSAGE ────►│  tất cả   │
    │                                      │   to all online     │  members  │
    │                                      │   members' sockets  │  online   │
    │                                      │                     │           │
    │                                      │── INCR unread ─────►│──────────►│
    │                                      │   for offline       │           │
    │                                      │   members           │           │
```

### Cơ chế tempId — tại sao cần?

Khi gửi qua socket, client không có HTTP response trả về message ID ngay. Vì vậy:

1. Client tạo một `tempId` (UUID phía client) cho mỗi tin nhắn
2. Client hiển thị tin nhắn ngay trong UI với trạng thái "đang gửi" (icon loading)
3. Server nhận, validate, lưu DB, trả về ACK kèm `{ tempId, messageId }`
4. Client nhận ACK → thay `tempId` bằng `messageId` thật, đổi trạng thái "đã gửi"
5. Nếu sau 5-10 giây không nhận ACK → client hiển thị "Gửi thất bại", cho phép retry

Nếu server gửi ERROR thay vì ACK:
- Client nhận ERROR kèm `tempId` → hiển thị "Gửi thất bại" kèm lý do
- Lý do có thể là: "Bạn đã bị chặn", "Bạn không phải thành viên", "Rate limit"

### Flow gửi FILE/ẢNH (hybrid: REST upload + Socket notify)

```
┌────────┐                          ┌─────────────┐         ┌────────┐
│Client A│                          │   Server     │         │  DB    │
└───┬────┘                          └──────┬───────┘         └───┬────┘
    │                                      │                     │
    │── POST /api/conversations/{id}/      │                     │
    │   messages/file (multipart) ────────►│                     │
    │   + tempId trong form data           │                     │
    │                                      │                     │
    │                                      │── Save file to disk │
    │                                      │── Resize if image   │
    │                                      │── Insert message ──►│
    │                                      │── Insert file_record►│
    │                                      │                     │
    │◄── HTTP 200 { tempId, messageId } ──│                     │
    │   (Client thay tempId bằng real id)  │                     │
    │                                      │                     │
    │                                      │── Push MESSAGE ────►│ tất cả
    │                                      │   qua WebSocket     │ members
    │                                      │   to all members    │
```

File/ảnh vẫn qua REST vì:
- Có progress bar (XMLHttpRequest/fetch có onProgress)
- Retry dễ (resend HTTP request)
- Không ảnh hưởng socket connection nếu upload thất bại
- Client cũng gửi kèm `tempId` trong form data để track trạng thái

---

## 2. Authentication Flow

### Flow A: Đăng nhập bằng Username/Password (truyền thống)

```
┌────────┐                        ┌─────────────┐    ┌──────────┐
│ Client │                        │ Your Server │    │  Redis   │
└───┬────┘                        └──────┬──────┘    └────┬─────┘
    │                                    │                 │
    │── POST /auth/login ──────────────►│                 │
    │   { username, password }           │                 │
    │                                    │                 │
    │                                    │── Find user     │
    │                                    │   by username   │
    │                                    │                 │
    │                                    │── Verify        │
    │                                    │   password_hash │
    │                                    │   (BCrypt)      │
    │                                    │                 │
    │                                    │── Generate      │
    │                                    │   JWT (1h) +    │
    │                                    │   Refresh (7d)  │
    │                                    │                 │
    │                                    │── Store refresh │
    │                                    │   token hash ──►│ Redis
    │                                    │                 │
    │◄── JWT + Refresh Token ───────────│                 │
    │                                    │                 │
```

### Flow B: Đăng nhập bằng OAuth2 (Google qua Firebase)

```
┌────────┐    ┌──────────┐    ┌─────────────┐    ┌──────────┐
│ Client │    │ Firebase │    │ Your Server │    │  Redis   │
└───┬────┘    └────┬─────┘    └──────┬──────┘    └────┬─────┘
    │              │                 │                 │
    │─── Login ───►│                 │                 │
    │◄─ Firebase   │                 │                 │
    │  ID Token ───│                 │                 │
    │              │                 │                 │
    │── POST /auth/oauth ──────────►│                 │
    │   { firebase_id_token }        │                 │
    │              │                 │── Verify token ─►│ Firebase SDK
    │              │                 │◄── Valid ───────│
    │              │                 │                 │
    │              │                 │── Check user_   │
    │              │                 │   auth_providers │
    │              │                 │                 │
    │              │                 │── If new: create │
    │              │                 │   user + provider│
    │              │                 │   If exists: find│
    │              │                 │   linked user    │
    │              │                 │                 │
    │              │                 │── Generate      │
    │              │                 │   JWT (1h) +    │
    │              │                 │   Refresh (7d)  │
    │              │                 │                 │
    │              │                 │── Store refresh │
    │              │                 │   token hash ──►│ Redis
    │              │                 │                 │
    │◄── JWT + Refresh Token ───────│                 │
    │                                │                 │
```

### Flow C: Đăng ký tài khoản mới (Username/Password)

```
POST /api/auth/register
{ email, username, password, full_name }

1. Validate: email format, username format (3-50 chars, alphanumeric + underscore),
   password strength (min 8 chars, có chữ hoa + số)
2. Check unique: email chưa tồn tại? username chưa tồn tại?
3. Hash password bằng BCrypt (strength 12)
4. Insert vào bảng users (password_hash = BCrypt hash)
5. Generate JWT + Refresh Token
6. Return JWT + Refresh Token
```

### Flow D: Link OAuth vào tài khoản đã có

```
POST /api/auth/link-provider   (yêu cầu đã đăng nhập — có JWT)
{ firebase_id_token }

1. Verify Firebase ID Token → lấy provider + provider_uid + email
2. Check: provider_uid này đã link với user khác chưa?
   → Nếu rồi: 409 Conflict "Tài khoản Google này đã liên kết với user khác"
3. Check: email từ OAuth có khớp với email của user hiện tại không?
   → Nếu khác: quyết định cho phép hay không (đề xuất V1: bắt buộc khớp email)
4. Insert vào user_auth_providers (user_id = current user, provider, provider_uid)
5. Return success
```

### Flow E: Đổi mật khẩu

```
PUT /api/auth/change-password   (yêu cầu đã đăng nhập)
{ current_password, new_password }

1. Verify current_password đúng với password_hash trong DB
2. Validate new_password (độ mạnh)
3. Update password_hash = BCrypt(new_password)
4. Invalidate tất cả refresh tokens của user trong Redis
5. Blacklist tất cả JWT đang active (thêm vào jwt:blacklist)
6. Disconnect tất cả WebSocket connections
7. Return success → client phải login lại
```

### Xử lý edge case: cùng email, khác hình thức đăng ký

```
Tình huống: User A đăng ký bằng email john@gmail.com + password.
            Sau đó login bằng Google OAuth (cũng là john@gmail.com).

Xử lý:
1. OAuth login → server verify Firebase token → lấy email = john@gmail.com
2. Check user_auth_providers: chưa có record cho Google provider này
3. Check bảng users: đã có user với email john@gmail.com
4. Vì email khớp → TỰ ĐỘNG link Google provider vào user hiện tại
   (Insert user_auth_providers: user_id = A, provider = 'google', provider_uid = ...)
5. Phát JWT cho user A
6. Lần sau user A có thể login bằng password HOẶC Google

Ngược lại: Nếu email OAuth KHÔNG khớp với bất kỳ user nào → tạo user mới
```

### JWT Payload

```json
{
  "sub": "user_uuid",
  "username": "john_doe",
  "auth_method": "password",
  "iat": 1700000000,
  "exp": 1700003600,
  "jti": "unique_token_id"
}
```

Trường `auth_method` có giá trị `"password"` hoặc `"oauth2_google"` — hữu ích khi
cần phân biệt (ví dụ: yêu cầu nhập lại password khi đổi password, nhưng không yêu
cầu nếu user chỉ dùng OAuth).

### Force Logout Flow

```
1. User đổi password hoặc logout all devices
2. Server thêm tất cả jti của user vào Redis blacklist:
   SET "jwt:blacklist:{jti}" "" EX 3600  (TTL = thời gian còn lại của token)
3. Server xóa tất cả refresh token của user khỏi Redis
4. Server disconnect tất cả WebSocket connections của user
5. Mỗi request, middleware check: jti có trong blacklist → reject
```

### Endpoints Auth

```
POST   /api/auth/register       # Đăng ký bằng email + username + password → JWT + Refresh
POST   /api/auth/login          # Đăng nhập bằng username + password → JWT + Refresh
POST   /api/auth/oauth          # Đăng nhập bằng Firebase ID Token (OAuth2) → JWT + Refresh
POST   /api/auth/link-provider  # Link thêm OAuth provider vào account đã có (cần JWT)
POST   /api/auth/change-password # Đổi mật khẩu (cần JWT)
POST   /api/auth/refresh        # Refresh Token → New JWT
POST   /api/auth/logout         # Invalidate current session
POST   /api/auth/logout-all     # Invalidate tất cả sessions
```

---

## 3. Database Schema (PostgreSQL)

### 3.1 Users & Auth

```sql
-- Bảng chính: thông tin user
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL UNIQUE,
    username        VARCHAR(50)  NOT NULL UNIQUE,
    full_name       VARCHAR(100) NOT NULL,
    password_hash   VARCHAR(255),          -- NULL nếu chỉ dùng OAuth
    avatar_url      VARCHAR(500),
    status          VARCHAR(20) DEFAULT 'active',  -- active, deleted
    deleted_name    VARCHAR(100),          -- lưu tên gốc khi xóa account
    username_changed_at TIMESTAMPTZ,       -- track thời gian đổi username (60 ngày/lần)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- OAuth providers: 1 user có thể link nhiều provider
CREATE TABLE user_auth_providers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider        VARCHAR(50) NOT NULL,      -- 'google', 'facebook', ...
    provider_uid    VARCHAR(255) NOT NULL,      -- ID từ Firebase/provider
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (provider, provider_uid)
);

CREATE INDEX idx_auth_providers_user ON user_auth_providers(user_id);

-- Block list: 2 chiều
CREATE TABLE user_blocks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    blocker_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (blocker_id, blocked_id)
);

CREATE INDEX idx_blocks_blocker ON user_blocks(blocker_id);
CREATE INDEX idx_blocks_blocked ON user_blocks(blocked_id);
```

### 3.2 Conversations & Members

```sql
-- Mọi cuộc trò chuyện đều là conversation (1-1 và nhóm)
CREATE TABLE conversations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type            VARCHAR(10) NOT NULL CHECK (type IN ('direct', 'group')),
    name            VARCHAR(100),              -- NULL cho direct chat
    avatar_url      VARCHAR(500),              -- NULL cho direct chat
    created_by      UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Thành viên trong conversation
CREATE TABLE conversation_members (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id      UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id              UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role                 VARCHAR(10) NOT NULL DEFAULT 'member'
                         CHECK (role IN ('owner', 'admin', 'member')),
    last_read_message_id UUID,                 -- đánh dấu đã đọc mức conversation
    mute_until           TIMESTAMPTZ,          -- tắt thông báo đến thời điểm X
    is_hidden            BOOLEAN DEFAULT FALSE, -- ẩn conversation khỏi danh sách
    cleared_at           TIMESTAMPTZ,          -- "xóa lịch sử": chỉ hiển thị tin sau thời điểm này
    joined_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at              TIMESTAMPTZ,          -- NOT NULL = đã rời/bị kick
    leave_reason         VARCHAR(50),          -- 'left', 'kicked_by_admin'
    UNIQUE (conversation_id, user_id)
);

CREATE INDEX idx_members_user ON conversation_members(user_id) WHERE left_at IS NULL;
CREATE INDEX idx_members_conv ON conversation_members(conversation_id) WHERE left_at IS NULL;
CREATE INDEX idx_members_joined ON conversation_members(conversation_id, joined_at);
```

**Giải thích thiết kế quan trọng:**

- `left_at` NOT NULL = user đã rời. Query active members: `WHERE left_at IS NULL`
- User bị kick vẫn giữ record (không xóa) → xem lại tin nhắn cũ được
- `last_read_message_id`: đánh dấu đã đọc mức conversation. Unread count = messages sau ID này
- `mute_until`: NULL = bình thường, timestamp = tắt thông báo đến thời điểm đó, 'infinity' = mute vĩnh viễn

**`is_hidden` và `cleared_at` — hai trường phối hợp với nhau:**

```
is_hidden   cleared_at          Ý nghĩa
────────    ──────────          ───────────────────────────────────
false       NULL                Bình thường, thấy toàn bộ lịch sử
false       2025-06-15 10:00    Đã từng "xóa chat", giờ có tin mới
                                → hiện conversation nhưng chỉ hiển thị
                                  tin nhắn sau 2025-06-15 10:00
true        2025-06-15 10:00    Vừa "xóa chat" → ẩn khỏi danh sách
                                Khi có tin mới → unhide, nhưng vẫn
                                chỉ hiển thị tin sau cleared_at
```

Flow khi user "xóa cuộc trò chuyện":
1. `UPDATE conversation_members SET is_hidden = true, cleared_at = NOW()`
2. Conversation biến mất khỏi danh sách của user
3. Khi có tin nhắn mới → server set `is_hidden = false` (unhide)
4. User thấy lại conversation nhưng chỉ thấy tin nhắn mới
5. User có thể "xóa chat" nhiều lần → `cleared_at` luôn cập nhật lần xóa gần nhất

Flow khi user bị kick khỏi nhóm:
1. `left_at = NOW(), leave_reason = 'kicked_by_admin'`
2. `cleared_at` KHÔNG đổi → user vẫn xem được tin nhắn cũ
   (theo yêu cầu: user bị kick vẫn xem lại tin nhắn cũ được)
3. Nhưng chỉ xem được tin nhắn từ `MAX(joined_at, cleared_at)` đến `left_at`

### 3.3 Messages

```sql
CREATE TABLE messages (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id   UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    type              VARCHAR(20) NOT NULL DEFAULT 'text'
                      CHECK (type IN ('text', 'image', 'file', 'system')),
    content           TEXT,                    -- nội dung text, hoặc system message
    metadata          JSONB,                   -- file/image info (url, filename, size, mime, thumbnail_url)
    reply_to_id       UUID REFERENCES messages(id) ON DELETE SET NULL,
    is_pinned         BOOLEAN DEFAULT FALSE,
    is_edited         BOOLEAN DEFAULT FALSE,
    edited_at         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index chính: query tin nhắn theo conversation, sắp xếp theo thời gian
CREATE INDEX idx_messages_conv_time ON messages(conversation_id, created_at DESC);

-- Index cho tìm kiếm tin nhắn ghim
CREATE INDEX idx_messages_pinned ON messages(conversation_id) WHERE is_pinned = TRUE;

-- Full-text search (PostgreSQL built-in)
ALTER TABLE messages ADD COLUMN search_vector tsvector;
CREATE INDEX idx_messages_search ON messages USING GIN(search_vector);

-- Trigger tự update search_vector khi insert/update
CREATE OR REPLACE FUNCTION update_search_vector() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.type = 'text' AND NEW.content IS NOT NULL THEN
        NEW.search_vector := to_tsvector('simple', NEW.content);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_messages_search
    BEFORE INSERT OR UPDATE ON messages
    FOR EACH ROW EXECUTE FUNCTION update_search_vector();
```

**Metadata JSONB cho từng loại tin nhắn:**

```json
// type = 'image'
{
  "url": "/files/abc123.jpg",
  "thumbnail_url": "/files/abc123_thumb.jpg",
  "original_name": "photo.jpg",
  "size": 1048576,
  "mime": "image/jpeg",
  "width": 1280,
  "height": 960
}

// type = 'file'
{
  "url": "/files/xyz789.pdf",
  "original_name": "report.pdf",
  "size": 2097152,
  "mime": "application/pdf"
}

// type = 'system' → content chứa text: "User A đã thêm User B vào nhóm"
// metadata có thể chứa thêm context
{
  "action": "member_added",
  "actor_id": "uuid-of-A",
  "target_id": "uuid-of-B"
}
```

### 3.4 Reactions

```sql
CREATE TABLE message_reactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id      UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    emoji           VARCHAR(10) NOT NULL,      -- '👍', '❤️', '😂', ...
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (message_id, user_id, emoji)        -- 1 user chỉ react 1 lần mỗi emoji
);

CREATE INDEX idx_reactions_message ON message_reactions(message_id);
```

### 3.5 File Expiry Tracking

```sql
-- Track file để cleanup sau 30 ngày
CREATE TABLE file_records (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id      UUID REFERENCES messages(id) ON DELETE SET NULL,
    file_path       VARCHAR(500) NOT NULL,     -- đường dẫn trên server/S3
    original_name   VARCHAR(255),
    size            BIGINT,
    mime_type       VARCHAR(100),
    expires_at      TIMESTAMPTZ NOT NULL,       -- created_at + 30 ngày
    is_deleted      BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_files_expiry ON file_records(expires_at) WHERE is_deleted = FALSE;
```

### 3.6 Audit Log

```sql
CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id        UUID REFERENCES users(id) ON DELETE SET NULL,
    action          VARCHAR(50) NOT NULL,
    -- Các action: member_added, member_removed, member_left,
    --             role_changed, group_created, group_deleted,
    --             owner_transferred, user_blocked, user_unblocked
    target_type     VARCHAR(50),               -- 'conversation', 'user', 'message'
    target_id       UUID,
    metadata        JSONB,                     -- chi tiết bổ sung
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_time ON audit_logs(created_at DESC);
CREATE INDEX idx_audit_target ON audit_logs(target_type, target_id);
```

---

## 4. Redis Data Structures

```
# Online status
SET   user:online:{user_id}  "1"  EX 300        # TTL 5 phút, heartbeat renew

# WebSocket session tracking
SADD  user:sockets:{user_id}  "{socket_session_id}"

# Unread count per conversation per user
INCR  unread:{user_id}:{conversation_id}         # +1 khi có tin mới
DEL   unread:{user_id}:{conversation_id}          # reset khi đọc

# JWT Blacklist (force logout)
SET   jwt:blacklist:{jti}  ""  EX {remaining_ttl}

# Refresh Token (hashed)
SET   refresh:{user_id}:{token_hash}  ""  EX 604800   # 7 ngày

# Typing indicator (tự hết hạn)
SET   typing:{conversation_id}:{user_id}  "1"  EX 3

# Rate limiting
INCR  rate:msg:{user_id}      EX 1              # 10 tin/giây
INCR  rate:upload:{user_id}   EX 60             # 10 file/phút
INCR  rate:login:{ip}         EX 900            # 5 lần/15 phút

# Message dedup (prevent duplicate on retry)
SET   msg:temp:{tempId}  "{messageId}"  EX 300   # TTL 5 phút
```

---

## 5. WebSocket / STOMP Design

### STOMP Destinations

```
# ═══════════════════════════════════════════════════════════════
# CLIENT SEND (client → server)
# ═══════════════════════════════════════════════════════════════

/app/chat.send                    # Gửi tin nhắn text
                                  # Payload: { tempId, conversationId, content,
                                  #            type:"text", replyToId? }

/app/chat.edit                    # Sửa tin nhắn (trong 24h)
                                  # Payload: { messageId, content }

/app/chat.delete                  # Xóa tin nhắn
                                  # Payload: { messageId }

/app/chat.typing                  # Typing indicator
                                  # Payload: { conversationId, isTyping }

/app/chat.reaction                # Thêm/xóa reaction
                                  # Payload: { messageId, emoji, action:"add"|"remove" }

/app/chat.read                    # Đánh dấu đã đọc
                                  # Payload: { conversationId, lastReadMessageId }

# ═══════════════════════════════════════════════════════════════
# CLIENT SUBSCRIBE (server → client)
# ═══════════════════════════════════════════════════════════════

/user/queue/messages              # Tin nhắn mới (tất cả conversations)
/user/queue/acks                  # ACK: server xác nhận đã lưu tin nhắn
/user/queue/errors                # ERROR: server báo gửi thất bại
/user/queue/notifications         # Thông báo hệ thống (member added/removed, ...)
/topic/conversation/{id}/typing   # Typing indicator cho conversation cụ thể
```

### Client SEND Payloads

```json
// Gửi tin nhắn text
// SEND /app/chat.send
{
  "tempId": "client-uuid-123",
  "conversationId": "conv-uuid",
  "content": "Hello!",
  "type": "text",
  "replyToId": null
}

// Sửa tin nhắn
// SEND /app/chat.edit
{
  "messageId": "msg-uuid",
  "content": "Hello! (đã sửa)"
}

// Xóa tin nhắn
// SEND /app/chat.delete
{
  "messageId": "msg-uuid"
}

// Typing indicator
// SEND /app/chat.typing
{
  "conversationId": "conv-uuid",
  "isTyping": true
}

// Reaction
// SEND /app/chat.reaction
{
  "messageId": "msg-uuid",
  "emoji": "👍",
  "action": "add"
}

// Đánh dấu đã đọc
// SEND /app/chat.read
{
  "conversationId": "conv-uuid",
  "lastReadMessageId": "msg-uuid"
}
```

### Server → Client: ACK & ERROR

```json
// ACK: tin nhắn đã lưu thành công
// → /user/queue/acks
{
  "type": "MESSAGE_ACK",
  "tempId": "client-uuid-123",
  "messageId": "real-msg-uuid",
  "createdAt": "2025-01-01T00:00:00Z",
  "status": "saved"
}

// ERROR: gửi thất bại
// → /user/queue/errors
{
  "type": "MESSAGE_ERROR",
  "tempId": "client-uuid-123",
  "code": "BLOCKED",
  "message": "Bạn đã chặn người này"
}

// Các error codes:
// BLOCKED          - user bị block hoặc đã block người nhận
// NOT_MEMBER       - không phải thành viên conversation
// RATE_LIMITED     - gửi quá nhanh
// CONTENT_TOO_LONG - tin nhắn quá 4000 ký tự
// CONVERSATION_NOT_FOUND - conversation không tồn tại
// EDIT_EXPIRED     - tin nhắn đã quá 24h, không sửa được
// PERMISSION_DENIED - không có quyền (ví dụ: xóa tin người khác mà không phải admin)
// INTERNAL_ERROR   - lỗi server
```

### Server → Client: Event Types (broadcast)

```json
// Tin nhắn mới (push đến tất cả members trong conversation)
// → /user/queue/messages
{
  "type": "NEW_MESSAGE",
  "data": {
    "id": "msg-uuid",
    "conversationId": "conv-uuid",
    "sender": { "id": "...", "username": "...", "avatarUrl": "..." },
    "type": "text",
    "content": "Hello!",
    "replyTo": null,
    "createdAt": "2025-01-01T00:00:00Z"
  }
}

// Typing indicator
// → /topic/conversation/{id}/typing
{
  "type": "TYPING",
  "data": {
    "conversationId": "conv-uuid",
    "user": { "id": "...", "username": "..." },
    "isTyping": true
  }
}

// Online status change
// → /user/queue/notifications
{
  "type": "PRESENCE",
  "data": {
    "userId": "...",
    "isOnline": true
  }
}

// Message reaction
// → /user/queue/messages
{
  "type": "REACTION",
  "data": {
    "messageId": "...",
    "userId": "...",
    "emoji": "👍",
    "action": "add"
  }
}

// Conversation update (member added/removed, name changed, ...)
// → /user/queue/notifications
{
  "type": "CONVERSATION_UPDATE",
  "data": {
    "conversationId": "...",
    "action": "member_added",
    "actor": { "id": "...", "username": "..." },
    "target": { "id": "...", "username": "..." }
  }
}

// Message edited/deleted
// → /user/queue/messages
{
  "type": "MESSAGE_UPDATE",
  "data": {
    "messageId": "...",
    "action": "edited",
    "newContent": "...",
    "editedAt": "..."
  }
}

// Read receipt
// → /user/queue/messages
{
  "type": "READ_RECEIPT",
  "data": {
    "conversationId": "...",
    "userId": "...",
    "lastReadMessageId": "..."
  }
}
```

### Client-side State Machine cho tin nhắn

```
┌──────────┐   SEND qua socket   ┌──────────┐   Nhận ACK    ┌──────────┐
│ COMPOSING│──────────────────────► SENDING  │───────────────► SENT     │
│          │                      │ (hiện ⏳) │               │ (hiện ✓) │
└──────────┘                      └────┬─────┘               └──────────┘
                                       │
                                       │ Nhận ERROR hoặc
                                       │ Timeout 10 giây
                                       ▼
                                  ┌──────────┐
                                  │ FAILED   │──── Tap "Thử lại"
                                  │ (hiện ✗) │     → quay lại SENDING
                                  └──────────┘
```

Client implementation notes:
- Tạo `tempId` = UUID v4 phía client
- Hiển thị tin nhắn ngay với trạng thái SENDING (optimistic UI)
- Set timeout 10 giây — nếu không nhận ACK hoặc ERROR → chuyển FAILED
- Khi nhận ACK → thay `tempId` bằng `messageId` thật trong local state
- Khi nhận ERROR → hiển thị toast/inline error, cho phép retry
- Khi retry → gửi lại SEND với cùng `tempId` (server check duplicate bằng tempId)

### Reconnect Flow

```
1. Client disconnect (mất mạng, đóng tab)
2. Client tự động reconnect (STOMP reconnect / SockJS fallback)
3. Client gửi SEND /app/chat.sync { lastReceivedTimestamp }
4. Server query: SELECT * FROM messages
   WHERE conversation_id IN (user's conversations)
   AND created_at > lastReceivedTimestamp
   ORDER BY created_at ASC
5. Server push tất cả missed messages về client qua /user/queue/messages
6. Client merge vào local state, xử lý duplicate bằng message ID
7. Client kiểm tra tin nhắn SENDING nào chưa có ACK → retry hoặc chuyển FAILED
```

### Duplicate Prevention (server-side)

```
Khi nhận SEND /app/chat.send:
1. Check Redis: EXISTS "msg:temp:{tempId}"
2. Nếu đã tồn tại → đây là retry, trả ACK lại với messageId đã lưu (idempotent)
3. Nếu chưa → xử lý bình thường, sau khi lưu:
   SET "msg:temp:{tempId}" "{messageId}" EX 300  (TTL 5 phút, đủ cho retry)
```

---

## 6. REST API Endpoints

### Auth
```
POST   /api/auth/register           # Đăng ký bằng email + username + password
POST   /api/auth/login              # Đăng nhập bằng username + password
POST   /api/auth/oauth              # Đăng nhập bằng Firebase ID Token (OAuth2 Google)
POST   /api/auth/link-provider      # Link thêm OAuth provider vào account hiện tại (cần JWT)
PUT    /api/auth/change-password     # Đổi mật khẩu (cần JWT)
POST   /api/auth/refresh            # Refresh token → new JWT
POST   /api/auth/logout             # Invalidate current session
POST   /api/auth/logout-all         # Invalidate tất cả sessions
```

### Users
```
GET    /api/users/me                # Profile hiện tại
PUT    /api/users/me                # Update profile
PUT    /api/users/me/username       # Đổi username (60 ngày/lần)
PUT    /api/users/me/avatar         # Upload avatar (multipart)
DELETE /api/users/me                # Xóa account
GET    /api/users/search?q=         # Tìm user theo username/email
GET    /api/users/{id}              # Xem profile user khác
POST   /api/users/block/{id}        # Block user
DELETE /api/users/block/{id}        # Unblock user
GET    /api/users/blocked           # Danh sách đã block
```

### Conversations
```
GET    /api/conversations                   # List conversations của user
POST   /api/conversations/direct/{userId}   # Tạo/reuse chat 1-1
POST   /api/conversations/group             # Tạo nhóm
GET    /api/conversations/{id}              # Chi tiết conversation
PUT    /api/conversations/{id}              # Update tên/avatar nhóm (group only, admin+)
DELETE /api/conversations/{id}              # "Xóa cuộc trò chuyện" phía mình
                                            # (áp dụng CẢ direct + group)
                                            # Thực chất: set is_hidden=true + cleared_at=NOW
                                            # Sau đó user chỉ thấy tin nhắn SAU thời điểm xóa
DELETE /api/conversations/{id}/destroy      # Xóa nhóm HOÀN TOÀN cho mọi người
                                            # (GROUP + OWNER only)
                                            # Cascade xóa messages, members, files
POST   /api/conversations/{id}/members      # Thêm thành viên (group only, ai cũng được)
DELETE /api/conversations/{id}/members/{uid} # Xóa thành viên (group only, admin+)
POST   /api/conversations/{id}/leave        # Rời nhóm thật (group only)
PUT    /api/conversations/{id}/role         # Đổi role (group only, owner only)
PUT    /api/conversations/{id}/transfer     # Chuyển quyền owner (group only, owner only)
PUT    /api/conversations/{id}/mute         # Mute/unmute (cả direct + group)
```

**3 hành động dễ nhầm — phân biệt rõ ràng:**

```
Endpoint                      │ Ai làm      │ Tác động
──────────────────────────────┼─────────────┼─────────────────────────────────
DELETE /{id}                  │ Bất kỳ      │ Chỉ phía mình:
"Xóa cuộc trò chuyện"         │ thành viên  │ - is_hidden = true
                              │             │ - cleared_at = NOW()
                              │             │ - Conversation biến mất khỏi list
                              │             │ - Có tin mới → hiện lại nhưng
                              │             │   chỉ thấy tin SAU cleared_at
                              │             │ - Data chung không bị đụng
                              │             │ - Áp dụng direct + group
──────────────────────────────┼─────────────┼─────────────────────────────────
POST /{id}/leave              │ Bất kỳ      │ Rời nhóm thật:
"Rời nhóm"                    │ thành viên  │ - left_at = NOW()
                              │ group only  │ - Member khác nhận thông báo
                              │             │ - Owner rời → auto transfer
                              │             │ - Direct KHÔNG có endpoint này
──────────────────────────────┼─────────────┼─────────────────────────────────
DELETE /{id}/destroy          │ Owner       │ Xóa nhóm cho TẤT CẢ:
"Giải tán nhóm"               │ group only  │ - DROP conversation + cascade
                              │             │ - Mọi member mất nhóm ngay
                              │             │ - Direct KHÔNG có endpoint này
```

**Luồng "Xóa cuộc trò chuyện" (DELETE /{id}) — cách hoạt động:**

```
User nhấn nút "Xóa cuộc trò chuyện" trong UI

Backend:
1. UPDATE conversation_members
   SET is_hidden = true, cleared_at = NOW()
   WHERE conversation_id = ? AND user_id = :currentUser

2. Conversation biến mất khỏi GET /api/conversations (vì filter is_hidden)

3. Khi có tin nhắn mới trong conversation này:
   - Server fan-out đến member
   - Detect: member đó có is_hidden = true
   - UPDATE is_hidden = false (unhide)
   - Conversation xuất hiện lại trong danh sách

4. User click vào conversation → gọi GET /messages?convId=...
   - Server query với filter: created_at > cleared_at
   - Chỉ trả về tin nhắn sau thời điểm xóa
   - Tin nhắn cũ vĩnh viễn không thấy được (trừ khi user query không filter — không có cách nào)

5. User có thể xóa lại nhiều lần → cleared_at luôn cập nhật lần cuối
```

### Messages (chỉ read + file upload — gửi/sửa/xóa text qua Socket)
```
GET    /api/conversations/{id}/messages            # Phân trang (cursor-based)
POST   /api/conversations/{id}/messages/file       # Gửi file (multipart) + tempId
POST   /api/conversations/{id}/messages/images     # Gửi ảnh (multipart, max 5) + tempId
PUT    /api/messages/{id}/pin                      # Ghim/bỏ ghim
GET    /api/conversations/{id}/messages/pinned     # Danh sách tin ghim
GET    /api/conversations/{id}/messages/search?q=  # Tìm kiếm tin nhắn
```

Chú ý: gửi tin nhắn text, sửa, xóa, reaction, typing, đánh dấu đã đọc
đều qua WebSocket SEND (xem mục 5). REST chỉ dùng cho:
- Query lịch sử (phân trang)
- Upload file/ảnh (binary lớn không phù hợp với STOMP)
- Ghim tin nhắn (ít dùng, không cần realtime)
- Tìm kiếm (query phức tạp, trả về nhiều kết quả)

---

## 7. Business Logic Chi Tiết

### 7.1 Chat 1-1: Tìm hoặc tạo

```
POST /api/conversations/direct/{targetUserId}

1. Check block: nếu A block B hoặc B block A → 403
2. Query: SELECT c.id FROM conversations c
          JOIN conversation_members cm1 ON c.id = cm1.conversation_id AND cm1.user_id = A
          JOIN conversation_members cm2 ON c.id = cm2.conversation_id AND cm2.user_id = B
          WHERE c.type = 'direct' AND cm1.left_at IS NULL AND cm2.left_at IS NULL
3. Nếu tìm thấy → unhide nếu đang ẩn, return conversation
4. Nếu không → tạo mới conversation + 2 members
```

### 7.2 Block User (2 chiều)

```
POST /api/users/block/{targetId}

1. Insert vào user_blocks (blocker_id, blocked_id)
2. Tìm conversation direct giữa 2 người
3. KHÔNG xóa conversation — chỉ ngăn gửi tin mới
4. Khi A gửi tin cho B (1-1): check block → 403 "Bạn đã chặn người này"
5. Khi B gửi tin cho A (1-1): check block → 403 "Bạn đã bị chặn"
6. Trong nhóm: vẫn nhắn bình thường, nhìn thấy tin nhau
7. Người bị block BIẾT mình bị block (khi cố gửi tin 1-1)
```

### 7.3 Owner rời nhóm — logic chuyển quyền

```
POST /api/conversations/{id}/leave   (khi user là owner)

1. Tìm admin có joined_at sớm nhất (không tính owner):
   SELECT user_id FROM conversation_members
   WHERE conversation_id = ? AND role = 'admin' AND left_at IS NULL AND user_id != owner_id
   ORDER BY joined_at ASC LIMIT 1

2. Nếu không có admin → tìm member có joined_at sớm nhất:
   SELECT user_id FROM conversation_members
   WHERE conversation_id = ? AND role = 'member' AND left_at IS NULL AND user_id != owner_id
   ORDER BY joined_at ASC LIMIT 1

3. Nếu tìm được → chuyển role thành 'owner', gửi system message
4. Nếu không còn ai → xóa conversation + tất cả messages + files
5. Set left_at + leave_reason = 'left' cho owner
```

### 7.4 Gửi tin nhắn TEXT — flow đầy đủ (qua Socket)

```
Client SEND /app/chat.send
{ tempId, conversationId, content, type:"text", replyToId? }

Server-side handler (Spring @MessageMapping):

1. Duplicate check: Redis GET "msg:temp:{tempId}"
   → Nếu đã tồn tại → trả ACK lại với messageId đã lưu (idempotent, skip bước 2-7)

2. Rate limit check (Redis: INCR rate:msg:{userId}, max 10/giây)
   → Nếu vượt → gửi ERROR { tempId, code: "RATE_LIMITED" } về /user/queue/errors
   → RETURN (không xử lý tiếp)

3. Validate:
   a. User là active member của conversation? (left_at IS NULL)
      → Nếu không → ERROR { tempId, code: "NOT_MEMBER" }
   b. Content <= 4000 ký tự?
      → Nếu quá → ERROR { tempId, code: "CONTENT_TOO_LONG" }
   c. Conversation tồn tại?
      → Nếu không → ERROR { tempId, code: "CONVERSATION_NOT_FOUND" }

4. Block check (chỉ với direct chat):
   → Query user_blocks → nếu bị block → ERROR { tempId, code: "BLOCKED" }

5. Lưu message vào PostgreSQL (INSERT)
   → Lấy messageId + createdAt thật từ DB

6. Lưu tempId mapping vào Redis:
   SET "msg:temp:{tempId}" "{messageId}" EX 300

7. Update conversations.updated_at (để sort conversation list)

8. Gửi ACK về cho sender:
   → /user/queue/acks: { tempId, messageId, createdAt, status: "saved" }

9. Fan-out cho các members khác trong conversation:
   a. Với mỗi member (trừ sender):
      - Nếu member online (Redis: EXISTS user:online:{memberId})
        → push NEW_MESSAGE qua /user/queue/messages
      - Nếu member KHÔNG đang mute conversation
        → INCR unread:{memberId}:{conversationId}
      - Nếu member đã ẩn conversation (is_hidden = true)
        → UPDATE is_hidden = false
```

### 7.5 Sửa tin nhắn (qua Socket, trong 24h)

```
Client SEND /app/chat.edit
{ messageId, content }

Server-side handler:

1. Check: sender_id = current user?
   → Nếu không → ERROR { code: "PERMISSION_DENIED" }
2. Check: created_at > NOW() - 24 hours?
   → Nếu quá → ERROR { code: "EDIT_EXPIRED" }
3. Check: content <= 4000 ký tự?
4. Update DB: content, is_edited = true, edited_at = NOW()
5. Gửi ACK về sender: { messageId, status: "edited" }
6. Push MESSAGE_UPDATE { messageId, action: "edited", newContent, editedAt }
   → đến tất cả members online trong conversation
```

### 7.6 Xóa tin nhắn (qua Socket, hard delete)

```
Client SEND /app/chat.delete
{ messageId }

Server-side handler:

1. Check: sender_id = current user? HOẶC user là admin trong conversation?
   → Nếu không → ERROR { code: "PERMISSION_DENIED" }
2. Nếu message có file/image → đánh dấu file_records.is_deleted = true
3. Các tin reply_to message này → UPDATE reply_to_id = NULL
   (client hiển thị "Tin nhắn không tồn tại")
4. DELETE message record từ DB
5. Gửi ACK về sender: { messageId, status: "deleted" }
6. Push MESSAGE_UPDATE { messageId, action: "deleted" }
   → đến tất cả members online trong conversation
```

### 7.7 Upload file & ảnh (qua REST, rồi push qua Socket)

```
POST /api/conversations/{id}/messages/images   (multipart, max 5 ảnh)
Header: Authorization: Bearer {JWT}
Form data: files[], tempId

1. Rate limit check (10 file/phút)
2. Validate: mỗi file <= 20MB, mime type là image/*
3. Validate: user là active member, block check (direct only)
4. Với mỗi ảnh:
   a. Resize → full (max 1280px) + thumbnail (300px) bằng Thumbnailator
   b. Lưu vào /uploads/{yyyy/MM/dd}/{uuid}.jpg  và  {uuid}_thumb.jpg
   c. Tạo file_record (expires_at = NOW() + 30 ngày)
5. Tạo message(s) trong DB, type = 'image', metadata = JSON chứa url + thumbnail
6. HTTP Response 200: { tempId, messageId, createdAt }
   (client thay tempId bằng real id — giống ACK nhưng qua HTTP)
7. Push NEW_MESSAGE qua WebSocket đến tất cả members online

POST /api/conversations/{id}/messages/file     (multipart, 1 file)
- Tương tự nhưng không resize, type = 'file'
```

### 7.8 File Cleanup Job (chạy daily)

```
@Scheduled(cron = "0 0 3 * * *")   // 3 giờ sáng mỗi ngày

1. SELECT * FROM file_records WHERE expires_at < NOW() AND is_deleted = FALSE
2. Xóa file vật lý khỏi disk
3. Update is_deleted = true
4. Update message.metadata → set url = null (hoặc giữ metadata nhưng file 404)
```

### 7.9 Query tin nhắn — phân trang có filter cleared_at

```
GET /api/conversations/{id}/messages?cursor={lastMessageId}&limit=30

1. Lấy conversation_members record của current user cho conversation này
2. Xác định "mốc thời gian bắt đầu hiển thị":
   visible_from = GREATEST(
       cm.joined_at,                    -- không thấy tin trước khi join
       COALESCE(cm.cleared_at, '1970-01-01')  -- không thấy tin trước khi "xóa chat"
   )

3. Xác định "mốc thời gian kết thúc hiển thị" (cho user bị kick):
   visible_until = COALESCE(cm.left_at, '9999-12-31')  -- nếu đã rời, chỉ thấy đến lúc rời

4. Query:
   SELECT m.id, m.content, m.type, m.metadata, m.sender_id,
          m.reply_to_id, m.is_pinned, m.is_edited, m.created_at,
          u.username, u.avatar_url, u.full_name
   FROM messages m
   JOIN users u ON m.sender_id = u.id
   WHERE m.conversation_id = :convId
     AND m.created_at > :visible_from          -- ← cleared_at filter
     AND m.created_at < :visible_until         -- ← left_at filter
     AND (:cursor IS NULL OR m.created_at < (
         SELECT created_at FROM messages WHERE id = :cursor
     ))
   ORDER BY m.created_at DESC
   LIMIT :limit

5. Return: { messages: [...], hasMore: boolean, nextCursor: lastMessage.id }
```

**Giải thích logic visibility:**

```
Tình huống 1: User bình thường, chưa bao giờ xóa chat
  joined_at  = 2025-01-01
  cleared_at = NULL
  left_at    = NULL
  → visible_from = 2025-01-01, visible_until = ∞
  → Thấy tất cả tin từ lúc join

Tình huống 2: User đã "xóa chat" ngày 15/6, sau đó có tin mới
  joined_at  = 2025-01-01
  cleared_at = 2025-06-15 10:00
  left_at    = NULL
  → visible_from = 2025-06-15 10:00 (cleared_at > joined_at)
  → Chỉ thấy tin sau 10:00 ngày 15/6

Tình huống 3: User bị kick ngày 20/6
  joined_at  = 2025-01-01
  cleared_at = NULL
  left_at    = 2025-06-20 15:00
  → visible_from = 2025-01-01, visible_until = 2025-06-20 15:00
  → Thấy tin từ lúc join đến lúc bị kick

Tình huống 4: User xóa chat ngày 15/6, bị kick ngày 20/6
  joined_at  = 2025-01-01
  cleared_at = 2025-06-15 10:00
  left_at    = 2025-06-20 15:00
  → visible_from = 2025-06-15 10:00, visible_until = 2025-06-20 15:00
  → Chỉ thấy tin trong khoảng 15/6 → 20/6
```

### 7.10 Tìm kiếm tin nhắn — cũng cần filter cleared_at

```
GET /api/conversations/{id}/messages/search?q=keyword

1. Lấy visible_from và visible_until (cùng logic với 7.9)
2. Query:
   SELECT id, content, sender_id, created_at
   FROM messages
   WHERE conversation_id = ?
     AND created_at > :visible_from
     AND created_at < :visible_until
     AND search_vector @@ plainto_tsquery('simple', ?)
   ORDER BY created_at DESC
   LIMIT 20 OFFSET ?
```

### 7.11 Xóa account

```
DELETE /api/users/me

1. Update users: username = 'deleted_user_{id}', full_name = 'Deleted User',
   status = 'deleted', email = 'deleted_{id}@deleted', avatar_url = NULL,
   password_hash = NULL
2. Xóa tất cả user_auth_providers
3. Xóa tất cả refresh tokens trong Redis
4. Blacklist JWT hiện tại
5. Rời tất cả conversations (trigger logic chuyển quyền nếu là owner)
6. Messages giữ nguyên nhưng sender_id vẫn trỏ đến user (hiển thị "Deleted User")
7. Xóa avatar file
8. Disconnect WebSocket
```

---

## 8. Rate Limiting

| Action | Limit | Window | Implement |
|--------|-------|--------|-----------|
| Login failed | 5 lần | 15 phút | Per IP |
| Gửi tin nhắn | 10 tin | 1 giây | Per user |
| Upload file | 10 file | 1 phút | Per user |
| API general | 100 requests | 1 phút | Per user |
| Typing indicator | 1 event | 2 giây | Per user per conversation |

Implement bằng Redis INCR + EXPIRE. Spring Boot: dùng Filter hoặc HandlerInterceptor.

---

## 9. Concurrency & Performance

Với quy mô hiện tại (1,000 users, 4,000 tin/ngày), kiến trúc đơn giản đủ dùng.
Nhưng để chuẩn bị cho peak load và scale dần, phần này liệt kê các cấu hình,
pattern code, và kỹ thuật cần áp dụng.

### 9.1 Ước lượng tải và các ngưỡng cần lưu ý

```
┌─────────────────────┬──────────────┬──────────────┬─────────────────┐
│ Kịch bản            │ Tin/giây     │ Fan-out/giây │ Đánh giá        │
├─────────────────────┼──────────────┼──────────────┼─────────────────┤
│ Trung bình ngày     │ 0.05         │ ~2.5         │ Cực nhẹ         │
│ (4k tin/24h)        │              │              │                 │
├─────────────────────┼──────────────┼──────────────┼─────────────────┤
│ Peak giờ cao điểm   │ 5-10         │ 250-500      │ Nhẹ             │
│ (tập trung 1-2h)    │              │              │                 │
├─────────────────────┼──────────────┼──────────────┼─────────────────┤
│ Burst 500 nhóm      │ 500          │ 25,000       │ Bottleneck nếu  │
│ cùng gửi 1 giây     │              │              │ chưa tối ưu     │
├─────────────────────┼──────────────┼──────────────┼─────────────────┤
│ Burst 1000 nhóm     │ 1000         │ 50,000       │ Bottleneck rõ,  │
│ cùng gửi 1 giây     │              │              │ cần tối ưu      │
└─────────────────────┴──────────────┴──────────────┴─────────────────┘

Giả định trung bình 50 member/nhóm cho cột fan-out.
```

### 9.2 Các bottleneck và giải pháp

```
┌──────────────────────────────┬─────────────────────────────────────┐
│ Bottleneck                   │ Giải pháp V1                        │
├──────────────────────────────┼─────────────────────────────────────┤
│ DB connection pool cạn       │ Tăng HikariCP từ 10 → 30            │
│ (default chỉ 10)             │                                     │
├──────────────────────────────┼─────────────────────────────────────┤
│ Redis connection bottleneck  │ Tăng Lettuce pool max-active → 20   │
├──────────────────────────────┼─────────────────────────────────────┤
│ Fan-out đồng bộ blocking     │ @Async + TaskExecutor thread pool   │
│ sender                       │ Sender nhận ACK ngay, fan-out chạy  │
│                              │ background                          │
├──────────────────────────────┼─────────────────────────────────────┤
│ N Redis round-trip cho       │ Redis pipeline/batch INCR           │
│ N member khi INCR unread     │ Giảm N round-trip → 1 round-trip    │
├──────────────────────────────┼─────────────────────────────────────┤
│ Hot row: conversations.      │ Bỏ UPDATE, dùng subquery hoặc       │
│ updated_at bị UPDATE liên    │ Redis cache cho last_message_time   │
│ tục                          │                                     │
├──────────────────────────────┼─────────────────────────────────────┤
│ WebSocket inbound thread     │ Tăng messageBroker thread pool      │
│ pool (default nhỏ)           │                                     │
└──────────────────────────────┴─────────────────────────────────────┘
```

### 9.3 Cấu hình application.yml

```yaml
# application.yml

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/chatapp
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      # ═════ Connection pool ═════
      maximum-pool-size: 30          # Mặc định 10 → tăng lên 30
      minimum-idle: 10                # Giữ sẵn 10 connection idle
      connection-timeout: 5000        # 5s timeout lấy connection
      idle-timeout: 300000            # 5 phút idle thì close
      max-lifetime: 1800000           # 30 phút renew connection
      leak-detection-threshold: 60000 # Cảnh báo nếu connection leak > 60s

  jpa:
    hibernate:
      ddl-auto: validate              # Production: validate, không dùng update
    properties:
      hibernate:
        jdbc:
          batch_size: 50              # Batch INSERT (dùng cho fan-out)
        order_inserts: true
        order_updates: true

  redis:
    host: localhost
    port: 6379
    lettuce:
      pool:
        max-active: 20                # Mặc định 8 → tăng 20
        max-idle: 10
        min-idle: 3
        max-wait: 2000ms

  task:
    execution:
      pool:
        # ═════ Thread pool cho @Async (fan-out) ═════
        core-size: 10
        max-size: 50
        queue-capacity: 500
        keep-alive: 60s
        thread-name-prefix: async-fanout-

server:
  tomcat:
    threads:
      max: 200                        # Mặc định 200, giữ nguyên
      min-spare: 20
    max-connections: 8192
    accept-count: 100

  # ═════ WebSocket buffer ═════
  servlet:
    context-path: /

# Spring WebSocket config trong Java (xem mục 9.4)
```

### 9.4 Spring WebSocket STOMP configuration

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();  // fallback cho browser cũ
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Simple broker in-memory (đủ cho 1 server)
        registry.enableSimpleBroker("/topic", "/queue", "/user")
                .setTaskScheduler(heartbeatScheduler())
                .setHeartbeatValue(new long[] { 10000, 10000 });

        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // ═════ Tăng thread pool xử lý SEND từ client ═════
        registration.taskExecutor()
                .corePoolSize(20)
                .maxPoolSize(50)
                .queueCapacity(500);

        // JWT auth interceptor
        registration.interceptors(stompAuthInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        // ═════ Tăng thread pool push xuống client ═════
        registration.taskExecutor()
                .corePoolSize(20)
                .maxPoolSize(50)
                .queueCapacity(500);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024)          // 64KB max/frame
                    .setSendBufferSizeLimit(512 * 1024)      // 512KB buffer/session
                    .setSendTimeLimit(20000);                 // 20s send timeout
    }

    @Bean
    public TaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }
}
```

### 9.5 Async fan-out pattern

Pattern này **quan trọng nhất** — giúp sender nhận ACK nhanh và tách fan-out ra
background, tránh blocking thread chính.

```java
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final ConversationMemberRepository memberRepository;
    private final MessageFanoutService fanoutService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redis;

    /**
     * Xử lý khi client SEND /app/chat.send
     * Luồng:
     *   1. Validate + INSERT DB (đồng bộ - nhanh, ~5-10ms)
     *   2. Gửi ACK cho sender (đồng bộ - ~1ms)
     *   3. Gọi fanout ASYNC → return ngay
     *   4. Fan-out chạy background ở thread pool khác
     */
    @Transactional
    public void handleSendMessage(String userId, SendMessageRequest req) {
        // ═══ Bước 1: Dedup check ═══
        String dedupKey = "msg:temp:" + req.getTempId();
        String existingMessageId = redis.opsForValue().get(dedupKey);
        if (existingMessageId != null) {
            // Idempotent: retry → trả ACK cũ
            sendAck(userId, req.getTempId(), existingMessageId);
            return;
        }

        // ═══ Bước 2: Validate ═══
        try {
            validateSender(userId, req);
            validateContent(req);
            validateBlock(userId, req);
        } catch (ChatException e) {
            sendError(userId, req.getTempId(), e.getCode(), e.getMessage());
            return;
        }

        // ═══ Bước 3: INSERT message (đồng bộ) ═══
        Message msg = new Message();
        msg.setConversationId(req.getConversationId());
        msg.setSenderId(userId);
        msg.setType("text");
        msg.setContent(req.getContent());
        msg.setReplyToId(req.getReplyToId());
        Message saved = messageRepository.save(msg);

        // ═══ Bước 4: Set dedup key (TTL 5 phút) ═══
        redis.opsForValue().set(dedupKey, saved.getId().toString(),
                                 Duration.ofMinutes(5));

        // ═══ Bước 5: Gửi ACK cho sender NGAY ═══
        sendAck(userId, req.getTempId(), saved.getId().toString());

        // ═══ Bước 6: Trigger fan-out ASYNC (return ngay, không chờ) ═══
        fanoutService.fanoutMessageAsync(saved);

        // Sender đã nhận ACK, transaction commit.
        // Fan-out chạy trong background thread, không ảnh hưởng latency của sender.
    }

    private void sendAck(String userId, String tempId, String messageId) {
        Map<String, Object> ack = Map.of(
            "type", "MESSAGE_ACK",
            "tempId", tempId,
            "messageId", messageId,
            "createdAt", Instant.now().toString(),
            "status", "saved"
        );
        messagingTemplate.convertAndSendToUser(userId, "/queue/acks", ack);
    }

    private void sendError(String userId, String tempId, String code, String msg) {
        Map<String, Object> error = Map.of(
            "type", "MESSAGE_ERROR",
            "tempId", tempId,
            "code", code,
            "message", msg
        );
        messagingTemplate.convertAndSendToUser(userId, "/queue/errors", error);
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class MessageFanoutService {

    private final ConversationMemberRepository memberRepo;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redis;
    private final PresenceService presenceService;

    /**
     * Fan-out message đến tất cả members — chạy ASYNC, không block sender.
     *
     * @Async dùng thread pool "async-fanout-*" đã config ở application.yml
     */
    @Async("taskExecutor")
    public void fanoutMessageAsync(Message message) {
        UUID convId = message.getConversationId();
        UUID senderId = message.getSenderId();

        // Lấy danh sách active members (trừ sender)
        List<ConversationMember> members = memberRepo
            .findActiveMembersExceptSender(convId, senderId);

        if (members.isEmpty()) return;

        // ═══ Build payload 1 lần, reuse cho tất cả members ═══
        Map<String, Object> payload = buildMessagePayload(message);

        // ═══ Batch Redis operations bằng pipeline ═══
        batchIncrUnread(members, convId);

        // ═══ Push socket cho members online ═══
        // Không song song hóa ở đây vì SimpMessagingTemplate đã dùng thread pool
        // outbound channel (20-50 threads). Nếu loop ở đây, mỗi convertAndSendToUser
        // sẽ enqueue vào outbound channel → thread pool xử lý song song tự động.
        for (ConversationMember member : members) {
            String memberId = member.getUserId().toString();

            // Check online qua Redis (rất nhanh ~0.1ms)
            if (presenceService.isOnline(memberId)) {
                messagingTemplate.convertAndSendToUser(
                    memberId, "/queue/messages", payload
                );
            }

            // Auto unhide nếu member đã "xóa cuộc trò chuyện"
            if (Boolean.TRUE.equals(member.getIsHidden())) {
                memberRepo.unhide(member.getId());
            }
        }
    }

    /**
     * Batch INCR unread cho tất cả members bằng Redis pipeline.
     * Giảm từ N round-trip xuống 1 round-trip.
     */
    private void batchIncrUnread(List<ConversationMember> members, UUID convId) {
        redis.executePipelined((RedisCallback<Object>) connection -> {
            for (ConversationMember member : members) {
                // Bỏ qua member đang mute
                if (member.getMuteUntil() != null
                    && member.getMuteUntil().isAfter(Instant.now())) {
                    continue;
                }

                String key = "unread:" + member.getUserId() + ":" + convId;
                connection.stringCommands().incr(key.getBytes());
            }
            return null;
        });
    }

    private Map<String, Object> buildMessagePayload(Message msg) {
        return Map.of(
            "type", "NEW_MESSAGE",
            "data", Map.of(
                "id", msg.getId().toString(),
                "conversationId", msg.getConversationId().toString(),
                "senderId", msg.getSenderId().toString(),
                "type", msg.getType(),
                "content", msg.getContent(),
                "replyToId", msg.getReplyToId(),
                "createdAt", msg.getCreatedAt().toString()
            )
        );
    }
}
```

### 9.6 Tối ưu DB: bỏ UPDATE hot row

**Vấn đề**: Nếu mỗi message INSERT kèm `UPDATE conversations SET updated_at = NOW()
WHERE id = ?`, thì 1 nhóm đang chat sôi nổi sẽ có nhiều UPDATE liên tục trên cùng
1 row → row-level lock contention, waiting transactions.

**Giải pháp V1**: Dùng subquery khi list conversations, không UPDATE hot row.

```sql
-- List conversations của user, sort theo tin cuối cùng
SELECT
    c.id,
    c.type,
    c.name,
    c.avatar_url,
    (SELECT MAX(created_at)
     FROM messages
     WHERE conversation_id = c.id
       AND created_at > COALESCE(cm.cleared_at, '1970-01-01'::timestamptz)
    ) AS last_message_time,
    (SELECT content
     FROM messages
     WHERE conversation_id = c.id
       AND created_at > COALESCE(cm.cleared_at, '1970-01-01'::timestamptz)
     ORDER BY created_at DESC
     LIMIT 1
    ) AS last_message_preview
FROM conversations c
JOIN conversation_members cm ON c.id = cm.conversation_id
WHERE cm.user_id = :userId
  AND cm.left_at IS NULL
  AND cm.is_hidden = false
ORDER BY last_message_time DESC NULLS LAST
LIMIT 50;
```

Với index đã có `idx_messages_conv_time ON (conversation_id, created_at DESC)`,
subquery `MAX(created_at)` chỉ cần 1 index lookup — cực nhanh (< 1ms).

**Giải pháp V2 (nếu cần)**: Redis cache.

```java
// Khi có message mới:
redis.opsForZSet().add(
    "user:convs:" + userId,
    convId.toString(),
    message.getCreatedAt().toEpochMilli()  // score = timestamp
);

// List conversations:
Set<ZSetOperations.TypedTuple<String>> convs = redis.opsForZSet()
    .reverseRangeWithScores("user:convs:" + userId, 0, 49);
// Kết quả đã sort theo timestamp DESC, lấy 50 conversations gần nhất
```

### 9.7 Batch operations với JDBC

Khi cần fan-out INSERT (ví dụ: audit log cho nhiều action), dùng JDBC batch:

```java
@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbc;

    public void batchInsert(List<AuditLog> logs) {
        String sql = """
            INSERT INTO audit_logs (actor_id, action, target_type, target_id, metadata)
            VALUES (?, ?, ?, ?, ?::jsonb)
        """;

        jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                AuditLog log = logs.get(i);
                ps.setObject(1, log.getActorId());
                ps.setString(2, log.getAction());
                ps.setString(3, log.getTargetType());
                ps.setObject(4, log.getTargetId());
                ps.setString(5, log.getMetadataJson());
            }

            @Override
            public int getBatchSize() {
                return logs.size();
            }
        });
    }
}
```

### 9.8 Expected latency benchmark

Dựa trên kiến trúc đã tối ưu V1, các con số kỳ vọng:

```
┌────────────────────────────┬──────────┬────────────────────────────┐
│ Operation                  │ P50      │ P99                        │
├────────────────────────────┼──────────┼────────────────────────────┤
│ Gửi tin text (ACK)         │ 15-30ms  │ 50-80ms                    │
│ Fan-out đến 50 members     │ 50-100ms │ 200-400ms (async, không    │
│ (background, không block)  │          │ ảnh hưởng sender)          │
│ List 50 conversations      │ 20-40ms  │ 80-120ms                   │
│ Load 30 messages gần nhất  │ 10-20ms  │ 40-60ms                    │
│ Search full-text           │ 30-80ms  │ 150-300ms                  │
│ Upload ảnh 5MB + resize    │ 300-500ms│ 1-2s                       │
│ WebSocket connect + auth   │ 50-100ms │ 200ms                      │
└────────────────────────────┴──────────┴────────────────────────────┘

Benchmark này giả định: 1 VPS 4 vCPU / 8GB RAM, Postgres + Redis cùng host,
fresh data không bị swap, không có network issue.
```

### 9.9 Monitoring — các metric cần theo dõi

Với quy mô nhỏ, chỉ cần các metric tối thiểu để phát hiện sớm bottleneck:

```
┌─────────────────────────────────┬──────────────────────┬────────────┐
│ Metric                          │ Cảnh báo khi         │ Giải pháp  │
├─────────────────────────────────┼──────────────────────┼────────────┤
│ HikariCP active connections     │ > 80% pool size      │ Tăng pool  │
│ HikariCP pending threads        │ > 0 liên tục         │ Tăng pool  │
│ HikariCP connection acquire     │ P99 > 100ms          │ Tăng pool  │
│   time                          │                      │            │
├─────────────────────────────────┼──────────────────────┼────────────┤
│ Redis commands/sec              │ > 5000/sec           │ Pipeline   │
│ Redis memory usage              │ > 80% max            │ Check leak │
├─────────────────────────────────┼──────────────────────┼────────────┤
│ @Async queue size               │ > 300 liên tục       │ Tăng pool  │
│ @Async rejected tasks           │ > 0                  │ Tăng queue │
├─────────────────────────────────┼──────────────────────┼────────────┤
│ WebSocket active sessions       │ Theo dõi trend       │ Info only  │
│ Message send latency (P99)      │ > 200ms              │ Profile    │
│ Fan-out completion time (P99)   │ > 1s                 │ Profile    │
├─────────────────────────────────┼──────────────────────┼────────────┤
│ Postgres slow query log         │ Query > 500ms        │ Check index│
│ Postgres deadlocks              │ > 0                  │ Check code │
│ Postgres active connections     │ > 80% max            │ Tăng max   │
└─────────────────────────────────┴──────────────────────┴────────────┘
```

Tools đề xuất V1 (miễn phí):
- **Spring Boot Actuator** + **Micrometer** cho metric
- **Prometheus** + **Grafana** cho dashboard
- **Sentry** (free tier) cho error tracking
- **pg_stat_statements** cho slow query trong Postgres

### 9.10 Roadmap scale lên V2 (khi cần)

Dấu hiệu cần lên V2:

```
■ Concurrent users > 3,000
■ Tin/ngày > 50,000
■ P99 send latency > 500ms liên tục
■ DB CPU > 70% trung bình
■ Single server không đủ (CPU/RAM)
```

Thứ tự nâng cấp V2 (ưu tiên giảm dần):

```
1. Read replica cho Postgres
   → Query đọc (list convs, load messages, search) route sang replica
   → Write vẫn đi primary

2. External message broker (RabbitMQ hoặc Redis Streams)
   → Cho phép scale Spring Boot nhiều instance
   → Fan-out giữa các instance qua broker

3. Redis Cluster
   → Nếu Redis command > 20k/sec
   → Shard theo conversation_id

4. Partition messages table theo tháng
   → Khi bảng > 50 triệu record
   → PostgreSQL native partitioning

5. CDN + Object Storage cho file/ảnh
   → Thay vì serve từ Spring
   → Giảm load CPU + bandwidth

6. Dedicated socket server (Node.js với uWebSockets)
   → Nếu concurrent socket > 10,000
   → Tách khỏi Spring Boot business logic
```

Mỗi bước làm tăng độ phức tạp vận hành đáng kể — chỉ làm khi thực sự cần.

---

## 10. Phân công & Lộ trình triển khai V1 (Team 3 người)

### Team structure

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│  BE (Backend)         FE (Frontend)        FS (Fullstack)       │
│  ─────────────        ────────────         ───────────────      │
│  Spring Boot          React/Vue            Cầu nối BE ↔ FE     │
│  PostgreSQL           UI components        WebSocket handler    │
│  Redis                State management     Integration test     │
│  REST API             Socket client        Deploy & DevOps      │
│  Business logic       Routing              Xử lý các phần      │
│                                            cần hiểu cả 2 đầu   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Nguyên tắc phân công

FS (Fullstack) đóng vai trò **chìa khóa** trong team này. Lý do: phần khó nhất
của app chat không phải BE hay FE riêng lẻ, mà là **vùng giao nhau** — WebSocket
handshake, tempId lifecycle, optimistic UI sync với server state, reconnect flow.
Đây là những thứ mà BE thuần hoặc FE thuần thường hiểu sai nếu làm một mình.

**Quy tắc vàng**: BE và FE KHÔNG nên code song song mà chưa thống nhất contract.
Trước mỗi phase, cả team ngồi lại 30 phút chốt:
- Request/response format (JSON structure)
- Error codes và cách client xử lý
- Socket event names và payloads
- Viết thành file contract (Swagger cho REST, markdown cho Socket events)

FS chịu trách nhiệm viết và maintain file contract này.

### Phân chia cụ thể theo module

```
┌─────────────────────────┬──────────────┬──────────────┬──────────────┐
│ Module                  │ BE           │ FE           │ FS           │
├─────────────────────────┼──────────────┼──────────────┼──────────────┤
│ Auth (register/login)   │ API + JWT    │ Login form   │ OAuth flow   │
│                         │ + password   │ + token      │ + Firebase   │
│                         │   hash       │   storage    │   integration│
├─────────────────────────┼──────────────┼──────────────┼──────────────┤
│ User profile            │ CRUD API     │ Profile UI   │              │
│                         │ + avatar     │ + avatar     │              │
│                         │   upload     │   upload     │              │
├─────────────────────────┼──────────────┼──────────────┼──────────────┤
│ Conversation CRUD       │ REST API     │ Chat list UI │              │
│ (tạo, list, members)    │ + business   │ + group      │              │
│                         │   logic      │   management │              │
├─────────────────────────┼──────────────┼──────────────┼──────────────┤
│ Gửi tin nhắn qua Socket │              │              │ ★ TOÀN BỘ   │
│ (STOMP handler, ACK,    │              │              │ BE handler + │
│  ERROR, tempId, fanout) │              │              │ FE client    │
├─────────────────────────┼──────────────┼──────────────┼──────────────┤
│ Hiển thị tin nhắn       │ Query API    │ Message UI   │              │
│ (phân trang, scroll)    │ (cursor)     │ + infinite   │              │
│                         │              │   scroll     │              │
├─────────────────────────┼──────────────┼──────────────┼──────────────┤
│ File/ảnh upload         │ Multipart    │ Upload UI    │ Tích hợp     │
│                         │ + resize     │ + preview    │ upload xong  │
│                         │ + storage    │ + progress   │ → push socket│
├─────────────────────────┼──────────────┼──────────────┼──────────────┤
│ Typing, presence,       │              │              │ ★ TOÀN BỘ   │
│ read receipts           │              │              │ (realtime)   │
├─────────────────────────┼──────────────┼──────────────┼──────────────┤
│ Reactions               │ DB + API     │ Emoji picker │ Socket event │
│                         │              │ + animation  │              │
├─────────────────────────┼──────────────┼──────────────┼──────────────┤
│ Block, mute             │ Business     │ UI controls  │              │
│                         │ logic + API  │              │              │
├─────────────────────────┼──────────────┼──────────────┼──────────────┤
│ Search, pin             │ Full-text    │ Search UI    │              │
│                         │ query        │ + pin UI     │              │
├─────────────────────────┼──────────────┼──────────────┼──────────────┤
│ DevOps, deploy          │              │              │ ★ CHÍNH      │
│ CI/CD, monitoring       │              │              │              │
└─────────────────────────┴──────────────┴──────────────┴──────────────┘
```

★ = FS là owner chính của module đó

### Lộ trình theo tuần (3 người song song)

```
═══════════════════════════════════════════════════════════════════════
TUẦN 1: Foundation — ai cũng setup được, bắt đầu song song
═══════════════════════════════════════════════════════════════════════

BE                      FE                       FS
────────────────        ────────────────         ────────────────
□ Setup Spring Boot     □ Setup React/Vue        □ Setup Git repo,
  + PostgreSQL            project                  branching strategy
  + Redis               □ Setup routing           (main/dev/feature)
□ DB migration          □ Design system:         □ Setup Docker Compose
  (Flyway)                color, font,             (Postgres + Redis
□ User entity             component library        + Spring Boot)
  + repository          □ Login/Register         □ Firebase project
                          page UI (static,          setup
                          chưa call API)         □ Viết API contract
                                                   v1 (REST + Socket)

Deliverable tuần 1:
  ✓ BE chạy được, có DB schema
  ✓ FE chạy được, có login page (static)
  ✓ Docker Compose 1 lệnh start tất cả
  ✓ API contract v1 đã review cả team

═══════════════════════════════════════════════════════════════════════
TUẦN 2: Auth — kết nối BE ↔ FE lần đầu tiên
═══════════════════════════════════════════════════════════════════════

BE                      FE                       FS
────────────────        ────────────────         ────────────────
□ POST /auth/register   □ Register form          □ Firebase Auth
□ POST /auth/login        validation               integration
  (username/password)   □ Call register/          □ POST /auth/oauth
□ JWT generation          login API              □ Auto-link by email
  + JwtAuthFilter       □ Token storage            logic
□ POST /auth/refresh      (localStorage)         □ POST /auth/
□ Rate limiting         □ Auth context/             link-provider
  middleware              interceptor            □ Integration test
  (login attempts)        (attach JWT              auth flow end-to-end
                          to requests)

Deliverable tuần 2:
  ✓ User đăng ký + đăng nhập được (cả password + Google)
  ✓ JWT flow hoàn chỉnh (access + refresh)
  ✓ FE gọi API có auth header

═══════════════════════════════════════════════════════════════════════
TUẦN 3: Chat core — REST phần conversation + Socket lần đầu
═══════════════════════════════════════════════════════════════════════

BE                      FE                       FS
────────────────        ────────────────         ────────────────
□ Conversation          □ Conversation           □ Spring WebSocket
  REST API:               list page                + STOMP config
  create direct,        □ Create group           □ StompAuthInterceptor
  create group,           modal                    (JWT verify)
  list, detail          □ Group member           □ WebSocket event
□ ConversationMembers     management UI            listener (connect/
  API: add, remove,     □ STOMP client             disconnect)
  leave, role change      setup (SockJS          □ PresenceService
□ Owner transfer          + @stomp/stompjs)        (online/offline
  logic                 □ Subscribe                via Redis)
                          /user/queue/*          □ Test: FE connect
                                                   socket + subscribe

Deliverable tuần 3:
  ✓ Tạo conversation, quản lý nhóm qua REST
  ✓ WebSocket connected + authenticated
  ✓ FE subscribe thành công, nhận được test event

═══════════════════════════════════════════════════════════════════════
TUẦN 4-5: Messaging — phần QUAN TRỌNG NHẤT, FS lead
═══════════════════════════════════════════════════════════════════════

BE                      FE                       FS (lead)
────────────────        ────────────────         ────────────────
□ Message entity        □ Chat window UI         □ ★ ChatMessageHandler
  + repository          □ Message bubble           (@MessageMapping
□ GET messages            component                chat.send)
  API (cursor-based     □ Message input          □ ★ ACK/ERROR routing
  pagination)             component                (/user/queue/acks
□ Full-text search      □ Infinite scroll          /user/queue/errors)
  (tsvector trigger)      (load older            □ ★ tempId lifecycle
                          messages)                + Redis dedup
                        □ ★ Client state         □ ★ MessageFanoutService
                          machine:                 (push đến members)
                          SENDING → SENT         □ ★ Sửa/xóa qua socket
                          → FAILED                 (chat.edit, chat.delete)
                        □ ★ Retry on             □ ★ Typing indicator
                          failure                  (cả BE handler
                        □ Reply UI                 + FE hiển thị)
                          (quote bubble)         □ ★ Reconnect + sync
                                                   missed messages

Deliverable tuần 4-5:
  ✓ Gửi/nhận tin nhắn text realtime qua socket
  ✓ ACK/ERROR flow hoạt động, retry khi fail
  ✓ Sửa/xóa tin nhắn, typing indicator
  ✓ Đọc lịch sử tin nhắn (phân trang)
  ✓ Reconnect không mất tin

═══════════════════════════════════════════════════════════════════════
TUẦN 6: File, ảnh, media
═══════════════════════════════════════════════════════════════════════

BE                      FE                       FS
────────────────        ────────────────         ────────────────
□ Multipart upload      □ File picker            □ Tích hợp:
  endpoint                + drag & drop            upload REST xong
□ Image resize          □ Upload progress          → tạo message
  (Thumbnailator)         bar                      → push qua socket
□ File storage          □ Image preview          □ File expiry
  (local disk)            (thumbnail)              cleanup job
□ file_records table    □ File download            (@Scheduled)
  + expiry tracking       link
□ Validation            □ "Image not found"
  (20MB, mime type)       fallback

Deliverable tuần 6:
  ✓ Upload/gửi ảnh + file trong chat
  ✓ Thumbnail tự động cho ảnh
  ✓ File hết hạn sau 30 ngày

═══════════════════════════════════════════════════════════════════════
TUẦN 7: Features bổ sung
═══════════════════════════════════════════════════════════════════════

BE                      FE                       FS
────────────────        ────────────────         ────────────────
□ Reaction DB           □ Emoji picker           □ Reaction socket
  + query               □ Reaction                 events
□ Pin message API         animation              □ ★ Read receipt
□ Block/unblock API     □ Pin message UI           (chat.read +
□ System messages       □ Block UI                 broadcast +
  (member added/          (button, confirm)        unread count
  removed, etc.)        □ Mute toggle              Redis)
□ Mute logic            □ User search            □ Badge count
  (mute_until)            + start chat             sync
□ User search API       □ System message
                          rendering

Deliverable tuần 7:
  ✓ Reactions, pin, block, mute hoạt động
  ✓ Đánh dấu đã đọc + unread badge

═══════════════════════════════════════════════════════════════════════
TUẦN 8: Polish, test, deploy
═══════════════════════════════════════════════════════════════════════

BE                      FE                       FS
────────────────        ────────────────         ────────────────
□ Swagger/OpenAPI       □ Responsive             □ Deploy lên VPS
  documentation           design                   Singapore
□ Error handling        □ Loading states         □ CI/CD pipeline
  toàn diện             □ Empty states           □ Nginx reverse proxy
□ Audit logging         □ Error toasts             + SSL (Let's Encrypt)
□ Xóa account flow     □ Cross-browser          □ Backup PostgreSQL
□ Change password         test                     (daily cron)
  + force logout        □ Dark mode?             □ Monitoring cơ bản
                                                   (Sentry free tier)
                                                 □ End-to-end test
                                                   toàn flow

Deliverable tuần 8:
  ✓ App hoàn chỉnh, deploy production
  ✓ CI/CD, backup, monitoring
```

### Tổng ước lượng: 8 tuần (3 người, full-time)

So với 6-10 tuần 1 người, team 3 người nhanh hơn nhưng KHÔNG phải nhanh gấp 3.
Lý do: overhead giao tiếp, chờ API contract, merge conflict, integration test.
Quy luật thực tế: team 3 người ≈ nhanh gấp 1.5-2x so với 1 người.

### Rủi ro lớn nhất và cách giảm thiểu

```
┌─────────────────────────────────────────────────────────────────────┐
│ RỦI RO                          │ CÁCH GIẢM                       │
├─────────────────────────────────────────────────────────────────────┤
│ BE và FE code xong nhưng        │ FS viết contract trước,          │
│ không khớp format JSON          │ cả team review trước khi code    │
├─────────────────────────────────────────────────────────────────────┤
│ Socket phần khó nhất nhưng      │ FS own toàn bộ socket layer,     │
│ chỉ FS hiểu cả 2 đầu           │ BE và FE không tự ý sửa         │
│                                 │ socket code                      │
├─────────────────────────────────────────────────────────────────────┤
│ FE chờ BE xong API              │ FE mock API bằng json-server     │
│ → FE ngồi không                 │ hoặc MSW, code song song        │
├─────────────────────────────────────────────────────────────────────┤
│ Merge conflict trên             │ Mỗi người 1 feature branch,     │
│ shared files                    │ merge vào dev hàng ngày          │
├─────────────────────────────────────────────────────────────────────┤
│ "Tôi tưởng anh làm"            │ Mỗi task gắn tên người trong    │
│ → task rơi giữa 2 người        │ board (Trello/Linear/GitHub      │
│                                 │ Projects)                        │
├─────────────────────────────────────────────────────────────────────┤
│ FS bị overload vì phải          │ Tuần 1-3: FS tập trung infra    │
│ làm quá nhiều thứ               │ Tuần 4-5: FS tập trung socket   │
│                                 │ Tuần 6+: FS chuyển sang DevOps  │
│                                 │ BE và FE tự handle feature       │
└─────────────────────────────────────────────────────────────────────┘
```

### Daily standup (15 phút, bắt buộc)

Mỗi người trả lời 3 câu:
1. Hôm qua làm gì xong?
2. Hôm nay làm gì?
3. Có bị block ở đâu không? (chờ API? chờ design? chưa hiểu requirement?)

Câu 3 là quan trọng nhất — nếu FE bị block vì chưa có API, FS cần hỗ trợ mock
ngay trong ngày, không để qua hôm sau.

---

## 11. Cấu trúc thư mục đề xuất (Spring Boot)

```
src/main/java/com/yourapp/chat/
├── config/
│   ├── SecurityConfig.java          # Spring Security + JWT filter
│   ├── WebSocketConfig.java         # STOMP configuration
│   ├── RedisConfig.java
│   └── FirebaseConfig.java
├── controller/
│   ├── AuthController.java          # register, login, oauth, link-provider, change-password
│   ├── UserController.java
│   ├── ConversationController.java
│   ├── MessageController.java
│   └── ReactionController.java
├── service/
│   ├── AuthService.java             # JWT generation, refresh, logout logic
│   ├── PasswordAuthService.java     # Username/password login + register + change password
│   ├── OAuthService.java            # Firebase token verify + auto-link by email
│   ├── UserService.java
│   ├── ConversationService.java
│   ├── MessageService.java
│   ├── FileService.java
│   ├── NotificationService.java     # WebSocket push logic
│   └── BlockService.java
├── repository/
│   ├── UserRepository.java
│   ├── UserAuthProviderRepository.java
│   ├── ConversationRepository.java
│   ├── ConversationMemberRepository.java
│   ├── MessageRepository.java
│   ├── ReactionRepository.java
│   └── AuditLogRepository.java
├── model/
│   ├── entity/                      # JPA entities
│   ├── dto/
│   │   ├── request/
│   │   │   ├── LoginRequest.java          # { username, password }
│   │   │   ├── RegisterRequest.java       # { email, username, password, fullName }
│   │   │   ├── OAuthLoginRequest.java     # { firebaseIdToken }
│   │   │   ├── ChangePasswordRequest.java # { currentPassword, newPassword }
│   │   │   └── ...
│   │   └── response/
│   │       ├── AuthResponse.java          # { accessToken, refreshToken, user }
│   │       └── ...
│   └── enums/
│       ├── AuthMethod.java          # PASSWORD, OAUTH2_GOOGLE
│       └── ...
├── security/
│   ├── JwtTokenProvider.java
│   ├── JwtAuthFilter.java
│   ├── PasswordEncoder.java         # BCrypt configuration (strength 12)
│   └── FirebaseTokenVerifier.java
├── websocket/
│   ├── ChatMessageHandler.java     # @MessageMapping: chat.send, chat.edit, chat.delete
│   ├── ChatReactionHandler.java    # @MessageMapping: chat.reaction
│   ├── ChatReadHandler.java        # @MessageMapping: chat.read
│   ├── ChatTypingHandler.java      # @MessageMapping: chat.typing
│   ├── ChatSyncHandler.java        # @MessageMapping: chat.sync (reconnect)
│   ├── WebSocketEventListener.java # Connect/disconnect handling
│   ├── StompAuthInterceptor.java   # JWT verify cho WebSocket handshake
│   ├── PresenceService.java        # Online/offline tracking (Redis)
│   └── MessageFanoutService.java   # Push tin đến members + ACK/ERROR routing
├── scheduler/
│   └── FileCleanupJob.java
└── exception/
    ├── GlobalExceptionHandler.java
    └── AppException.java
```

---

## 12. Architectural Decision Records (ADR)

> Đây là mục tổng hợp các quyết định kiến trúc có ảnh hưởng đến toàn dự án.
> **ADR chi tiết đầy đủ nằm trong `.claude/memory/reviewer-knowledge.md`** (ADR-001 … ADR-018).
> Mục này chỉ chứa các ADR làm thay đổi tài liệu gốc (ARCHITECTURE.md mục 1-11)
> để reader không đọc knowledge file vẫn nhận biết được sự lệch.

### ADR-014: W4 chọn REST-gửi + STOMP-broadcast (thay cho tempId flow gốc mục 5)

- **Status**: Superseded (xem ADR-016)
- **Tóm tắt**: Tuần 4 đã implement mô hình REST `POST /messages` + STOMP broadcast `MESSAGE_CREATED`, tạm thời bỏ qua tempId ACK/ERROR flow mô tả ở mục 5 (dòng 84-124) và mục 7.4 (dòng 1051-1097).
- **Chi tiết**: xem `.claude/memory/reviewer-knowledge.md` → ADR-014.

### ADR-015: SimpleBroker cho V1, RabbitMQ cho V2

- **Status**: Accepted
- **Tóm tắt**: Dùng Spring SimpleBroker (in-memory) cho V1 (1 BE instance, <1000 concurrent). Trigger migrate RabbitMQ: (1) scale >1 BE instance, hoặc (2) cần persistent queue cho offline catch-up.
- **Chi tiết**: xem `.claude/memory/reviewer-knowledge.md` → ADR-015.

### ADR-016: Switch message send path from REST to STOMP

- **Status**: Accepted
- **Ngày**: 2026-04-20 (Tuần 4, sau khi W4D4 xong)
- **Context**: ADR-014 chọn REST-send để đơn giản (không tempId, không ACK/ERROR routing, broadcast sau `AFTER_COMMIT`). Sau khi implement xong W4, team đánh giá lại và quyết định chuyển sang STOMP-send để:
  - Giảm latency: bỏ 1 HTTP round-trip (~30-50ms) trên mọi lần gửi tin.
  - Thống nhất transport layer: mọi tương tác real-time (send, typing, presence, read-receipt) đều đi qua STOMP → giảm code path phân mảnh.
  - Chuẩn bị cho Tuần 5 (typing/presence/read) — các event này vốn đã qua STOMP, nên send-message qua cùng transport sẽ đơn giản hoá state machine FE.
  - Tiến gần lại thiết kế gốc của ARCHITECTURE.md mục 5 (tempId flow).
- **Decision**:
  - Client gửi tin nhắn qua STOMP destination `/app/conv.{convId}.message` với `tempId` (UUID v4 client-generated).
  - Server ACK qua `/user/queue/acks` với payload `{tempId, message: MessageDto}` — chỉ sender nhận.
  - Server ERROR qua `/user/queue/errors` với payload `{tempId, error, code}` — chỉ sender nhận.
  - Redis dedup bằng key `msg:dedup:{userId}:{tempId}` TTL 60s — nếu key tồn tại, drop silently và trả ACK cho message đã save.
  - Broadcast `MESSAGE_CREATED` qua `/topic/conv.{convId}` giữ nguyên như ADR-014 — tất cả subscriber (kể cả sender) nhận.
  - Client dedupe broadcast bằng message id thật (sau khi ACK đã replace optimistic tempId bằng id thật).
  - REST `POST /api/conversations/{id}/messages` **không bị xoá** — giữ lại cho batch import, bot API, testing, và fallback khi STOMP không khả dụng. FE không còn gọi endpoint này trên hot path.
- **Consequences**:
  - BE phải implement `@MessageMapping("/conv.{convId}.message")` handler với Redis dedup check trước khi save + publish event.
  - BE phải route ACK/ERROR qua `/user/queue/acks` và `/user/queue/errors` (Spring user destination).
  - FE phải implement tempId lifecycle state machine: SENDING → SENT (nhận ACK) hoặc FAILED (nhận ERROR hoặc timeout 10s).
  - FE phải implement timeout 10s client-side — nếu không nhận ACK/ERROR → set message status `failed` + show retry button.
  - Nếu STOMP chưa connect khi user gửi → queue trong FE hoặc disable input, không fallback sang REST.
  - Transaction boundary vẫn phải đúng: `publishEvent(MessageCreatedEvent)` trong `@Transactional`, broadcaster chạy ở `@TransactionalEventListener(AFTER_COMMIT)` (giữ nguyên pattern ADR-014).
  - Error code mới cần thống nhất: `CONV_NOT_FOUND` (không phải member), `FORBIDDEN` (bị block), `INTERNAL` (lỗi server), `MSG_CONTENT_TOO_LONG` (>5000 chars), `MSG_RATE_LIMITED` (vượt 30/phút).
- **Migration path**:
  - BE implement STOMP handler + giữ REST handler (không deprecate code, chỉ deprecate FE usage).
  - FE refactor `sendMessage` mutation từ axios POST sang STOMP `publish()` + subscribe 2 user queue.
  - Contract update: SOCKET_EVENTS.md bump `v1.0-draft-w4` → `v1.1-w4` (minor bump vì thêm inbound destination breaking so với W4).
  - API_CONTRACT.md thêm note deprecated vào POST `/messages` (không xoá section).
- **Chi tiết đầy đủ**: xem `.claude/memory/reviewer-knowledge.md` → ADR-016 (khi reviewer add vào knowledge file sau).

### ADR-018: Delete policy — no time window, soft delete, content strip tại mapper

- **Status**: Accepted
- **Ngày**: 2026-04-20 (Tuần 5, W5-D3)
- **Context**: Tuần 5 cần implement delete message. Team đứng trước 2 trục quyết định:
  1. **Time window**: có giới hạn thời gian được xoá (giống edit 5 phút) hay không?
  2. **Semantics**: hard delete (xoá khỏi DB) hay soft delete (giữ row, set `deleted_at`)?
  Ngoài ra cần thống nhất cách serialize: BE có strip `content` trước khi gửi client hay FE tự hiện placeholder dựa trên `deletedAt != null`?
- **Decision**:
  - **Không time window**: user có quyền xoá lịch sử của chính mình bất kỳ lúc nào (khác EDIT — edit có 5 phút window vì sửa content đã gửi có thể "gaslight" người khác; delete chỉ xoá khỏi hiển thị chung, không thay đổi ngữ nghĩa).
  - **Soft delete**: thêm 2 cột `deleted_at TIMESTAMPTZ NULL` + `deleted_by UUID NULL REFERENCES users(id)` vào bảng `messages`. Không xoá row. Lý do:
    - Bảo toàn thứ tự và scroll position (FE không cần reflow layout).
    - Giữ reply-to reference — message Y reply X, nếu X bị hard delete → Y mồ côi (snapshot `replyToMessage` vẫn có nhưng FE không tra được context).
    - Giữ unread count chính xác (count message visible, nhưng đã mark read từ trước thì không ảnh hưởng).
    - Audit trail: admin có thể xem log ai xoá gì (V2 feature).
  - **BE strip `content=null` tại `MessageMapper.toDto`**: khi `deletedAt != null`, mapper set `content=null` bất kể chạy cho REST response hay WS broadcast hay ACK. FE chỉ việc check `deletedAt != null` → render placeholder "🚫 Tin nhắn đã bị xóa". Lý do: single source of stripping logic (không trùng lặp BE + FE), không leak content qua payload nào.
  - **Anti-enumeration**: merge 4 case thành 1 error code `MSG_NOT_FOUND` (null / wrong-conv / not-owner / already-deleted). Cùng pattern với EDIT (§3c.2) và REST 404 (CONV_NOT_FOUND).
  - **Rate limit**: 10 delete/phút/user (Redis `rate:msg-delete:{userId}` INCR + EX 60, pattern ADR-005). Thấp hơn send (30/phút) và bằng edit (10/phút) — delete là destructive op, cần giới hạn mạnh hơn.
  - **Broadcast minimal**: MESSAGE_DELETED payload chỉ `{id, conversationId, deletedAt, deletedBy}` — không phải MessageDto đầy đủ. FE đã có message trong cache, chỉ cần patch 3 field.
  - **ACK metadata minimal**: DELETE ACK `message` field chỉ có 4 key như broadcast (không có content/sender/createdAt/replyToMessage) — tránh leak + giảm payload.
- **Consequences**:
  - BE migration (Flyway) thêm 2 cột `deleted_at` + `deleted_by` vào `messages`. Partial index `WHERE deleted_at IS NOT NULL` cho audit query V2.
  - BE `MessageMapper.toDto` cần 2 field mới + strip `content=null`. Test: serialize message có `deletedAt` → JSON có `"content": null`, `"deletedAt": "..."`, `"deletedBy": "..."`.
  - BE `MessageService.deleteViaStomp(convId, userId, payload)` pattern giống `editViaStomp`: validate → rate limit → dedup NX EX → load + ownership check (merge NOT_FOUND) → update `deletedAt + deletedBy` trong `@Transactional` → publishEvent → ACK trong `afterCommit`.
  - BE query `GET /conversations/{id}/messages` KHÔNG filter `deleted_at IS NULL` — trả về tất cả message, FE render placeholder cho message đã xoá. Lý do: đảm bảo thứ tự + reply-to context + unread count.
  - FE `MessageDto` type thêm 2 field `deletedAt + deletedBy`. Render component check `deletedAt != null` → placeholder, không hover actions.
  - FE edit flow: nếu đang edit message X mà X bị delete broadcast về → exit edit silently (discard draft).
  - Tech debt V2: undo grace period 5s client-side (show "Đã xoá" snackbar with "Hoàn tác" button → nếu click trước 5s, gửi `/app/conv.{id}.undelete` hoặc `/app/conv.{id}.edit` với restored content). V1 chấp nhận delete là vĩnh viễn.
  - Tech debt V2: thêm `replyToMessage.deleted: boolean` vào snapshot để FE render quote box với placeholder nếu parent đã xoá. V1 FE check qua cache client (nếu message gốc còn cache) — best effort.
- **Alternatives considered**:
  - **Hard delete**: loại vì mất reply-to context + phá thứ tự.
  - **Time window**: loại vì user có quyền xoá lịch sử — khác edit (edit có thể manipulate ngữ nghĩa).
  - **FE tự render placeholder, BE không strip content**: loại vì (1) duplicate logic BE/FE, (2) content vẫn bay qua network dù không render → leak + waste bandwidth cho group lớn.
- **Chi tiết đầy đủ**: xem `.claude/memory/reviewer-knowledge.md` → ADR-018.