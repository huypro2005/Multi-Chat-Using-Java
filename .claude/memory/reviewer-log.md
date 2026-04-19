# Reviewer Log — Nhật ký review

> Append-only, mới nhất ở đầu file.
> Mỗi session review tạo 1 entry.

---

## Template cho entry review

```
## YYYY-MM-DD — Review <branch / task name>

### Verdict
✅ APPROVE / ⚠️ APPROVE WITH COMMENTS / ❌ REQUEST CHANGES

### Files reviewed
- <path>: <1 câu tóm tắt thay đổi>

### Issues found
- [BLOCKING] <vấn đề> — đã yêu cầu fix
- [WARNING] <vấn đề> — gợi ý, không block

### Contract impact
- Có/Không cập nhật contract
```

---

## Template cho entry viết contract

```
## YYYY-MM-DD — Contract update: <feature>

### Thêm vào API_CONTRACT.md
- <endpoint 1>
- <endpoint 2>

### Thêm vào SOCKET_EVENTS.md
- <event>

### Ghi chú
- <quyết định đặc biệt khi thiết kế>
```

---

## Entries

## 2026-04-19 — Contract update: Auth endpoints (tuần 1)

### Thêm vào API_CONTRACT.md
- `POST /api/auth/register` — 5 error codes
- `POST /api/auth/login` — 5 error codes
- `POST /api/auth/oauth` — 6 error codes
- `POST /api/auth/refresh` — 6 error codes
- `POST /api/auth/logout` — 4 error codes

### Thêm vào SOCKET_EVENTS.md
- Không có thay đổi (auth không dùng socket)

### Ghi chú
- Chốt refresh token rotation (mỗi lần refresh phát token mới, invalidate token cũ).
- OAuth auto-link theo email nếu email đã có trong DB (theo ARCHITECTURE.md mục edge case).
- Login rate limit chỉ tính lần thất bại, không tính login thành công.
- Logout yêu cầu gửi refreshToken trong body để server biết token nào cần xóa khỏi Redis.
- `isNewUser` field thêm vào OAuth response (ngoài token shape chuẩn) để FE hiện onboarding.

[2026-04-19] Viết API_CONTRACT.md v0.2-auth — 5 Auth endpoints (register, login, oauth, refresh, logout). Contract chốt, sẵn sàng cho BE/FE tuần 1.
[2026-04-19] Cập nhật API_CONTRACT.md: thêm Refresh Queue Pattern note vào /refresh, xác nhận isNewUser field trong /oauth response. Contract version: v0.2-auth (final cho tuần 1).
