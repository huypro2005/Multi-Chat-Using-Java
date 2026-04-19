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

## Conversations API (v0.5.0-conversations — W3-D2+)

> **Naming note**: V1 đơn giản hoá so với ARCHITECTURE.md mục 3.2:
> - `type` dùng UPPERCASE `ONE_ON_ONE` | `GROUP` (không phải lowercase `direct`/`group` trong ARCHITECTURE gốc). Xem ADR-012.
> - Bỏ `left_at` / `leave_reason` (soft-leave), `is_hidden` / `cleared_at` (soft-hide) khỏi V1 migration. Khi cần tính năng "rời nhóm" hoặc "xoá lịch sử chat" sẽ thêm migration V4. Trong scope tuần 3, rời nhóm = hard-delete row `conversation_members` (qua endpoint riêng sẽ design ở tuần 5-6).
> - `muted_until` (snake_case → DB) / `mutedUntil` (camelCase → JSON) có mặt nhưng endpoint mute sẽ chốt sau.
>
> Nguyên tắc chung cho mọi endpoint trong nhóm này:
> - **Auth required**: Bearer JWT trên header `Authorization: Bearer <accessToken>`. Thiếu → `401 AUTH_REQUIRED`; hết hạn → `401 AUTH_TOKEN_EXPIRED`.
> - **IDs là UUID v4** (string). Mọi reference user/conversation/member trong body/response đều là UUID.
> - **Timestamp là ISO-8601 với offset** (ví dụ `"2026-04-19T10:00:00Z"` hoặc `"2026-04-19T17:00:00+07:00"`).

---

### POST /api/conversations

**Description**: Tạo conversation mới (1-1 hoặc nhóm). Caller tự động trở thành thành viên với role `OWNER`.

**Auth required**: Yes

**Rate limit**: 10 requests/phút/user (chống spam tạo nhóm).

**Request body**:

```json
{
  "type": "ONE_ON_ONE",
  "name": null,
  "memberIds": ["9b1a7c16-4c72-4a2a-a8f6-abc111222333"]
}
```

Validation rules:
- `type`: bắt buộc, enum `"ONE_ON_ONE" | "GROUP"`.
- `name`:
  - `ONE_ON_ONE`: phải là `null` (server không dùng field này; gửi string sẽ bị bỏ qua hoặc trả `VALIDATION_FAILED` — để rõ ràng FE gửi `null`).
  - `GROUP`: bắt buộc, 1–100 ký tự, không toàn whitespace.
- `memberIds`: bắt buộc, array UUID, **không chứa UUID của caller** (caller tự add qua OWNER role). Không được có phần tử trùng. Giới hạn độ dài:
  - `ONE_ON_ONE`: exactly 1 phần tử (sẽ có tổng 2 members gồm caller + 1 user kia).
  - `GROUP`: tối thiểu 2, tối đa 49 (cộng caller = 50, khớp giới hạn V1 "tối đa 50 thành viên" trong ARCHITECTURE).

**Response 201**:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "type": "ONE_ON_ONE",
  "name": null,
  "avatarUrl": null,
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
      "joinedAt": "2026-04-19T10:00:00Z"
    },
    {
      "userId": "7c1a7c16-4c72-4a2a-a8f6-def444555666",
      "username": "bob",
      "fullName": "Bob Tran",
      "avatarUrl": null,
      "role": "MEMBER",
      "joinedAt": "2026-04-19T10:00:00Z"
    }
  ],
  "createdAt": "2026-04-19T10:00:00Z",
  "lastMessageAt": null
}
```

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | Vi phạm validation rule (type sai enum, memberIds rỗng, GROUP thiếu name, ONE_ON_ONE có name ≠ null, v.v.); kèm `details.fields`. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 404 | `CONV_MEMBER_NOT_FOUND` | Một hoặc nhiều `memberIds` không trỏ tới user tồn tại; kèm `details.missingIds: [uuid, ...]`. |
| 409 | `CONV_ONE_ON_ONE_EXISTS` | Đã tồn tại `ONE_ON_ONE` giữa caller và user kia; kèm `details.conversationId` để FE redirect luôn. |
| 409 | `CONV_MEMBER_BLOCKED` | Caller đã block / bị block bởi 1 trong các `memberIds`; kèm `details.blockedIds: [uuid, ...]`. (V1 nếu `user_blocks` chưa wire, error này không fire — documented để FE sẵn sàng khi bật.) |
| 429 | `RATE_LIMITED` | Vượt 10 requests/phút/user; kèm `details.retryAfterSeconds`. |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Notes**:
- **Idempotency `ONE_ON_ONE`**: server query xem giữa caller và target user đã có `ONE_ON_ONE` chưa. Có → trả `409 CONV_ONE_ON_ONE_EXISTS` với `details.conversationId` (FE điều hướng sang conv cũ). KHÔNG tự động trả 200 với conv cũ — để FE chủ động xử lý UX "bạn đã có chat với người này".
- **Race duplicate ONE_ON_ONE**: 2 request song song có thể cùng pass existence check. V1 chấp nhận edge case (traffic thấp); cần partial UNIQUE index cho lần cleanup V2: `CREATE UNIQUE INDEX ... ON conversations(LEAST(user_a, user_b), GREATEST(user_a, user_b)) WHERE type='ONE_ON_ONE'` (denormalize 2 columns hoặc dùng conv_members swap). Documented trong WARNINGS.md.
- **Role mặc định**: caller = `OWNER`; các `memberIds` khác = `MEMBER`. Không cho phép set role khác qua endpoint này.
- `lastMessageAt` luôn `null` khi vừa tạo — cập nhật khi có message đầu tiên (tuần 4).

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

**Response 200**: cùng shape với response 201 của `POST /api/conversations` — đầy đủ `createdBy` và `members[]` với `role` + `joinedAt`.

**Error responses**:

| HTTP | Error code | Điều kiện |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | `id` không phải UUID hợp lệ. |
| 401 | `AUTH_REQUIRED` / `AUTH_TOKEN_EXPIRED` | Thiếu/expired JWT. |
| 404 | `CONV_NOT_FOUND` | Conversation không tồn tại **HOẶC** caller không phải member (trả cùng code để không leak conversation existence). |
| 500 | `INTERNAL_ERROR` | Lỗi server. |

**Notes**:
- **Authorization merge với not-found**: nếu conv tồn tại nhưng caller không phải member → trả `404 CONV_NOT_FOUND` (không phải `403 AUTH_FORBIDDEN`). Lý do: tránh tiết lộ "conv này tồn tại" qua error code khác nhau. Đây là pattern chống enumeration — thống nhất với cách `/login` dùng cùng error cho user-not-found và wrong-password.
- BE implement hiện đã có `ConversationRepository.findByIdWithMembers(UUID)` (JOIN FETCH) — dùng trực tiếp, tránh N+1.

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
    "contentPreview": "string (max 100 chars + '...' nếu truncated)"
  } | null,
  "editedAt": "ISO8601|null",
  "createdAt": "ISO8601 UTC"
}
```

### POST /api/conversations/{convId}/messages

Gửi tin nhắn vào conversation.

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
| cursor | string (ISO8601) | null | Lấy messages có createdAt < cursor. Null = trang đầu |
| limit | int | 50 | Số messages per page (1-100) |

**Response 200:**
```json
{
  "items": [MessageDto],
  "hasMore": true,
  "nextCursor": "ISO8601 UTC string | null"
}
```

Note:
- `items` sorted **ASC** (cũ nhất đến mới nhất).
- `nextCursor` = `createdAt` của item **cũ nhất** trong page (dùng để lấy page tiếp theo với messages cũ hơn).
- `nextCursor` null khi `hasMore=false`.

**Errors:**
| Code | HTTP | Mô tả |
|------|------|-------|
| VALIDATION_FAILED | 400 | limit ngoài range 1-100, cursor không đúng ISO8601 |
| AUTH_REQUIRED | 401 | Không có JWT |
| CONV_NOT_FOUND | 404 | Conversation không tồn tại hoặc user không phải thành viên |

---

## Files

(reviewer sẽ thêm khi phase file upload bắt đầu)

---

## Changelog

| Ngày | Version | Nội dung |
|------|---------|---------|
| 2026-04-19 | v0.6.0-messages-rest | Thêm Messages API: POST /api/conversations/{convId}/messages (gửi tin nhắn), GET /api/conversations/{convId}/messages (lịch sử, cursor-based). Rate limit 30/min. Anti-enumeration 404 cho non-member. ReplyPreviewDto shallow 1-level. nextCursor = createdAt của item cũ nhất. |
| 2026-04-19 | v0.5.2-conversations | Thêm `GET /api/users/{id}` vào mục Users (W3D4). Response dùng lại `UserSearchDto` shape (không expose email/status/lastSeenAt). Merge 404 `USER_NOT_FOUND` cho cả not-exist + inactive để chống enumeration. Documented `last_seen_at` column đã add ở V4 migration nhưng KHÔNG expose ở V1 (xem WARNINGS.md). |
| 2026-04-19 | v0.5.1-conversations | POST /api/conversations: đổi rate limit từ "30/giờ" → "10/phút" để khớp implementation; rate limit block giờ trả `details.retryAfterSeconds` với TTL thực từ Redis. |
| 2026-04-19 | v0.5.0-conversations | Thêm 4 endpoints Conversations phase: POST /api/conversations, GET /api/conversations, GET /api/conversations/{id}, GET /api/users/search. Chốt UPPERCASE ONE_ON_ONE/GROUP (ADR-012). Chốt pattern auth-merge-with-404 cho GET detail (không leak existence). Idempotency ONE_ON_ONE trả 409 CONV_ONE_ON_ONE_EXISTS kèm conversationId. Noted race dup và soft-leave/soft-hide out-of-scope V1. |
| 2026-04-19 | v0.3.0-auth | POST /api/auth/refresh implemented + contract sync: rate limit đổi sang 10 req/60s/userId (khớp implementation); reuse case trả `AUTH_REFRESH_TOKEN_INVALID` (bỏ tên `REFRESH_TOKEN_REUSED` trong note vì error code thực tế là INVALID); bổ sung note revoke-all-sessions khi detect reuse. |
| 2026-04-19 | v0.2.1-auth | Thêm note phân biệt AUTH_REQUIRED vs AUTH_TOKEN_EXPIRED vào mục Error codes dùng chung. |
| 2026-04-19 | v0.2-auth | Thêm 5 Auth endpoints: register, login, oauth, refresh, logout. Chốt contract cho BE/FE tuần 1. |
| (khởi tạo) | v0.1 | Initial skeleton, chưa có endpoint. |
