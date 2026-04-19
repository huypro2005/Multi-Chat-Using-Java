# API Contract — REST Endpoints

> File này là **source of truth** cho mọi REST API giữa frontend và backend.
> **Chỉ `code-reviewer` agent được sửa file này.** BE và FE đọc và implement theo.
> Khi có conflict giữa code và contract, contract thắng. Muốn đổi → reviewer đổi trước.

## Format chuẩn

Mỗi endpoint theo format:

### HTTP_METHOD /path

**Auth**: required | optional | none

**Request body** (nếu có):
```json
{ "field": "type và mô tả" }
```

**Query params** (nếu có):
| Param | Type | Required | Mô tả |
|-------|------|----------|-------|
| foo   | string | yes   | ... |

**Response 200**:
```json
{ "field": "type và mô tả" }
```

**Errors**:
- `400 ERROR_CODE` — mô tả
- `401 AUTH_REQUIRED` — thiếu/hết hạn JWT
- ...

**Notes**: (tuỳ chọn) — rate limit, business rule, side effect

---

## Error code convention chung

Tất cả error response có shape:
```json
{
  "code": "DOMAIN_REASON",
  "message": "Thông điệp cho người dùng",
  "timestamp": "ISO 8601",
  "details": { /* optional, context-specific */ }
}
```

Mã dùng chung (có thể trả về ở bất kỳ endpoint):
- `AUTH_REQUIRED` (401) — chưa đăng nhập
- `AUTH_EXPIRED` (401) — JWT hết hạn, cần refresh
- `AUTH_FORBIDDEN` (403) — đã login nhưng không có quyền
- `VALIDATION_FAILED` (400) — body/param không hợp lệ, kèm `details.fields`
- `RATE_LIMITED` (429) — vượt rate limit, kèm `details.retryAfter`
- `NOT_FOUND` (404) — resource không tồn tại
- `INTERNAL_ERROR` (500) — lỗi server, FE hiển thị "Có lỗi, thử lại sau"

---

## Authentication

### POST /api/auth/register

(reviewer sẽ điền khi được gọi)

### POST /api/auth/login

(reviewer sẽ điền khi được gọi)

### POST /api/auth/oauth

(reviewer sẽ điền khi được gọi)

### POST /api/auth/refresh

(reviewer sẽ điền khi được gọi)

### POST /api/auth/logout

(reviewer sẽ điền khi được gọi)

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

Mọi thay đổi contract ghi lại ở đây với ngày và lý do:

- YYYY-MM-DD — initial contract skeleton
