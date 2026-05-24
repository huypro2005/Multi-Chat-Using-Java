# Báo cáo Kỹ thuật — Web Chat Application V1

> Báo cáo này phân tích toàn bộ hệ thống backend của dự án Chat App (thư mục `backend/`) cùng với các tài liệu thiết kế hiện hành (`ARCHITECTURE.md`, `API_CONTRACT.md`, `SOCKET_EVENTS.md`).
>
> Cập nhật: tuần 8 — đã hoàn tất các tính năng nhắn tin, group chat, file, reactions, pin message, read receipts.

---

## Mục lục

1. [Tổng quan dự án](#1-tổng-quan-dự-án)
2. [Kiến trúc hệ thống](#2-kiến-trúc-hệ-thống)
3. [Phân tích Use Case](#3-phân-tích-use-case)
4. [Phân tích cơ sở dữ liệu](#4-phân-tích-cơ-sở-dữ-liệu)
5. [Phân tích REST API](#5-phân-tích-rest-api)
6. [Phân tích WebSocket / STOMP](#6-phân-tích-websocket--stomp)
7. [Bảo mật & Xác thực](#7-bảo-mật--xác-thực)
8. [Vận hành & Triển khai](#8-vận-hành--triển-khai)
9. [Đánh giá & Tồn đọng](#9-đánh-giá--tồn-đọng)

---


## 1. Tổng quan dự án

### 1.1 Mục tiêu

Web Chat Application V1 là một ứng dụng nhắn tin thời gian thực, hỗ trợ:

- Nhắn tin 1-1 và nhóm (tối đa 50 thành viên / nhóm).
- Đăng nhập bằng username/password hoặc Google OAuth (qua Firebase).
- Gửi text, ảnh (JPEG/PNG/WebP/GIF), file (PDF), tối đa 20 MB / file.
- Reactions, reply, edit, delete, pin, read receipts, typing indicator, block user.
- Hỗ trợ catch-up tin nhắn lỡ (sau khi mất kết nối).

### 1.2 Quy mô và quyết định kỹ thuật chính

| Hạng mục | Quyết định V1 |
|----------|---------------|
| Quy mô mục tiêu | < 1 000 users, < 1 000 concurrent, ~4 000 tin/ngày |
| Group | Tối đa 50 thành viên, không giới hạn số nhóm/user |
| Backend | Spring Boot 3.4.4 + Spring WebSocket (STOMP) |
| Database | PostgreSQL (single instance) + Flyway migration |
| Cache / Realtime | Redis (presence, badge, JWT blacklist, dedup, rate-limit) |
| Auth | Spring Security + JWT (HMAC-SHA256), Google OAuth qua Firebase Admin |
| File Storage | Local disk (V1) → S3 (V2) — đã trừu tượng hóa qua `StorageService` |
| Deploy | 1 server, Docker (multi-stage Maven + JRE 21 Alpine) |
| Java | 21 (parent BOM Spring Boot 3.4.4) |

### 1.3 Stack công nghệ chi tiết (đọc từ `backend/pom.xml`)

| Lớp | Thư viện | Vai trò |
|-----|----------|---------|
| Web | `spring-boot-starter-web` | REST controller, JSON binding |
| Realtime | `spring-boot-starter-websocket` | STOMP + SockJS broker |
| Persistence | `spring-boot-starter-data-jpa`, `postgresql`, `flyway-core`, `flyway-database-postgresql` | ORM + migration |
| Cache | `spring-boot-starter-data-redis` (Lettuce pool) | Online status, dedup, rate-limit, JWT blacklist |
| Security | `spring-boot-starter-security`, `jjwt-api/impl/jackson 0.12.6` | JWT + filter chain |
| OAuth | `firebase-admin 9.4.1` | Verify Firebase ID token |
| File | `tika-core 3.3.0`, `thumbnailator 0.4.20` | MIME magic-bytes detection + thumbnail |
| Validation | `spring-boot-starter-validation` | Bean Validation |
| Dev | `spring-dotenv 4.0.0`, `lombok` | Đọc `.env`, giảm boilerplate |
| Test | `spring-boot-starter-test`, `h2`, `spring-security-test` | Integration test với DB in-memory |

---

## 2. Kiến trúc hệ thống

### 2.1 Sơ đồ tổng thể

```
┌─────────────┐    HTTPS/WSS    ┌────────────────────────────────┐
│   Browser    │◄───────────────►│       Spring Boot Server        │
│ React + TS   │                 │                                 │
│ STOMP client │                 │  ┌──────────────────────────┐   │
└─────────────┘                  │  │ REST Controllers          │   │
                                 │  │ Auth | User | Conversation│   │
                                 │  │ Message | File | Block    │   │
                                 │  └──────────┬───────────────┘   │
                                 │             │                   │
                                 │  ┌──────────▼───────────────┐   │
                                 │  │ STOMP Layer (/ws)         │   │
                                 │  │ AuthChannelInterceptor    │   │
                                 │  │ Chat*Handler / @MessageMap│   │
                                 │  └──────────┬───────────────┘   │
                                 │             │                   │
                                 │  ┌──────────▼───────────────┐   │
                                 │  │ Service Layer             │   │
                                 │  │ Validate / Save / Fan-out │   │
                                 │  │ Application Events        │   │
                                 │  └──────────┬───────────────┘   │
                                 │             │                   │
                                 │  ┌──────────▼───────────────┐   │
                                 │  │ Broadcasters (AFTER_COMMIT)│  │
                                 │  │ Message/Reaction/Pin/Conv │   │
                                 │  └──────────────────────────┘   │
                                 └─────────────┬─────────────────┘
                                               │
                ┌──────────────────────────────┼──────────────────────────────┐
                │                              │                              │
         ┌──────▼──────┐                ┌──────▼──────┐               ┌──────▼──────┐
         │ PostgreSQL  │                │   Redis      │               │ Local Disk  │
         │ + Flyway    │                │ (cache, pub) │               │ uploads/    │
         └─────────────┘                └─────────────┘                └─────────────┘
                                               │
                                        ┌──────▼──────┐
                                        │  Firebase   │
                                        │ (OAuth verify)
                                        └─────────────┘
```

### 2.2 Phân chia trách nhiệm REST vs WebSocket

| Kênh | Đối tượng đi qua | Lý do |
|------|------------------|-------|
| **REST** | Auth, User CRUD, Conversation CRUD, Member management, file upload/download, history pagination | Request-response, không cần realtime; binary upload không phù hợp STOMP |
| **WebSocket / STOMP** | Send / edit / delete message, typing, reactions, pin, read-receipt, broadcast event nhóm | Latency thấp, push 2 chiều, kết hợp ACK + broadcast |

### 2.3 Cấu trúc package backend

Toàn bộ source code backend được tổ chức theo **package by feature** dưới `com.chatapp.*` (~136 file Java). Mỗi module gồm các sub-package: `controller/`, `service/`, `repository/`, `entity/`, `dto/`, `event/`, `enums/`, `exception/`, `broadcast/`.

| Package | Vai trò chính | Số file ước tính |
|---------|---------------|------------------|
| `com.chatapp` | Entry point (`ChatAppApplication`), `HealthController` | 2 |
| `auth` | Đăng ký, đăng nhập, OAuth, refresh, change-password, logout | ~12 |
| `user` | Profile, search, block, last_seen | ~14 |
| `conversation` | 1-1, group, member, role, transfer-owner, system message events | ~30 |
| `message` | Send/edit/delete, history, read-receipt, pin, system message | ~28 |
| `reaction` | Toggle emoji reaction, broadcast | ~6 |
| `file` | Upload, download, thumbnail, expiry, validation | ~18 |
| `websocket` | STOMP inbound handlers (typing, react, pin, read, edit, delete) | ~8 |
| `security` | `JwtTokenProvider`, `JwtAuthFilter` | 2 |
| `config` | `SecurityConfig`, `WebSocketConfig`, `AuthChannelInterceptor`, `FirebaseConfig`, `StompPrincipal`, `StompErrorHandler` | 6 |
| `exception` | `AppException`, `GlobalExceptionHandler`, `ErrorResponse` | 3 |

### 2.4 Pattern kiến trúc nổi bật

1. **Layered architecture**: Controller → Service → Repository → Entity, không skip layer.
2. **Application Events + `@TransactionalEventListener(AFTER_COMMIT)`**: tất cả broadcast STOMP đều chờ DB commit thành công, tránh việc client nhận được tin nhắn nhưng DB rollback.
3. **STOMP-send pattern (ADR-016)**: client gửi tin qua STOMP với `tempId`, server reply ACK qua `/user/queue/acks`, broadcast `MESSAGE_CREATED` qua `/topic/conv.{id}`. REST `POST /messages` còn lại làm fallback / batch import.
4. **Soft-delete cho message** (ADR-018): `deleted_at` + `deleted_by`, mapper strip `content=null` ngay tại tầng DTO để không leak qua bất kỳ payload nào.
5. **Storage abstraction**: interface `StorageService` (impl hiện tại `LocalStorageService`) cho phép V2 chuyển sang S3 mà không đổi code caller.
6. **Pre-checked broadcast**: AuthChannelInterceptor dùng policy theo suffix (`STRICT_MEMBER` vs `SILENT_DROP`) để chặn SUBSCRIBE/SEND ngay tại tầng kênh, giảm tải cho handler.

---

## 3. Phân tích Use Case

### 3.1 Tác nhân (Actors)

| Actor | Mô tả |
|-------|-------|
| **Guest** | Người chưa đăng nhập, chỉ truy cập được `/api/auth/*`, `/api/health`, `/api/files/{id}/public` |
| **User** | Người dùng đã xác thực, có JWT hợp lệ |
| **Member** | User là thành viên hợp lệ của một conversation cụ thể (`left_at IS NULL`) |
| **Admin** | Member có role ADMIN trong group |
| **Owner** | Member có role OWNER trong group |
| **System / Scheduler** | `FileCleanupJob` chạy theo cron — không phải actor con người |

### 3.2 Sơ đồ use case rút gọn

```
                           ┌──────────────┐
                           │     Guest    │
                           └──────┬───────┘
                                  │
                  ┌───────────────┼─────────────────┐
                  │               │                 │
            Đăng ký          Đăng nhập         OAuth Google
            (password)       (password)        (Firebase)
                  │               │                 │
                  └───────────────┴─────────────────┘
                                  ▼
                           ┌──────────────┐
                           │     User     │
                           └──────┬───────┘
                                  │
        ┌─────────────────────────┼──────────────────────────────┐
        │                         │                              │
   Quản lý profile          Quản lý chat                  Tương tác hội thoại
   - Xem/sửa profile       - Tạo 1-1 / nhóm              - Gửi text/file/ảnh
   - Đổi password          - Tham gia / rời              - Edit / Delete
   - Block / Unblock       - Quản lý thành viên          - Reaction, Pin, Reply
   - Search user           - Đổi role (Owner)             - Read-receipt, Typing
   - Refresh token         - Transfer ownership          - Lịch sử (cursor)
   - Logout                - Xoá nhóm (Owner)            - Catch-up sau reconnect
                                                          - Upload / Download file
```

### 3.3 Chi tiết các use case chính

#### UC-1: Đăng ký tài khoản (`POST /api/auth/register`)

- **Actor**: Guest.
- **Pre-condition**: email + username chưa tồn tại.
- **Flow**:
  1. Validate email format, username `[a-zA-Z0-9_]{3..50}`, password ≥ 8 ký tự + 1 chữ hoa + 1 số.
  2. Bcrypt hash password (strength 12).
  3. INSERT `users`, set `auth_method = PASSWORD`.
  4. Phát access JWT (1h) + refresh token (7d), refresh hash lưu Redis `refresh:{userId}:{jti}`.
  5. Trả `AuthResponse` chứa cả 2 token + `UserDto`.
- **Rate limit**: 10 req / 15 phút / IP (`rate:register:{ip}`).
- **Error codes**: `VALIDATION_FAILED`, `EMAIL_TAKEN`, `USERNAME_TAKEN`, `RATE_LIMITED`.

#### UC-2: Đăng nhập username/password (`POST /api/auth/login`)

- Validate, BCrypt match → cấp token. Anti-enumeration (sai password vs sai user trả cùng error).
- Rate limit: 5 lần fail / 15 phút / IP.

#### UC-3: Đăng nhập Google OAuth (`POST /api/auth/oauth`)

1. Client lấy `idToken` từ Firebase JS SDK.
2. Server `firebaseAuth.verifyIdToken(idToken)` → `(uid, email, name, picture)`.
3. Tìm `user_auth_providers` theo `(provider=google, provider_uid=uid)` → nếu có, trả token cũ user.
4. Nếu chưa có nhưng email khớp với 1 `users` → **auto-link** (insert provider).
5. Nếu không khớp → tạo mới `users` (random username) + `user_auth_providers`.
6. Phát JWT chuẩn của hệ thống (không dùng Firebase token).

#### UC-4: Refresh token (`POST /api/auth/refresh`)

- So khớp hash refresh trong Redis → cấp token mới + xoay (rotate) refresh.
- Nếu refresh đã bị thu hồi (đổi mật khẩu, logout) → 401 `AUTH_REFRESH_INVALID`.

#### UC-5: Đổi mật khẩu / Logout

- Đổi password: verify current → cập nhật hash → xoá hết refresh → blacklist tất cả `jti` access đang sống.
- Logout: thêm `jwt:blacklist:{jti}` (TTL = TTL còn lại của access) + xoá refresh hash.

#### UC-6: Tạo conversation 1-1 (`POST /api/conversations`)

- Thân request: `{ type: "ONE_ON_ONE", memberIds: [uid] }`.
- Kiểm tra block 2 chiều (`BlockService.isBilaterallyBlocked`) → 403 `FORBIDDEN`.
- Tìm conversation 1-1 hiện hữu giữa 2 user (idempotent) → trả lại nếu có.
- Nếu chưa → tạo `conversations(type=ONE_ON_ONE)` + 2 `conversation_members`.

#### UC-7: Tạo group (`POST /api/conversations`)

- Thân request: `{ type: "GROUP", name, memberIds: [uid1..uidN], avatarFileId? }`.
- Owner = caller, Members = caller (OWNER) + N user (MEMBER).
- Insert SYSTEM message `GROUP_CREATED` để hiện trên timeline.
- Broadcast `MESSAGE_CREATED` (system) trên `/topic/conv.{id}` + `/user/{uid}/queue/conv-added` cho từng member mới.

#### UC-8: Quản lý thành viên (group)

| Hành động | Endpoint | Người thực hiện | Side-effects |
|-----------|----------|-----------------|--------------|
| Thêm thành viên (1–10) | `POST /api/conversations/{id}/members` | OWNER, ADMIN | Insert members, SYSTEM `MEMBER_ADDED`, push `conv-added` cho từng người |
| Kick | `DELETE /api/conversations/{id}/members/{uid}` | OWNER, ADMIN | Set `left_at`, SYSTEM `MEMBER_REMOVED`, push `conv-removed` |
| Tự rời | `POST /api/conversations/{id}/leave` | bất kỳ member | Nếu OWNER rời → auto transfer (admin sớm nhất → member sớm nhất → xoá nhóm nếu trống) |
| Đổi role | `PATCH /api/conversations/{id}/members/{uid}/role` | OWNER | ADMIN ↔ MEMBER, SYSTEM `ROLE_CHANGED` |
| Transfer owner | `POST /api/conversations/{id}/transfer-owner` | OWNER | Đổi role 2 người, SYSTEM `OWNER_TRANSFERRED` |
| Xoá nhóm | `DELETE /api/conversations/{id}` | OWNER | Soft-delete (`deleted_at`), broadcast `GROUP_DELETED` |

#### UC-9: Gửi tin nhắn text (STOMP `/app/conv.{id}.message`) — **Path B / ADR-016**

Đây là use case quan trọng nhất.

```
Client                                Server                          DB / Redis

  │── SEND /app/conv.{id}.message ───►│
  │   { tempId, content, type:TEXT,    │
  │     replyToMessageId?,             │
  │     attachmentIds? }               │
  │                                    │── Dedup check ─────────────►│ Redis NX
  │                                    │  msg:dedup:{uid}:{tempId}   │  TTL 60s
  │                                    │                             │
  │                                    │── Validate (member, block,  │
  │                                    │   content ≤ 5000, attach    │
  │                                    │   ownership, rate-limit)    │
  │                                    │                             │
  │                                    │── INSERT message ──────────►│ Postgres
  │                                    │── Update conversations.     │
  │                                    │   last_message_at           │
  │                                    │── publishEvent              │
  │                                    │   MessageCreatedEvent       │
  │   ◄── /user/queue/acks ────────────│  (sau commit)               │
  │      { tempId, message: MsgDto }   │── @AFTER_COMMIT broadcaster │
  │   ◄── /topic/conv.{id} ────────────│  fanout MESSAGE_CREATED     │
```

- **tempId lifecycle**: client tạo UUID v4, hiển thị optimistic `SENDING`. Khi nhận ACK → đổi thành id thật, status `SENT`. Timeout 10s → `FAILED` (retry với cùng tempId, server idempotent qua Redis dedup).
- **Error codes**: `CONV_NOT_FOUND`, `FORBIDDEN`, `MSG_CONTENT_TOO_LONG`, `MSG_RATE_LIMITED`, `INTERNAL`.

#### UC-10: Edit / Delete message

- **Edit** (`/app/conv.{id}.edit`): chỉ sender, trong 5 phút sau khi gửi, content ≤ 5000. Set `edited_at`. Broadcast `MESSAGE_UPDATED`.
- **Delete** (`/app/conv.{id}.delete`): chỉ sender, **không có time-window** (ADR-018). Soft delete, mapper strip `content=null`. Broadcast `MESSAGE_DELETED` payload tối thiểu `{id, conversationId, deletedAt, deletedBy}`.
- **Error code chung**: `MSG_NOT_FOUND` (anti-enumeration cho null / not-owner / wrong-conv / already-deleted).

#### UC-11: Reaction (`/app/msg.{messageId}.react`)

- 1 user chỉ giữ 1 emoji / message → toggle:
  - Chưa có → `ADDED`.
  - Có cùng emoji → `REMOVED` (xoá row).
  - Có khác emoji → `CHANGED` (UPDATE).
- Broadcast `REACTION_CHANGED` trên `/topic/conv.{convId}`. Fire-and-forget — không có ACK riêng.

#### UC-12: Pin / Unpin (`/app/msg.{messageId}.pin`)

- Trong **group** chỉ OWNER / ADMIN, trong **1-1** ai cũng pin được.
- Tối đa 3 pin / conversation. Lưu `pinned_at`, `pinned_by_user_id` trên `messages` (không có bảng riêng).
- Broadcast `MESSAGE_PINNED` / `MESSAGE_UNPINNED`.

#### UC-13: Read-receipt (`/app/conv.{id}.read`)

- Cập nhật `conversation_members.last_read_message_id` (forward-only — không cho lùi).
- Broadcast `READ_UPDATED` chứa `{userId, lastReadMessageId}` trên `/topic/conv.{convId}` để FE cập nhật badge "đã xem".

#### UC-14: Typing indicator (`/app/conv.{id}.typing`)

- Payload `{ action: START | STOP }`.
- Rate limit 1 event / 2 giây / user / conv qua `TypingRateLimiter` (Redis).
- Không lưu DB. Broadcast `TYPING_STARTED` / `TYPING_STOPPED` ephemeral.

#### UC-15: Upload file (`POST /api/files/upload?public=`)

1. Spring multipart kiểm soát ≤ 20 MB.
2. `FileValidationService` xác minh MIME bằng Tika magic-bytes (chống spoof header `Content-Type`).
3. Tạo `FileRecord` với `internal_path = {yyyy}/{mm}/{uuid}.{ext}`, `expires_at = NOW() + 30 days`.
4. Nếu là ảnh → `ThumbnailService` sinh thumbnail 200×200 lưu cùng folder.
5. Trả `FileDto`. Sau đó client gắn `file.id` vào `attachmentIds` khi gửi message.

#### UC-16: Block user (`POST /api/users/{id}/block`)

- 1 chiều, idempotent. Sau khi block:
  - Hai bên không gửi được tin trong chat 1-1 (chặn ở STOMP handler — `FORBIDDEN`).
  - Trong group vẫn nhắn bình thường.
  - `searchUsers` không trả về user bị block (cả 2 chiều).

---

## 4. Phân tích cơ sở dữ liệu

Toàn bộ schema được quản lý bởi **Flyway** trong `backend/src/main/resources/db/migration/V*.sql`. Hệ thống sử dụng **PostgreSQL**, mọi PK là `UUID` sinh bằng `gen_random_uuid()` (extension `pgcrypto` được bật ở V2).

### 4.1 Lịch sử migration

| Version | Mục đích chính |
|---------|----------------|
| V1 | Placeholder `SELECT 1` để khởi tạo Flyway |
| **V2** | Tạo `users`, `user_auth_providers`, `user_blocks`; bật `pgcrypto` |
| **V3** | Tạo `conversations`, `conversation_members` |
| V4 | Thêm `users.last_seen_at` + index |
| **V5** | Tạo `messages` (TEXT/IMAGE/FILE/SYSTEM) + index `(conversation_id, created_at DESC)` + FK `conversation_members.last_read_message_id → messages` |
| V6 | Thêm `messages.deleted_by` FK → users (cho soft-delete) |
| **V7** | Tạo `files`, `message_attachments` (composite PK `message_id+file_id`) |
| V8 | Thêm `files.thumbnail_internal_path` |
| V9 | Group chat: `conversations.owner_id`, `avatar_file_id`, `deleted_at`, CHECK `chk_group_metadata` |
| V10 | SYSTEM message: `messages.system_event_type`, `system_metadata JSONB`, `sender_id` nullable |
| V11 | `files.uploader_id` nullable + `files.is_public`, seed 2 default avatar UUID |
| V12 | Tái khẳng định `last_read_message_id` (idempotent) + composite index |
| **V13** | Tạo `message_reactions` (UNIQUE `message_id+user_id`) |
| **V14** | Pin: cột `messages.pinned_at`, `pinned_by_user_id` + partial index pinned |
| V15 | `user_blocks` idempotent + CHECK `blocker_id != blocked_id` |

### 4.2 ERD rút gọn

```
                ┌──────────────────────┐
                │       users          │
                │ id PK, email, username
                │ password_hash, status │
                │ last_seen_at          │
                └────┬───────┬─────┬───┘
                     │       │     │
       1..N          │       │     │      1..N
   user_auth_        │       │     │   user_blocks
   providers ───────┘       │     └──── (blocker_id,
   (provider, uid)           │           blocked_id)
                             │
                       1..N  │
                             ▼
              ┌─────────────────────────────┐
              │     conversations            │
              │ id PK, type, name            │
              │ owner_id, avatar_file_id     │
              │ deleted_at, last_message_at  │
              └────────────┬────────────────┘
                           │  1..N
                           ▼
              ┌─────────────────────────────┐
              │   conversation_members       │
              │ (conversation_id, user_id)   │
              │ role, joined_at, left_at     │
              │ last_read_message_id         │
              │ muted_until                  │
              └────────────┬────────────────┘
                           │
                           │
              ┌────────────▼────────────────┐
              │         messages             │
              │ id PK, conversation_id, sender_id
              │ type (TEXT/IMAGE/FILE/SYSTEM)│
              │ content, reply_to_message_id │
              │ edited_at, deleted_at, deleted_by
              │ pinned_at, pinned_by_user_id │
              │ system_event_type, system_metadata JSONB
              └────┬───────────────────┬─────┘
                   │                   │
            1..N   │                   │  1..N
                   ▼                   ▼
       ┌─────────────────────┐ ┌──────────────────────┐
       │ message_reactions   │ │ message_attachments  │
       │ (message_id, user_id│ │ (message_id, file_id)│
       │  emoji)             │ │ display_order        │
       └─────────────────────┘ └────────────┬─────────┘
                                            │
                                            ▼
                                   ┌──────────────────────┐
                                   │       files           │
                                   │ id PK, uploader_id    │
                                   │ mime, size            │
                                   │ internal_path UNIQUE  │
                                   │ thumbnail_internal_path
                                   │ is_public, expires_at │
                                   └──────────────────────┘
```

### 4.3 Bảng chính — chi tiết

#### `users`

| Cột | Kiểu | Ràng buộc | Ghi chú |
|-----|------|-----------|---------|
| `id` | UUID | PK | `gen_random_uuid()` |
| `email` | VARCHAR(255) | UNIQUE NOT NULL | |
| `username` | VARCHAR(50) | UNIQUE NOT NULL | `[a-zA-Z0-9_]{3..50}` |
| `password_hash` | VARCHAR(255) | NULL | NULL nếu chỉ dùng OAuth |
| `full_name` | VARCHAR(100) | NOT NULL | |
| `avatar_url` | VARCHAR(500) | NULL | Trỏ đến `/api/files/{id}/public` |
| `status` | VARCHAR(20) | DEFAULT `active` | `active`, `deleted` |
| `last_seen_at` | TIMESTAMPTZ | NULL | Cập nhật throttle 30s/lần ở `JwtAuthFilter` |
| `created_at`, `updated_at` | TIMESTAMPTZ | NOT NULL | |

#### `user_auth_providers`

UNIQUE `(provider, provider_uid)` — đảm bảo 1 tài khoản Firebase chỉ link với 1 user. Index `idx_auth_providers_user`.

#### `conversations`

| Cột | Kiểu | Ghi chú |
|-----|------|---------|
| `type` | VARCHAR(10) | `ONE_ON_ONE` hoặc `GROUP` |
| `name` | VARCHAR(100) | NULL với 1-1 |
| `owner_id` | UUID FK users | NULL với 1-1, NOT NULL với GROUP (CHECK `chk_group_metadata`) |
| `avatar_file_id` | UUID FK files | Avatar nhóm (nullable) |
| `deleted_at` | TIMESTAMPTZ | NULL = còn sống; soft-delete cho group |
| `last_message_at` | TIMESTAMPTZ | Sort danh sách hội thoại |

#### `conversation_members`

| Cột | Kiểu | Ghi chú |
|-----|------|---------|
| `role` | VARCHAR(10) | `OWNER`, `ADMIN`, `MEMBER` |
| `joined_at` | TIMESTAMPTZ | |
| `left_at` | TIMESTAMPTZ | NULL = active member, NOT NULL = đã rời/kick |
| `leave_reason` | VARCHAR(50) | `left`, `kicked_by_admin` |
| `last_read_message_id` | UUID FK messages | Cursor "đã đọc" |
| `muted_until` | TIMESTAMPTZ | NULL = không mute |

UNIQUE `(conversation_id, user_id)`, index trên `(user_id) WHERE left_at IS NULL`.

#### `messages`

| Cột | Kiểu | Ghi chú |
|-----|------|---------|
| `type` | VARCHAR(20) | `TEXT`, `IMAGE`, `FILE`, `SYSTEM` |
| `content` | TEXT | NULL khi đã xoá hoặc chỉ có attachments |
| `reply_to_message_id` | UUID FK self | Quote message — `ON DELETE SET NULL` |
| `edited_at` | TIMESTAMPTZ | Đánh dấu đã edit |
| `deleted_at` | TIMESTAMPTZ | Soft delete (ADR-018) |
| `deleted_by` | UUID FK users | Người xoá (sender hoặc admin) |
| `pinned_at`, `pinned_by_user_id` | TIMESTAMPTZ + UUID | Pin info inline (V14) |
| `system_event_type` | VARCHAR | 8 giá trị enum (xem `SystemEventType`) |
| `system_metadata` | JSONB | Mảng payload tuỳ event |

Index quan trọng:

- `idx_messages_conv_time (conversation_id, created_at DESC)` — list messages cursor-based.
- Partial `WHERE pinned_at IS NOT NULL` — list pinned.
- CHECK `sender_id IS NOT NULL OR type = SYSTEM`.

#### `files` & `message_attachments`

- `files.internal_path` UNIQUE → mỗi file chỉ có 1 record/disk path.
- `message_attachments`: composite PK `(message_id, file_id)`, `display_order INT` để sắp xếp ảnh trong gallery (1–5 ảnh / message hoặc 1 PDF / message).
- `expires_at` mặc định `created_at + 30 days`. Cleanup hằng ngày 03:00 UTC; orphan cleanup mỗi giờ.

#### `message_reactions`

- UNIQUE `(message_id, user_id)` — 1 user chỉ giữ 1 emoji per message (không phải per emoji). Toggle = UPDATE/DELETE/INSERT.

#### `user_blocks`

- UNIQUE `(blocker_id, blocked_id)` + CHECK `blocker_id != blocked_id`.
- Index trên cả 2 chiều để query nhanh `isBilaterallyBlocked`.

### 4.4 Redis data structures

| Pattern key | TTL | Mục đích |
|-------------|-----|----------|
| `refresh:{userId}:{jti}` | 7d | Hash refresh token (rotate khi /refresh) |
| `jwt:blacklist:{jti}` | = TTL còn lại của access | Logout / change password |
| `msg:dedup:{userId}:{tempId}` | 60s | Idempotent cho STOMP send |
| `rate:login:{ip}` | 15 phút | 5 fails / 15 phút |
| `rate:register:{ip}` | 15 phút | 10 / 15 phút |
| `rate:refresh:{userId}` | 60s | 5 / 60s |
| `rate:msg:{userId}` | 60s | 30 send / 60s |
| `rate:msg-edit:{userId}` | 60s | 10 / 60s |
| `rate:msg-delete:{userId}` | 60s | 10 / 60s |
| `rate:typing:{userId}:{convId}` | 2s | 1 event / 2s |
| `rate:react:{userId}` | 1s | 5 / 1s |
| `rate:pin:{userId}` | 1s | 5 / 1s |
| `user:online:{userId}` | 5 phút (heartbeat) | Online flag (phase sau) |

---

## 5. Phân tích REST API

Tất cả endpoint được trả lỗi theo shape chuẩn (xem `docs/API_CONTRACT.md`):

```json
{
  "error": "ERROR_CODE_STRING",
  "message": "Human readable message",
  "timestamp": "2026-04-19T10:00:00Z",
  "details": { "..." }
}
```

Header xác thực: `Authorization: Bearer <accessToken>` (trừ các endpoint công khai).

### 5.1 Health & Public

| Method | Path | Auth | Mục đích |
|--------|------|------|----------|
| GET | `/api/health` | Public | Liveness check (Docker healthcheck) |
| GET | `/api/files/{id}/public` | Public | Tải file `is_public=true` (avatar mặc định) |

### 5.2 Auth (`/api/auth`)

| Method | Path | Auth | Mục đích |
|--------|------|------|----------|
| POST | `/register` | Public | Đăng ký + auto login |
| POST | `/login` | Public | Username/password login |
| POST | `/oauth` | Public | Login bằng Firebase ID token |
| POST | `/refresh` | Public | Đổi refresh → access mới (rotate) |
| POST | `/logout` | JWT | Blacklist `jti` access + xoá refresh |
| POST | `/change-password` | JWT | Đổi mật khẩu (chỉ password account) |

Tất cả endpoint trả token đều dùng shape:

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": { "id": "...", "username": "...", "fullName": "...", "avatarUrl": "..." }
}
```

### 5.3 User (`/api/users`)

| Method | Path | Mục đích |
|--------|------|----------|
| GET | `/me` | Profile bản thân |
| PATCH | `/me` | Update `fullName`, `avatarUrl` |
| GET | `/{id}` | Profile public của user khác |
| GET | `/search?q=&limit=` | Tìm user theo username / fullName (loại bỏ user đã block 2 chiều) |
| POST | `/{id}/block` | Block user (idempotent) |
| DELETE | `/{id}/block` | Unblock |
| GET | `/blocked` | Danh sách user đã block |

### 5.4 Conversation (`/api/conversations`)

| Method | Path | Mục đích |
|--------|------|----------|
| POST | `/` | Tạo 1-1 hoặc group (`type`, `memberIds`, `name?`) |
| GET | `/?page=&size=` | List hội thoại (cursor / page) |
| GET | `/{id}` | Detail + members + last message |
| PATCH | `/{id}` | Rename group / đổi avatar (OWNER, ADMIN) |
| DELETE | `/{id}` | OWNER xoá nhóm (soft `deleted_at`) |
| POST | `/{id}/members` | Add 1–10 member (OWNER, ADMIN) |
| DELETE | `/{id}/members/{userId}` | Kick (OWNER, ADMIN) |
| POST | `/{id}/leave` | Tự rời (auto transfer nếu OWNER) |
| PATCH | `/{id}/members/{userId}/role` | Đổi role ADMIN ↔ MEMBER (OWNER) |
| POST | `/{id}/transfer-owner` | Chuyển OWNER cho member khác |

Mọi thao tác group có ảnh hưởng UI realtime đều phát SYSTEM message + push qua STOMP.

### 5.5 Message (`/api/conversations/{convId}/messages`)

| Method | Path | Mục đích |
|--------|------|----------|
| POST | `/` | Gửi message (REST fallback — vẫn còn dùng cho bot/batch) |
| GET | `/?cursor=&after=&limit=` | Lịch sử cursor-based (DESC theo `created_at`) |

Lưu ý: edit/delete đã chuyển hoàn toàn sang STOMP (không có REST endpoint).

### 5.6 File (`/api/files`)

| Method | Path | Auth | Mục đích |
|--------|------|------|----------|
| POST | `/upload?public={bool}` | JWT | Upload, trả `FileDto` |
| GET | `/{id}` | JWT | Tải file private (uploader hoặc member của conv chứa attachment) |
| GET | `/{id}/thumb` | JWT | Tải thumbnail 200×200 (chỉ cho IMAGE) |
| GET | `/{id}/public` | Public | Tải file `is_public=true` |

`POST /upload` validate:

- Size ≤ 20 MB.
- MIME whitelist: `image/jpeg`, `image/png`, `image/webp`, `image/gif`, `application/pdf`.
- Verify magic-bytes bằng Apache Tika để chống spoof header.
- File ngay sau upload là *orphan* — sau 1 giờ không attach vào message sẽ bị `FileCleanupJob` xoá.

### 5.7 Sơ đồ khái niệm: cách REST + STOMP gắn vào nhau

```
                    ┌─────────────────────────────────────┐
                    │ 1. POST /api/files/upload          │
                    │    → trả file.id                    │
                    └─────────────────┬───────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────────┐
                    │ 2. STOMP /app/conv.{id}.message    │
                    │    { tempId, type:IMAGE,            │
                    │      attachmentIds: [file.id] }    │
                    └─────────────────┬───────────────────┘
                                      │
                                      ▼
            ┌─────────────────────────────────────────────────┐
            │ 3. /user/queue/acks  → { tempId, message: ... } │
            │    /topic/conv.{id}  → { type:MESSAGE_CREATED } │
            └─────────────────────────────────────────────────┘

---

## 6. Phân tích WebSocket / STOMP

### 6.1 Cấu hình broker

`WebSocketConfig`:

- Endpoint HTTP upgrade: `/ws` (raw WebSocket + SockJS fallback).
- Broker: **Spring SimpleBroker** (in-memory) — đủ cho V1 (1 instance, < 1 000 concurrent). Khi scale > 1 instance → migrate RabbitMQ (ADR-015).
- Prefix:
  - `/app` — application destination (client SEND đến đây).
  - `/topic`, `/queue` — broker destination.
  - `/user` — user destination, Spring resolve theo `Principal.getName() = userId`.
- Limit transport: `messageSize 64KB`, `sendBuffer 512KB`, `sendTimeLimit 20s`.

### 6.2 Auth flow STOMP

`AuthChannelInterceptor` (đặt trên inbound channel):

| Frame | Hành động |
|-------|-----------|
| **CONNECT** | Lấy `Authorization: Bearer <jwt>` header → `JwtTokenProvider.validateTokenDetailed()`. Nếu invalid → trả `ERROR` frame `AUTH_REQUIRED` → đóng kết nối. Nếu valid → set `StompPrincipal(userId)` vào session attributes. |
| **SUBSCRIBE** | Nếu destination khớp `^/topic/conv\.([0-9a-f-]+)$` → check user là active member của conversation đó (qua `ConversationMemberRepository`). Sai → reject. |
| **SEND** | Phân loại theo suffix: `.typing` = `SILENT_DROP` (drop yên không trả lỗi), `.message/.edit/.delete/.read` = `STRICT_MEMBER`, `/app/msg.*` = pass-through (handler tự check). |
| **DISCONNECT** | (V1 chưa có presence broadcast) — chỉ log. |

### 6.3 Destinations (cập nhật version `v1.11-w8`)

#### Server → Client (subscribe)

| Destination | Mô tả |
|-------------|-------|
| `/topic/conv.{convId}` | Mọi event trong conversation: `MESSAGE_CREATED`, `MESSAGE_UPDATED`, `MESSAGE_DELETED`, `READ_UPDATED`, `REACTION_CHANGED`, `MESSAGE_PINNED`, `MESSAGE_UNPINNED`, `TYPING_STARTED`, `TYPING_STOPPED`, `MEMBER_ADDED`, `MEMBER_REMOVED`, `ROLE_CHANGED`, `OWNER_TRANSFERRED`, `CONVERSATION_UPDATED`, `GROUP_DELETED` |
| `/user/queue/acks` | ACK riêng cho sender: SEND / EDIT / DELETE |
| `/user/queue/errors` | Validation/auth fail cho các thao tác `/app/conv.*` |
| `/user/queue/conv-added` | User vừa được thêm vào group mới |
| `/user/queue/conv-removed` | User vừa bị kick |

#### Client → Server (send)

| Destination | Auth policy | Rate limit |
|-------------|-------------|-----------|
| `/app/conv.{id}.message` | STRICT_MEMBER | 30 msg / 60s / user |
| `/app/conv.{id}.edit` | STRICT_MEMBER | 10 / 60s |
| `/app/conv.{id}.delete` | STRICT_MEMBER | 10 / 60s |
| `/app/conv.{id}.typing` | SILENT_DROP | 1 event / 2s / user / conv |
| `/app/conv.{id}.read` | STRICT_MEMBER | 1 / 2s / user / conv |
| `/app/msg.{messageId}.react` | HANDLER_CHECK | 5 / s / user |
| `/app/msg.{messageId}.pin` | HANDLER_CHECK + role-gate (OWNER/ADMIN với group) | 5 / s / user |

### 6.4 Envelope chuẩn

Tất cả event trên `/topic/conv.{id}` theo cấu trúc:

```json
{ "type": "EVENT_TYPE", "payload": { "..." } }
```

Trong đó `type` là UPPERCASE_SNAKE_CASE.

### 6.5 Một số payload quan trọng

#### MESSAGE_CREATED (TEXT)

```json
{
  "type": "MESSAGE_CREATED",
  "payload": {
    "id": "uuid",
    "conversationId": "uuid",
    "sender": { "id": "...", "username": "...", "fullName": "...", "avatarUrl": "..." },
    "type": "TEXT",
    "content": "Hello!",
    "attachments": [],
    "replyToMessage": null,
    "reactions": [],
    "pinnedAt": null,
    "pinnedBy": null,
    "edited": false,
    "editedAt": null,
    "deletedAt": null,
    "deletedBy": null,
    "createdAt": "2026-04-19T10:00:00Z"
  }
}
```

#### MESSAGE_DELETED (payload tối thiểu — ADR-018)

```json
{
  "type": "MESSAGE_DELETED",
  "payload": {
    "id": "msg-uuid",
    "conversationId": "conv-uuid",
    "deletedAt": "2026-04-19T10:01:00Z",
    "deletedBy": "user-uuid"
  }
}
```

#### REACTION_CHANGED

```json
{
  "type": "REACTION_CHANGED",
  "payload": {
    "messageId": "msg-uuid",
    "userId": "user-uuid",
    "emoji": "👍",
    "previousEmoji": null,
    "action": "ADDED"
  }
}
```

#### READ_UPDATED

```json
{
  "type": "READ_UPDATED",
  "payload": {
    "conversationId": "conv-uuid",
    "userId": "user-uuid",
    "lastReadMessageId": "msg-uuid"
  }
}
```

#### ACK / ERROR (chỉ sender nhận)

```json
{ "tempId": "client-uuid", "message": { "...MessageDto..." } }
```

```json
{ "tempId": "client-uuid", "code": "FORBIDDEN", "error": "Bạn đã bị chặn" }
```

### 6.6 Sơ đồ flow gửi tin nhắn (Path B / ADR-016)

```
Client                             AuthChannelInterceptor          MessageStompController        DB / Redis        Broadcaster
  │                                       │                               │                        │                  │
  │── SEND /app/conv.X.message ──────────►│                               │                        │                  │
  │   { tempId, content, type:TEXT }      │── checkMember(userId, X) ────►│                        │                  │
  │                                       │   STRICT_MEMBER OK            │                        │                  │
  │                                       │── forward ───────────────────►│                        │                  │
  │                                       │                               │── Redis SETNX dedup ──►│                  │
  │                                       │                               │   msg:dedup:U:T  TTL60 │                  │
  │                                       │                               │── INSERT message ──────►│                  │
  │                                       │                               │── publishEvent ────────│                  │
  │                                       │                               │  MessageCreatedEvent   │                  │
  │                                       │                               │   (txn commit)         │                  │
  │  ◄── /user/queue/acks ────────────────┼───────────────────────────────│  (afterCommit)         │                  │
  │                                       │                               │                                          │
  │  ◄── /topic/conv.X ───────────────────┼───────────────────────────────┼─────────── @TransactionalEventListener ──│
  │     { type:MESSAGE_CREATED, payload } │                               │                                          │
```

### 6.7 Reconnect & catch-up

- Client backoff: 1s, 2s, 4s, 8s, 16s, 30s (cap), max 10 lần.
- Sau reconnect, FE **không** gọi `chat.sync` qua STOMP (V1 không có), mà gọi REST `GET /api/conversations/{id}/messages?cursor=<lastKnownId>` cho từng conversation đang mở để lấy missed messages.
- Client dedupe broadcast vs REST bằng `message.id`.

---

## 7. Bảo mật & Xác thực

### 7.1 JWT

- **Thuật toán**: HMAC-SHA256, secret ≥ 256 bit từ biến môi trường `JWT_SECRET`.
- **Access token**: TTL 1h, claims `sub` (userId), `username`, `auth_method`, `jti`, `iat`, `exp`.
- **Refresh token**: TTL 7d, claims `sub` + `jti`. Hash SHA-256 của refresh được lưu Redis dưới key `refresh:{userId}:{jti}` để có thể thu hồi.
- **Validation chi tiết**: `JwtTokenProvider.validateTokenDetailed()` trả `VALID | EXPIRED | INVALID` để filter biết phân biệt 401 `AUTH_REQUIRED` (sai signature/format) vs `AUTH_TOKEN_EXPIRED` (hết hạn → FE trigger refresh).

### 7.2 Filter chain (`SecurityConfig`)

- Sử dụng `JwtAuthFilter` đặt trước `UsernamePasswordAuthenticationFilter`.
- Tắt CSRF (REST + JWT, không session cookie).
- CORS bật theo `CORS_ALLOWED_ORIGINS` env (mặc định `http://localhost:3000,http://localhost:5173`).
- Stateless session (`SessionCreationPolicy.STATELESS`).
- **Public**: `/api/auth/{register|login|oauth|refresh}`, `/api/health`, `/actuator/health`, `/ws/**`, `/api/files/*/public`.
- **Protected**: tất cả còn lại — yêu cầu JWT.

### 7.3 STOMP authentication

- Validate JWT trên frame **CONNECT** (chỉ một lần). Sau đó session giữ Principal đến khi disconnect.
- Nếu access token hết hạn giữa session → BE không tự kick; FE phát hiện qua REST 401 → refresh → reconnect STOMP.

### 7.4 Firebase OAuth

- `FirebaseConfig` lazy-init từ `FIREBASE_CREDENTIALS_PATH`. Nếu env trống → log WARN, OAuth bị disable, app vẫn start (graceful).
- `AuthService.oauth()` chỉ tin claim từ `firebaseAuth.verifyIdToken()` — không tin trực tiếp request body.

### 7.5 Mật khẩu & chống brute-force

- BCrypt strength 12 (~250ms / hash).
- Anti-enumeration: sai password và sai user trả cùng error `LOGIN_FAILED`.
- Rate limit `rate:login:{ip}` = 5 fail / 15 phút (Redis INCR + EXPIRE).

### 7.6 Token revocation

| Tình huống | Hành động |
|------------|-----------|
| Logout | Add `jti` access vào `jwt:blacklist:{jti}` (TTL = TTL còn lại). Xoá `refresh:{userId}:{jti}`. |
| Đổi password | Xoá tất cả `refresh:{userId}:*`. (Access cũ vẫn dùng được tới hết TTL — chấp nhận được vì TTL chỉ 1h.) |
| Refresh token rotate | Mỗi lần `/refresh` xoá refresh cũ và cấp mới |

### 7.7 File security

| Vector | Phòng vệ |
|--------|----------|
| Path traversal | Dùng UUID + extension whitelist (không lấy từ filename gốc) |
| MIME spoof | `Tika magic-bytes` verify nội dung — từ chối nếu không khớp `Content-Type` |
| Quyền truy cập | `FileAuthService` check uploader hoặc member của conv chứa attachment |
| Storage exhaustion | 30 ngày auto-expire + orphan cleanup mỗi giờ |
| File quá lớn | Spring multipart limit 20 MB + service double-check |

---

## 8. Vận hành & Triển khai

### 8.1 Cấu hình runtime (`application.yml`)

| Khu vực | Tham số | Mặc định |
|---------|---------|----------|
| Server | `server.port` | 8080 |
| DB | URL | `jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}` |
| HikariCP | maximum-pool-size / min-idle | 30 / 10 |
| JPA | `hibernate.ddl-auto` | `validate` (sản phẩm dùng Flyway, không cho Hibernate sửa schema) |
| Flyway | locations | `classpath:db/migration` |
| Redis | `spring.data.redis.{host,port}` | `${REDIS_HOST:localhost}:${REDIS_PORT:6379}` |
| Multipart | max-file-size / max-request-size | 20MB / 21MB |
| JWT | secret / access-ttl / refresh-ttl | env / 1h / 7d |
| CORS | allowed origins | env `CORS_ALLOWED_ORIGINS` |
| WebSocket | allowed origins | env `WS_ALLOWED_ORIGINS` |
| Storage | base-path | `STORAGE_LOCAL_BASE_PATH:./uploads` |
| File cleanup | expired-cron / orphan-cron | 03:00 UTC daily / hằng giờ |
| Logging | `com.chatapp` | DEBUG |

### 8.2 Docker

`backend/Dockerfile` (multi-stage):

1. **Builder stage**: `maven:3.9-eclipse-temurin-21` → `mvn -B -DskipTests package`.
2. **Runtime stage**: `eclipse-temurin:21-jre-alpine`, user `chatapp` (uid 1000), workdir `/app`.
3. Tạo sẵn `/app/uploads/default` cho avatar mặc định.
4. Healthcheck: `wget -qO- http://localhost:8080/api/health`.
5. Expose **8080**.

Khuyến nghị deploy V1: 1 container backend + Postgres container + Redis container (Docker Compose).

### 8.3 Schedulers

| Job | Cron | Vai trò |
|-----|------|---------|
| `FileCleanupJob.cleanupExpiredFiles` | `0 0 3 * * *` (03:00 UTC) | Xoá file `expires_at < NOW()` khỏi disk + DB |
| `FileCleanupJob.cleanupOrphanFiles` | `0 0 * * * *` (đầu giờ) | Xoá file không attach vào message > 1 giờ |

`@EnableScheduling` được bật ở `ChatAppApplication`.

### 8.4 Logging & quan sát

- Log level package `com.chatapp` = `DEBUG` (V1 — production có thể giảm `INFO`).
- Spring Boot Actuator: chỉ expose `/actuator/health` (public) — chưa expose metrics, tracing.
- Phase 8 dự kiến tích hợp Sentry (free tier) cho error tracking.

### 8.5 Test suite (`backend/src/test/java`)

Có ~28 file test JUnit/Spring Boot Test, tiêu biểu:

- `AuthChannelInterceptorTest` — verify auth ở 3 frame STOMP.
- `WebSocketIntegrationTest` — end-to-end CONNECT + SUBSCRIBE + SEND.
- `MessageServiceStompTest` — gửi tin qua handler, kiểm tra ACK + broadcast.
- `ReadReceiptTest`, `PinServiceTest`, `EmojiValidatorTest`, `BlockServiceTest`.
- `FileValidationServiceTest`, `LocalStorageServiceTest` — verify Tika check + disk I/O.
- `JwtTokenProviderTest`, `GroupConversationTest`, `MemberManagementTest`.

Test profile dùng `application-test.yml` + H2 in-memory DB cho hầu hết test, một số test integration chạy với Testcontainers (nếu cấu hình).

---

## 9. Đánh giá & Tồn đọng

### 9.1 Điểm mạnh

- **Kiến trúc tách bạch rõ ràng** giữa REST (CRUD, file, history) và STOMP (realtime). Sự phân chia này là lý do chính giúp message latency thấp mà vẫn upload được file 20 MB.
- **Tài liệu hoá nghiêm túc**: 3 file source-of-truth (`API_CONTRACT.md`, `SOCKET_EVENTS.md`, `ARCHITECTURE.md`) + chuỗi ADR (014, 015, 016, 017, 018, 019…). Các thay đổi lớn đều được ghi lại.
- **Idempotent message send** thông qua `tempId` + Redis dedup — đáp ứng yêu cầu retry-on-failure mà không tạo tin trùng.
- **Schema cẩn trọng**: soft-delete cho message, `last_read_message_id` forward-only, partial index cho pinned, FK `ON DELETE SET NULL` để giữ reply context.
- **Storage abstraction** sẵn sàng cho V2 chuyển S3 mà không đổi caller.
- **Bảo mật chiều sâu**: BCrypt 12, JWT blacklist, anti-enumeration, MIME magic-bytes, rate limit toàn diện.

### 9.2 Hạn chế hiện tại (V1)

| Hạn chế | Tác động | Đề xuất V2 |
|---------|----------|------------|
| Realtime presence chưa có | FE chỉ đọc `last_seen_at` qua REST → trễ vài giây | Thêm `/topic/presence.{userId}` + Redis `user:online:{id}` heartbeat |
| SimpleBroker in-memory | Không scale > 1 instance, mất queue khi restart | Migrate RabbitMQ (ADR-015) khi đạt > 1 instance hoặc cần persistent queue |
| Message search chưa có | Không tìm được nội dung tin cũ | Thêm tsvector + GIN index như mục §3.3 thiết kế |
| Bad-word filter chưa implement | Không lọc nội dung | V2 thêm `BadWordService` |
| `is_hidden`, `cleared_at` (soft hide chat) | Đã thiết kế nhưng chưa code | V2: implement endpoint + filter `visible_from = MAX(joined_at, cleared_at)` |
| Audit log table | Đã thiết kế nhưng chưa migration | V2 thêm `audit_logs` |
| Logout-all devices | Đã có thiết kế, chưa expose endpoint | V2 thêm `POST /api/auth/logout-all` |
| File V1 lưu local | Single point of failure | V2 chuyển S3 |
| Frontend coverage | Không nằm trong phạm vi báo cáo này | — |

### 9.3 Rủi ro cần theo dõi

- **Hot-row `conversations.last_message_at`** khi nhiều nhóm cùng bùng nổ — cân nhắc thay bằng Redis cache hoặc subquery.
- **Fan-out đồng bộ trong handler**: hiện broadcaster chạy `@AFTER_COMMIT` trên cùng thread của outbound channel; với group 50 member đồng thời active sẽ tạo 50 lần `convertAndSendToUser`. Nếu peak mạnh, cần mở `@Async("taskExecutor")` ở broadcaster.
- **N+1 cho `MessageDto.attachments`**: cần `@EntityGraph` hoặc JOIN FETCH ở `MessageRepository.findByConversation*`.
- **WebSocket frame limit 64 KB**: nếu group đông + message kèm 5 ảnh + reactions → kích thước payload có thể vượt. Hiện thực tế ~1–2 KB/event là an toàn.

### 9.4 Khuyến nghị cho phase tiếp theo

1. **Ưu tiên cao**: Hoàn thiện presence realtime + soft-hide (`is_hidden`/`cleared_at`) + logout-all.
2. **Ưu tiên trung bình**: Migrate sang RabbitMQ khi chuẩn bị scale; tích hợp Sentry; tự động hoá CI/CD.
3. **Ưu tiên thấp**: Bad-word, audit log, full-text search nâng cao, S3.

---

## Phụ lục A — Bảng tham chiếu nhanh

### A1. File entry-point quan trọng

| File | Đường dẫn |
|------|-----------|
| Application | `backend/src/main/java/com/chatapp/ChatAppApplication.java` |
| Maven config | `backend/pom.xml` |
| Docker | `backend/Dockerfile` |
| Spring config | `backend/src/main/resources/application.yml` |
| Migration root | `backend/src/main/resources/db/migration/V*.sql` |
| Security | `backend/src/main/java/com/chatapp/config/SecurityConfig.java` |
| WebSocket | `backend/src/main/java/com/chatapp/config/WebSocketConfig.java` |
| STOMP auth | `backend/src/main/java/com/chatapp/config/AuthChannelInterceptor.java` |
| JWT | `backend/src/main/java/com/chatapp/security/JwtTokenProvider.java`, `JwtAuthFilter.java` |
| Firebase | `backend/src/main/java/com/chatapp/config/FirebaseConfig.java` |

### A2. Mã lỗi REST chuẩn

| HTTP | Code | Ý nghĩa |
|------|------|---------|
| 400 | `VALIDATION_FAILED` | Body/param không hợp lệ |
| 401 | `AUTH_REQUIRED` | Thiếu/JWT sai format |
| 401 | `AUTH_TOKEN_EXPIRED` | JWT hết hạn → FE refresh |
| 403 | `AUTH_FORBIDDEN` / `FORBIDDEN` | Đã login nhưng không đủ quyền hoặc bị block |
| 404 | `NOT_FOUND` | Resource không tồn tại / không có quyền (anti-enumeration) |
| 409 | `EMAIL_TAKEN`, `USERNAME_TAKEN` | Trùng dữ liệu |
| 429 | `RATE_LIMITED` | Vượt rate limit, kèm `details.retryAfterSeconds` |
| 500 | `INTERNAL_ERROR` | Lỗi server không xác định |

### A3. Mã lỗi STOMP

| Code | Ý nghĩa |
|------|---------|
| `AUTH_REQUIRED` | JWT thiếu/ sai ở CONNECT |
| `CONV_NOT_FOUND` | Không phải member hoặc conv không tồn tại |
| `MSG_NOT_FOUND` | Message không tồn tại / wrong-conv / not-owner / đã xoá |
| `MSG_CONTENT_TOO_LONG` | content > 5 000 ký tự |
| `MSG_RATE_LIMITED` | Vượt 30 send / 60s |
| `MSG_EDIT_EXPIRED` | Quá 5 phút sau send (chỉ cho EDIT) |
| `FORBIDDEN` | Block hoặc không đủ role pin/admin |
| `INTERNAL` | Lỗi server |

### A4. Tham chiếu chéo tài liệu

- Mọi REST endpoint chi tiết hơn → `docs/API_CONTRACT.md`.
- Mọi STOMP destination + payload chính xác → `docs/SOCKET_EVENTS.md`.
- Lý do thiết kế (decision rationale) → `docs/ARCHITECTURE.md` (mục 1–11) + ADR 014–019.
- Cảnh báo / pitfall vận hành → `docs/WARNINGS.md`.
- Retrospective qua các tuần → `docs/RETROSPECTIVE.md`.

---

*Báo cáo được tạo bởi quá trình quét toàn bộ thư mục `backend/` (~136 file Java + 18 file resource) và đối chiếu với tài liệu chính thức trong `docs/`. Khi có sai lệch giữa code và tài liệu thiết kế, ưu tiên đối chiếu lại `API_CONTRACT.md` và `SOCKET_EVENTS.md` (source of truth, do `code-reviewer` agent maintain).*






```










