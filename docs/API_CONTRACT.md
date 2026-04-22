# API Contract — REST Endpoints

> File này là **source of truth** cho mọi REST API giữa frontend và backend.
> **Chỉ `code-reviewer` agent được sửa file này.** BE và FE đọc và implement theo.
> Khi có conflict giữa code và contract, contract thắng. Muốn đổi → reviewer đổi trước.

---

## Error response shape (chuẩn toàn hệ thống)

Mọi error response đều trả về shape sau:

```json
{
  "error": "ERROR_CODE_STRING",
  "message": "Human readable message",
  "timestamp": "2026-04-19T10:00:00Z"
}
```

Một số error có thêm field `details` để FE hiển thị lỗi per-field:

```json
{
  "error": "VALIDATION_FAILED",
  "message": "Dữ liệu không hợp lệ",
  "timestamp": "2026-04-19T10:00:00Z",
  "details": {
    "fields": {
      "email": "Email không đúng định dạng",
      "username": "Username chỉ chứa a-z, 0-9, dấu gạch dưới"
    }
  }
}
```

### Error codes dùng chung (có thể trả về từ bất kỳ endpoint nào)

| HTTP | Error code | Ý nghĩa |
|------|-----------|---------|
| 400 | `VALIDATION_FAILED` | Request body/param không hợp lệ |
| 401 | `AUTH_REQUIRED` | Thiếu JWT hoặc JWT không đọc được |
| 401 | `AUTH_TOKEN_EXPIRED` | JWT đã hết hạn, cần gọi `/refresh` |
| 403 | `AUTH_FORBIDDEN` | Đã login nhưng không có quyền |
| 404 | `NOT_FOUND` | Resource không tồn tại |
| 429 | `RATE_LIMITED` | Vượt rate limit, kèm `details.retryAfterSeconds` |
| 500 | `INTERNAL_ERROR` | Lỗi server không xác định |

**Phân biệt AUTH_REQUIRED vs AUTH_TOKEN_EXPIRED:**
- `AUTH_REQUIRED`: chưa có token, hoặc token sai format/signature — FE redirect về /login
- `AUTH_TOKEN_EXPIRED`: token đúng format nhưng hết hạn — FE trigger refresh queue (gọi /refresh, retry request)

---

## Token response shape (chuẩn cho mọi endpoint trả token)

Bất kỳ endpoint nào trả về token đều dùng shape sau (không được thêm bớt field):

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "username": "john_doe",
    "email": "john@example.com",
    "fullName": "John Doe",
    "avatarUrl": "https://example.com/avatar.jpg"
  }
}
```

Field notes:
- `accessToken`: JWT, thời hạn 1 giờ (3600 giây).
- `refreshToken`: JWT hoặc opaque token, thời hạn 7 ngày.
- `tokenType`: luôn là `"Bearer"`.
- `expiresIn`: số giây đến khi `accessToken` hết hạn (luôn là `3600`).
- `user.id`: UUID v4.
- `user.avatarUrl`: `string` hoặc `null` nếu chưa có avatar.

---

## Authentication

### POST /api/auth/register

**Description**: Đăng ký tài khoản mới bằng email, username và password.

**Auth required**: No

**Rate limit**: 10 requests/15 phút/IP

**Request body**:

```json
{
  "email": "string, required",
  "username": "string, required",
  "password": "string, required",
  "fullName": "string, required"
}
```

Validation rules:
- `email`: đúng định dạng email (RFC 5322), max 255 ký tự.
- `username`: 3–50 ký tự, chỉ chứa `[a-zA-Z0-9_]` (alphanumeric + dấu gạch dưới), không bắt đầu bằng số.
- `password`: tối thiểu 8 ký tự, phải có ít nhất 1 chữ hoa (`A-Z`) và 1 chữ số (`0-9`), max 128 ký tự.
- `fullName`: 1–100 ký tự, không được để trống.

**Response 200**:

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "username": "john_doe",
    "email": "john@example.com",
    "fullName": "John Doe",
    "avatarUrl": null
  }
}
```

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | Một hoặc nhiều field vi phạm validation rule; kèm `details.fields` |
| 409 | `AUTH_EMAIL_TAKEN` | Email đã được đăng ký bởi tài khoản khác |
| 409 | `AUTH_USERNAME_TAKEN` | Username đã tồn tại trong hệ thống |
| 429 | `RATE_LIMITED` | Vượt 10 requests/15 phút/IP; kèm `details.retryAfterSeconds` |
| 500 | `INTERNAL_ERROR` | Lỗi server (ví dụ: không hash được password) |

**Notes**:
- Password được hash bằng BCrypt strength 12 trước khi lưu vào DB. Server không bao giờ lưu plain-text password.
- Sau khi đăng ký thành công, user được tự động login (trả về token ngay).
- `avatarUrl` mặc định là `null`. User upload avatar ở endpoint riêng (phase sau).
- Nếu cả `email` lẫn `username` đều đã tồn tại, trả về `AUTH_EMAIL_TAKEN` trước (kiểm tra email trước trong code).

---

### POST /api/auth/login

**Description**: Đăng nhập bằng username và password, nhận access token và refresh token.

**Auth required**: No

**Rate limit**: 5 attempts/15 phút/IP (áp dụng trên IP; thất bại mới tính vào quota)

**Request body**:

```json
{
  "username": "string, required",
  "password": "string, required"
}
```

Validation rules:
- `username`: 1–50 ký tự, không được để trống.
- `password`: 1–128 ký tự, không được để trống.
- Server KHÔNG validate format username/password ở đây để tránh lộ thông tin "username không tồn tại".

**Response 200**:

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "username": "john_doe",
    "email": "john@example.com",
    "fullName": "John Doe",
    "avatarUrl": "https://example.com/avatar.jpg"
  }
}
```

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `username` hoặc `password` để trống |
| 401 | `AUTH_INVALID_CREDENTIALS` | Username không tồn tại hoặc password sai (message cố tình mơ hồ để tránh user enumeration) |
| 403 | `AUTH_ACCOUNT_LOCKED` | Tài khoản bị khóa bởi admin |
| 429 | `RATE_LIMITED` | Vượt 5 lần thất bại/15 phút/IP; kèm `details.retryAfterSeconds` |
| 500 | `INTERNAL_ERROR` | Lỗi server |

**Notes**:
- Rate limit chỉ tính các lần **thất bại** (sai password hoặc username không tồn tại). Login thành công không bị tính vào quota.
- Response `401` cho cả 2 trường hợp (sai username lẫn sai password) với cùng message: `"Tên đăng nhập hoặc mật khẩu không đúng"`. Đây là security requirement (chống user enumeration attack).
- Refresh token được lưu hash trong Redis với TTL 7 ngày (`SET "refresh:{userId}:{jti}" "{hash}" EX 604800`).
- Access token payload theo spec trong `ARCHITECTURE.md` mục JWT Payload (có `sub`, `username`, `auth_method`, `iat`, `exp`, `jti`).

---

### POST /api/auth/oauth

**Description**: Đăng nhập hoặc đăng ký bằng Google OAuth thông qua Firebase ID Token.

**Auth required**: No

**Rate limit**: 20 requests/15 phút/IP

**Request body**:

```json
{
  "firebaseIdToken": "string, required"
}
```

Validation rules:
- `firebaseIdToken`: string không được để trống; server sẽ verify với Firebase Admin SDK.

**Response 200**:

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "isNewUser": true,
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "username": "john_doe_g",
    "email": "john@gmail.com",
    "fullName": "John Doe",
    "avatarUrl": "https://lh3.googleusercontent.com/..."
  }
}
```

Extra field so với token shape chuẩn:
- `isNewUser`: `boolean` — `true` nếu user vừa được tạo mới, `false` nếu user đã tồn tại trước. FE dùng field này để quyết định redirect đến onboarding hay thẳng vào chat.

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `firebaseIdToken` để trống |
| 401 | `AUTH_FIREBASE_TOKEN_INVALID` | Firebase ID Token không hợp lệ hoặc đã hết hạn |
| 403 | `AUTH_ACCOUNT_LOCKED` | Tài khoản tương ứng với email này bị khóa bởi admin |
| 429 | `RATE_LIMITED` | Vượt 20 requests/15 phút/IP; kèm `details.retryAfterSeconds` |
| 500 | `INTERNAL_ERROR` | Lỗi server hoặc Firebase Admin SDK không phản hồi |
| 503 | `AUTH_FIREBASE_UNAVAILABLE` | Firebase Admin SDK timeout sau 5 giây |

**Notes — Auto-link logic (quan trọng, BE phải implement đúng)**:

Sau khi verify Firebase ID Token thành công, server có `{ email, googleUid, displayName, photoUrl }`:

1. Tìm trong `user_auth_providers` theo `provider = 'google'` và `provider_uid = googleUid`.
   - Tìm thấy → đây là user đã login Google trước đây → load user, phát token.
2. Nếu không tìm thấy trong `user_auth_providers`, tìm trong `users` theo `email`.
   - Tìm thấy → user đã đăng ký bằng password trước đây với cùng email → **tự động link** (insert vào `user_auth_providers`), phát token, `isNewUser = false`.
3. Nếu không tìm thấy cả hai → **tạo user mới**: insert `users` (username tự sinh từ `displayName`, đảm bảo unique), insert `user_auth_providers`, phát token, `isNewUser = true`.
4. Nếu tạo user mới và `displayName` từ Google tạo ra username đã tồn tại → server tự thêm suffix ngẫu nhiên 4 số (ví dụ `john_doe_4821`). FE không cần handle trường hợp này.

`avatarUrl` với user mới tạo từ OAuth lấy từ `photoUrl` của Firebase token.

---

### POST /api/auth/refresh

**Description**: Dùng refresh token hợp lệ để lấy access token mới (và refresh token mới nếu rotate).

**Auth required**: No (chỉ cần refresh token hợp lệ trong body)

**Rate limit**: 10 requests/60 giây/userId (per-user, không per-IP — tránh brute-force rotation attack trên một user cụ thể; FE refresh queue pattern đảm bảo không vượt)

**Request body**:

```json
{
  "refreshToken": "string, required"
}
```

Validation rules:
- `refreshToken`: string không được để trống.

**Response 200**:

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "username": "john_doe",
    "email": "john@example.com",
    "fullName": "John Doe",
    "avatarUrl": "https://example.com/avatar.jpg"
  }
}
```

Notes về `refreshToken` trong response:
- Dự án dùng **refresh token rotation**: mỗi lần gọi `/refresh`, server phát `refreshToken` mới và invalidate token cũ trong Redis.
- FE phải lưu `refreshToken` mới vào storage, thay thế token cũ ngay sau khi nhận response.

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `refreshToken` để trống |
| 401 | `AUTH_REFRESH_TOKEN_INVALID` | Token không đúng định dạng, signature sai, hoặc đã bị blacklist/invalidate |
| 401 | `AUTH_REFRESH_TOKEN_EXPIRED` | Token đúng định dạng nhưng đã hết TTL 7 ngày |
| 403 | `AUTH_ACCOUNT_LOCKED` | Tài khoản bị khóa sau khi token được phát |
| 429 | `RATE_LIMITED` | Vượt 10 requests/60 giây/userId; kèm `details.retryAfterSeconds` |
| 500 | `INTERNAL_ERROR` | Lỗi server |

**Notes**:
- Nếu cùng refresh token được dùng 2 lần (replay attack), lần 2 trả về `AUTH_REFRESH_TOKEN_INVALID` (cùng code với malformed để không leak "token từng tồn tại nhưng đã bị rotate" qua error differentiation). Server detect bằng cách: khi rotate, xóa token cũ khỏi Redis; nếu token không còn trong Redis → invalid. **Side effect**: khi detect reuse, server revoke TOÀN BỘ sessions của user đó (xóa tất cả `refresh:{userId}:*` keys) vì coi như user đã bị compromise.
- Endpoint này không yêu cầu Authorization header (access token đã hết hạn nên không có để gửi).

**Refresh Queue Pattern (bắt buộc cho FE):**
Khi nhiều request đồng thời gặp lỗi 401 AUTH_TOKEN_EXPIRED, FE KHÔNG được gọi /refresh song song.
Thay vào đó FE phải:
1. Chặn tất cả request đang pending (đưa vào queue)
2. Chỉ gọi /refresh MỘT LẦN
3. Sau khi nhận token mới, retry toàn bộ queue với accessToken mới
4. Nếu /refresh thất bại → clear token, redirect về /login

Lý do: mỗi refreshToken chỉ dùng được 1 lần (rotating). Nếu 2 request cùng gọi /refresh với cùng token, request thứ 2 sẽ nhận `AUTH_REFRESH_TOKEN_INVALID` (do token cũ đã bị xóa khỏi Redis sau request 1) — và server coi đây là reuse attack → revoke tất cả sessions của user → user buộc phải đăng nhập lại. FE phải tuyệt đối implement queue pattern để tránh kịch bản này.

---

### POST /api/auth/logout

**Description**: Đăng xuất phiên hiện tại — invalidate access token và refresh token của session đang dùng.

**Auth required**: Yes (Bearer token trong header `Authorization`)

**Rate limit**: Không giới hạn (operation nhẹ)

**Request body**:

```json
{
  "refreshToken": "string, required"
}
```

Validation rules:
- `refreshToken`: string không được để trống. Server cần biết token nào cần xóa khỏi Redis.

**Response 200**:

```json
{
  "message": "Đăng xuất thành công"
}
```

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `refreshToken` để trống |
| 401 | `AUTH_REQUIRED` | Thiếu hoặc không đọc được Authorization header |
| 401 | `AUTH_TOKEN_EXPIRED` | Access token đã hết hạn — FE nên gọi `/refresh` trước, sau đó logout |
| 500 | `INTERNAL_ERROR` | Lỗi server |

**Notes — Side effects khi logout**:
1. Access token bị **blacklist** trong Redis: `SET "jwt:blacklist:{jti}" "" EX {remaining_ttl}` (TTL tính từ thời điểm logout đến khi access token hết hạn tự nhiên).
2. Refresh token bị **xóa** khỏi Redis: `DEL "refresh:{userId}:{refreshJti}"`.
3. WebSocket connections của session này bị **disconnect** — server gửi frame DISCONNECT đến client.
4. Các phiên khác của cùng user **không bị ảnh hưởng** (đây là single-device logout). Muốn logout all devices → dùng endpoint `POST /api/auth/logout-all` (ngoài scope tuần 1, sẽ contract sau).

**FE implementation note** (để phối hợp — không phải server behavior):
- FE gọi logout → nhận 200 → xóa access token và refresh token khỏi localStorage/cookie → redirect về trang login.
- Nếu access token đã hết hạn trước khi logout, FE nên: (1) gọi `/refresh` lấy token mới → (2) gọi `/logout` với token mới. Không được bỏ qua logout vì sẽ để lại refresh token orphan trong Redis.

---

## Users

(reviewer sẽ thêm khi phase user CRUD bắt đầu)

---

## Conversations API (v1.5.0-w8-reactions — Message Reactions)

> **Version bump W8-D1 (2026-04-22)**: v1.4.0-w7-read → **v1.5.0-w8-reactions** (minor).
> Lý do: thêm Message Reactions — MessageDto extend với `reactions: ReactionAggregateDto[]`,
> ReactionAggregateDto shape mới, STOMP §3.15 `/app/msg.{messageId}.react` inbound + broadcast §3.16 `REACTION_CHANGED`,
> ADR-022 (ARCHITECTURE.md §12), Migration V13 (`V13__add_message_reactions.sql`).
> Additive only — không breaking existing clients (reactions field mới, FE cũ tolerant empty array fallback).
> Xem changelog W8-D1 cuối file.
>
> **Version bump W7-D5 (2026-04-22)**: v1.3.0-w7 → **v1.4.0-w7-read** (minor). Lý do: thêm Read Receipts + enable `unreadCount` real compute (bỏ placeholder 0). Changes vs v1.3.0-w7: (1) **Schema V12 migration** (`V12__add_last_read_message_id.sql`) — thêm column `conversation_members.last_read_message_id UUID REFERENCES messages(id) ON DELETE SET NULL` + index `(conversation_id, last_read_message_id)` cho query readBy computation. (2) **MemberDto extended** — thêm field `lastReadMessageId: uuid | null` vào member object trả về từ `GET /api/conversations/{id}` (members[]), `POST /api/conversations` response (created members), `MEMBER_ADDED` STOMP broadcast (new member có `lastReadMessageId = null`). FE compute "readBy per message" client-side từ field này (không cần BE compute). (3) **`unreadCount` compute rule** — placeholder 0 → real compute `COUNT(messages WHERE conv_id = X AND created_at > lastRead.created_at AND type != 'SYSTEM')`. Edge: `lastReadMessageId = null` → count all non-SYSTEM messages. **SYSTEM KHÔNG count** (đổi từ v1.2.0-w7-system rule — xem "unreadCount compute rule" mục bên dưới cho rationale). (4) **STOMP `/app/conv.{convId}.read`** (xem SOCKET_EVENTS.md §3f v1.9-w7) — inbound idempotent forward-only, broadcast `READ_UPDATED` §3.13 lên `/topic/conv.{id}`. (5) Không đụng Auth / Users / Messages / Files sections — read feature isolated trong Conversations domain. KHÔNG breaking cho existing shape (additive field `lastReadMessageId` + same-shape `unreadCount: number` nhưng giờ > 0). Xem changelog W7-D5 cuối file.
>
> **Version bump W7-D4 (2026-04-22)**: v1.2.0-w7-system (SYSTEM messages — MessageDto extend, migration V10) — see Changelog. Giữ chung Conversations API umbrella vì SYSTEM msg trigger từ group management endpoints. v1.3.0-w7 được reserve/skip — next bump đi trực tiếp lên v1.4.0-w7-read để sync nhịp bump version với SOCKET_EVENTS.md (v1.7 → v1.9).
>
> **Version bump W7-D2 (2026-04-21)**: v1.0.0-w7 → **v1.1.0-w7** (minor). Lý do: finalize 5 member management endpoints trước BE/FE implement D2 — (1) partial success response shape cho add-members (`added[] + skipped[]` với `reason`), (2) error code naming refactor (`CANNOT_KICK_SELF`, `CANNOT_TRANSFER_TO_SELF`, `CANNOT_CHANGE_OWNER_ROLE`, `MEMBER_LIMIT_EXCEEDED`, `CANNOT_LEAVE_EMPTY_GROUP`, `INVALID_ROLE` — thay thế/bổ sung cho `INVALID_ROLE_CHANGE`, `GROUP_FULL`, `CANNOT_REMOVE_SELF` legacy), (3) thêm user-specific STOMP destinations `/user/{userId}/queue/conv-added` + `/user/{userId}/queue/conv-removed` để BE notify riêng user vừa bị add/kick, (4) OWNER_TRANSFERRED thêm `autoTransferred: boolean` (true khi OWNER leave auto-transfer, false khi `/transfer-owner` explicit), (5) formalize no-op idempotent behavior (PATCH role với same role → 200 OK, không broadcast). Không đụng schema, không có migration mới. Xem Changelog W7-D2 entry cuối file.
>
> **Version bump W7-D1 (2026-04-21)**: v0.9.5-files-extended → **v1.0.0-w7**. Lý do major bump: thêm full Group Chat management (W7-D1) — role enum (OWNER/ADMIN/MEMBER), metadata group (name/avatar/owner), + 7 endpoints mới (PATCH/DELETE conv, add/remove/leave/role-change/transfer-owner). Xem ADR-020. Schema migration V7 (`V7__add_group_chat.sql`) thêm `member_role` enum + columns `joined_at`, `conversations.name/avatar_file_id/owner_id` + CHECK constraint type-specific. Breaking: `POST /api/conversations` payload cho GROUP đổi — thêm `name` + `avatarFileId` optional; `type="GROUP"` BẮT BUỘC có `name` + `memberIds` ≥ 2 (tổng member ≥ 3 gồm caller).
>
> **Naming note**: V1 đơn giản hoá so với ARCHITECTURE.md mục 3.2:
> - `type` dùng UPPERCASE `ONE_ON_ONE` | `GROUP` (không phải lowercase `direct`/`group` trong ARCHITECTURE gốc). Xem ADR-012.
> - Bỏ `left_at` / `leave_reason` (soft-leave), `is_hidden` / `cleared_at` (soft-hide) khỏi V1 migration. Khi cần tính năng "rời nhóm" hoặc "xoá lịch sử chat" sẽ thêm migration riêng. W7 implement rời nhóm = hard-delete row `conversation_members` (qua `POST /api/conversations/{id}/leave`).
> - `muted_until` (snake_case → DB) / `mutedUntil` (camelCase → JSON) có mặt nhưng endpoint mute sẽ chốt sau.
>
> Nguyên tắc chung cho mọi endpoint trong nhóm này:
> - **Auth required**: Bearer JWT trên header `Authorization: Bearer <accessToken>`. Thiếu → `401 AUTH_REQUIRED`; hết hạn → `401 AUTH_TOKEN_EXPIRED`.
> - **IDs là UUID v4** (string). Mọi reference user/conversation/member trong body/response đều là UUID.
> - **Timestamp là ISO-8601 với offset** (ví dụ `"2026-04-19T10:00:00Z"` hoặc `"2026-04-19T17:00:00+07:00"`).

---

### Schema V7 migration (W7-D1)

File: `backend/src/main/resources/db/migration/V7__add_group_chat.sql`

```sql
-- Enum role (OWNER / ADMIN / MEMBER)
CREATE TYPE member_role AS ENUM ('OWNER', 'ADMIN', 'MEMBER');

-- Alter conversation_members: thêm role + joined_at
ALTER TABLE conversation_members
  ADD COLUMN role member_role NOT NULL DEFAULT 'MEMBER',
  ADD COLUMN joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Alter conversations: thêm metadata group
ALTER TABLE conversations
  ADD COLUMN name VARCHAR(100),
  ADD COLUMN avatar_file_id UUID REFERENCES files(id) ON DELETE SET NULL,
  ADD COLUMN owner_id UUID REFERENCES users(id) ON DELETE SET NULL;

-- Indexes
CREATE INDEX idx_conversation_members_conv_role
  ON conversation_members(conversation_id, role);
CREATE INDEX idx_conversation_members_conv_joined
  ON conversation_members(conversation_id, joined_at);
CREATE INDEX idx_conversations_owner
  ON conversations(owner_id) WHERE owner_id IS NOT NULL;

-- Constraint: GROUP conversation PHẢI có name + owner; DIRECT (ONE_ON_ONE) PHẢI có cả 2 NULL
ALTER TABLE conversations
  ADD CONSTRAINT chk_group_metadata
  CHECK (
    (type = 'ONE_ON_ONE' AND name IS NULL AND owner_id IS NULL) OR
    (type = 'GROUP' AND name IS NOT NULL AND owner_id IS NOT NULL)
  );
```

**Backfill note**: V7 migration chạy khi chưa có GROUP conversation trong DB (W1-W6 chỉ test ONE_ON_ONE). Nếu production đã có row GROUP pre-W7, cần backfill `name` + `owner_id` TRƯỚC khi apply CHECK constraint — V1 acceptable không có rows.

> **Naming drift nhẹ**: Migration dùng `DIRECT` / `GROUP` trong doc ADR-020, nhưng DB column `type` enum giữ `ONE_ON_ONE` | `GROUP` theo ADR-012. CHECK constraint code trên đã sync lại `'ONE_ON_ONE'` để khớp DB. Trong text/docs có thể dùng "DIRECT" đồng nghĩa "ONE_ON_ONE" cho ngắn gọn; code luôn dùng `ONE_ON_ONE`.

---

### Schema V12 migration (W7-D5 — Read Receipts)

File: `backend/src/main/resources/db/migration/V12__add_last_read_message_id.sql`

```sql
-- V12: Read receipts — per-member pointer tới message cuối cùng đã đọc (W7-D5).
-- Additive: nullable column, KHÔNG breaking. Rows cũ → NULL (chưa đọc), next STOMP .read frame sẽ set.

ALTER TABLE conversation_members
  ADD COLUMN last_read_message_id UUID REFERENCES messages(id) ON DELETE SET NULL;

-- Index cho composite query khi FE compute readBy per-message:
--   SELECT user_id, last_read_message_id FROM conversation_members WHERE conversation_id = ?;
-- (conversation_id already indexed via PK/FK; last_read_message_id column gắn thêm để query readBy filter nhanh nếu cần).
CREATE INDEX idx_conv_members_last_read
  ON conversation_members(conversation_id, last_read_message_id);
```

**Migration notes**:

- **FK `ON DELETE SET NULL`** (BLOCKING choice): nếu message được `last_read_message_id` trỏ tới bị HARD-delete (rare — V1 dùng soft-delete mặc định, xem ADR-022) → field auto-set NULL. Next `.read` frame từ user đó sẽ advance bình thường từ NULL. Alternative `RESTRICT` rejected: có thể block hard-delete legitimate (admin tool xoá message dirty), tạo phụ thuộc ngầm. Alternative `CASCADE` rejected: xoá member row khỏi conv vì 1 message bị xoá → mất context nghiêm trọng.
- **Không backfill giá trị**: rows pre-W12 giữ `last_read_message_id = NULL`. Behavior equivalent "user chưa từng mở conv" — `unreadCount` sẽ = tổng non-SYSTEM messages trong conv cho đến khi user đánh dấu read lần đầu qua STOMP. Acceptable UX (FE có thể auto-fire `.read` lần đầu khi user mở conv).
- **Không UNIQUE constraint** trên `(conversation_id, user_id, last_read_message_id)` — PK composite `(conversation_id, user_id)` đã unique per-member; `last_read_message_id` chỉ là pointer mutable. Không cần constraint phụ.
- **Foreign key composite validation at application level**: DB không enforce "message thuộc conversation_id" (FK chỉ reference `messages.id`, không compound). BE handler MUST validate `message.conversation_id == member.conversation_id` trước khi UPDATE (xem SOCKET_EVENTS.md §3f.2 step 6). Nếu bypass → dirty data không enforce DB-level.
- **Rollback**: `ALTER TABLE conversation_members DROP COLUMN last_read_message_id;` + `DROP INDEX idx_conv_members_last_read;`. Safe rollback — không dữ liệu nào khác phụ thuộc.

---

### MemberDto — extended shape (v1.4.0-w7-read)

Shape chuẩn của member object (dùng trong response `POST /api/conversations` thành viên khi tạo, `GET /api/conversations/{id}` `members[]`, và STOMP broadcast `MEMBER_ADDED` `member` field — xem SOCKET_EVENTS.md §3.7):

```json
{
  "userId": "uuid",
  "username": "string",
  "fullName": "string",
  "avatarUrl": "string | null",
  "role": "OWNER | ADMIN | MEMBER",
  "joinedAt": "ISO8601",
  "lastReadMessageId": "uuid | null"
}
```

**Field rules**:

| Field | Rule |
|-------|------|
| `userId` | UUID v4. Key để FE dedup members list. |
| `username` / `fullName` / `avatarUrl` | Snapshot từ `users` table tại thời điểm query. Update live (không snapshot time-travel như `systemMetadata.actorName` của SYSTEM msg). |
| `role` | Enum `OWNER` (đúng 1 per GROUP), `ADMIN`, `MEMBER`. ONE_ON_ONE: cả 2 member đều có role nhưng không có semantic — FE ignore. |
| `joinedAt` | ISO-8601 UTC. Không đổi sau khi set. |
| `lastReadMessageId` (**v1.4.0-w7-read**) | UUID của message cuối cùng user đã đánh dấu đọc trong conv, hoặc `null` nếu chưa bao giờ đánh dấu. Update trigger: STOMP `/app/conv.{convId}.read` (§3f SOCKET_EVENTS.md) — idempotent forward-only. Khi message ref bị hard-delete → FK `ON DELETE SET NULL` tự chuyển về null. |

**Rule BE rendering**:
1. **MEMBER_ADDED broadcast** (STOMP §3.7): new member khi vừa được add có `lastReadMessageId = null` (chưa đọc message nào trong conv). FE cache patch dùng giá trị này.
2. **GET /api/conversations/{id}** (`members[]`): BE query JOIN `conversation_members` + include `last_read_message_id` trong SELECT. Chạy đồng thời với query load `users` table cho `username/fullName/avatarUrl`.
3. **READ_UPDATED broadcast** (STOMP §3.13): payload KHÔNG trả toàn bộ member object — chỉ `{conversationId, userId, lastReadMessageId, readAt}`. FE tự patch 1 field trong cache.
4. **POST /api/conversations response** (tạo conversation mới): creator có `lastReadMessageId = null` (chưa có message nào khi vừa create). Nếu `createGroup` auto-insert SYSTEM msg `GROUP_CREATED` (xem W7-D4) → creator vẫn có `lastReadMessageId = null` (auto-fire `.read` xảy ra client-side sau khi FE render — không phải server-side).

**Client-side readBy compute** (pattern BE expect FE theo):

```ts
function readBy(message: MessageDto, members: MemberDto[], messagesById: Map<string, MessageDto>): string[] {
  return members
    .filter((m) => m.userId !== message.sender?.id) // exclude sender
    .filter((m) => {
      if (!m.lastReadMessageId) return false;
      const lastRead = messagesById.get(m.lastReadMessageId);
      if (!lastRead) return true; // lastRead message chưa load → assume đã đọc (tránh flash unread)
      return new Date(lastRead.createdAt) >= new Date(message.createdAt);
    })
    .map((m) => m.userId);
}
```

> **BE MUST NOT compute readBy list per message** — payload explosion với group ≥ 10 member, 1000+ messages. Client-side compute rẻ (Array.filter O(M*N) với M ≤ 50 member, N ≤ 50 messages visible — negligible). Nếu future scale cần server-side compute (group 500+), tách endpoint riêng `/api/messages/{id}/read-by` trả list userId (V2).

---

### unreadCount compute rule (v1.4.0-w7-read)

`unreadCount` field trong `ConversationSummaryDto` (từ `GET /api/conversations` list endpoint) server-computed theo công thức:

```
unreadCount = COUNT(*)
FROM messages m
WHERE m.conversation_id = :convId
  AND m.type != 'SYSTEM'
  AND m.deleted_at IS NULL  -- không count soft-deleted
  AND (
    :lastReadMessageId IS NULL  -- user chưa từng đánh dấu → count tất cả
    OR m.created_at > (SELECT created_at FROM messages WHERE id = :lastReadMessageId)
  )
```

**Rules**:

1. **Per-caller**: tính từ `conversation_members.last_read_message_id` của CALLER (user gọi `GET /api/conversations`). Mỗi request cùng conv, 2 user khác nhau → 2 `unreadCount` khác nhau.
2. **SYSTEM exclusion** (BLOCKING): `type = 'SYSTEM'` KHÔNG count. Lý do: system msg fire rất nhiều trong group active (member add/kick/rename) — count vào unread làm badge phình to vô nghĩa. User không cần "đánh dấu đọc" system notice chủ động. Supersedes rule cũ v1.2.0-w7-system. Xem "Validation rules cho SYSTEM messages" rule 3 (updated).
3. **Soft-deleted exclusion**: `deleted_at IS NOT NULL` → không count. User không muốn thấy badge tăng vì message đã bị xóa. Nhất quán với FE render placeholder "🚫 Tin nhắn đã bị xóa" (không count là unread).
4. **NULL lastReadMessageId**: user chưa bao giờ đánh dấu đọc → count TẤT CẢ non-SYSTEM non-deleted messages. Acceptable UX (chỉ xảy ra conv mới tạo / user mới join).
5. **Cap value V1**: nếu count > 99 → trả `99` (frontend hiển thị "99+"). BE: `LEAST(count, 99)`. Mục đích: tránh "9999 unread" gây shock UX. Cap apply TRƯỚC khi serialize — field vẫn là `number`. Optional optimization (FE có thể cap client-side thay); V1 khuyến cáo BE cap để giảm load COUNT(*) khi conv cực nhiều msg (thực tế V1 <4000 msg/ngày total — OK).
6. **Performance**: query COUNT(*) trên 1 conv → index `messages(conversation_id, created_at)` đã có (V4 migration). Filter `type != 'SYSTEM' AND deleted_at IS NULL` filter ngoài index → chấp nhận full scan per-conv (V1 ≤ few thousand rows per conv). V2 có thể add partial index `WHERE type != 'SYSTEM' AND deleted_at IS NULL` nếu query slow.
7. **Update trigger** (invalidation cho FE cache):
   - Self-echo READ_UPDATED (§3.13) → FE optimistic set `unreadCount = 0`. KHÔNG chờ REST.
   - Other-user READ_UPDATED → KHÔNG đụng `unreadCount` của caller.
   - New MESSAGE_CREATED trong conv (caller là member nhưng KHÔNG phải sender, KHÔNG phải SYSTEM) → FE increment `unreadCount` cached +1. Soft-delete MESSAGE_DELETED → FE decrement nếu message đó trong unread range (khó track chính xác — acceptable stale until next REST refetch).
   - Invalidation fallback: 30s poll hoặc reconnect → gọi lại `GET /api/conversations` (đã có pattern catch-up). BE source-of-truth.

**Behavior table**:

| Caller `lastReadMessageId` | Conv có 10 TEXT + 3 SYSTEM + 1 SOFT-DELETED | Expected `unreadCount` |
|----------------------------|---------------------------------------------|------------------------|
| `null` (chưa đọc) | Total non-SYSTEM non-deleted = 10 | **10** |
| Message 5 (TEXT, `createdAt` = T5) | Messages sau T5 = 5 TEXT (msg 6-10), SYSTEM và deleted loại → count 5 | **5** |
| Message 10 (TEXT, newest) | Không có msg nào sau T10 | **0** |

**Contract test guidance** (reviewer recommend):

- Test 1: Fresh conv, 0 messages → `unreadCount = 0` (COUNT empty).
- Test 2: 5 TEXT messages, member chưa đánh dấu read → `unreadCount = 5`.
- Test 3: 5 TEXT + 3 SYSTEM, member chưa đánh dấu read → `unreadCount = 5` (SYSTEM loại).
- Test 4: 10 TEXT, member `lastReadMessageId` = msg 10 → `unreadCount = 0`.
- Test 5: 10 TEXT, member `lastReadMessageId` = msg 5, msg 8 đã soft-delete → `unreadCount = 4` (msg 6,7,9,10; 8 loại deleted).
- Test 6: 100+ TEXT, member `lastReadMessageId` = null → `unreadCount = 99` (cap).
- Test 7: 2 tabs, tab A send `.read(msg 10)` → broadcast READ_UPDATED → tab A optimistic set `unreadCount = 0`; tab B nhận self-echo với `userId === self` → cũng set `unreadCount = 0`. Sau next `GET /api/conversations` → REST confirm `unreadCount = 0`.

---

### POST /api/conversations

**Description**: Tạo conversation mới (1-1 hoặc nhóm). Caller tự động trở thành thành viên với role `OWNER` (GROUP) hoặc `MEMBER` (ONE_ON_ONE — role không có ý nghĩa với DIRECT).

**Auth required**: Yes

**Rate limit**: 10 requests/phút/user (chống spam tạo nhóm).

**Request body (ONE_ON_ONE)**:

```json
{
  "type": "ONE_ON_ONE",
  "targetUserId": "9b1a7c16-4c72-4a2a-a8f6-abc111222333"
}
```

> **Shape change W7**: `targetUserId` (singular UUID) thay cho `memberIds: [uuid]` array cho DIRECT. Lý do: DIRECT luôn 1-1, array 1-phần-tử thừa + dễ confuse với GROUP. BE backward-compat: nếu FE gửi `memberIds: [uuid]` với `type="ONE_ON_ONE"` → vẫn accept (deprecated path, BE lấy phần tử [0] làm targetUserId).

**Request body (GROUP)**:

```json
{
  "type": "GROUP",
  "name": "Dự án đồ án",
  "memberIds": [
    "9b1a7c16-4c72-4a2a-a8f6-abc111222333",
    "7c1a7c16-4c72-4a2a-a8f6-def444555666"
  ],
  "avatarFileId": "550e8400-e29b-41d4-a716-446655440000"
}
```

Validation rules:
- `type`: bắt buộc, enum `"ONE_ON_ONE" | "GROUP"`.
- `targetUserId` (chỉ cho `ONE_ON_ONE`): bắt buộc UUID, **khác caller id**. Gửi kèm `GROUP` → `VALIDATION_FAILED`.
- `name`:
  - `ONE_ON_ONE`: phải là `null` hoặc không gửi field (server không dùng; gửi string non-null → `VALIDATION_FAILED`).
  - `GROUP`: **bắt buộc non-null**, 1–100 ký tự sau trim, không toàn whitespace. Thiếu hoặc rỗng → `GROUP_NAME_REQUIRED`.
- `memberIds` (chỉ cho `GROUP`): bắt buộc, array UUID, **không chứa UUID của caller** (caller tự add qua OWNER role). Không được có phần tử trùng. Giới hạn độ dài:
  - `GROUP`: tối thiểu **2 phần tử** (tổng member ≥ 3 gồm caller + 2 others → minimum group size). < 2 → `GROUP_MEMBERS_MIN`. Tối đa 49 (cộng caller = 50, khớp giới hạn V1 "tối đa 50 thành viên"). > 49 → `GROUP_MEMBERS_MAX`.
  - Nếu 1 UUID trong `memberIds` không tồn tại / `status != 'active'` → `GROUP_MEMBER_NOT_FOUND` (details.missingIds).
- `avatarFileId` (optional, chỉ cho `GROUP`): UUID của `FileRecord` đã upload qua `POST /api/files/upload`. Validation:
  - File phải exists (else `GROUP_AVATAR_NOT_OWNED` — merge với not-owned để anti-enum).
  - File `uploader_id == callerId` (else `GROUP_AVATAR_NOT_OWNED`).
  - File MIME phải là image (group A: jpeg/png/webp/gif). PDF/office → `GROUP_AVATAR_NOT_IMAGE`.
  - File chưa expire.
  - File sẽ được "attach" vào conversation (BE set `attached_at` để không bị orphan cleanup — xem `AttachAvatarToConversation` flow). Reuse cho multiple group không OK V1 (file chỉ attach 1 nơi theo UNIQUE constraint message_attachments; avatar đi qua conversations.avatar_file_id column KHÔNG qua message_attachments → không conflict nhưng V1 giới hạn 1 group dùng 1 file avatar cho đơn giản).

**Response 201**:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "type": "GROUP",
  "name": "Dự án đồ án",
  "avatarUrl": "/api/files/550e8400-e29b-41d4-a716-446655440000",
  "owner": {
    "userId": "9b1a7c16-4c72-4a2a-a8f6-abc111222333",
    "username": "alice",
    "fullName": "Alice Nguyen"
  },
  "createdBy": {
    "id": "9b1a7c16-4c72-4a2a-a8f6-abc111222333",
    "username": "alice",
    "fullName": "Alice Nguyen",
    "avatarUrl": null
  },
  "members": [
    {
      "userId": "9b1a7c16-4c72-4a2a-a8f6-abc111222333",
      "username": "alice",
      "fullName": "Alice Nguyen",
      "avatarUrl": null,
      "role": "OWNER",
      "joinedAt": "2026-04-21T10:00:00Z"
    },
    {
      "userId": "7c1a7c16-4c72-4a2a-a8f6-def444555666",
      "username": "bob",
      "fullName": "Bob Tran",
      "avatarUrl": null,
      "role": "MEMBER",
      "joinedAt": "2026-04-21T10:00:00Z"
    }
  ],
  "createdAt": "2026-04-21T10:00:00Z",
  "lastMessageAt": null
}
```

Field notes (W7):
- `name`: `null` cho ONE_ON_ONE, string (1-100) cho GROUP.
- `avatarUrl`: `null` cho ONE_ON_ONE; cho GROUP — `null` nếu không có avatar, else `/api/files/{avatarFileId}` (FE dùng `useProtectedObjectUrl` render).
- `owner` (W7 mới): chỉ có cho GROUP (null cho ONE_ON_ONE). Shape minimal `{userId, username, fullName}` — **không expose** `avatarUrl` tại `owner` (FE đã có full info trong `members` array; tránh duplicate). Khi user OWNER bị xoá → `owner_id` DB set NULL → response trả `owner: null` (V1 edge case rare, UI fallback).

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | Vi phạm validation rule chung (type sai enum, UUID malformed, ONE_ON_ONE có name/memberIds, targetUserId = callerId, …); kèm `details.fields`. |
| 400 | `GROUP_NAME_REQUIRED` | `type="GROUP"` nhưng `name` null/empty/toàn whitespace. |
| 400 | `GROUP_MEMBERS_MIN` | `type="GROUP"` nhưng `memberIds.length < 2` (nhóm tối thiểu 3 người gồm caller). |
| 400 | `GROUP_MEMBERS_MAX` | `type="GROUP"` nhưng `memberIds.length > 49` (tổng > 50). |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 403 | `GROUP_AVATAR_NOT_OWNED` | `avatarFileId` không exists HOẶC uploader_id ≠ callerId (merge anti-enum). |
| 404 | `CONV_MEMBER_NOT_FOUND` / `GROUP_MEMBER_NOT_FOUND` | Một hoặc nhiều `memberIds` (hoặc `targetUserId`) không trỏ tới user active; kèm `details.missingIds: [uuid, ...]`. Alias: `GROUP_MEMBER_NOT_FOUND` dùng cho GROUP để rõ nghĩa; `CONV_MEMBER_NOT_FOUND` giữ cho ONE_ON_ONE (backward-compat). |
| 409 | `CONV_ONE_ON_ONE_EXISTS` | Đã tồn tại `ONE_ON_ONE` giữa caller và target user; kèm `details.conversationId` để FE redirect luôn. |
| 409 | `CONV_MEMBER_BLOCKED` | Caller đã block / bị block bởi 1 trong các `memberIds`; kèm `details.blockedIds: [uuid, ...]`. (V1 nếu `user_blocks` chưa wire, error này không fire — documented để FE sẵn sàng khi bật.) |
| 415 | `GROUP_AVATAR_NOT_IMAGE` | `avatarFileId` trỏ tới file không phải image (PDF, docx, …). Kèm `details.actualMime`. |
| 429 | `RATE_LIMITED` | Vượt 10 requests/phút/user; kèm `details.retryAfterSeconds`. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Notes**:
- **Idempotency `ONE_ON_ONE`**: server query xem giữa caller và target user đã có `ONE_ON_ONE` chưa. Có → trả `409 CONV_ONE_ON_ONE_EXISTS` với `details.conversationId` (FE điều hướng sang conv cũ). KHÔNG tự động trả 200 với conv cũ — để FE chủ động xử lý UX "bạn đã có chat với người này".
- **Race duplicate ONE_ON_ONE**: 2 request song song có thể cùng pass existence check. V1 chấp nhận edge case (traffic thấp); cần partial UNIQUE index cho lần cleanup V2: `CREATE UNIQUE INDEX ... ON conversations(LEAST(user_a, user_b), GREATEST(user_a, user_b)) WHERE type='ONE_ON_ONE'` (denormalize 2 columns hoặc dùng conv_members swap). Documented trong WARNINGS.md.
- **Role mặc định**: caller (GROUP) = `OWNER`; các `memberIds` khác = `MEMBER`. Không cho phép set role khác qua endpoint này — dùng `PATCH /api/conversations/{id}/members/{userId}/role` sau khi tạo. Với ONE_ON_ONE, role không có nghĩa — BE set cả 2 members = `MEMBER` (hoặc lưu NULL — chọn MEMBER để NOT NULL constraint pass).
- **owner_id persistence**: BE set `conversations.owner_id = callerId` cho GROUP, `NULL` cho ONE_ON_ONE (CHECK constraint enforce).
- **Avatar attach flow**: sau khi validate `avatarFileId` pass, BE set `FileRecord.attached_at = NOW()` để không bị orphan cleanup (giống pattern message attachment). V1 KHÔNG lưu row vào `message_attachments` — avatar đi qua `conversations.avatar_file_id` column trực tiếp.
- `lastMessageAt` luôn `null` khi vừa tạo — cập nhật khi có message đầu tiên.

---

### GET /api/conversations

**Description**: Lấy danh sách conversations mà caller đang là thành viên, sắp xếp theo hoạt động gần nhất.

**Auth required**: Yes

**Rate limit**: 60 requests/phút/user.

**Query params**:

| Param | Kiểu | Default | Mô tả |
|-------|------|---------|------|
| `page` | integer ≥ 0 | `0` | Trang (0-indexed). |
| `size` | integer 1..50 | `20` | Số record mỗi trang. |

> Tại sao offset pagination ở đây? V1 danh sách conversations của 1 user nhỏ (≤ vài trăm). Cursor-based pagination sẽ áp dụng cho messages (tuần 4) vì lượng lớn.

**Response 200**:

```json
{
  "content": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "type": "ONE_ON_ONE",
      "name": null,
      "avatarUrl": null,
      "displayName": "Bob Tran",
      "displayAvatarUrl": null,
      "memberCount": 2,
      "lastMessageAt": "2026-04-19T09:30:00Z",
      "unreadCount": 3,
      "mutedUntil": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 12,
  "totalPages": 1
}
```

Field notes:
- `name`, `avatarUrl`: giá trị từ cột `conversations` — `null` cho `ONE_ON_ONE`.
- `displayName`, `displayAvatarUrl`: **server-computed** cho FE render trực tiếp:
  - `ONE_ON_ONE`: là `fullName` / `avatarUrl` của user kia (không phải caller).
  - `GROUP`: trùng `name` / `avatarUrl` của conversation. Nếu `avatarUrl` null → FE tự fallback (ví dụ avatar compose 2-3 thành viên đầu).
- `memberCount`: tính từ `COUNT(*) FROM conversation_members WHERE conversation_id = ?`.
- `lastMessageAt`: `null` nếu chưa có message nào.
- `unreadCount`: **v1.4.0-w7-read** (W7-D5) — server-computed thật sự từ `members[].lastReadMessageId` thay vì placeholder 0. Shape KHÔNG đổi (integer ≥ 0). Xem mục "unreadCount compute rule (v1.4.0-w7-read)" bên dưới cho công thức đầy đủ, edge case `lastReadMessageId = null` (count all non-SYSTEM), và behavior `type='SYSTEM'` (KHÔNG count — xem Rationale). FE vẫn chỉ đọc field `unreadCount: number` — KHÔNG breaking (trước đây luôn 0, giờ có thể > 0; FE phải handle cả 2 case tương tự trước).
- `mutedUntil`: ISO-8601 hoặc `null`. FE hiển thị icon mute nếu `mutedUntil` trong tương lai.

**Sort**: `lastMessageAt DESC NULLS LAST, createdAt DESC` (conversation mới tạo chưa có tin nằm dưới cùng).

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `page` < 0, `size` ≤ 0 hoặc > 50. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 429 | `RATE_LIMITED` | Vượt 60/phút/user. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Notes**:
- BE MUST filter by caller (`WHERE cm.user_id = :callerId`). Không được expose conversations không liên quan.
- Endpoint này sẽ được FE gọi lại sau mỗi reconnect socket (tuần 4) để sync danh sách.

---

### GET /api/conversations/{id}

**Description**: Lấy chi tiết conversation kèm danh sách members đầy đủ.

**Auth required**: Yes

**Rate limit**: 120 requests/phút/user.

**Path params**:
- `id`: UUID của conversation.

**Response 200**: cùng shape với response 201 của `POST /api/conversations` — đầy đủ `createdBy`, `owner` (nullable, chỉ có cho GROUP), `members[]` với `role` + `joinedAt`, `name`, `avatarUrl`.

```json
{
  "id": "uuid",
  "type": "ONE_ON_ONE" | "GROUP",
  "name": "string | null",
  "avatarUrl": "string | null",
  "createdAt": "ISO8601",
  "lastMessageAt": "ISO8601 | null",
  "members": [
    {
      "userId": "uuid",
      "username": "string",
      "fullName": "string",
      "avatarUrl": "string | null",
      "role": "OWNER" | "ADMIN" | "MEMBER",
      "joinedAt": "ISO8601",
      "lastReadMessageId": "uuid | null"
    }
  ],
  "owner": { "userId": "uuid", "username": "string", "fullName": "string" } | null,
  "createdBy": { "id": "uuid", "username": "string", "fullName": "string", "avatarUrl": "string | null" }
}
```

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `id` không phải UUID hợp lệ. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 404 | `CONV_NOT_FOUND` | Conversation không tồn tại **HOẶC** caller không phải member (trả cùng code để không leak conversation existence). |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Notes**:
- **Authorization merge với not-found**: nếu conv tồn tại nhưng caller không phải member → trả `404 CONV_NOT_FOUND` (không phải `403 AUTH_FORBIDDEN`). Lý do: tránh tiết lộ "conv này tồn tại" qua error code khác nhau. Đây là pattern chống enumeration — thống nhất với cách `/login` dùng cùng error cho user-not-found và wrong-password.
- **Member list sort (W7)**: BE trả `members[]` sort theo `role DESC, joinedAt ASC` (OWNER đầu → ADMINs cũ nhất → MEMBERs cũ nhất). Sidebar / DetailPage render theo order này không cần sort lại FE.
- **`lastReadMessageId` (v1.4.0-w7-read, W7-D5)**: UUID của message user này đã đánh dấu đọc đến, hoặc `null` nếu chưa bao giờ đánh dấu (user mới join conv / chưa từng mở conv). FE dùng **client-side** để compute "readBy per message" — với mỗi message `m`, tìm members có `lastReadMessageAt(lastReadMessageId) >= m.createdAt` (loại bỏ sender). BE KHÔNG compute readBy list per-message (tránh payload explosion). Update trigger: STOMP `/app/conv.{convId}.read` (xem SOCKET_EVENTS.md §3f) — idempotent forward-only; broadcast `READ_UPDATED` về `/topic/conv.{id}` sau commit (§3.13). Nếu message tương ứng bị hard-delete sau này → DB FK `ON DELETE SET NULL` → field chuyển về null (V2 ít khi xảy ra vì default soft-delete). Xem Migration V12.
- BE implement hiện đã có `ConversationRepository.findByIdWithMembers(UUID)` (JOIN FETCH) — dùng trực tiếp, tránh N+1. W7 cập nhật query include `role` + `joined_at` + LEFT JOIN `users` cho `owner` (có thể NULL sau cascade). **W7-D5**: thêm `last_read_message_id` vào SELECT columns của `conversation_members` join.

---

### PATCH /api/conversations/{id}

**Description** (W7-D1): Cập nhật metadata của GROUP conversation — rename hoặc đổi avatar. Không áp dụng cho ONE_ON_ONE (trả `NOT_GROUP`).

**Auth required**: Yes

**Rate limit**: 20 requests/phút/user.

**Path params**: `id` (UUID).

**Request body**:

```json
{
  "name": "Tên nhóm mới (optional)",
  "avatarFileId": "uuid | null (optional, null = remove avatar)"
}
```

Validation rules:
- **Ít nhất 1 field phải hiện diện** (cả 2 undefined → `VALIDATION_FAILED`).
- `name` (optional): nếu present — 1-100 chars sau trim, non-empty.
- `avatarFileId` (optional, nullable):
  - `undefined` → không đổi avatar.
  - `null` → remove avatar (set `conversations.avatar_file_id = NULL`). KHÔNG xoá `FileRecord` (giữ cho audit; file sẽ bị orphan cleanup sau 30d expiry nếu không attach lại).
  - `uuid` → set avatar mới. Validation giống POST: exists + uploader = caller + MIME image. Errors: `GROUP_AVATAR_NOT_OWNED`, `GROUP_AVATAR_NOT_IMAGE`.

**Authorization**: `OWNER` hoặc `ADMIN` của conversation. MEMBER → `INSUFFICIENT_PERMISSION`.

**Response 200**: updated `ConversationDto` (shape như GET /{id}).

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | Cả 2 field undefined, `name` rỗng sau trim hoặc > 100 chars. |
| 400 | `NOT_GROUP` | Conversation là ONE_ON_ONE. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 403 | `INSUFFICIENT_PERMISSION` | Caller là MEMBER (không phải OWNER/ADMIN). |
| 403 | `GROUP_AVATAR_NOT_OWNED` | `avatarFileId` không exists hoặc không phải caller upload. |
| 404 | `CONV_NOT_FOUND` | Conv không tồn tại hoặc caller không phải member (anti-enum). |
| 415 | `GROUP_AVATAR_NOT_IMAGE` | avatarFileId MIME ≠ image. |
| 429 | `RATE_LIMITED` | Vượt quota. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Broadcast**: sau commit, fire STOMP event `CONVERSATION_UPDATED` qua `/topic/conv.{id}` với `changes: {name?, avatarUrl?}`. Xem SOCKET_EVENTS.md §3.6. System message OPTIONAL (W7 dự kiến ngày 4 implement — placeholder "X đã đổi tên nhóm thành Y" / "X đã đổi ảnh đại diện nhóm").

---

### DELETE /api/conversations/{id}

**Description** (W7-D1): Soft-delete GROUP conversation. Đánh dấu `conversations.deleted_at`, đẩy toàn bộ members ra (hard-delete rows `conversation_members` — giữ messages cho audit). Không áp dụng cho ONE_ON_ONE V1 (trả `NOT_GROUP`).

**Auth required**: Yes

**Rate limit**: 10 requests/phút/user.

**Path params**: `id` (UUID).

**Authorization**: `OWNER` only. ADMIN/MEMBER → `INSUFFICIENT_PERMISSION`. OWNER leave-then-delete: dùng `POST /leave` để transfer ownership tự động, KHÔNG delete.

**Response 204**: No Content.

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `NOT_GROUP` | Conversation là ONE_ON_ONE (V1 không cho delete). |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 403 | `INSUFFICIENT_PERMISSION` | Caller không phải OWNER. |
| 404 | `CONV_NOT_FOUND` | Conv không tồn tại hoặc caller không phải member. |
| 429 | `RATE_LIMITED` | Vượt quota. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Side effects**:
1. Set `conversations.deleted_at = NOW()` (soft delete — row giữ).
2. Hard-delete toàn bộ rows `conversation_members WHERE conversation_id = :id` (caller nhận broadcast rồi mới bị remove khỏi subscription).
3. Detach avatar (set `avatar_file_id = NULL` nếu có — FileRecord giữ lại, orphan cleanup sẽ dọn sau 30d).
4. Broadcast `GROUP_DELETED` qua `/topic/conv.{id}` TRƯỚC khi unsubscribe all members. FE nhận → navigate về `/conversations` + remove conv khỏi sidebar.
5. Messages KHÔNG bị xoá (giữ cho compliance/audit). Nếu UI FE cần load lại conv đã delete → trả 404 (soft-delete filter).

**Query filter rule**: Tất cả endpoint list/read conversations (`GET /api/conversations`, `GET /api/conversations/{id}`, `GET /messages`) PHẢI filter `WHERE conversations.deleted_at IS NULL`.

---

### POST /api/conversations/{id}/members

**Description** (W7-D2 finalized; was W7-D1 draft): Thêm 1 hoặc nhiều user vào GROUP conversation. Batch tối đa 10 mỗi request. **Partial success**: userIds không hợp lệ từng phần (đã là member, user không tồn tại, bị block) → skip với reason, không fail cả batch.

**Auth required**: Yes

**Rate limit**: 20 requests/phút/user.

**Path params**: `id` (UUID).

**Request body**:

```json
{
  "userIds": ["uuid", "uuid", "..."]
}
```

Validation rules:
- `userIds`: bắt buộc array UUID, length 1-10. **Duplicate entries** trong array → BE dedupe silently (không throw).
- `MEMBER_LIMIT_EXCEEDED` check là **all-or-nothing**: BE `SELECT COUNT(*) FROM conversation_members WHERE conversation_id = :id FOR UPDATE` → nếu `currentCount + validToAddCount > 50` → throw ngay, KHÔNG partial insert (tránh state không deterministic: "tôi add 5 người, tại sao chỉ 3 vào?"). Xem WARNINGS W7-3 — RESOLVED với FOR UPDATE lock.
- Các user validation còn lại (exist + active, already-member, blocked) → per-user skip với `reason`, không fail batch.

**Authorization**: `OWNER` hoặc `ADMIN`. MEMBER → `INSUFFICIENT_PERMISSION`.

**Response 201 Created**:

```json
{
  "added": [
    {
      "userId": "uuid",
      "username": "string",
      "fullName": "string",
      "avatarUrl": "string | null",
      "role": "MEMBER",
      "joinedAt": "ISO8601"
    }
  ],
  "skipped": [
    {
      "userId": "uuid",
      "reason": "ALREADY_MEMBER | USER_NOT_FOUND | BLOCKED"
    }
  ]
}
```

Field rules:
- `added`: users thực sự inserted vào `conversation_members`. Có thể rỗng `[]` nếu tất cả đều skip.
- `skipped`: users KHÔNG insert, kèm lý do. Có thể rỗng `[]`. **Luôn non-null** (FE không cần null check).
- `skipped[].reason` enum:
  - `ALREADY_MEMBER`: userId đã có row trong `conversation_members` cho conv này.
  - `USER_NOT_FOUND`: userId không tồn tại trong `users` table hoặc `status != 'active'`. Merge (anti-enumeration) — không phân biệt deleted/never-existed/disabled.
  - `BLOCKED`: user-blocks relation giữa caller/target (V1 chưa wire `user_blocks` table — reserved enum cho forward-compat; hiện tại không fire). Khi wire sẽ check bidirectional.

**Error responses** (cả batch fail):

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | userIds rỗng / > 10 / malformed UUID. |
| 400 | `NOT_GROUP` | Conv là ONE_ON_ONE. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 403 | `INSUFFICIENT_PERMISSION` | Caller là MEMBER. |
| 404 | `CONV_NOT_FOUND` | Conv không tồn tại, đã soft-delete, hoặc caller không phải member (anti-enumeration merge). |
| 409 | `MEMBER_LIMIT_EXCEEDED` | `currentCount + validToAddCount > 50`. Kèm `details: { currentCount: int, attemptedCount: int, limit: 50 }`. |
| 429 | `RATE_LIMITED` | Vượt quota. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

> **Deprecated codes (removed in v1.1.0-w7)**: `GROUP_MEMBER_NOT_FOUND` (404) và `GROUP_MEMBER_ALREADY_IN` (409) — hai codes này từng fail cả batch ở v1.0.0-w7. Nay chuyển sang per-user skip với `reason: USER_NOT_FOUND` / `ALREADY_MEMBER` trong response 201. BE MUST NOT emit hai codes này trong W7-D2+ implementation. Legacy FE code bắt 2 codes này → không bao giờ trigger (safe dead branch).

**Broadcasts** (AFTER_COMMIT — `@TransactionalEventListener`, reuse pattern W4D4):

1. Per added user, fire `MEMBER_ADDED` event lên `/topic/conv.{id}` — xem SOCKET_EVENTS.md §3.7. BE gọi 1 broadcast per user (không gộp 1 frame với array, để shape event nhất quán single-item). Nếu batch add 5 user → 5 frames MEMBER_ADDED.
2. Per added user, `convertAndSendToUser(newUserId, "/queue/conv-added", ConversationSummaryDto)` — **gửi riêng user vừa được add** để FE add conv vào sidebar ngay (user chưa subscribe `/topic/conv.{id}` lúc add → sẽ miss broadcast #1). Xem SOCKET_EVENTS.md §2 "User-specific destinations" + §3.7.

> **Skipped users KHÔNG broadcast** — không có side-effect state.

**Notes**:
- Transaction boundary: validate → `SELECT COUNT(*) FOR UPDATE` (lock) → classify users (add/skip) → INSERT batch → publish events. Tất cả trong 1 `@Transactional`.
- New members default `role='MEMBER'`, `joined_at=NOW()`. OWNER không thể add directly với role khác — dùng PATCH role riêng sau.
- `SELECT COUNT(*) FOR UPDATE` yêu cầu `FOR UPDATE` lock trên rows — Postgres semantics: COUNT aggregate với FOR UPDATE effectively locks all rows trong partition WHERE clause. Alternative: `SELECT 1 FROM conversation_members WHERE conversation_id = :id FOR UPDATE` rồi count ở Java. Chọn cách nào tuỳ BE, chốt race-safety. Xem WARNINGS W7-3.

---

### DELETE /api/conversations/{id}/members/{userId}

**Description** (W7-D2 finalized; was W7-D1 draft): Kick user ra khỏi GROUP conversation. Hard-delete row `conversation_members` (không soft-leave V1). Đồng thời force-unsubscribe user khỏi `/topic/conv.{id}` (V1 chấp nhận client chỉ bị filter qua member-check tại lần SUBSCRIBE tiếp theo — xem Limitations SOCKET_EVENTS.md §8).

**Auth required**: Yes

**Rate limit**: 20 requests/phút/user.

**Path params**: `id` (conversation UUID), `userId` (target user UUID).

**Authorization matrix** (enforce qua service-layer check, matrix chuẩn trong Appendix):
- **OWNER**: có thể kick ADMIN hoặc MEMBER. KHÔNG self-kick (OWNER self-kick qua endpoint này → `CANNOT_KICK_SELF`; dùng `/leave` thay — auto-transfer sẽ xử lý).
- **ADMIN**: chỉ kick được `MEMBER`. Kick ADMIN khác hoặc OWNER → `INSUFFICIENT_PERMISSION`. KHÔNG self-kick.
- **MEMBER**: không kick được ai → `INSUFFICIENT_PERMISSION`.

**Response 204**: No Content.

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `NOT_GROUP` | Conv là ONE_ON_ONE. |
| 400 | `CANNOT_KICK_SELF` | `callerId == userId` (path param). Dùng `/leave` thay. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 403 | `INSUFFICIENT_PERMISSION` | Vi phạm matrix: OWNER kick OWNER (chính mình — đã catch bởi `CANNOT_KICK_SELF` trước), ADMIN kick ADMIN/OWNER, MEMBER kick bất kỳ. |
| 404 | `CONV_NOT_FOUND` | Conv không tồn tại, đã soft-delete, hoặc caller không phải member. |
| 404 | `MEMBER_NOT_FOUND` | Target `userId` không phải member của conv (anti-enumeration merge với "user không tồn tại"). |
| 429 | `RATE_LIMITED` | Vượt quota. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

> **Renamed codes (v1.1.0-w7)**: `CANNOT_REMOVE_SELF` → `CANNOT_KICK_SELF` (rõ semantics); `GROUP_MEMBER_NOT_FOUND` → `MEMBER_NOT_FOUND` (tên ngắn + reuse cho endpoints /role, /transfer-owner); `CANNOT_REMOVE_OWNER` **removed** (merged vào `INSUFFICIENT_PERMISSION` — ADMIN/MEMBER không có quyền kick OWNER; OWNER self-kick catch trước bởi `CANNOT_KICK_SELF`). BE W7-D2 implementation MUST emit tên mới.

**Broadcasts** (AFTER_COMMIT):

1. **Topic broadcast** `/topic/conv.{id}` — event `MEMBER_REMOVED` với `reason: "KICKED"` + `removedBy: { userId, username, fullName }`. Xem SOCKET_EVENTS.md §3.8.
2. **User-specific notify** `convertAndSendToUser(kickedUserId, "/queue/conv-removed", { conversationId, reason: "KICKED" })` — target user nhận event ngắn gọn để (a) remove conv khỏi sidebar, (b) show toast "Bạn đã bị xoá khỏi nhóm {name} bởi {actor}", (c) navigate away nếu đang mở conv đó.

> **Broadcast ordering caveat**: User bị kick có thể nhận topic broadcast MEMBER_REMOVED TRƯỚC hoặc SAU `/queue/conv-removed` — thứ tự không đảm bảo (Spring STOMP 2 channel riêng). FE phải handle idempotent: nếu nhận MEMBER_REMOVED với `removedUserId == currentUser.id` → cũng treat như conv-removed (remove UI). Xem WARNINGS W7-4.

**Notes**:
- Transaction boundary: SELECT target member → auth check matrix → DELETE row → publish event → AFTER_COMMIT broadcast + user-notify. Tất cả trong 1 `@Transactional`.

---

### POST /api/conversations/{id}/leave

**Description** (W7-D2 finalized; was W7-D1 draft): Member tự rời khỏi GROUP conversation. Bất kỳ role nào cũng dùng được (MEMBER/ADMIN/OWNER). Khi OWNER leave → **auto-transfer** ownership theo rule dưới. Nếu OWNER là member duy nhất → **400 CANNOT_LEAVE_EMPTY_GROUP** (phải dùng `DELETE /{id}` thay — V1 không auto-delete empty group).

**Auth required**: Yes

**Rate limit**: 10 requests/phút/user.

**Path params**: `id` (UUID).

**Request body**: Không có (empty body).

**Authorization**: Caller phải là member của conv. Non-member → **404 `CONV_NOT_FOUND`** (anti-enumeration merge với "conv không tồn tại").

**Response 204**: No Content.

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `NOT_GROUP` | Conv là ONE_ON_ONE (V1 không cho leave DIRECT — dùng block hoặc ẩn hội thoại, chưa implement). |
| 400 | `CANNOT_LEAVE_EMPTY_GROUP` | OWNER leave nhưng không có member nào khác trong group (caller là member duy nhất). Dùng `DELETE /{id}` thay. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 404 | `CONV_NOT_FOUND` | Conv không tồn tại, đã soft-delete, hoặc caller không phải member. |
| 429 | `RATE_LIMITED` | Vượt quota. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Auto-transfer logic (OWNER leave only)**:

1. **Race-safe lock**: `SELECT ... FOR UPDATE` trên row của caller trong `conversation_members` (hoặc toàn bộ rows của conv — BE chọn scope). Lock này ngăn concurrent kick/leave race. Xem WARNINGS W7-1 — RESOLVED W7-D2.
2. SELECT other members (EXCLUDE caller), ORDER BY role priority (`ADMIN` trước `MEMBER`, OWNER không trong set vì chỉ có 1 OWNER là caller), THEN `joined_at ASC`. LIMIT 1.
3. **Nếu không có candidate** (caller là member duy nhất) → throw `CANNOT_LEAVE_EMPTY_GROUP` (400). Rollback transaction. Caller phải dùng `DELETE /{id}`. V1 không auto-delete để giữ rõ intent "chủ động xoá group vs rời bỏ".
4. **Nếu có candidate** → transaction atomic:
   - UPDATE caller row: `role = 'MEMBER'` (demote OWNER → MEMBER trước khi delete, để constraint `owner_id` match pattern atomic).
   - UPDATE candidate row: `role = 'OWNER'`.
   - UPDATE `conversations.owner_id = candidate.userId`.
   - DELETE caller row khỏi `conversation_members`.
5. Fire broadcasts AFTER_COMMIT theo thứ tự dưới.

**Broadcasts** (AFTER_COMMIT):

- **Non-OWNER leave (ADMIN hoặc MEMBER)**: chỉ 1 event.
  - `MEMBER_REMOVED` lên `/topic/conv.{id}` với `reason: "LEFT"`, `removedBy: null` (khác kick: removedBy có giá trị). Xem SOCKET_EVENTS.md §3.8.
- **OWNER leave (auto-transfer xảy ra)**: 2 events, fire theo thứ tự:
  1. `OWNER_TRANSFERRED` với `autoTransferred: true` (khác `/transfer-owner` explicit là `false`). Xem §3.10. Fire TRƯỚC MEMBER_REMOVED để FE patch newOwner lên UI rồi mới thấy oldOwner biến mất — tránh flicker "2 OWNER tạm thời".
  2. `MEMBER_REMOVED` với `reason: "LEFT"`, `removedBy: null`.

> **Ordering note (v1.1.0-w7 change)**: v1.0.0-w7 dùng `ROLE_CHANGED` cho auto-transfer. v1.1.0-w7 đổi sang `OWNER_TRANSFERRED` để consistent với explicit transfer endpoint. Phân biệt 2 case bằng field `autoTransferred: boolean`. BE W7-D2 MUST emit OWNER_TRANSFERRED (không phải ROLE_CHANGED) cho auto-transfer path.

> **Không broadcast user-specific** `/queue/conv-removed` cho self-leave — user tự bấm leave, FE đã navigate away sau khi REST 204 về. Nếu có session khác (tab khác) → nhận MEMBER_REMOVED topic broadcast là đủ (reason LEFT → self-user match → remove UI giống pattern W7-4).

**Notes**:
- Sau leave, caller KHÔNG còn subscribe `/topic/conv.{id}` (member check fail ở SUBSCRIBE lần sau; V1 chấp nhận currently-open subscription vẫn nhận event cho đến unsubscribe — xem SOCKET_EVENTS.md §8 Limitations).
- OWNER self-kick qua `DELETE /members/{self-id}` → `CANNOT_KICK_SELF`. Dùng `/leave` thay.
- "Empty group" case documented tại WARNINGS W7-2 — V1 không auto-delete; caller phải chủ động `DELETE /{id}` nếu muốn disband.

---

### PATCH /api/conversations/{id}/members/{userId}/role

**Description** (W7-D2 finalized; was W7-D1 draft): Thay đổi role của 1 member giữa ADMIN ↔ MEMBER. OWNER-only (V1). Promote thành OWNER phải dùng `/transfer-owner` (atomic 2-way swap, flow riêng).

**Auth required**: Yes

**Rate limit**: 20 requests/phút/user.

**Path params**: `id` (conv UUID), `userId` (target UUID).

**Request body**:

```json
{
  "role": "ADMIN" | "MEMBER"
}
```

Validation rules:
- `role`: enum strict `"ADMIN" | "MEMBER"`. Truyền `"OWNER"` → `400 INVALID_ROLE`. Truyền enum khác (không hợp lệ) → `400 VALIDATION_FAILED`.
- Target member hiện tại là OWNER → `403 CANNOT_CHANGE_OWNER_ROLE` (OWNER self-demote cấm qua endpoint này; dùng `/transfer-owner` để đổi chủ).
- **No-op idempotent**: `newRole == target.currentRole` → **200 OK**, trả response bình thường, **KHÔNG broadcast** (tránh noise + log spam khi FE double-click). Không coi là lỗi.

**Authorization**: `OWNER` only (V1). ADMIN/MEMBER → `403 INSUFFICIENT_PERMISSION`.

**Response 200**:

```json
{
  "userId": "uuid",
  "role": "ADMIN | MEMBER",
  "changedAt": "ISO8601",
  "changedBy": {
    "userId": "uuid",
    "username": "string"
  }
}
```

Field rules:
- `role`: new role sau khi đổi (hoặc role hiện tại nếu no-op).
- `changedAt`: ISO8601 timestamp của thao tác (hoặc thời điểm role được set gần nhất nếu no-op — BE chọn, FE không dùng làm state diff).
- `changedBy`: actor (caller = OWNER). Shape minimal (userId + username). Broadcast payload shape fuller (xem §3.9).

> **Response shape change (v1.1.0-w7)**: v1.0.0-w7 trả `{userId, oldRole, newRole, changedAt}`. v1.1.0-w7 bỏ `oldRole` (FE tự biết từ cache), thêm `changedBy` object (nhất quán với các response khác có actor). Broadcast §3.9 vẫn giữ `oldRole` vì receiver không có context.

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `role` không đúng enum (null, empty, invalid string). |
| 400 | `INVALID_ROLE` | `role == "OWNER"` (dùng `/transfer-owner` thay). |
| 400 | `NOT_GROUP` | Conv là ONE_ON_ONE. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 403 | `INSUFFICIENT_PERMISSION` | Caller không phải OWNER. |
| 403 | `CANNOT_CHANGE_OWNER_ROLE` | Target hiện tại là OWNER (including `userId == callerId` khi caller là OWNER tự đổi role của mình). |
| 404 | `CONV_NOT_FOUND` | Conv không tồn tại, đã soft-delete, hoặc caller không phải member. |
| 404 | `MEMBER_NOT_FOUND` | Target userId không phải member. |
| 429 | `RATE_LIMITED` | Vượt quota. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

> **Renamed/refactored codes (v1.1.0-w7)**: `INVALID_ROLE_CHANGE` cũ (gộp 3 sub-case) nay tách:
> - Target = OWNER → `CANNOT_CHANGE_OWNER_ROLE` (403, rõ nghĩa: OWNER protected).
> - No-op (same role) → không throw nữa, trả 200 OK idempotent (thay đổi behavior).
> - `role == "OWNER"` trong body → `INVALID_ROLE` (400, body sai schema).
>
> BE W7-D2 MUST emit tên mới. Legacy code còn throw `INVALID_ROLE_CHANGE` → sửa trong implementation.

**Broadcast**: fire `ROLE_CHANGED` event qua `/topic/conv.{id}` — **CHỈ KHI KHÔNG phải no-op**. Payload: `{conversationId, userId, oldRole, newRole, changedBy}`. Xem SOCKET_EVENTS.md §3.9. No-op → silent (không broadcast).

---

### POST /api/conversations/{id}/transfer-owner

**Description** (W7-D2 finalized; was W7-D1 draft): OWNER chuyển quyền sở hữu cho 1 member khác. Current OWNER → ADMIN (KHÔNG về MEMBER — OWNER cũ giữ quyền quản lý), new user → OWNER. Atomic 2-way swap.

**Auth required**: Yes

**Rate limit**: 10 requests/phút/user.

**Path params**: `id` (UUID).

**Request body**:

```json
{
  "targetUserId": "uuid"
}
```

> **Field rename (v1.1.0-w7)**: `newOwnerId` → `targetUserId` (nhất quán với path `/members/{userId}` convention). BE W7-D2 MUST accept tên mới. v1.0.0-w7 clients chưa có → không backward-compat concern.

Validation rules:
- `targetUserId`: bắt buộc UUID format, phải là current member của conv (`MEMBER_NOT_FOUND` nếu không).
- `targetUserId != callerId` (self-transfer không hợp lệ → `CANNOT_TRANSFER_TO_SELF`).
- Target không được là OWNER hiện tại. V1 invariant chỉ có 1 OWNER tồn tại (CHECK constraint `chk_group_metadata` + flow đảm bảo), nên case này trivially không xảy ra nếu target ≠ caller. Defensive check vẫn có: target role == OWNER → `CANNOT_TRANSFER_TO_SELF` (safe fallback, nghĩa là "target trùng caller").

**Authorization**: `OWNER` hiện tại only. ADMIN/MEMBER → `403 INSUFFICIENT_PERMISSION`.

**Response 200**:

```json
{
  "previousOwner": {
    "userId": "uuid",
    "username": "string",
    "newRole": "ADMIN"
  },
  "newOwner": {
    "userId": "uuid",
    "username": "string"
  }
}
```

Field rules:
- `previousOwner`: caller (OWNER cũ). `newRole` luôn là `"ADMIN"` (hằng số, FE có thể hardcode check).
- `newOwner`: target user được promote. Không cần kèm `newRole: "OWNER"` vì tên field đã nói rõ.

> **Response shape change (v1.1.0-w7)**: v1.0.0-w7 dùng `oldOwner` / `newOwner`. v1.1.0-w7 đổi `oldOwner` → `previousOwner` (rõ hơn, không bị nhầm với "cựu owner đã rời"), và thêm `username` cho human-readable. Broadcast §3.10 payload độc lập — xem event schema.

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | targetUserId malformed UUID hoặc missing. |
| 400 | `NOT_GROUP` | Conv là ONE_ON_ONE. |
| 400 | `CANNOT_TRANSFER_TO_SELF` | `targetUserId == callerId`. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 403 | `INSUFFICIENT_PERMISSION` | Caller không phải OWNER. |
| 404 | `CONV_NOT_FOUND` | Conv không tồn tại, đã soft-delete, hoặc caller không phải member. |
| 404 | `MEMBER_NOT_FOUND` | targetUserId không phải member của conv. |
| 429 | `RATE_LIMITED` | Vượt quota. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

> **Renamed codes (v1.1.0-w7)**: `INVALID_ROLE_CHANGE` (400, khi self-transfer) → `CANNOT_TRANSFER_TO_SELF` (400, rõ intent). `GROUP_MEMBER_NOT_FOUND` → `MEMBER_NOT_FOUND` (đồng bộ với các endpoints /role, /members/{uid}).

**Broadcast**: fire `OWNER_TRANSFERRED` event qua `/topic/conv.{id}` với `autoTransferred: false` (phân biệt với auto-transfer trong `/leave`). Payload: `{conversationId, previousOwner: {userId, username}, newOwner: {userId, username, fullName}, autoTransferred: false}`. Xem SOCKET_EVENTS.md §3.10.

**Notes**:
- Transaction boundary: `SELECT ... FOR UPDATE` cả 2 rows (caller + target) → UPDATE cả 2 role → UPDATE `conversations.owner_id = targetUserId` → publish event → AFTER_COMMIT broadcast. Tất cả 1 `@Transactional`.
- Khác với `/leave` + auto-transfer: `/transfer-owner` giữ previousOwner trong group (downgrade thành ADMIN). `/leave` sẽ remove previousOwner.
- KHÔNG broadcast `ROLE_CHANGED` cho 2 swaps — chỉ 1 event OWNER_TRANSFERRED atomic để FE patch cả 2 members cùng lúc (tránh "2 OWNER" flicker). Xem lý do tại §3.10 SOCKET_EVENTS.md.

---

### Appendix — Group Chat Authorization Matrix (W7, v1.1.0-w7 finalized)

| Action | OWNER | ADMIN | MEMBER |
|--------|-------|-------|--------|
| Create group (POST /conversations type=GROUP) | ✓ (any auth user; becomes OWNER) | ✓ (becomes OWNER) | ✓ (becomes OWNER) |
| Rename group / Change avatar (PATCH /{id}) | ✓ | ✓ | ✗ |
| Delete group (DELETE /{id}) | ✓ | ✗ | ✗ |
| Add members (POST /{id}/members) | ✓ | ✓ | ✗ |
| Remove MEMBER (DELETE /{id}/members/{uid}) | ✓ | ✓ | ✗ |
| Remove ADMIN (DELETE /{id}/members/{uid}) | ✓ | ✗ | ✗ |
| Remove OWNER via kick | ✗ (use `/leave`) | ✗ | ✗ |
| Kick self (DELETE /{id}/members/{self}) | ✗ (`CANNOT_KICK_SELF`, use `/leave`) | ✗ (same) | ✗ (same) |
| Leave group (POST /{id}/leave) | ✓* | ✓ | ✓ |
| Change role ADMIN↔MEMBER (PATCH /{id}/members/{uid}/role) | ✓ | ✗ | ✗ |
| Change role of OWNER (target=OWNER) | ✗ (`CANNOT_CHANGE_OWNER_ROLE`) | ✗ | ✗ |
| Transfer ownership (POST /{id}/transfer-owner) | ✓ | ✗ | ✗ |
| View members (GET /{id}) | ✓ | ✓ | ✓ |
| Send/Edit/Delete own messages | ✓ | ✓ | ✓ |

\* OWNER leave → auto-transfer (oldest-ADMIN first, fallback oldest-MEMBER). Nếu OWNER là member duy nhất → `CANNOT_LEAVE_EMPTY_GROUP` (phải `DELETE /{id}` để disband).

**Error code → HTTP status cheat sheet** (từ 5 endpoints trên):

| Code | HTTP | Endpoint(s) |
|------|------|-------------|
| `VALIDATION_FAILED` | 400 | all (body/path malformed) |
| `NOT_GROUP` | 400 | all (conv type = ONE_ON_ONE) |
| `INVALID_ROLE` | 400 | PATCH /role (role=OWNER in body) |
| `CANNOT_KICK_SELF` | 400 | DELETE /members/{uid} |
| `CANNOT_TRANSFER_TO_SELF` | 400 | POST /transfer-owner |
| `CANNOT_LEAVE_EMPTY_GROUP` | 400 | POST /leave (OWNER alone) |
| `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | 401 | all |
| `INSUFFICIENT_PERMISSION` | 403 | all (role không đủ) |
| `CANNOT_CHANGE_OWNER_ROLE` | 403 | PATCH /role (target=OWNER) |
| `CONV_NOT_FOUND` | 404 | all (anti-enumeration merge) |
| `MEMBER_NOT_FOUND` | 404 | DELETE /members/{uid}, PATCH /role, POST /transfer-owner |
| `MEMBER_LIMIT_EXCEEDED` | 409 | POST /members (>50 cap) |
| `RATE_LIMITED` | 429 | all |
| `INTERNAL_ERROR` | 500 | all |

> **Skipped reasons** (response 201 của POST /members) không phải error codes — là status per-user trong batch: `ALREADY_MEMBER`, `USER_NOT_FOUND`, `BLOCKED`.

**BE implementation note**: Role enum PHẢI embed permission methods để tránh scatter if-else khắp service layer:

```java
public enum MemberRole {
    OWNER, ADMIN, MEMBER;

    public boolean canRename() { return this != MEMBER; }
    public boolean canDeleteGroup() { return this == OWNER; }
    public boolean canAddMembers() { return this != MEMBER; }
    public boolean canRemoveMember(MemberRole targetRole) {
        if (this == OWNER) return targetRole != OWNER; // OWNER không remove OWNER (self)
        if (this == ADMIN) return targetRole == MEMBER; // ADMIN chỉ remove MEMBER
        return false;
    }
    public boolean canChangeRole() { return this == OWNER; }
    public boolean canTransferOwnership() { return this == OWNER; }
}
```

FE dùng TypeScript tương đương (tính các boolean này từ `members[].role` sau khi fetch conv detail) để disable/hide actions trong UI dropdown.

---

### GET /api/users/search

**Description**: Tìm user theo username hoặc fullName, dùng cho flow "tạo conversation mới" (FE cần lookup target user).

**Auth required**: Yes

**Rate limit**: 30 requests/phút/user (chống abuse enumerate user list).

**Query params**:

| Param | Kiểu | Bắt buộc | Mô tả |
|-------|------|----------|------|
| `q` | string | Yes | Query string. Trim rồi check: tối thiểu **2 ký tự sau trim**, tối đa 50. |
| `limit` | integer 1..20 | No (default 10) | Số kết quả tối đa. |

**Response 200**:

```json
[
  {
    "id": "7c1a7c16-4c72-4a2a-a8f6-def444555666",
    "username": "bob",
    "fullName": "Bob Tran",
    "avatarUrl": null
  }
]
```

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `q` rỗng, < 2 ký tự sau trim, > 50 ký tự; hoặc `limit` ngoài range. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 429 | `RATE_LIMITED` | Vượt 30/phút/user. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Notes**:
- Search strategy: `LOWER(username) LIKE LOWER(:q) || '%' OR LOWER(full_name) LIKE '%' || LOWER(:q) || '%'` (username prefix-match để dùng index `users_username_key`; fullName substring-match chấp nhận scan vì dataset V1 nhỏ). Nếu tương lai scale → thêm GIN trigram index hoặc elasticsearch.
- **Luôn exclude caller** khỏi results (`WHERE id <> :callerId`).
- **Luôn exclude users `status != 'active'`** (suspended, deleted).
- **Luôn exclude users đã block caller hoặc bị caller block** (khi `user_blocks` wire; V1 chấp nhận tạm chưa filter — documented).
- Kết quả sort theo `username ASC` để ổn định (không sort theo relevance ở V1).
- Response KHÔNG bao gồm `email` (privacy — email là PII, chỉ owner của account xem được qua endpoint self-profile).

---

### GET /api/users/{id}

**Description**: Lấy thông tin public của 1 user theo UUID. Dùng ở ConversationDetailPage (khi cần lookup thêm thông tin member peer) hoặc cho future "user profile modal".

**Auth required**: Yes

**Rate limit**: không áp riêng — chung với global per-user request budget (V1 chưa enforce).

**Path params**:

| Param | Kiểu | Mô tả |
|-------|------|------|
| `id` | UUID | ID của user cần xem. |

**Response 200**:

```json
{
  "id": "7c1a7c16-4c72-4a2a-a8f6-def444555666",
  "username": "bob",
  "fullName": "Bob Tran",
  "avatarUrl": null
}
```

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `id` không phải UUID hợp lệ. *(V1 có thể bubble 500 nếu GlobalExceptionHandler chưa map `MethodArgumentTypeMismatchException`; documented warning.)* |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 404 | `USER_NOT_FOUND` | User không tồn tại **HOẶC** `status != 'active'` (suspended, deleted). Merge để không leak existence. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Notes**:
- Response shape **dùng lại `UserSearchDto`** — cùng 4 field (id, username, fullName, avatarUrl). KHÔNG expose `email` (PII), KHÔNG expose `status` (internal), KHÔNG expose `lastSeenAt` (V1 privacy — xem WARNINGS.md mục "last_seen_at chưa expose").
- Merge `USER_NOT_FOUND` cho cả case không tồn tại + inactive để tránh enumeration (giống pattern `CONV_NOT_FOUND` ở GET detail).
- Endpoint hiện **không filter** user đã block caller / bị caller block — V1 chấp nhận (tương tự `/api/users/search`), wire khi `user_blocks` table ra đời.
- Không expose quan hệ conversation giữa caller và target — FE tự tính từ `/api/conversations` list nếu cần.

---

---

## Messages (REST parts)

> Chú ý: Gửi tin nhắn text đi qua WebSocket, không phải REST. Xem `SOCKET_EVENTS.md`.
> REST chỉ cho: đọc lịch sử, search, pin/unpin, upload file.

(reviewer sẽ thêm khi phase messaging bắt đầu)

---

## Messages API (v0.6.0-messages-rest — W4-D1, updated v1.2.0-w7-system for SYSTEM messages)

Base URL: `/api/conversations/{convId}/messages`

Auth: Bearer JWT bắt buộc cho tất cả endpoints.

### MessageDto shape

> **Historical note**: Đây là shape gốc W4-D1. Xem shape **finalized** (với `attachments` W6-D1 và `systemEventType` / `systemMetadata` W7-D4) ở mục **"MessageDto — finalized shape (v1.2.0-w7-system)"** bên dưới. Shape đầy đủ là nguồn duy nhất FE/BE dùng.

```json
{
  "id": "uuid",
  "conversationId": "uuid",
  "sender": {
    "id": "uuid",
    "username": "string",
    "fullName": "string",
    "avatarUrl": "string|null"
  },
  "type": "TEXT|IMAGE|FILE|SYSTEM",
  "content": "string",
  "replyToMessage": {
    "id": "uuid",
    "senderName": "string",
    "contentPreview": "string (max 100 chars + '...' nếu truncated) | null nếu source đã bị xóa",
    "deletedAt": "ISO8601 | null — non-null nếu source message đã bị soft delete"
  } | null,
  "editedAt": "ISO8601|null",
  "createdAt": "ISO8601 UTC"
}
```

### POST /api/conversations/{convId}/messages

Gửi tin nhắn vào conversation.

> **Deprecated (ADR-016, Post-W4)**: Endpoint này vẫn hoạt động nhưng FE **không còn dùng để gửi tin nhắn real-time**. Dùng STOMP `/app/conv.{id}.message` thay thế (xem `SOCKET_EVENTS.md` mục 3b). Endpoint giữ lại cho:
> - Batch import (migration tool, CSV uploader).
> - Bot API / 3rd-party integration (HTTP token-based auth dễ hơn STOMP).
> - Testing (integration test viết với HTTP dễ hơn STOMP frame mocking).
> - Fallback khi STOMP bất khả dụng dài hạn (infra outage) — hiện chưa wire FE fallback logic V1.
>
> Shape response không đổi — reviewer sẽ không breaking change endpoint này.

**Request:**
```json
{
  "content": "string (1-5000 chars, required)",
  "type": "TEXT|IMAGE|FILE|SYSTEM (optional, default TEXT)",
  "replyToMessageId": "uuid (optional)"
}
```

**Response 201:** MessageDto

**Errors:**
| Code | HTTP | Mô tả |
|------|------|-------|
| VALIDATION_FAILED | 400 | content trống/quá dài, replyToMessageId không tồn tại trong conv |
| AUTH_REQUIRED | 401 | Không có JWT |
| CONV_NOT_FOUND | 404 | Conversation không tồn tại hoặc user không phải thành viên (anti-enumeration) |
| RATE_LIMITED | 429 | Quá 30 messages/phút. details: `{ retryAfterSeconds: 60 }` |

### GET /api/conversations/{convId}/messages

Lấy lịch sử tin nhắn với cursor-based pagination.

**Query params:**
| Param | Type | Default | Mô tả |
|-------|------|---------|-------|
| cursor | string (ISO8601) | null | Lấy messages có createdAt **<** cursor, ORDER DESC reversed to ASC. Null = trang đầu (newest) |
| after | string (ISO8601) | null | Lấy messages có createdAt **>** after, ORDER ASC. Dùng cho reconnect catch-up |
| limit | int | 50 | Số messages per page (1-100) |

**`after` param — forward pagination (W5-D4):**
- Lấy messages SAU timestamp này, ORDER **ASC** (cũ → mới).
- **Mutex với `cursor`**: không thể dùng cùng nhau → `400 VALIDATION_FAILED`.
- Dùng cho **reconnect catch-up**: client truyền `createdAt` của message mới nhất trong cache để lấy về tất cả messages đã bị miss trong khi offline.
- **Include cả deleted messages** (content=null, deletedAt set) — FE cần biết placeholder state của message đã xóa khi catch-up.
- `nextCursor` trong response = `createdAt` của item **mới nhất** (last item) — dùng để tiếp tục paginate forward nếu missed nhiều messages.

**Response 200:**
```json
{
  "items": [MessageDto],
  "hasMore": true,
  "nextCursor": "ISO8601 UTC string | null"
}
```

Note:
- `items` sorted **ASC** (cũ nhất đến mới nhất) — dù dùng `cursor` hay `after` hay không có tham số.
- `nextCursor` khi dùng `cursor` (backward) = `createdAt` của item **cũ nhất** trong page (index 0 sau reverse).
- `nextCursor` khi dùng `after` (forward) = `createdAt` của item **mới nhất** trong page (last item).
- `nextCursor` null khi `hasMore=false`.

**Errors:**
| Code | HTTP | Mô tả |
|------|------|-------|
| VALIDATION_FAILED | 400 | limit ngoài range 1-100, cursor/after không đúng ISO8601, hoặc cursor và after dùng cùng nhau |
| AUTH_REQUIRED | 401 | Không có JWT |
| CONV_NOT_FOUND | 404 | Conversation không tồn tại hoặc user không phải thành viên |

---

## Files Management (v0.9.5-files-extended — W6-D4-extend)

> **Phase**: Tuần 6 — File upload + attachments (mở rộng MIME types W6-D4).
> **Scope V1**: Local disk storage + StorageService interface (ADR-019, ARCHITECTURE.md §7.7). V2 migrate S3.
> **Allowed MIME**: Xem mục "Allowed MIME types (v0.9.5)" bên dưới — Group A (images) + Group B (office docs, text, archives).
> **Max size**: 20MB per file.
> **Expiry**: 30 ngày kể từ `createdAt`. Daily cleanup job xoá file disk + mark row `expired`. Xem ADR-019 & §7.8.
> **Auth**: Bearer JWT bắt buộc cho tất cả endpoints.

### Allowed MIME types (v0.9.5)

Group A — Images (gallery-capable, 1–5 per message):
- image/jpeg  (.jpg, .jpeg)
- image/png   (.png)
- image/webp  (.webp)
- image/gif   (.gif)

Group B — Non-image documents & archives (exactly 1 per message):
- application/pdf (.pdf)
- application/vnd.openxmlformats-officedocument.wordprocessingml.document (.docx)
- application/vnd.openxmlformats-officedocument.spreadsheetml.sheet (.xlsx)
- application/vnd.openxmlformats-officedocument.presentationml.presentation (.pptx)
- application/msword (.doc — legacy Word)
- application/vnd.ms-excel (.xls — legacy Excel)
- application/vnd.ms-powerpoint (.ppt — legacy PowerPoint)
- text/plain (.txt)
- application/zip (.zip)
- application/x-7z-compressed (.7z)

Blacklist — Never allow (even if future expand):
- Executables: .exe, .dll, .bat, .sh, .cmd, .ps1, .msi
- Scripts: .js (standalone), .vbs, .py
- Markup/XSS vectors: image/svg+xml, text/html, application/xhtml+xml

**Mixing rules:**
- Group A + Group A: OK if total ≤ 5
- Group B: exactly 1 file, alone (no mixing with Group A or other Group B)
- Group A + Group B together: REJECT → MSG_ATTACHMENTS_MIXED
- 2+ Group B files: REJECT → MSG_ATTACHMENTS_MIXED

**Error codes:**
- MSG_ATTACHMENTS_TOO_MANY: > 5 images in Group A
- MSG_ATTACHMENTS_MIXED: mixing groups, or > 1 non-image file

### FileDto shape (dùng chung mọi nơi — response upload, attachment trong MessageDto)

```json
{
  "id": "uuid (server-generated)",
  "mime": "string (allowed MIME)",
  "name": "string (original name, sanitized — max 255 chars)",
  "size": "long (bytes, 1..20971520)",
  "url": "string (GET /api/files/{id})",
  "thumbUrl": "string | null (GET /api/files/{id}/thumb — null nếu không phải image)",
  "iconType": "IMAGE | PDF | WORD | EXCEL | POWERPOINT | TEXT | ARCHIVE | GENERIC",
  "expiresAt": "ISO8601 UTC (createdAt + 30 ngày)"
}
```

Field notes:
- `id`: UUID v4 server-generated. Client KHÔNG tự sinh id cho file (khác tempId của message).
- `name`: `originalName` đã sanitize (strip path separator, control chars). Đây là tên hiển thị cho user download, KHÔNG phải filesystem path. Path nội bộ là `{base}/{yyyy}/{mm}/{uuid}.{ext}` — không bao giờ expose.
- `url`: path relative (FE prefix với base API URL). Authorization check mỗi lần GET.
- `thumbUrl`: `null` cho PDF / non-image. Cho image: trả về endpoint `/api/files/{id}/thumb` luôn (không pre-compute thumbnail tồn tại hay không — server lazy-generate).
- `size`: bytes, client dùng để hiển thị (ví dụ "2.3 MB").
- `iconType`: enum server-computed từ MIME. FE dùng để chọn icon render (không dùng MIME trực tiếp ở FE để dễ extend whitelist sau này mà không cần đổi FE). Mapping:
  - `IMAGE` → image/* (jpeg, png, webp, gif)
  - `PDF` → application/pdf
  - `WORD` → .doc, .docx
  - `EXCEL` → .xls, .xlsx
  - `POWERPOINT` → .ppt, .pptx
  - `TEXT` → text/plain
  - `ARCHIVE` → .zip, .7z
  - `GENERIC` → fallback nếu MIME match whitelist nhưng chưa có mapping cụ thể (defensive — KHÔNG xảy ra V1, có để future-proof)
- `expiresAt`: FE dùng để warn user trước expiry (ví dụ hiển thị "File sẽ hết hạn trong 3 ngày").

---

### POST /api/files/upload

**Description**: Upload 1 file lên server. Trả về `FileDto` mà client có thể gắn vào message attachment khi gửi tin nhắn.

**Auth required**: Yes

**Rate limit**: 20 uploads/phút/user (Redis `rate:file-upload:{userId}` INCR + EX 60, pattern ADR-005).

**Content-Type**: `multipart/form-data`

**Request** (form fields):

| Field | Type | Required | Mô tả |
|-------|------|----------|------|
| `file` | File | Yes | Binary file. Max 20MB. MIME phải trong whitelist. |

> Không có field nào khác. Không có `convId` hay `messageId` — file được upload "standalone" rồi mới attach vào message khi gửi `/app/conv.{id}.message`. Orphan file (upload nhưng không attach) sẽ bị cleanup sau 1h (xem W6-4 WARNINGS).

**Response 201** — `FileDto`:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "mime": "image/jpeg",
  "name": "vacation.jpg",
  "size": 2457600,
  "url": "/api/files/550e8400-e29b-41d4-a716-446655440000",
  "thumbUrl": "/api/files/550e8400-e29b-41d4-a716-446655440000/thumb",
  "iconType": "IMAGE",
  "expiresAt": "2026-05-20T10:00:00Z"
}
```

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `FILE_EMPTY` | Multipart field `file` thiếu hoặc size = 0 bytes. |
| 400 | `VALIDATION_FAILED` | `file` field sai shape (không phải multipart part). |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 413 | `FILE_TOO_LARGE` | File > 20 MB. Kèm `details.maxBytes: 20971520` + `details.actualBytes`. |
| 415 | `FILE_TYPE_NOT_ALLOWED` | MIME không nằm trong whitelist (xem mục "Allowed MIME types (v0.9.5)" — Group A images + Group B docs/archives). Kèm `details.allowedMimes` + `details.actualMime`. |
| 415 | `MIME_MISMATCH` | MIME detect qua magic bytes (Apache Tika) khác với `Content-Type` header. Attacker đổi extension/header để bypass whitelist. Kèm `details.declaredMime` + `details.detectedMime`. |
| 429 | `RATE_LIMITED` | Vượt 20/phút/user. Kèm `details.retryAfterSeconds`. |
| 500 | `STORAGE_FAILED` | Lỗi ghi disk (disk full, permission denied, I/O error). Log chi tiết server-side, client chỉ nhận generic message. |
| 500 | `INTERNAL_ERROR` | Lỗi khác (DB, thumbnail generation fail, v.v.). |

**Notes — Security (BLOCKING check cho reviewer)**:
- **Path traversal**: `originalName` **KHÔNG BAO GIỜ** được dùng làm phần của filesystem path. Path nội bộ = `{base}/{yyyy}/{mm}/{fileId}.{ext}` (ext lấy từ MIME whitelist, không từ original filename). Tham khảo W6-1 trong WARNINGS.md.
- **MIME spoofing**: BẮT BUỘC verify MIME qua Apache Tika `detect(InputStream)` đọc magic bytes — KHÔNG trust `Content-Type` header từ client. Nếu declared MIME (từ header) khác detected MIME → `MIME_MISMATCH`. Xem W6-2.
- **Executable protection**: file `.exe`, `.sh`, `.bat`, … đều fail `FILE_TYPE_NOT_ALLOWED` vì MIME không trong whitelist. Nhưng defense-in-depth: serve `Content-Disposition: attachment` (hoặc `inline` với `X-Content-Type-Options: nosniff`) để browser không auto-execute.
- **Disk quota V1**: KHÔNG có per-user quota. Acceptable V1 (<1000 users, 30-day expiry), V2 cần implement. Xem W6-3.
- **Orphan cleanup**: file upload nhưng 1 tiếng không được attach vào message → cleanup job xoá. File đã attach → expire sau 30 ngày theo `expiresAt`. Xem W6-4.

**Notes — Implementation**:
- Size limit check ở 2 level: (1) Spring `spring.servlet.multipart.max-file-size: 20MB` reject ở HTTP layer, (2) service-level double-check (defense-in-depth nếu misconfig).
- Response 201 (Created) — không phải 200 — theo REST convention.
- `expiresAt` = `now() + 30 ngày` UTC. Server là authority về expiry, client không được override.

---

### GET /api/files/{id}

**Description**: Download / stream file content theo `id`. Dùng cho inline display (browser render image trực tiếp trong `<img src>`) hoặc trigger download (PDF).

**Auth required**: Yes (JWT trong header).

**Rate limit**: Không áp riêng (global per-user budget, V1 chưa enforce).

**Path params**:

| Param | Type | Mô tả |
|-------|------|------|
| `id` | UUID | File id từ `FileDto.id`. |

**Authorization logic** (BẮT BUỘC check theo thứ tự):

1. Caller là `uploader_id` của file → 200 serve.
2. Caller là member của conversation chứa message có attachment `file_id = id` (query `message_attachments` JOIN `messages` JOIN `conversation_members`) → 200 serve.
3. Không thoả 1 hoặc 2 → `404 NOT_FOUND` (merge với "file không tồn tại" — anti-enumeration).

**Response 200**:

- `Content-Type`: MIME thật của file (từ DB `files.mime`).
- `Content-Disposition`: `inline; filename="{originalName sanitized}"` cho image; `inline; filename="{originalName}"` cho PDF (trình duyệt PDF viewer sẽ render).
- `Cache-Control`: `private, max-age=604800` (7 ngày — file immutable, cho phép cache client).
- `X-Content-Type-Options: nosniff` — chống MIME sniffing attack.
- `ETag`: dùng `file.id` (immutable).
- Body: binary file content.

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `id` không phải UUID hợp lệ. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 404 | `NOT_FOUND` | File không tồn tại **HOẶC** caller không có quyền (không uploader + không member của conv chứa message attach file này) **HOẶC** file đã expire (`expires_at < now()`). Merge tất cả để chống enumeration. |
| 404 | `FILE_PHYSICALLY_DELETED` | File record còn trong DB (vì có message attachment FK) nhưng physical file đã bị cleanup job xóa khỏi disk. Xảy ra khi `expired=true` + `stillAttached=true`. FE nên xử lý gracefully (show placeholder). |
| 500 | `INTERNAL_ERROR` | I/O error không mong đợi (disk full, permission denied, v.v.). Phân biệt với `FILE_PHYSICALLY_DELETED` ở trên. |

**Notes — V1 anti-enumeration**:
- **404 cho cả expired và not-found**: không trả `410 GONE` (sẽ tiết lộ "file từng tồn tại"). Consumer chỉ biết "tôi không có quyền xem file này hoặc nó không tồn tại" — không phân biệt được.
- V2 có thể cân nhắc tách `FILE_EXPIRED` (410) nếu UX cần warning rõ hơn.

**Notes — Performance**:
- Stream file qua `ResponseEntity<Resource>` với `InputStreamResource` hoặc `FileSystemResource`. KHÔNG load toàn file vào memory (`byte[]`) — OOM với file 20MB × concurrent.
- Range request (`Range: bytes=...`) — V1 chưa support (Spring Resource handler có sẵn nhưng cần wire). Defer V2 khi có video.

---

### GET /api/files/{id}/thumb

**Description**: Trả về thumbnail 200×200 (cover) của image. Dùng cho preview trong chat bubble, list view.

**Auth required**: Yes.

**Path params**: `id` (UUID, giống GET /api/files/{id}).

**Authorization**: cùng logic với GET /api/files/{id} (uploader OR member of conv containing message with this attachment).

**Response 200**:

- `Content-Type`: `image/jpeg` (thumbnail luôn JPEG bất kể source — tối ưu size).
- `Cache-Control`: `private, max-age=604800`.
- `ETag`: `file.id + "-thumb"`.
- Body: 200×200 JPEG (cover fit — giữ aspect ratio, crop phần dư).

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `id` không phải UUID hợp lệ. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 404 | `NOT_FOUND` | File không tồn tại / không có quyền / đã expire / **KHÔNG PHẢI image** (PDF, docx, xlsx, pptx, doc, xls, ppt, txt, zip, 7z không có thumbnail → 404 giống "không tồn tại" để consistent — FE dùng `iconType` chọn icon static). |
| 500 | `INTERNAL_ERROR` | Lỗi generate thumbnail (Thumbnailator exception, disk I/O). |

**Notes**:
- **Lazy generation + cache**: lần GET đầu tiên cho 1 file image → server generate thumbnail bằng `Thumbnailator.of(src).size(200, 200).outputFormat("jpg").toFile(thumbPath)`, lưu vào `{base}/{yyyy}/{mm}/{uuid}_thumb.jpg`. Lần sau serve từ cache.
- **PDF**: trả 404 (không generate PDF preview V1). FE fallback hiển thị icon PDF generic.
- **GIF animated**: thumbnail chỉ lấy frame đầu (Thumbnailator mặc định) — acceptable V1.

---

### MessageDto — thêm field `attachments` (W6-D1)

> **Breaking change for MessageDto**: Thêm field `attachments` vào mọi MessageDto (REST response, STOMP ACK, STOMP broadcast MESSAGE_CREATED). `content` trở thành nullable khi có attachments (1 trong 2 phải non-null). BE + FE phải deploy đồng bộ.

**Updated MessageDto shape (W6-D1)**:

```json
{
  "id": "uuid",
  "conversationId": "uuid",
  "sender": {
    "id": "uuid",
    "username": "string",
    "fullName": "string",
    "avatarUrl": "string|null"
  },
  "type": "TEXT|IMAGE|FILE|SYSTEM",
  "content": "string | null",
  "attachments": [
    {
      "id": "uuid",
      "mime": "string",
      "name": "string",
      "size": "long",
      "url": "string",
      "thumbUrl": "string | null",
      "iconType": "IMAGE | PDF | WORD | EXCEL | POWERPOINT | TEXT | ARCHIVE | GENERIC",
      "expiresAt": "ISO8601"
    }
  ],
  "replyToMessage": { "id", "senderName", "contentPreview", "deletedAt" } | null,
  "editedAt": "ISO8601|null",
  "deletedAt": "ISO8601|null",
  "deletedBy": "uuid|null",
  "createdAt": "ISO8601 UTC"
}
```

Field rules:
- `content`: **nullable** từ W6-D1. Trước W6: required 1-5000 chars. Sau W6: required nếu `attachments` rỗng; có thể `null` nếu `attachments` non-empty.
- `attachments`: array (có thể rỗng `[]`). **Không bao giờ `null`** — luôn là array (FE không phải check null).
- `type`: V1 mapping từ attachments mix:
  - `attachments` rỗng + `content` non-null → `TEXT`.
  - `attachments` có 1+ image → `IMAGE`.
  - `attachments` có 1 PDF → `FILE`.
  - `SYSTEM` do server phát (không có attachment).

**BE mapper note** (BLOCKING): `MessageMapper.toDto` phải load `attachments` qua `JOIN message_attachments ORDER BY display_order ASC`, map từng row sang `FileDto` reuse `FileMapper`. Khi message `deletedAt != null` → strip cả `content=null` (đã có từ W5-D3) VÀ `attachments=[]` (W6-D1 mới). Lý do: attachment cũng là "content" đã xoá, không leak URL sau delete.

---

### MessageDto — finalized shape (v1.2.0-w7-system, W7-D4)

> **Additive change** (non-breaking cho TEXT path): thêm 2 optional field `systemEventType` + `systemMetadata` vào MessageDto để support SYSTEM messages (in-chat system notifications cho group events: create, add/remove/leave member, role change, owner transfer, rename). Hai field này `null` với mọi message `type != 'SYSTEM'`. BE + FE deploy đồng bộ — BE không broadcast SYSTEM frame cho tới khi FE có handler; FE luôn tolerant 2 field mới = null.

**Finalized MessageDto shape** (áp dụng cho MỌI endpoint/event serialize message — REST list, REST POST response, STOMP ACK SEND, STOMP broadcast `MESSAGE_CREATED`, STOMP broadcast `MESSAGE_UPDATED` reuse minimal, STOMP ACK EDIT, STOMP ACK DELETE (chỉ echo id+metadata)):

```json
{
  "id": "uuid",
  "conversationId": "uuid",
  "sender": {
    "id": "uuid",
    "username": "string",
    "fullName": "string",
    "avatarUrl": "string|null"
  } | null,
  "type": "TEXT | IMAGE | FILE | SYSTEM",
  "content": "string | null",
  "attachments": [ FileDto, ... ],
  "replyToMessage": {
    "id": "uuid",
    "senderName": "string",
    "contentPreview": "string | null",
    "deletedAt": "ISO8601 | null"
  } | null,
  "editedAt": "ISO8601 | null",
  "deletedAt": "ISO8601 | null",
  "deletedBy": "uuid | null",
  "systemEventType": "GROUP_CREATED | MEMBER_ADDED | MEMBER_REMOVED | MEMBER_LEFT | ROLE_PROMOTED | ROLE_DEMOTED | OWNER_TRANSFERRED | GROUP_RENAMED | null",
  "systemMetadata": { ... } | null,
  "reactions": [ ReactionAggregateDto, ... ],
  "createdAt": "ISO8601 UTC"
}
```

**Field `reactions` (v1.5.0-w8-reactions)**: `Array<ReactionAggregateDto>` — aggregate reactions cho message này. Empty array `[]` nếu không có reaction, nếu message bị soft-delete (`deletedAt != null`), hoặc nếu `type == 'SYSTEM'`. **Luôn là array, không bao giờ `null`** — FE không phải null-check. Sort: `count DESC, emoji ASC` (stable fallback). Xem shape `ReactionAggregateDto` bên dưới.

**Rule về `type`**:
- `type == 'TEXT' | 'IMAGE' | 'FILE'` → user-generated message. `systemEventType` và `systemMetadata` PHẢI `null`. `sender` non-null.
- `type == 'SYSTEM'` → server-generated system message. `content` PHẢI là empty string `""` (KHÔNG null — FE không cần `content ?? ''` check, đồng bộ với render từ metadata). `systemEventType` non-null (1 trong 8 enum). `systemMetadata` non-null JSONB object theo shape per-event-type bên dưới. `attachments` = `[]` (empty array — SYSTEM không có attachment V1). `replyToMessage` = `null` (SYSTEM không reply). `editedAt` = `null` (SYSTEM không editable — xem §Validation). `deletedAt` = `null` thông thường (xem §Validation cho delete rules). `sender`:
  - V1 choice: `sender` = `null` (SYSTEM không thuộc về user nào — actor nằm trong `systemMetadata.actorId`/`actorName`). FE render "avatar system icon" fallback khi `sender == null && type == 'SYSTEM'`.
  - *Rationale*: tránh phải maintain "system user" UUID đặc biệt trong `users` table. `sender_id` column trong DB nullable cho SYSTEM (xem Migration V10).

**`systemEventType` enum** (8 values, W7-D4):

| Enum | Trigger (service method commit xong) | `systemMetadata` shape |
|------|--------------------------------------|------------------------|
| `GROUP_CREATED` | `createGroup` (POST /api/conversations type=GROUP) | `{actorId, actorName}` |
| `MEMBER_ADDED` | `addMembers` (POST /api/conversations/{id}/members) — **1 SYSTEM msg per user added**; KHÔNG tạo cho user trong `skipped[]` | `{actorId, actorName, targetId, targetName}` |
| `MEMBER_REMOVED` | `removeMember` (DELETE /api/conversations/{id}/members/{uid}, KICKED) | `{actorId, actorName, targetId, targetName}` |
| `MEMBER_LEFT` | `leaveGroup` (POST /api/conversations/{id}/leave) — non-OWNER path, hoặc OWNER leave sau khi auto-transfer xong | `{actorId, actorName}` (actor == target khi LEFT — không cần target fields) |
| `ROLE_PROMOTED` | `changeRole` (PATCH /role) với `newRole='ADMIN'` và `oldRole='MEMBER'` | `{actorId, actorName, targetId, targetName}` |
| `ROLE_DEMOTED` | `changeRole` với `newRole='MEMBER'` và `oldRole='ADMIN'` | `{actorId, actorName, targetId, targetName}` |
| `OWNER_TRANSFERRED` | (a) `transferOwner` explicit (POST /transfer-owner) → `autoTransferred: false`; (b) `leaveGroup` OWNER path auto-transfer → `autoTransferred: true` | `{actorId, actorName, targetId, targetName, autoTransferred}` |
| `GROUP_RENAMED` | `updateGroupInfo` (PATCH /api/conversations/{id}) khi `name` thực sự đổi (trim-compare `oldName != newName`) | `{actorId, actorName, oldValue, newValue}` |

**`systemMetadata` field dictionary** (JSONB object — field nào không apply cho event type thì bỏ hẳn khỏi JSON, KHÔNG để `null`):

| Field | Type | Khi nào có | Ý nghĩa |
|-------|------|-----------|---------|
| `actorId` | `uuid` | Luôn luôn | User thực hiện action. Bắt buộc cho MỌI system event. |
| `actorName` | `string` | Luôn luôn | Snapshot của `actor.fullName` tại thời điểm event fire — để render đúng cả khi actor đổi tên/xoá account sau đó. |
| `targetId` | `uuid` | `MEMBER_ADDED`, `MEMBER_REMOVED`, `ROLE_PROMOTED`, `ROLE_DEMOTED`, `OWNER_TRANSFERRED` | User bị action. Với `MEMBER_LEFT` và `GROUP_CREATED` / `GROUP_RENAMED` không có target — bỏ field. |
| `targetName` | `string` | Cùng điều kiện với `targetId` | Snapshot `target.fullName` tại thời điểm event. |
| `oldValue` | `string` | `GROUP_RENAMED` | Tên cũ của group (trim-ed). |
| `newValue` | `string` | `GROUP_RENAMED` | Tên mới của group (trim-ed). |
| `autoTransferred` | `boolean` | `OWNER_TRANSFERRED` | `true` = auto-transfer từ OWNER leave; `false` = explicit /transfer-owner. FE dùng để chọn copy khác nhau. |

**Ví dụ** (SYSTEM message trả về từ `GET /api/conversations/{id}/messages`):

```json
{
  "id": "a6f2...",
  "conversationId": "c1...",
  "sender": null,
  "type": "SYSTEM",
  "content": "",
  "attachments": [],
  "replyToMessage": null,
  "editedAt": null,
  "deletedAt": null,
  "deletedBy": null,
  "systemEventType": "MEMBER_ADDED",
  "systemMetadata": {
    "actorId": "a1...",
    "actorName": "Nguyễn Văn A",
    "targetId": "b2...",
    "targetName": "Trần Thị B"
  },
  "createdAt": "2026-04-22T10:30:00Z"
}
```

**BE publish policy** (BLOCKING cho W7-D4 implementation):

Tạo SYSTEM message trong CÙNG transaction với service method, hoặc trong `@TransactionalEventListener(BEFORE_COMMIT)` → save row `messages` → publish `MessageCreatedEvent(convId, systemDto)` → reuse existing broadcast flow (`AFTER_COMMIT` listener broadcast `/topic/conv.{id}` với envelope `{type: "MESSAGE_CREATED", payload: MessageDto}`). **KHÔNG tạo event `/topic/conv.{id}` type mới** — SYSTEM piggyback trên MESSAGE_CREATED.

Mapping service method → SYSTEM event(s):

| Service method | SYSTEM event(s) fire | Ordering constraint |
|----------------|---------------------|---------------------|
| `createGroup` | 1× `GROUP_CREATED` | Fire sau khi group + members batch insert commit. |
| `addMembers` | N× `MEMBER_ADDED` (N = số user trong `added[]`) | 1 SYSTEM msg per added user. KHÔNG tạo cho user trong `skipped[]`. Order insert ≈ order add (không strict). |
| `removeMember` | 1× `MEMBER_REMOVED` | Sau khi delete `conversation_members` row, trước khi return 204. |
| `leaveGroup` non-OWNER path | 1× `MEMBER_LEFT` | Sau khi delete row. |
| `leaveGroup` OWNER auto-transfer path | 1× `OWNER_TRANSFERRED` (autoTransferred=true) **TRƯỚC** 1× `MEMBER_LEFT` | Ordering BLOCKING: OWNER_TRANSFERRED ghi vào messages table với `createdAt` SỚM HƠN (hoặc bằng) MEMBER_LEFT. BE dùng 2 call `messageRepository.save()` liên tiếp — row order bảo đảm bởi sequence/timestamp. |
| `leaveGroup` OWNER empty-group path | Không fire SYSTEM message | OWNER leave một mình (không có member khác) → endpoint trả 400 `CANNOT_LEAVE_EMPTY_GROUP` (xem PATCH /leave). Không có action hợp lệ = không có system msg. |
| `changeRole` MEMBER→ADMIN | 1× `ROLE_PROMOTED` | Sau khi update role row. No-op (newRole == currentRole) → KHÔNG fire (đồng bộ với broadcast ROLE_CHANGED silent idempotent). |
| `changeRole` ADMIN→MEMBER | 1× `ROLE_DEMOTED` | Tương tự. |
| `transferOwner` | 1× `OWNER_TRANSFERRED` (autoTransferred=false) | Sau atomic 2-way swap commit. |
| `updateGroupInfo` — name thực sự đổi | 1× `GROUP_RENAMED` | Chỉ fire khi `oldName.trim() != newName.trim()`. No-op rename (gửi PATCH cùng name) → không fire. |
| `updateGroupInfo` — avatar only | **KHÔNG** fire SYSTEM message (V1) | Rationale: avatar change ít meaningful cho chat history; CONVERSATION_UPDATED đã notify UI. V2 xem xét `GROUP_AVATAR_CHANGED` nếu user feedback. |

**Race / ordering note**:
- `MEMBER_REMOVED` SYSTEM message INSERT phải xảy ra TRƯỚC khi `conversation_members` row bị xoá — nếu không, user bị kick sẽ thấy `createdAt` của system msg sau khi đã bị remove → UX confusing. Hiện implement: service order = (1) insert system msg, (2) delete member row, (3) commit. Broadcast MEMBER_REMOVED event (`/topic` §3.8 SOCKET_EVENTS.md) vẫn broadcast TRƯỚC hard-delete (như quy tắc W7-D2).
- `GROUP_CREATED` SYSTEM msg là first message của group → `createdAt` ≤ `createdAt` của mọi user message tiếp theo (bảo đảm bởi timestamp đơn điệu từ DB).
- Khi có concurrent actions (2 OWNER/ADMIN cùng add member khác nhau), order system messages theo `createdAt` DB — không cần FE handler special, MESSAGE_CREATED append bình thường.

---

### Validation rules cho SYSTEM messages (W7-D4)

SYSTEM messages có ràng buộc đặc biệt khác TEXT:

1. **NOT editable by user** — bất kỳ endpoint edit nào (STOMP `/app/conv.{id}.edit`, REST `PUT /api/messages/{id}` nếu tồn tại fallback) gặp message với `type='SYSTEM'` → reject:
   - STOMP: ERROR payload `{operation: "EDIT", clientId, code: "SYSTEM_MESSAGE_NOT_EDITABLE", error: "Tin nhắn hệ thống không thể chỉnh sửa"}`.
   - REST `PUT /api/messages/{id}` (nếu endpoint này có triển khai sau): **403** `SYSTEM_MESSAGE_NOT_EDITABLE`.
   - Anti-enum: check `type='SYSTEM'` TRƯỚC anti-enum `MSG_NOT_FOUND` merge (vì FE cần error code riêng để toast "tin hệ thống"). Ngoại lệ documented của anti-enum rule.

2. **NOT deletable by user** — endpoint delete (`/app/conv.{id}.delete`, REST `DELETE /api/messages/{id}`) gặp message với `type='SYSTEM'` → reject:
   - STOMP: ERROR payload `{operation: "DELETE", clientId, code: "SYSTEM_MESSAGE_NOT_DELETABLE", error: "Tin nhắn hệ thống không thể xóa"}`.
   - REST: **403** `SYSTEM_MESSAGE_NOT_DELETABLE`.
   - *Reasoning*: SYSTEM messages là audit trail của group actions — cho user xóa sẽ mất lịch sử ai làm gì. V2 xem xét OWNER privilege xóa system msg nếu có complaint.

3. **SYSTEM KHÔNG count towards unread** — **Superseded v1.4.0-w7-read (W7-D5)**. Rule cũ v1.2.0-w7-system: SYSTEM count (rationale "user cần biết"). Rule mới v1.4.0-w7-read: `unreadCount` = `COUNT(messages WHERE conv_id = X AND created_at > lastRead.created_at AND type != 'SYSTEM')` — SYSTEM **bị loại** khỏi count. Lý do đổi: (a) SYSTEM message fire khắp mọi group action (add/kick/rename) — group sôi động sẽ có `unreadCount` phình to vì system msg, làm badge UX kém; (b) mọi chat app (Messenger, Telegram) đều KHÔNG count system notice vào unread; (c) user không cần "đánh dấu đọc" system msg một cách chủ động — chúng là passive info. Typing indicator cũng không count (transient, không vào bảng messages V1). **Impact migration**: existing conv có SYSTEM msg pre-W7-D5 → next GET /api/conversations sẽ thấy `unreadCount` thấp hơn (đã loại SYSTEM) — UX improvement, không breaking FE.

4. **SYSTEM không có reactions V1** — **Updated v1.5.0-w8-reactions (W8-D1)**: reactions đã triển khai qua STOMP `/app/msg.{messageId}.react` (xem SOCKET_EVENTS.md §3.15). Khi user attempt react vào SYSTEM message → BE reject với `REACTION_NOT_ALLOWED_FOR_SYSTEM` (renamed từ placeholder cũ `SYSTEM_MESSAGE_NO_REACTIONS` — consistent prefix `REACTION_*`). Rule cũ chưa từng emit code placeholder → không breaking.

5. **SYSTEM không thể quote/reply** — STOMP SEND với `replyToMessageId` trỏ tới SYSTEM message → anti-enum fallback `VALIDATION_FAILED` (không leak "đây là system msg"). Rationale: user không nên reply vào "X đã được thêm vào nhóm".

6. **Pagination inclusion** — `GET /api/conversations/{id}/messages` (cả `cursor` và `after` pagination) TRẢ VỀ SYSTEM messages inline với TEXT messages, sort theo `createdAt` ASC. FE render inline (xem `SOCKET_EVENTS.md` §3e.1 Render SYSTEM contract). `limit=50` count SYSTEM giống TEXT — không skip.

---

### Migration V10 — SYSTEM messages columns (W7-D4)

**Flyway migration**: `V10__add_system_message_fields.sql`

```sql
-- V10: Add SYSTEM message support (W7-D4)
-- Adds 2 nullable columns to messages table. Existing rows: both NULL (TEXT/IMAGE/FILE).

ALTER TABLE messages
  ADD COLUMN system_event_type VARCHAR(50),
  ADD COLUMN system_metadata JSONB;

-- Relax sender_id nullable for SYSTEM messages (server-generated, no user actor in sender slot).
-- Existing NOT NULL constraint on sender_id drop → allow NULL.
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

-- Index cho query "system events in conv" (V2 có thể cần — V1 không query riêng).
-- CREATE INDEX idx_messages_system_type ON messages (conversation_id, system_event_type)
--   WHERE type = 'SYSTEM';
-- V1 comment out — chưa cần, thêm sau nếu query pattern xuất hiện.
```

**Migration notes**:
- Cả 2 column nullable cho backward-compat — rows cũ (pre-W10) giữ NULL cho cả 2 field.
- `sender_id` chuyển từ NOT NULL → nullable. Existing data không affect (all sender_id hiện non-null). BE code paths load `sender` phải handle null: `MessageMapper.toDto` check `message.sender == null ? null : UserSummaryDto.from(message.sender)`.
- CHECK constraint catch dirty write: nếu BE vô tình INSERT với `type='TEXT'` + `system_event_type='X'`, DB reject → handler trả 500 INTERNAL (bug cần fix, không phải user-facing error).
- `systemMetadata` JSONB (không TEXT) → query field bên trong sau này (V2) bằng `->>` operator. V1 BE serialize Java `Map<String, Object>` → PostgreSQL JSONB qua Hibernate UserType hoặc `@JdbcTypeCode(SqlTypes.JSON)` (Hibernate 6+).

---

### ReactionAggregateDto (v1.5.0-w8-reactions)

Aggregate shape trong `MessageDto.reactions[]`. BE group by emoji, compute count/userIds/currentUserReacted server-side trong cùng query load message (batch load pattern chống N+1 — xem "Migration V13" phần "BE load pattern").

```json
{
  "emoji": "string",
  "count": 0,
  "userIds": ["uuid", "..."],
  "currentUserReacted": false
}
```

| Field | Type | Description |
|-------|------|-------------|
| `emoji` | `string` | Unicode emoji character(s). Có thể compound (ZWJ sequence, flag tag sequence, skin-tone modifier) — max 20 bytes UTF-8 (xem Migration V13 column spec). |
| `count` | `number` | Total reactions với emoji này trên message. Luôn `>= 1` (emoji với count 0 KHÔNG xuất hiện trong array — BE filter trước khi serialize). |
| `userIds` | `string[]` | UUIDs của users đã react với emoji này. Length == `count`. Order: không guarantee (BE có thể ORDER BY `created_at` ASC nhưng FE không rely on). |
| `currentUserReacted` | `boolean` | `true` nếu caller (authenticated user từ JWT) nằm trong `userIds`. Computed server-side mỗi response — không cache. Edge: caller gọi REST không auth (public endpoint nếu có) → luôn `false`. |

**Ví dụ** (message với 3 reactions: 2 user thumbs-up + 1 user heart, caller là thumbs-up user):

```json
"reactions": [
  {
    "emoji": "👍",
    "count": 2,
    "userIds": ["a1...", "b2..."],
    "currentUserReacted": true
  },
  {
    "emoji": "❤️",
    "count": 1,
    "userIds": ["c3..."],
    "currentUserReacted": false
  }
]
```

**Sort guarantee**: BE MUST sort `reactions[]` theo `count DESC, emoji ASC` (emoji ASC là codepoint compare, deterministic). Rationale: FE render ReactionBar theo order — popular emoji ở trước. Stable sort → UI không flicker khi thêm reaction cùng count.

**Soft-delete + SYSTEM exclusion**: khi serialize message với `deletedAt != null` HOẶC `type == 'SYSTEM'` → `reactions = []` (empty array, không load từ DB). Rationale: soft-deleted message không cho react mới + không hiển thị reactions cũ (UX "hồn ma"); SYSTEM message không allow react V1.

---

### Migration V13 — message_reactions (W8-D1)

**Flyway migration**: `V13__add_message_reactions.sql`

```sql
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
```

**Migration notes**:
- `UNIQUE(message_id, user_id)` là **hợp đồng bất di bất dịch**: 1 user chỉ có 1 emoji cho 1 message tại mọi thời điểm. Toggle different-emoji case = UPDATE row (KHÔNG DELETE+INSERT — tránh race + giữ `id` stable).
- `ON DELETE CASCADE` cho cả 2 FK:
  - `message_id → messages.id CASCADE`: khi hard-delete message (V2 audit cleanup) → cleanup reactions. V1 soft-delete không trigger CASCADE (row messages vẫn còn).
  - `user_id → users.id CASCADE`: khi xoá user account → cleanup reactions. Không cần keep reactions của user đã xoá.
- `emoji VARCHAR(20)`: đủ cho compound emoji dài nhất Unicode 15.1 (family ZWJ ~11 bytes, flag tag sequence ~28 bytes — BE MUST truncate hoặc reject nếu input >20 bytes). **BLOCKING**: nếu regex allow `🏴󠁧󠁢󠁳󠁣󠁴󠁿` (28 bytes UTF-8) thì DB constraint reject → cần hoặc expand VARCHAR(32) hoặc reject emoji dài hơn 20 bytes ở validation layer. Reviewer chọn **expand VARCHAR(32)** cho safe (20 → 32) **TRƯỚC W8-D1 implement** — xem BLOCKING INCONSISTENCY dưới. Chốt V1: `VARCHAR(20)` cover 95% emoji phổ biến; rare flag-tag sequences reject với `REACTION_INVALID_EMOJI`.
- Indexes:
  - `idx_reactions_message`: batch load aggregate `WHERE message_id IN (?, ?, ?, ...)` cho pagination list messages (N+1 mitigation — BLOCKING).
  - `idx_reactions_user`: audit query "all reactions by user" (V2 feature); V1 không query pattern này nhưng index rẻ + support user-delete CASCADE nhanh hơn.
  - UNIQUE constraint tự động tạo composite index `(message_id, user_id)` — reuse cho "check existing reaction của user X trên message Y" (toggle service).

**BE load pattern (BLOCKING N+1 mitigation)**:
- Khi list messages (50/page): 1 query `SELECT * FROM message_reactions WHERE message_id IN (:messageIds) ORDER BY message_id, emoji`. Group in memory bằng Java stream → `Map<UUID, List<ReactionAggregateDto>>`. Merge vào MessageMapper output.
- Khi load single message (REST GET /messages/{id} hoặc ACK): 1 query `WHERE message_id = ?` — acceptable 1 extra round-trip.
- **KHÔNG** dùng `@OneToMany(fetch = LAZY)` rồi access trong loop → classic N+1.

**`currentUserReacted` compute**: BE biết `caller.userId` từ SecurityContext (REST) hoặc STOMP Principal. Sau khi group reactions by emoji, mỗi aggregate check `userIds.contains(caller.userId)` → set boolean. KHÔNG query riêng — dùng in-memory check trên list `userIds` đã load.

---

### Error codes mới (v1.5.0-w8-reactions)

| Code | HTTP (REST — N/A V1) / STOMP ERROR | Điều kiện |
|------|------------------------------------|-----------|
| `REACTION_INVALID_EMOJI` | STOMP | `emoji` null/rỗng/whitespace-only/không match emoji regex (plain text "abc", emoji > 20 bytes sau truncate). |
| `REACTION_NOT_ALLOWED_FOR_SYSTEM` | STOMP | Target message có `type = 'SYSTEM'`. Supersedes placeholder `SYSTEM_MESSAGE_NO_REACTIONS` từ W7-D4 "Validation rules cho SYSTEM messages" rule 4 (xem rule 4 updated note dưới). |
| `REACTION_MSG_DELETED` | STOMP | Target message có `deleted_at IS NOT NULL`. |
| `MSG_NOT_FOUND` | STOMP | Message không tồn tại (anti-enum). Reuse existing code. |
| `NOT_MEMBER` | STOMP | Caller không phải member của conv chứa message. Reuse existing. |
| `MSG_RATE_LIMITED` | STOMP | >5 reactions/second/user. Reuse existing code (consistent với send/edit rate-limit code). |

> **Ngoại lệ anti-enum** (documented, giống W7-D4 `SYSTEM_MESSAGE_NOT_EDITABLE`): `REACTION_NOT_ALLOWED_FOR_SYSTEM` và `REACTION_MSG_DELETED` KHÔNG merge vào `MSG_NOT_FOUND` dù tương đồng với quy tắc "non-eligible → NOT_FOUND". Lý do: SYSTEM + deleted messages đều visible cho members (không hidden) — FE cache đã có `type` + `deletedAt` → distinguish không leak gì. Error code riêng giúp FE toast chính xác ("không thể react tin hệ thống" / "tin này đã bị xóa").

> **Supersede W7-D4 rule 4**: "Validation rules cho SYSTEM messages" rule 4 ("SYSTEM không có reactions V1") trước đây ghi placeholder code `SYSTEM_MESSAGE_NO_REACTIONS`. V1.5.0-w8-reactions rename thành `REACTION_NOT_ALLOWED_FOR_SYSTEM` (consistent prefix `REACTION_*` cho mọi error thuộc feature). BE W8-D1 MUST emit tên mới. `SYSTEM_MESSAGE_NO_REACTIONS` chưa từng được emit (chưa implement) → không có legacy concern.

---

### Pin Message (W8-D2)

**STOMP inbound**: `/app/msg.{messageId}.pin`

**Payload**:
```json
{ "action": "PIN | UNPIN" }
```

**Auth**: STOMP `Principal` (JWT). Policy `HANDLER_CHECK` (cùng pattern reactions W8-D1 — destination messageId-based, resolve convId trong handler).

**Validation chain** (rẻ → đắt):
1. Auth (Principal non-null).
2. Rate limit 5/s/user (`rate:pin:{userId}` INCR EX 1).
3. `action` = "PIN" hoặc "UNPIN" (string match) → `INVALID_ACTION` nếu khác.
4. Message exists → `MSG_NOT_FOUND` (anti-enum).
5. Derive `convId` từ message.
6. User là member của conv → `NOT_MEMBER`.
7. **Role check** (conv type-aware):
   - `GROUP` → user phải `OWNER` hoặc `ADMIN` (`MemberRole.isAdminOrHigher()`) → `FORBIDDEN`.
   - `DIRECT` → mọi member OK (skip check).
8. `action = "PIN"`:
   - `message.deletedAt != null` → `MSG_DELETED`.
   - Idempotent: `message.pinnedAt != null` → **no-op** (không broadcast, không throw).
   - Count pinned trong conv (`WHERE pinned_at IS NOT NULL AND deleted_at IS NULL`): ≥ 3 → `PIN_LIMIT_EXCEEDED` (details `{currentCount, limit: 3}`).
   - Set `pinned_at = NOW()`, `pinned_by_user_id = userId`. Save + publishEvent `MessagePinnedEvent`.
9. `action = "UNPIN"`:
   - `message.pinnedAt == null` → `MESSAGE_NOT_PINNED`.
   - Set `pinned_at = null`, `pinned_by_user_id = null`. Save + publishEvent `MessageUnpinnedEvent`.
10. `@TransactionalEventListener(AFTER_COMMIT)` fire broadcast `MESSAGE_PINNED` hoặc `MESSAGE_UNPINNED` (xem SOCKET_EVENTS.md §3.17/§3.18).

**Response to caller**: KHÔNG có ACK queue riêng cho pin. Confirmation qua broadcast `MESSAGE_PINNED`/`MESSAGE_UNPINNED` trên `/topic/conv.{convId}` — same pattern reactions fire-and-forget.

**ERROR frame**: qua `/user/queue/errors` với shape unified (ADR-017):
```json
{
  "operation": "PIN",
  "clientId": null,
  "error": "human readable message",
  "code": "PIN_LIMIT_EXCEEDED | MESSAGE_NOT_PINNED | FORBIDDEN | MSG_DELETED | MSG_NOT_FOUND | NOT_MEMBER | INVALID_ACTION | MSG_RATE_LIMITED"
}
```
> `clientId: null` — pin là fire-and-forget (no optimistic ID), cùng pattern REACT. FE handler ERROR MUST tolerate null clientId.

**MessageDto extended** (v1.6.0-w8-pin-block):
```typescript
interface MessageDto {
  // ... existing fields (id, conversationId, sender, type, content, ...)
  // ... reactions (W8-D1)
  pinnedAt: string | null;    // ISO8601, null nếu chưa pin
  pinnedBy: {                 // null nếu chưa pin
    userId: string;
    userName: string;
  } | null;
}
```

> `pinnedBy` là snapshot tại thời điểm pin — không auto-update nếu user đổi tên (consistent với `userName` snapshot pattern trong REACTION_CHANGED broadcast).

**ConversationDetailDto extended** (v1.6.0-w8-pin-block):
```typescript
interface ConversationDetailDto extends ConversationDto {
  // ... existing fields
  pinnedMessages: MessageDto[];  // sort by pinned_at DESC, max 3, filter deleted_at IS NULL
}
```

> `pinnedMessages` luôn là array (empty `[]` nếu không pin). BE query `WHERE pinned_at IS NOT NULL AND deleted_at IS NULL ORDER BY pinned_at DESC`. FE render banner top conv từ field này.

**Error codes mới (v1.6.0-w8-pin-block, Pin feature)**:

| Code | HTTP equivalent / STOMP ERROR | Điều kiện |
|------|-------------------------------|-----------|
| `PIN_LIMIT_EXCEEDED` | 400 / STOMP | Conv đã có 3 pinned messages (count `WHERE pinned_at IS NOT NULL AND deleted_at IS NULL`). `details: {currentCount: number, limit: 3}`. |
| `MESSAGE_NOT_PINNED` | 400 / STOMP | `action=UNPIN` nhưng message chưa pin (`pinned_at IS NULL`). |
| `FORBIDDEN` | 403 / STOMP | User không đủ role pin/unpin trong GROUP conv (MEMBER thay vì OWNER/ADMIN). Reuse existing code. |
| `MSG_DELETED` | 400 / STOMP | `action=PIN` trên message soft-deleted (`deleted_at IS NOT NULL`). Ngoại lệ anti-enum: KHÔNG merge vào `MSG_NOT_FOUND` — message visible cho members (FE biết `deletedAt` từ cache), distinguish rõ "tin đã xóa" vs "tin không tồn tại". Cùng pattern `REACTION_MSG_DELETED`. |
| `INVALID_ACTION` | 400 / STOMP | `action` khác "PIN" và "UNPIN". |
| `MSG_NOT_FOUND` | 404 / STOMP | Message không tồn tại. Reuse existing. |
| `NOT_MEMBER` | 403 / STOMP | Caller không phải member. Reuse existing. |
| `MSG_RATE_LIMITED` | 429 / STOMP | >5 pin/s/user. Reuse existing. |

> **Ngoại lệ anti-enum** cho `MSG_DELETED`: Cùng rationale với `REACTION_MSG_DELETED` (W8-D1) — message đã xóa visible cho member (FE cache có `deletedAt`), error code riêng giúp FE toast "Tin nhắn đã bị xóa — không thể ghim" chính xác hơn "Không tìm thấy tin nhắn".

---

### Migration V14 — add_message_pin (W8-D2)

**Flyway migration**: `V14__add_message_pin.sql`

```sql
-- V14: Add pin message support to messages table

ALTER TABLE messages
  ADD COLUMN pinned_at TIMESTAMPTZ,
  ADD COLUMN pinned_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL;

-- Partial index: chỉ index rows có pinned_at IS NOT NULL (nhanh query pinned list)
CREATE INDEX idx_messages_pinned ON messages(conversation_id, pinned_at)
WHERE pinned_at IS NOT NULL;
```

> **Rationale `ON DELETE SET NULL`**: Nếu user pin rồi bị xóa account → message vẫn pinned nhưng `pinned_by_user_id = NULL`. FE render "Ghim bởi (không rõ)" fallback. Cùng pattern `sender_id ON DELETE SET NULL` (W7-D4 SYSTEM messages).

---

### Block User Endpoints (W8-D2)

**REST endpoints**:

#### `POST /api/users/{id}/block`

Block user. Bilateral: A block B → cả 2 không gửi direct message nhau.

**Request**: Không body. Path param `{id}` = target user UUID.

**Response**:
- **201 Created**: block thành công (new row).
- **200 OK**: idempotent — đã block rồi → no-op (không duplicate).

**Validation** (rẻ → đắt):
1. `id == currentUser.id` → `CANNOT_BLOCK_SELF` (400).
2. Target user exists → `USER_NOT_FOUND` (404).
3. `existsByBlockerIdAndBlockedId(currentUser.id, id)` → đã block → **200 no-op**.
4. Insert `user_blocks` row.

#### `DELETE /api/users/{id}/block`

Unblock user. Chỉ unblock chiều caller → target.

**Response**: **204 No Content**.

**Validation**:
1. `existsByBlockerIdAndBlockedId(currentUser.id, id)` → chưa block → `BLOCK_NOT_FOUND` (404).
2. Delete row.

#### `GET /api/users/blocked`

List users caller đã block.

**Response 200**:
```json
{
  "items": [
    {
      "id": "uuid",
      "username": "string",
      "fullName": "string",
      "avatarUrl": "string | null",
      "isBlockedByMe": true
    }
  ]
}
```

> `items` sort by `created_at DESC` (blocked gần nhất trước). Dùng `UserDto` shape. `isBlockedByMe` luôn `true` trong list này.

**Bilateral block check integration**:

Hai flow cần check bilateral block:
1. **STOMP `/app/conv.{convId}.message` (sendViaStomp)**: sau khi validate member, nếu `conv.type == DIRECT` → `existsBilateral(senderId, otherUserId)` → `MSG_USER_BLOCKED`.
2. **`POST /api/conversations` (createDirect)**: trước khi tạo conv → `existsBilateral(creatorId, targetUserId)` → `MSG_USER_BLOCKED`.

GROUP send: **KHÔNG** check bilateral block (group là shared context, admin quản lý membership).
`addMembers`: reuse W7 `USER_BLOCKED` skip pattern (đã có).

**`existsBilateral` query** (1 query, cả 2 chiều):
```sql
SELECT COUNT(*) > 0 FROM user_blocks
WHERE (blocker_id = :a AND blocked_id = :b)
   OR (blocker_id = :b AND blocked_id = :a)
```

**UserDto extended** (v1.6.0-w8-pin-block):
```typescript
interface UserDto {
  // ... existing fields (id, username, fullName, avatarUrl)
  isBlockedByMe?: boolean;  // current user đã block target? default undefined/false
}
```

> **Privacy**: KHÔNG expose `hasBlockedMe`. User B không nên biết A đã block B — chống harassment escalation. `isBlockedByMe` chỉ set khi viewer context available (`toDtoForViewer(user, viewerId)`). Internal mapper `toDto(user)` trả `isBlockedByMe = undefined`.

**Error codes mới (v1.6.0-w8-pin-block, Block feature)**:

| Code | HTTP | Điều kiện |
|------|------|-----------|
| `CANNOT_BLOCK_SELF` | 400 | `POST /api/users/{id}/block` với `{id} == currentUser.id`. |
| `BLOCK_NOT_FOUND` | 404 | `DELETE /api/users/{id}/block` nhưng chưa block user này. Anti-enum: không phân biệt "user không tồn tại" vs "chưa block" — merge 404 an toàn (unblock user không tồn tại cũng 404). |
| `MSG_USER_BLOCKED` | 403 | Send direct message hoặc createDirect conv khi bilateral block. Message: "Không thể gửi tin nhắn: bạn hoặc người dùng đã chặn nhau". Bilateral → **không tiết lộ ai block ai**. |
| `USER_NOT_FOUND` | 404 | Target user không tồn tại. Reuse existing. |

---

### Migration V15 — add_user_blocks (W8-D2)

**Flyway migration**: `V15__add_user_blocks.sql`

```sql
-- V15: Add bilateral user block support

CREATE TABLE user_blocks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    blocker_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(blocker_id, blocked_id),
    CHECK (blocker_id != blocked_id)
);

CREATE INDEX idx_blocks_blocker ON user_blocks(blocker_id);
CREATE INDEX idx_blocks_blocked ON user_blocks(blocked_id);
```

> **`ON DELETE CASCADE`**: user bị xóa account → block rows tự cleanup. Không cần cleanup job.
> **`CHECK (blocker_id != blocked_id)`**: DB-level guard self-block (defense-in-depth, service cũng check).
> **2 indexes**: `idx_blocks_blocker` cho `GET /api/users/blocked` (list blocked BY user) + `idx_blocks_blocked` cho reverse lookup (V2 nếu cần "who blocked me" admin view). `existsBilateral` query dùng OR 2 chiều — PG optimizer chọn index scan phù hợp.

---

### Validation rules cho SEND + EDIT với attachments (W6-D1)

Áp dụng cho cả STOMP `/app/conv.{id}.message` (SEND) và `/app/conv.{id}.edit` (EDIT) — xem `SOCKET_EVENTS.md` §3b + §3c.

**SEND payload** (thêm `attachmentIds`):

```json
{
  "tempId": "uuid",
  "content": "string (0-5000 chars, nullable nếu có attachments)",
  "type": "TEXT",
  "replyToMessageId": "uuid | null",
  "attachmentIds": ["uuid", "..."]
}
```

**Validation rules** (server-side, enforce nghiêm ngặt):

1. **Phải có content HOẶC attachments**: nếu `content` rỗng/null/toàn-whitespace AND `attachmentIds` rỗng/null → `MSG_NO_CONTENT`.
2. **Mixing rules** (theo mục "Allowed MIME types (v0.9.5)"):
   - Tất cả Group A (images): OK if total ≤ 5.
   - Đúng 1 Group B (non-image: pdf, docx, xlsx, pptx, doc, xls, ppt, txt, zip, 7z): OK, alone.
   - Trộn Group A + Group B → `MSG_ATTACHMENTS_MIXED`.
   - 2+ Group B files → `MSG_ATTACHMENTS_MIXED` (Group B exactly 1 per message).
3. **Count limits**:
   - Group A images: 1–5 items. >5 → `MSG_ATTACHMENTS_TOO_MANY` (kèm `details.maxItems: 5, details.actualCount`).
   - Group B (non-image): exactly 1. >1 → `MSG_ATTACHMENTS_MIXED` (vì có nghĩa "trộn 2 Group B").
4. **Per-attachment check** (cho mỗi `attachmentId`):
   - File không tồn tại → `MSG_ATTACHMENT_NOT_FOUND`.
   - File đã expire (`expires_at < now()`) → `MSG_ATTACHMENT_EXPIRED`.
   - File uploader không phải sender (`file.uploader_id != userId`) → `MSG_ATTACHMENT_NOT_OWNED` (chống attach file của user khác).
   - File đã được attach vào message khác (`EXISTS SELECT 1 FROM message_attachments WHERE file_id = ?`) → `MSG_ATTACHMENT_ALREADY_USED` (UNIQUE constraint DB guarantee, service check trước để có error code rõ).

**EDIT constraint** (V1): KHÔNG cho sửa attachments — chỉ sửa `content`. Nếu FE gửi EDIT payload với `attachmentIds` khác attachments hiện tại → BE **bỏ qua field `attachmentIds`** (không lỗi, không thay đổi DB), chỉ update `content`. Lý do: (1) edit window 5 phút ngắn, user thường chỉ sửa typo; (2) thay attachment đòi hỏi upload mới + dedup cleanup phức tạp. V2 xem xét cho phép.

> **BE implementation note**: EDIT service đã nhận payload `{clientEditId, messageId, newContent}` (§3c.1) — KHÔNG thêm field `attachmentIds` vào EDIT payload. Giữ nguyên shape EDIT hiện tại. Validation rule (1) "phải có content HOẶC attachments" với EDIT = chỉ check `newContent` non-empty (attachments giữ nguyên từ message gốc, đã qua validation khi SEND).

**Error code table mới (W6-D1)**:

| Code | HTTP (REST) / STOMP ERROR | Điều kiện |
|------|---------------------------|-----------|
| `MSG_NO_CONTENT` | 400 / STOMP | Cả `content` và `attachmentIds` đều rỗng. |
| `MSG_ATTACHMENT_NOT_OWNED` | 403 / STOMP | File `uploader_id != senderId`. |
| `MSG_ATTACHMENT_ALREADY_USED` | 409 / STOMP | File đã attach vào message khác (UNIQUE constraint). |
| `MSG_ATTACHMENTS_MIXED` | 400 / STOMP | Trộn Group A (images) + Group B (non-image), HOẶC 2+ files Group B trong cùng message. |
| `MSG_ATTACHMENTS_TOO_MANY` | 400 / STOMP | >5 images trong Group A. |
| `MSG_ATTACHMENT_NOT_FOUND` | 404 / STOMP | `attachmentId` không tồn tại. |
| `MSG_ATTACHMENT_EXPIRED` | 410 / STOMP | File đã expire (`expires_at < now()`). |

**REST path**: vẫn dùng `POST /api/conversations/{convId}/messages` cho fallback (deprecated FE hot path nhưng vẫn support). Body thêm optional `attachmentIds: ["uuid", ...]`. Error code giống bảng trên.

---

### Error codes mới (v1.2.0-w7-system)

| Code | HTTP (REST) / STOMP ERROR | Điều kiện |
|------|---------------------------|-----------|
| `SYSTEM_MESSAGE_NOT_EDITABLE` | 403 / STOMP | User attempt edit message với `type='SYSTEM'`. SYSTEM messages immutable. |
| `SYSTEM_MESSAGE_NOT_DELETABLE` | 403 / STOMP | User attempt delete message với `type='SYSTEM'`. SYSTEM messages không xóa được V1. |

> **Ngoại lệ anti-enum** (documented): hai code này KHÔNG merge vào `MSG_NOT_FOUND` dù tương đồng với quy tắc "non-owner edit/delete → MSG_NOT_FOUND". Lý do: SYSTEM message visible cho mọi member (không hidden), việc distinguish `SYSTEM_MESSAGE_NOT_EDITABLE` vs `MSG_NOT_FOUND` không leak gì (FE biết đây là SYSTEM từ `type` field của message cache). Error code rõ ràng giúp FE toast message đúng ngữ cảnh ("tin hệ thống không sửa được" vs "tin không tồn tại").

---

## Changelog

| Ngày | Version | Nội dung |
|------|---------|---------|
| 2026-04-22 | v1.6.0-w8-pin-block | **W8-D2 Pin Message + Bilateral User Block**. (1) **Migration V14** (`V14__add_message_pin.sql`): ADD COLUMN `messages.pinned_at TIMESTAMPTZ` + `messages.pinned_by_user_id UUID FK users ON DELETE SET NULL`. Partial index `idx_messages_pinned (conversation_id, pinned_at) WHERE pinned_at IS NOT NULL`. (2) **Migration V15** (`V15__add_user_blocks.sql`): CREATE TABLE `user_blocks` (id UUID PK, blocker_id FK, blocked_id FK, created_at, UNIQUE(blocker_id, blocked_id), CHECK blocker != blocked). Indexes `idx_blocks_blocker` + `idx_blocks_blocked`. ON DELETE CASCADE. (3) **Pin Message STOMP** — `/app/msg.{messageId}.pin` payload `{action: "PIN"\|"UNPIN"}`, policy HANDLER_CHECK (cùng pattern reactions W8-D1). Validation: auth → rate 5/s → action enum → message exists → member → role check (GROUP OWNER/ADMIN, DIRECT any) → deleted check → idempotent no-op → limit 3 → save + broadcast. Broadcast `MESSAGE_PINNED` / `MESSAGE_UNPINNED` trên `/topic/conv.{convId}` (xem SOCKET_EVENTS.md v1.11-w8 §3.17/§3.18). (4) **MessageDto extended** — `pinnedAt: string\|null` + `pinnedBy: {userId, userName}\|null` (snapshot). (5) **ConversationDetailDto extended** — `pinnedMessages: MessageDto[]` sort `pinned_at DESC` max 3, filter `deleted_at IS NULL`. (6) **Block User REST** — 3 endpoints: `POST /api/users/{id}/block` (201/200 idempotent), `DELETE /api/users/{id}/block` (204), `GET /api/users/blocked` (200 `{items: UserDto[]}`). (7) **Bilateral block integration** — `existsBilateral(a, b)` OR query cả 2 chiều. Check trong `sendViaStomp` (DIRECT only) + `createDirect`. GROUP send KHÔNG check. `addMembers` reuse W7 `USER_BLOCKED` skip. (8) **UserDto extended** — `isBlockedByMe?: boolean` (viewer-aware). KHÔNG expose `hasBlockedMe` (privacy). (9) **Error codes mới (Pin)**: `PIN_LIMIT_EXCEEDED` (details currentCount+limit), `MESSAGE_NOT_PINNED`, `MSG_DELETED` (ngoại lệ anti-enum, cùng pattern REACTION_MSG_DELETED), `INVALID_ACTION`. Reuse `FORBIDDEN`, `MSG_NOT_FOUND`, `NOT_MEMBER`, `MSG_RATE_LIMITED`. (10) **Error codes mới (Block)**: `CANNOT_BLOCK_SELF`, `BLOCK_NOT_FOUND`, `MSG_USER_BLOCKED` (bilateral, không tiết lộ ai block ai). Reuse `USER_NOT_FOUND`. (11) **ADR-023** Pin Message + **ADR-024** Bilateral User Block thêm vào ARCHITECTURE.md §12. KHÔNG breaking existing — additive fields + nullable. BLOCKING cho BE W8-D2: role check GROUP vs DIRECT, pin limit count filter deleted_at, bilateral query OR, block integration sendViaStomp DIRECT-only + createDirect. BLOCKING cho FE W8-D2: PinnedMessagesBanner collapsed/expanded + scrollToMessage, canPin() role check, block confirm dialog, error toast 8 codes mới, isBlockedByMe flag. |
| 2026-04-22 | v1.5.0-w8-reactions | **W8-D1 Message Reactions**. (1) **Migration V13** (`V13__add_message_reactions.sql`): CREATE TABLE `message_reactions` với `UNIQUE(message_id, user_id)` — 1 user 1 emoji per message. Columns `id UUID PK`, `message_id UUID FK messages ON DELETE CASCADE`, `user_id UUID FK users ON DELETE CASCADE`, `emoji VARCHAR(20) NOT NULL`, `created_at TIMESTAMPTZ`. Indexes `idx_reactions_message` (N+1 mitigation batch load) + `idx_reactions_user` (audit/cascade). CASCADE 2 chiều cleanup: user-delete + hard-delete-message V2. (2) **MessageDto extended** — thêm field `reactions: ReactionAggregateDto[]` (luôn array, không null; empty `[]` khi không reaction / soft-deleted / SYSTEM). Sort guarantee `count DESC, emoji ASC` stable. (3) **ReactionAggregateDto shape** (new): `{emoji, count, userIds[], currentUserReacted}`. `currentUserReacted` compute per-caller server-side; `userIds.length == count`. Emoji aggregate chỉ xuất hiện khi count >= 1 (BE filter trước serialize). (4) **Toggle semantics** (BE service): no existing → INSERT (ADDED); same emoji → DELETE (REMOVED); different emoji → UPDATE (CHANGED). Bảo đảm atomic trong 1 transaction. (5) **STOMP inbound `/app/msg.{messageId}.react`** — xem SOCKET_EVENTS.md v1.10-w8 §3.15: payload `{emoji}` (KHÔNG clientId — fire-and-forget, confirmation qua broadcast). Validation chain: auth → member → rate limit → emoji regex → message exists → message.type != SYSTEM → message.deleted_at IS NULL → toggle DB + broadcast. (6) **STOMP broadcast `REACTION_CHANGED`** — xem SOCKET_EVENTS.md §3.16: payload `{messageId, userId, userName, action: ADDED|REMOVED|CHANGED, emoji, previousEmoji}`. `emoji` null khi REMOVED; `previousEmoji` null khi ADDED. (7) **Error codes mới**: `REACTION_INVALID_EMOJI`, `REACTION_NOT_ALLOWED_FOR_SYSTEM` (supersedes placeholder `SYSTEM_MESSAGE_NO_REACTIONS` từ W7-D4 rule 4), `REACTION_MSG_DELETED`. Reuse `MSG_NOT_FOUND`, `NOT_MEMBER`, `MSG_RATE_LIMITED`. Ngoại lệ anti-enum: 2 error REACTION_* không merge vào MSG_NOT_FOUND (visible type không leak, giống SYSTEM_MESSAGE_NOT_EDITABLE). (8) **Rate limit**: 5 reactions/second/user — Redis `rate:msg-react:{userId}` INCR EX 1. Looser hơn send/edit/delete (30/phút, 10/phút, 10/phút) vì quick-react batch UX hợp lệ. (9) **BE load pattern BLOCKING N+1**: batch query `WHERE message_id IN (?)` trong pagination list → group in-memory; KHÔNG @OneToMany LAZY fetch trong loop. (10) **ADR-022** thêm vào ARCHITECTURE.md §12 canonical (ghi chú legacy "ADR-022 Soft-deleted strip" + "ADR-021 Content XOR / Hybrid Visibility" trong reviewer-knowledge.md là memory notes — sẽ consolidate sau W8, không block D1). (11) Additive-only: không breaking existing clients — FE cũ không biết `reactions` field tolerate missing (JSON optional); FE mới đọc `reactions` default empty array. KHÔNG đụng Auth / Users / Files / SYSTEM messages (beyond rule 4 supersede). BLOCKING cho BE W8-D1: UNIQUE constraint + toggle atomic, emoji regex validate server-side (defense vs client), batch load N+1 mitigation, supersede error code name. BLOCKING cho FE W8-D1: `@emoji-mart/react` lazy-load nếu bundle budget strict; ReactionBar render sort count DESC stable; optimistic toggle + broadcast reconcile idempotent. |
| 2026-04-22 | v1.4.0-w7-read | **W7-D5 Read Receipts + unreadCount real compute**. (1) **Migration V12** (`V12__add_last_read_message_id.sql`): ADD COLUMN `conversation_members.last_read_message_id UUID REFERENCES messages(id) ON DELETE SET NULL` + index `(conversation_id, last_read_message_id)`. Additive, nullable, non-breaking. Rows cũ NULL. FK `ON DELETE SET NULL` (không CASCADE/RESTRICT — rationale: giữ member khi hard-delete message, auto-reset pointer thay vì block). BE MUST validate composite `message.conversation_id == member.conversation_id` ở application level (DB không enforce compound FK). (2) **MemberDto extended shape**: thêm field `lastReadMessageId: uuid | null` vào member object trả về từ `GET /api/conversations/{id}` members[], `POST /api/conversations` response, STOMP `MEMBER_ADDED` `member` (new member luôn null). BE MUST NOT compute readBy list per message — FE tự filter `members[]` theo `lastReadMessageAt >= message.createdAt` (exclude sender). Pattern code snippet documented. (3) **`unreadCount` compute rule**: placeholder 0 → real compute `COUNT(messages WHERE conv_id = X AND created_at > lastRead.created_at AND type != 'SYSTEM' AND deleted_at IS NULL)`. `lastReadMessageId = null` → count all non-SYSTEM non-deleted. Cap `LEAST(count, 99)` (UX "99+"). Per-caller (mỗi user `GET /api/conversations` thấy unreadCount của chính mình). **SYSTEM KHÔNG count** (đổi từ rule v1.2.0-w7-system — xem "Validation rules cho SYSTEM messages" rule 3 superseded note: rationale: system msg fire rất nhiều trong group active, count vào unread làm badge phình to vô nghĩa; nhất quán với mọi chat app lớn). (4) **STOMP `/app/conv.{convId}.read`** — xem SOCKET_EVENTS.md v1.9-w7 §3f: payload `{messageId}` (KHÔNG có clientId), idempotent forward-only (compare `createdAt`, incoming <= current → silent no-op không broadcast), broadcast `READ_UPDATED` §3.13 lên `/topic/conv.{id}` sau commit. Error codes: `AUTH_REQUIRED`, `NOT_MEMBER`, `VALIDATION_FAILED`, `MSG_NOT_FOUND` (anti-enum merge cả "not in conv"), `MSG_NOT_IN_CONV` (reserved, không emit V1), `MSG_RATE_LIMITED` (1 event/2s/user/conv), `INTERNAL`. (5) **Conversations API header** bump v1.1.0-w7 → v1.4.0-w7-read (skip v1.3.0-w7 — sync version number với SOCKET_EVENTS.md v1.7 → v1.9 nhịp bump cùng W7-D5; v1.2.0-w7-system giữ tag cho SYSTEM messages trước đó). (6) KHÔNG đụng Auth / Users / Messages POST/GET / Files sections. KHÔNG breaking shape cho existing — additive field + same-type `unreadCount: number`. (7) Contract test guidance: 7 test cases documented (empty conv, caller null-read, SYSTEM loại, newest-read, soft-deleted loại, cap 99, multi-tab self-echo). BLOCKING cho BE W7-D5: idempotent forward-only logic (compare createdAt, KHÔNG compare UUID), SYSTEM + soft-deleted filter trong `unreadCount` COUNT, FK `ON DELETE SET NULL` (không CASCADE), validate cross-conv composite trong application layer. BLOCKING cho FE W7-D5: readBy client-side compute, optimistic `unreadCount = 0` khi self-echo READ_UPDATED, debounce 500ms trước khi fire STOMP `.read` + server rate limit 2s defense-in-depth. |
| 2026-04-22 | v1.2.0-w7-system | **W7-D4 SYSTEM messages**. Extend `MessageDto` (additive, non-breaking cho TEXT path) với 2 optional field: `systemEventType` (enum 8 values: `GROUP_CREATED`, `MEMBER_ADDED`, `MEMBER_REMOVED`, `MEMBER_LEFT`, `ROLE_PROMOTED`, `ROLE_DEMOTED`, `OWNER_TRANSFERRED`, `GROUP_RENAMED`) + `systemMetadata` (JSONB `{actorId, actorName, targetId?, targetName?, oldValue?, newValue?, autoTransferred?}`). `sender` = `null` cho SYSTEM (rationale: tránh maintain "system user" row). `content` = `""` (empty string, không null — FE render từ metadata). `type='SYSTEM'` bổ sung vào type enum cho type field của message (trước đây đã declare trong enum nhưng chưa có usage). **BE publish policy**: sau khi service method commit xong → save SYSTEM row + publish `MessageCreatedEvent` → reuse broadcast pipe (`/topic/conv.{id}` MESSAGE_CREATED) — KHÔNG tạo STOMP event type mới. Mapping service → SYSTEM: `createGroup` → 1× GROUP_CREATED; `addMembers` → N× MEMBER_ADDED (per user added, KHÔNG tạo cho skipped); `removeMember` → MEMBER_REMOVED; `leaveGroup` non-OWNER → MEMBER_LEFT; `leaveGroup` OWNER auto-transfer → OWNER_TRANSFERRED (autoTransferred=true) **TRƯỚC** MEMBER_LEFT (ordering BLOCKING); `changeRole` → ROLE_PROMOTED hoặc ROLE_DEMOTED (no-op silent không fire); `transferOwner` explicit → OWNER_TRANSFERRED (autoTransferred=false); `updateGroupInfo` rename → GROUP_RENAMED (chỉ nếu `oldName != newName` trim-compare); `updateGroupInfo` avatar-only → **KHÔNG** fire SYSTEM msg V1 (CONVERSATION_UPDATED đã notify UI; V2 xem xét). **Validation**: SYSTEM NOT editable → `SYSTEM_MESSAGE_NOT_EDITABLE` (403 REST / STOMP); NOT deletable → `SYSTEM_MESSAGE_NOT_DELETABLE` (403 REST / STOMP); count towards unread; không reactions V1; không quote/reply (`VALIDATION_FAILED`); pagination GET /messages trả inline với TEXT. **Migration V10** (`V10__add_system_message_fields.sql`): ADD COLUMN `system_event_type VARCHAR(50)` + `system_metadata JSONB`; ALTER `sender_id` DROP NOT NULL (SYSTEM không có user sender); CHECK constraint `chk_message_system_consistency` (type='SYSTEM' ↔ system_event_type non-null AND sender_id null; type!=SYSTEM ↔ cả 2 system field null). 2 error codes mới (ngoại lệ anti-enum documented). KHÔNG đổi shape REST POST /messages (endpoint deprecated, không ảnh hưởng). KHÔNG đổi STOMP SEND payload — client vẫn không gửi SYSTEM (server-only). BLOCKING cho BE W7-D4: order OWNER_TRANSFERRED trước MEMBER_LEFT trong leaveGroup OWNER path; insert SYSTEM msg TRƯỚC delete member row trong removeMember; CHECK constraint catch dirty INSERT. |
| 2026-04-21 | v1.1.0-w7 | **W7-D2 contract finalize** cho 5 member management endpoints trước BE/FE implement. Changes vs v1.0.0-w7: (1) **POST /{id}/members** response đổi từ single-shape `{added[]}` → partial-success `{added[], skipped[{userId, reason: ALREADY_MEMBER|USER_NOT_FOUND|BLOCKED}]}` — 201 Created thay vì 200; status 201 khi có ít nhất 1 added hoặc khi tất cả skipped (vẫn success). `GROUP_FULL` → `MEMBER_LIMIT_EXCEEDED` (409, details `{currentCount, attemptedCount, limit: 50}`, all-or-nothing). `GROUP_MEMBER_NOT_FOUND` + `GROUP_MEMBER_ALREADY_IN` deprecated — chuyển sang per-user `skipped[].reason`. (2) **DELETE /{id}/members/{uid}**: `CANNOT_REMOVE_SELF` → `CANNOT_KICK_SELF`; `GROUP_MEMBER_NOT_FOUND` → `MEMBER_NOT_FOUND`; `CANNOT_REMOVE_OWNER` removed (merged vào INSUFFICIENT_PERMISSION). Thêm user-specific broadcast `/user/{kickedUserId}/queue/conv-removed` với `{conversationId, reason: "KICKED"}`. (3) **POST /{id}/leave**: thêm `CANNOT_LEAVE_EMPTY_GROUP` (400) khi OWNER là member duy nhất — V1 không auto-delete. OWNER leave auto-transfer nay fire `OWNER_TRANSFERRED` (với `autoTransferred: true`) thay vì `ROLE_CHANGED` — consistent với `/transfer-owner`. Thứ tự broadcast: OWNER_TRANSFERRED TRƯỚC MEMBER_REMOVED. `MEMBER_REMOVED` với `reason: "LEFT"` có `removedBy: null` (khác KICKED: non-null). `SELECT FOR UPDATE` trên caller row chống race W7-1. (4) **PATCH /role**: response bỏ `oldRole`, thêm `changedBy: {userId, username}`. No-op (newRole == currentRole) → 200 OK idempotent KHÔNG broadcast. `INVALID_ROLE_CHANGE` tách thành 3 codes: `INVALID_ROLE` (400, body `role=OWNER`), `CANNOT_CHANGE_OWNER_ROLE` (403, target=OWNER), silent-idempotent cho no-op. `GROUP_MEMBER_NOT_FOUND` → `MEMBER_NOT_FOUND`. (5) **POST /transfer-owner**: body field `newOwnerId` → `targetUserId` (rename for consistency path `/members/{userId}`). Response `oldOwner` → `previousOwner` + thêm `username`. `INVALID_ROLE_CHANGE` → `CANNOT_TRANSFER_TO_SELF`. Broadcast OWNER_TRANSFERRED với `autoTransferred: false`. (6) **Authorization matrix appendix** finalize + thêm "error code → HTTP status cheat sheet". (7) **Add user-specific STOMP destinations** `/user/{userId}/queue/conv-added` (ConversationSummaryDto) + `/user/{userId}/queue/conv-removed` (`{conversationId, reason}`) — xem SOCKET_EVENTS.md §2 + §3.7/3.8. KHÔNG đụng schema, không migration mới. BLOCKING cho BE W7-D2 implementation: tên code mới, response shape mới, broadcast ordering mới. |
| 2026-04-21 | v1.0.0-w7 | **W7-D1 Group Chat backfill** (major bump — W3 spec gốc yêu cầu group chat + role management nhưng đã skip, W7 backfill). Schema V7 migration (`V7__add_group_chat.sql`): CREATE TYPE `member_role` ENUM ('OWNER','ADMIN','MEMBER'); ALTER `conversation_members` ADD role + joined_at; ALTER `conversations` ADD name + avatar_file_id (FK → files) + owner_id (FK → users, ON DELETE SET NULL); CHECK constraint `chk_group_metadata` (GROUP phải có name+owner, ONE_ON_ONE phải cả 2 NULL); indexes conv+role, conv+joined_at, owner (partial). `POST /api/conversations` update payload: ONE_ON_ONE dùng `targetUserId` (backward-compat với memberIds[0]); GROUP bắt buộc `name` (1-100) + `memberIds` (2-49) + `avatarFileId` optional. Response shape thêm `owner: {userId, username, fullName} | null`. 7 endpoints mới: PATCH /{id} (rename/avatar, OWNER+ADMIN), DELETE /{id} (soft delete, OWNER only), POST /{id}/members (add batch ≤10, OWNER+ADMIN), DELETE /{id}/members/{uid} (kick, role matrix), POST /{id}/leave (any member, OWNER triggers auto-transfer), PATCH /{id}/members/{uid}/role (ADMIN↔MEMBER, OWNER only), POST /{id}/transfer-owner (OWNER only, atomic 2-way swap). Auto-transfer rule khi OWNER leave: oldest-ADMIN → oldest-MEMBER → NULL (empty group preserved V1, monitoring alert >7d). Error codes mới (15): GROUP_NAME_REQUIRED, GROUP_MEMBERS_MIN, GROUP_MEMBERS_MAX, GROUP_MEMBER_NOT_FOUND, GROUP_AVATAR_NOT_OWNED, GROUP_AVATAR_NOT_IMAGE, GROUP_MEMBER_ALREADY_IN, GROUP_FULL, NOT_GROUP, INSUFFICIENT_PERMISSION, CANNOT_REMOVE_OWNER, CANNOT_REMOVE_SELF, INVALID_ROLE_CHANGE. Appendix Authorization Matrix. ADR-020 (Group Chat Architecture). WARNINGS W7-1/2/3 (auto-transfer race, empty group edge case, max-50 enforcement with FOR UPDATE lock). |
| 2026-04-21 | v0.9.5-files-extended | **W6-D4-extend**: Mở rộng MIME whitelist từ 5 type (image jpeg/png/webp/gif + pdf) sang 14 type chia 2 group: Group A (images, gallery 1–5/message) + Group B (non-image: pdf, docx, xlsx, pptx, doc, xls, ppt, txt, zip, 7z — exactly 1/message). Documented blacklist (executables, scripts, XSS vectors svg/html). Mixing rules formalized: Group A only OR exactly 1 Group B; trộn → `MSG_ATTACHMENTS_MIXED`; 2+ Group B → `MSG_ATTACHMENTS_MIXED`. Thêm field `iconType` vào `FileDto`: enum `IMAGE | PDF | WORD | EXCEL | POWERPOINT | TEXT | ARCHIVE | GENERIC` server-computed từ MIME (FE dùng để chọn icon, không bind MIME trực tiếp → dễ extend whitelist sau). Thumbnail endpoint vẫn chỉ phục vụ Group A images; Group B trả 404 (FE fallback `iconType` icon static). Update `MSG_ATTACHMENTS_TOO_MANY` chỉ cho >5 images; >1 Group B fall vào `MSG_ATTACHMENTS_MIXED`. WARNINGS thêm office macro risk + blacklist maintenance cycle. |
| 2026-04-20 | v0.9.0-files | **W6-D1**: Thêm Files Management section với `FileDto` shape dùng chung (id, mime, name, size, url, thumbUrl, expiresAt). 3 endpoints: `POST /api/files/upload` (multipart, 20MB max, rate limit 20/phút, MIME whitelist jpeg/png/webp/gif/pdf, MIME verify qua Apache Tika magic bytes — khác `Content-Type` header → `MIME_MISMATCH`), `GET /api/files/{id}` (auth = uploader OR member của conv chứa attachment, 404 merge cho expired + not-found + forbidden, Content-Disposition inline + X-Content-Type-Options nosniff), `GET /api/files/{id}/thumb` (image 200×200 JPEG lazy-generate cache, PDF/non-image trả 404). Update `MessageDto` thêm `attachments: FileDto[]` (luôn là array, không null), `content` nullable khi có attachments. Validation SEND+EDIT với attachments: phải có content HOẶC attachments (`MSG_NO_CONTENT`), images 1-5 OR pdf đúng 1, không trộn (`MSG_ATTACHMENTS_MIXED`, `MSG_ATTACHMENTS_TOO_MANY`), per-file check `MSG_ATTACHMENT_NOT_OWNED/ALREADY_USED/NOT_FOUND/EXPIRED`. EDIT KHÔNG sửa attachments V1 (chỉ sửa content). Error codes mới: `FILE_TOO_LARGE` (413), `FILE_TYPE_NOT_ALLOWED` (415), `FILE_EMPTY` (400), `MIME_MISMATCH` (415), `STORAGE_FAILED` (500), và 7 MSG_* codes ở trên. Soft-delete message strip cả `content=null` + `attachments=[]` (W5-D3 mở rộng cho W6). ADR-019 quyết local disk + StorageService interface, migration V7 thêm `files` + `message_attachments`. |
| 2026-04-20 | v0.6.2-messages-after-param | **W5-D4**: GET /api/conversations/{convId}/messages thêm `after` param (forward pagination, ORDER ASC, include deleted). `cursor` và `after` mutually exclusive (400 nếu dùng cùng nhau). `ReplyPreviewDto` thêm field `deletedAt` (null nếu source chưa bị xóa, ISO8601 nếu đã bị xóa) và `contentPreview` = null khi source deleted. STOMP `SendMessagePayload` thêm `replyToMessageId` (nullable UUID) với validation: source phải thuộc cùng conversation, quoting deleted source allowed. |
| 2026-04-20 | v0.6.1-messages-stomp-shift | **ADR-016**: POST /api/conversations/{convId}/messages được **deprecated** cho FE hot path. FE chuyển sang STOMP `/app/conv.{id}.message` với tempId (xem SOCKET_EVENTS.md v1.1-w4). Endpoint REST KHÔNG bị xoá — giữ cho batch import, bot API, integration testing, và fallback. Shape response không đổi. |
| 2026-04-19 | v0.6.0-messages-rest | Thêm Messages API: POST /api/conversations/{convId}/messages (gửi tin nhắn), GET /api/conversations/{convId}/messages (lịch sử, cursor-based). Rate limit 30/min. Anti-enumeration 404 cho non-member. ReplyPreviewDto shallow 1-level. nextCursor = createdAt của item cũ nhất. |
| 2026-04-19 | v0.5.2-conversations | Thêm `GET /api/users/{id}` vào mục Users (W3D4). Response dùng lại `UserSearchDto` shape (không expose email/status/lastSeenAt). Merge 404 `USER_NOT_FOUND` cho cả not-exist + inactive để chống enumeration. Documented `last_seen_at` column đã add ở V4 migration nhưng KHÔNG expose ở V1 (xem WARNINGS.md). |
| 2026-04-19 | v0.5.1-conversations | POST /api/conversations: đổi rate limit từ "30/giờ" → "10/phút" để khớp implementation; rate limit block giờ trả `details.retryAfterSeconds` với TTL thực từ Redis. |
| 2026-04-19 | v0.5.0-conversations | Thêm 4 endpoints Conversations phase: POST /api/conversations, GET /api/conversations, GET /api/conversations/{id}, GET /api/users/search. Chốt UPPERCASE ONE_ON_ONE/GROUP (ADR-012). Chốt pattern auth-merge-with-404 cho GET detail (không leak existence). Idempotency ONE_ON_ONE trả 409 CONV_ONE_ON_ONE_EXISTS kèm conversationId. Noted race dup và soft-leave/soft-hide out-of-scope V1. |
| 2026-04-19 | v0.3.0-auth | POST /api/auth/refresh implemented + contract sync: rate limit đổi sang 10 req/60s/userId (khớp implementation); reuse case trả `AUTH_REFRESH_TOKEN_INVALID` (bỏ tên `REFRESH_TOKEN_REUSED` trong note vì error code thực tế là INVALID); bổ sung note revoke-all-sessions khi detect reuse. |
| 2026-04-19 | v0.2.1-auth | Thêm note phân biệt AUTH_REQUIRED vs AUTH_TOKEN_EXPIRED vào mục Error codes dùng chung. |
| 2026-04-19 | v0.2-auth | Thêm 5 Auth endpoints: register, login, oauth, refresh, logout. Chốt contract cho BE/FE tuần 1. |
| (khởi tạo) | v0.1 | Initial skeleton, chưa có endpoint. |
