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

[2026-04-19 - W1 Fix Review] APPROVE. JwtTokenProvider.validateTokenDetailed() phân biệt VALID/EXPIRED/INVALID. Request attribute 'jwt_expired' set đúng. authenticationEntryPoint trả AUTH_TOKEN_EXPIRED vs AUTH_REQUIRED chính xác. Contract cập nhật với AUTH_TOKEN_EXPIRED code. Contract version: v0.2.1-auth.

## 2026-04-19 — Review Phase 3A: Spring Security 6 + JWT setup

### Verdict
APPROVE WITH COMMENTS

### Files reviewed
- `backend/src/main/java/com/chatapp/config/SecurityConfig.java`: Spring Security 6 lambda DSL, STATELESS, CORS, filter chain
- `backend/src/main/java/com/chatapp/security/JwtAuthFilter.java`: OncePerRequestFilter, no-throw design
- `backend/src/main/java/com/chatapp/security/JwtTokenProvider.java`: jjwt 0.12.x, secret from env, BCrypt 12
- `backend/src/main/java/com/chatapp/exception/GlobalExceptionHandler.java`: AppException, Validation, catch-all
- `backend/src/main/java/com/chatapp/exception/ErrorResponse.java`: record, @JsonInclude NON_NULL
- `backend/src/main/resources/application.yml`: JWT secret via env var, cors via env var
- `backend/src/test/java/com/chatapp/security/JwtTokenProviderTest.java`: 6 unit tests
- `backend/src/test/java/com/chatapp/security/SecurityConfigTest.java`: 4 integration tests

### Issues found
- [WARNING] `JwtTokenProvider.java`: access token hardcode `auth_method = "password"` — cần truyền dynamic khi OAuth. Code đã có comment "Tuần 2 sẽ truyền dynamic" nhưng nên đảm bảo signature API hỗ trợ param này sớm.
- [WARNING] `JwtAuthFilter.java`: DB query (`userRepository.findById`) cho mọi request authenticated. Khi load tăng cần cache User entity vào Redis (Phase sau).
- [WARNING] `application-test.yml`: JWT secret để rõ trong file test config — chấp nhận được cho dev/test nhưng đừng để value này leak ra production.
- [WARNING] `GlobalExceptionHandler.java`: AppException log ở level DEBUG — nếu client gửi AUTH_INVALID_CREDENTIALS liên tục sẽ không thấy trong log level INFO. Cân nhắc log WARN cho 4xx security errors.

### Contract impact
- Không thay đổi contract. ErrorResponse shape khớp API_CONTRACT.md. Error codes AUTH_REQUIRED, AUTH_FORBIDDEN, VALIDATION_FAILED, INTERNAL_ERROR đều đúng.

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
