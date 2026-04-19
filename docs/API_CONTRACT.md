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

**Rate limit**: 30 requests/15 phút/IP

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
| 429 | `RATE_LIMITED` | Vượt 30 requests/15 phút/IP; kèm `details.retryAfterSeconds` |
| 500 | `INTERNAL_ERROR` | Lỗi server |

**Notes**:
- Nếu cùng refresh token được dùng 2 lần (replay attack), lần 2 trả về `AUTH_REFRESH_TOKEN_INVALID`. Server detect bằng cách: khi rotate, xóa token cũ khỏi Redis; nếu token không còn trong Redis → invalid.
- Endpoint này không yêu cầu Authorization header (access token đã hết hạn nên không có để gửi).

**Refresh Queue Pattern (bắt buộc cho FE):**
Khi nhiều request đồng thời gặp lỗi 401 AUTH_TOKEN_EXPIRED, FE KHÔNG được gọi /refresh song song.
Thay vào đó FE phải:
1. Chặn tất cả request đang pending (đưa vào queue)
2. Chỉ gọi /refresh MỘT LẦN
3. Sau khi nhận token mới, retry toàn bộ queue với accessToken mới
4. Nếu /refresh thất bại → clear token, redirect về /login

Lý do: mỗi refreshToken chỉ dùng được 1 lần (rotating). Nếu 2 request cùng gọi /refresh với cùng token, request thứ 2 sẽ nhận REFRESH_TOKEN_REUSED và bị từ chối.

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

## Conversations

(reviewer sẽ thêm khi phase conversation bắt đầu)

---

## Messages (REST parts)

> Chú ý: Gửi tin nhắn text đi qua WebSocket, không phải REST. Xem `SOCKET_EVENTS.md`.
> REST chỉ cho: đọc lịch sử, search, pin/unpin, upload file.

(reviewer sẽ thêm khi phase messaging bắt đầu)

---

## Files

(reviewer sẽ thêm khi phase file upload bắt đầu)

---

## Changelog

| Ngày | Version | Nội dung |
|------|---------|---------|
| 2026-04-19 | v0.2-auth | Thêm 5 Auth endpoints: register, login, oauth, refresh, logout. Chốt contract cho BE/FE tuần 1. |
| (khởi tạo) | v0.1 | Initial skeleton, chưa có endpoint. |
