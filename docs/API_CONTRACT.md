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

## Conversations API (v1.0.0-w7 — Group Chat)

> **Version bump W7 (2026-04-21)**: v0.9.5-files-extended → **v1.0.0-w7**. Lý do major bump: thêm full Group Chat management (W7-D1) — role enum (OWNER/ADMIN/MEMBER), metadata group (name/avatar/owner), + 7 endpoints mới (PATCH/DELETE conv, add/remove/leave/role-change/transfer-owner). Xem ADR-020. Schema migration V7 (`V7__add_group_chat.sql`) thêm `member_role` enum + columns `joined_at`, `conversations.name/avatar_file_id/owner_id` + CHECK constraint type-specific. Breaking: `POST /api/conversations` payload cho GROUP đổi — thêm `name` + `avatarFileId` optional; `type="GROUP"` BẮT BUỘC có `name` + `memberIds` ≥ 2 (tổng member ≥ 3 gồm caller).
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
- `unreadCount`: V1 placeholder — luôn trả `0` cho tới khi implement unread counter (Redis `unread:{userId}:{convId}`) ở tuần 4. FE phải handle `number` field, không assume > 0.
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
      "joinedAt": "ISO8601"
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
- BE implement hiện đã có `ConversationRepository.findByIdWithMembers(UUID)` (JOIN FETCH) — dùng trực tiếp, tránh N+1. W7 cập nhật query include `role` + `joined_at` + LEFT JOIN `users` cho `owner` (có thể NULL sau cascade).

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

**Description** (W7-D1): Thêm 1 hoặc nhiều user vào GROUP conversation. Batch tối đa 10 mỗi request.

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
- `userIds`: bắt buộc array UUID, length 1-10. Không duplicate.
- Mỗi user phải `status='active'`.
- User không được là current member (else `GROUP_MEMBER_ALREADY_IN`).
- Tổng sau add không vượt 50 (else `GROUP_FULL`). BE dùng `SELECT COUNT(*) FROM conversation_members WHERE conversation_id = :id FOR UPDATE` trước INSERT để chống race (xem WARNINGS W7-3).

**Authorization**: `OWNER` hoặc `ADMIN`. MEMBER → `INSUFFICIENT_PERMISSION`.

**Response 200**:

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
  ]
}
```

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | userIds rỗng / > 10 / duplicate. |
| 400 | `NOT_GROUP` | Conv là ONE_ON_ONE. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 403 | `INSUFFICIENT_PERMISSION` | Caller là MEMBER. |
| 404 | `CONV_NOT_FOUND` | Conv không tồn tại hoặc caller không phải member. |
| 404 | `GROUP_MEMBER_NOT_FOUND` | 1+ userIds không tồn tại hoặc inactive (details.missingIds). |
| 409 | `GROUP_MEMBER_ALREADY_IN` | 1+ userIds đã là member (details.alreadyMemberIds). |
| 409 | `GROUP_FULL` | Sau add > 50 members. details.currentCount + details.attemptedCount. |
| 429 | `RATE_LIMITED` | Vượt quota. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Broadcast**: fire `MEMBER_ADDED` event qua `/topic/conv.{id}` với list users mới + `addedBy`. Xem SOCKET_EVENTS.md §3.7.

**Notes**:
- Transaction boundary: validate → COUNT FOR UPDATE → INSERT batch → publish event. Tất cả trong 1 `@Transactional`.
- New members default `role='MEMBER'`, `joined_at=NOW()`. OWNER không thể add directly với role khác — dùng PATCH role riêng sau.

---

### DELETE /api/conversations/{id}/members/{userId}

**Description** (W7-D1): Kick user ra khỏi GROUP conversation. Hard-delete row `conversation_members` (không soft-leave V1). Đồng thời force-unsubscribe user khỏi `/topic/conv.{id}` (V1 chấp nhận client chỉ bị filter qua member-check tại lần SUBSCRIBE tiếp theo — xem Limitations SOCKET_EVENTS.md §8).

**Auth required**: Yes

**Rate limit**: 20 requests/phút/user.

**Path params**: `id` (conversation UUID), `userId` (target user UUID).

**Authorization matrix**:
- **OWNER**: có thể kick bất kỳ ai **trừ chính mình** (OWNER self-kick = transfer-then-leave, dùng endpoint `/leave` thay).
- **ADMIN**: chỉ kick được `MEMBER`. Kick ADMIN khác hoặc OWNER → `INSUFFICIENT_PERMISSION`.
- **MEMBER**: không kick được ai → `INSUFFICIENT_PERMISSION`.

**Response 204**: No Content.

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `NOT_GROUP` | Conv là ONE_ON_ONE. |
| 400 | `CANNOT_REMOVE_OWNER` | Target là OWNER (chỉ OWNER mới được remove OWNER, mà OWNER không remove chính mình — dùng `/leave` thay). |
| 400 | `CANNOT_REMOVE_SELF` | Caller = target qua endpoint này (dùng `/leave`). |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 403 | `INSUFFICIENT_PERMISSION` | ADMIN kick ADMIN/OWNER; MEMBER kick bất kỳ. |
| 404 | `CONV_NOT_FOUND` | Conv không tồn tại hoặc caller không phải member. |
| 404 | `GROUP_MEMBER_NOT_FOUND` | Target userId không phải member của conv. |
| 429 | `RATE_LIMITED` | Vượt quota. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Broadcast**: fire `MEMBER_REMOVED` với `reason: "KICKED"` + `removedBy`. Target user FE nhận → navigate away khỏi conv (nếu đang mở) + remove khỏi sidebar. Xem SOCKET_EVENTS.md §3.8.

---

### POST /api/conversations/{id}/leave

**Description** (W7-D1): Member tự rời khỏi GROUP conversation. Bất kỳ role nào cũng dùng được (MEMBER/ADMIN/OWNER). Khi OWNER leave → **auto-transfer** ownership theo rule dưới.

**Auth required**: Yes

**Rate limit**: 10 requests/phút/user.

**Path params**: `id` (UUID).

**Request body**: Không có (empty body).

**Authorization**: Bất kỳ member nào (MEMBER / ADMIN / OWNER).

**Response 204**: No Content.

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `NOT_GROUP` | Conv là ONE_ON_ONE (V1 không cho leave DIRECT — dùng block hoặc ẩn hội thoại, chưa implement). |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 404 | `CONV_NOT_FOUND` | Conv không tồn tại hoặc caller không phải member. |
| 429 | `RATE_LIMITED` | Vượt quota. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Auto-transfer logic (OWNER leave only)**:

1. SELECT ADMIN có `joined_at` nhỏ nhất (oldest ADMIN) — nếu có → promote thành OWNER.
2. Nếu không có ADMIN — SELECT MEMBER oldest `joined_at` → promote thành OWNER.
3. Nếu không còn ai khác (OWNER là member cuối) — set `conversations.owner_id = NULL` (DB cho phép SET NULL theo FK) + **giữ group**. V1 không auto-delete empty group — xem WARNINGS W7-2. Empty group sẽ bị alert monitoring sau 7 ngày.
4. Bước 1-3 dùng `SELECT ... FOR UPDATE` trên `conversation_members` rows để tránh race với concurrent kick/leave (xem WARNINGS W7-1).
5. Fire 2 broadcasts atomic-in-order:
   - `ROLE_CHANGED` (với newOwner info) NẾU có transfer.
   - `MEMBER_LEFT` với `reason: "LEFT"` cho caller.

**Notes**:
- Sau leave, caller KHÔNG còn subscribe `/topic/conv.{id}` (member check fail ở SUBSCRIBE lần sau; V1 chấp nhận currently-open subscription vẫn nhận event cho đến unsubscribe — xem SOCKET_EVENTS.md §8 Limitations).
- OWNER self-kick qua `DELETE /members/{self-id}` → `CANNOT_REMOVE_SELF`. Dùng `/leave` thay.

---

### PATCH /api/conversations/{id}/members/{userId}/role

**Description** (W7-D1): Thay đổi role của 1 member. OWNER-only (V1).

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
- `role`: enum strict `"ADMIN" | "MEMBER"`. Promote thành `"OWNER"` phải dùng `/transfer-owner` thay (V1 phân tách flow).

**Authorization**: `OWNER` only (V1). ADMIN/MEMBER → `INSUFFICIENT_PERMISSION`.

**Response 200**:

```json
{
  "userId": "uuid",
  "oldRole": "MEMBER",
  "newRole": "ADMIN",
  "changedAt": "ISO8601"
}
```

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `role` không đúng enum. |
| 400 | `NOT_GROUP` | Conv là ONE_ON_ONE. |
| 400 | `INVALID_ROLE_CHANGE` | target là OWNER (đổi OWNER role qua endpoint này cấm — dùng transfer); hoặc new role = current role (no-op); hoặc target == caller (OWNER self-demote cấm — dùng transfer). |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 403 | `INSUFFICIENT_PERMISSION` | Caller không phải OWNER. |
| 404 | `CONV_NOT_FOUND` | Conv không tồn tại hoặc caller không phải member. |
| 404 | `GROUP_MEMBER_NOT_FOUND` | Target userId không phải member. |
| 429 | `RATE_LIMITED` | Vượt quota. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Broadcast**: fire `ROLE_CHANGED` event với `userId + newRole + changedBy`. Xem SOCKET_EVENTS.md §3.9.

---

### POST /api/conversations/{id}/transfer-owner

**Description** (W7-D1): OWNER chuyển quyền sở hữu cho 1 member khác. Current OWNER → ADMIN, new user → OWNER. Atomic 2-way swap.

**Auth required**: Yes

**Rate limit**: 10 requests/phút/user.

**Path params**: `id` (UUID).

**Request body**:

```json
{
  "newOwnerId": "uuid"
}
```

Validation rules:
- `newOwnerId`: bắt buộc UUID, phải là current member của conv. Khác caller id (self-transfer = no-op `INVALID_ROLE_CHANGE`).

**Authorization**: `OWNER` only.

**Response 200**:

```json
{
  "oldOwner": { "userId": "uuid", "newRole": "ADMIN" },
  "newOwner": { "userId": "uuid", "newRole": "OWNER" }
}
```

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | newOwnerId malformed UUID. |
| 400 | `NOT_GROUP` | Conv là ONE_ON_ONE. |
| 400 | `INVALID_ROLE_CHANGE` | newOwnerId = callerId. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 403 | `INSUFFICIENT_PERMISSION` | Caller không phải OWNER. |
| 404 | `CONV_NOT_FOUND` | Conv không tồn tại hoặc caller không phải member. |
| 404 | `GROUP_MEMBER_NOT_FOUND` | newOwnerId không phải member của conv. |
| 429 | `RATE_LIMITED` | Vượt quota. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Broadcast**: fire `OWNER_TRANSFERRED` event (FE dùng để update UI + sidebar owner indicator). Xem SOCKET_EVENTS.md §3.10.

**Notes**:
- Transaction boundary: `SELECT ... FOR UPDATE` cả 2 rows (current OWNER + newOwnerId) → UPDATE cả 2 role → UPDATE `conversations.owner_id = newOwnerId` → publish event. Tất cả 1 `@Transactional`.
- Khác với `/leave` + auto-transfer: `/transfer-owner` giữ OWNER cũ trong group (downgrade thành ADMIN). `/leave` sẽ remove OWNER.

---

### Appendix — Group Chat Authorization Matrix (W7)

| Action | OWNER | ADMIN | MEMBER |
|--------|-------|-------|--------|
| Create group (POST /conversations type=GROUP) | N/A (caller becomes OWNER) | - | - |
| Rename group / Change avatar (PATCH /{id}) | ✓ | ✓ | ✗ |
| Delete group (DELETE /{id}) | ✓ | ✗ | ✗ |
| Add members (POST /{id}/members) | ✓ | ✓ | ✗ |
| Remove MEMBER (DELETE /{id}/members/{uid}) | ✓ | ✓ | ✗ |
| Remove ADMIN (DELETE /{id}/members/{uid}) | ✓ | ✗ | ✗ |
| Remove OWNER via kick | ✗ (use `/leave`) | ✗ | ✗ |
| Change role ADMIN↔MEMBER (PATCH /{id}/members/{uid}/role) | ✓ | ✗ | ✗ |
| Transfer ownership (POST /{id}/transfer-owner) | ✓ | ✗ | ✗ |
| Leave group (POST /{id}/leave) | ✓* | ✓ | ✓ |
| View members (GET /{id}) | ✓ | ✓ | ✓ |
| Send/Edit/Delete own messages | ✓ | ✓ | ✓ |

\* OWNER leave triggers auto-transfer (oldest-ADMIN → oldest-MEMBER → NULL if alone).

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

## Messages API (v0.6.0-messages-rest — W4-D1)

Base URL: `/api/conversations/{convId}/messages`

Auth: Bearer JWT bắt buộc cho tất cả endpoints.

### MessageDto shape

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

**Updated MessageDto shape**:

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

## Changelog

| Ngày | Version | Nội dung |
|------|---------|---------|
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
