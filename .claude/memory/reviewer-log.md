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

## 2026-04-19 — W2D2 Review Phase A (FE authService.init) + Phase B (BE register + login)

### Verdict
⚠️ APPROVE WITH COMMENTS

### Files reviewed
- `backend/src/main/java/com/chatapp/auth/controller/AuthController.java`: 2 endpoints (register + login), extractClientIp helper.
- `backend/src/main/java/com/chatapp/auth/service/AuthService.java`: business logic, Redis rate limit, SHA-256 hash refresh token.
- `backend/src/main/java/com/chatapp/auth/dto/request/{RegisterRequest,LoginRequest}.java`: record + Jakarta Validation.
- `backend/src/main/java/com/chatapp/auth/dto/response/{AuthResponse,UserDto}.java`: token shape chuẩn.
- `backend/src/test/java/com/chatapp/auth/AuthControllerTest.java`: 13 integration tests bao phủ happy path + edge cases.
- `frontend/src/services/authService.ts`: init() dùng rawAxios, 3 case logic đúng.
- `frontend/src/components/AppLoadingScreen.tsx`: spinner + text, không có bug.
- `frontend/src/App.tsx`: isInitialized gate qua useEffect+finally.

### Issues found
- [WARNING][BE] `AuthService.register()` không catch `DataIntegrityViolationException` — race condition khi 2 request cùng email vượt qua existsByEmail rồi cả 2 save. Lần thứ 2 hiện throw 500 thay vì 409 AUTH_EMAIL_TAKEN. Non-blocking cho V1 (<1000 users, traffic thấp) nhưng cần fix trước production.
- [WARNING][BE] Redis fail SAU khi save user → user đã tồn tại DB nhưng không có refresh token. @Transactional chỉ bao DB, không rollback Redis side effect. Acceptable vì FE sẽ login lại, nhưng document lại hành vi này.
- [WARNING][BE] `extractClientIp()` lấy X-Forwarded-For[0] mà không sanitize ký tự. Redis key `rate:login:{ip}` về lý thuyết có thể bị inject nếu attacker forge header (ví dụ `"; FLUSHDB; #`). Redis command injection qua StringRedisTemplate hầu như không xảy ra (serialized key), nhưng nên validate IP format để phòng abuse counter space.
- [WARNING][BE] Rate limit register tính MỌI request thay vì chỉ thất bại. OK theo contract ("10 requests/15 phút/IP, mọi request đều tính"), nhưng user legitimate tạo 10 account hợp lệ cũng bị chặn. Phù hợp với intent anti-abuse.
- [WARNING][FE] `init()` catch empty — nuốt mọi error bao gồm cả network timeout. Acceptable vì mục đích là "gate luôn mở", nhưng nên `console.warn()` để dev debug.
- [WARNING][FE] `AppLoadingScreen` hiển thị text không dấu "Dang khoi dong...". Nếu cố tình vì tránh encoding issue thì OK, nhưng toàn bộ app khác dùng tiếng Việt có dấu, không nhất quán.

### Contract impact
- Không cập nhật contract. Implementation khớp 100% với `docs/API_CONTRACT.md` v0.2.1-auth:
  - HTTP status 200 cho register (khớp contract dòng 112).
  - Error codes đều có prefix `AUTH_`: AUTH_EMAIL_TAKEN, AUTH_USERNAME_TAKEN, AUTH_INVALID_CREDENTIALS, AUTH_ACCOUNT_LOCKED (khớp dòng 135-137, 193-194).
  - Response shape `{accessToken, refreshToken, tokenType, expiresIn, user:{id,username,email,fullName,avatarUrl}}` khớp.
  - user.id là UUID string (UserDto.from gọi user.getId().toString()).
  - Rate limit: register 10/15min/IP, login 5/15min/IP (chỉ tính fail) — khớp.
  - Refresh token lưu SHA-256 hash vào Redis key `refresh:{userId}:{jti}` TTL 7 ngày — khớp contract dòng 201.

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

[2026-04-19 - W2D4 Review] OAuth + Logout. Verdict: APPROVE WITH COMMENTS. Auth foundation Tuần 2 complete. Firebase SDK verify (A1): PASS — dùng `firebaseAuth.verifyIdToken()` của Admin SDK, không tự parse JWT. FirebaseAuth null check (A5): PASS — `if (firebaseAuth == null) throw AUTH_FIREBASE_UNAVAILABLE` line 307 trước khi gọi. Auto-link by email (A2): PASS logic, `email_verified` chưa check (Google luôn verified nên OK V1, DOCUMENTED cần add khi thêm provider khác). generateUniqueUsername (A3): race theoretical (check→save không atomic, fallback UUID 8 ký tự) — acceptable V1. Password_hash=null cho OAuth user (A4): DOCUMENTED — login() chưa guard null passwordHash, BCrypt.matches sẽ throw IllegalArgumentException→500. Sửa khi touch login tiếp. Blacklist ordering (B6): PASS — check Redis TRƯỚC khi set SecurityContext (line 75-89 rồi mới line 91+). Logout không trong whitelist (B7): PASS — SecurityConfig list explicit register/login/oauth/refresh. Blacklist TTL (B8): PASS — `accessTokenRemainingMs/1000` giới hạn đúng. Best-effort logout (B9): PASS — try/catch quanh refresh delete, vẫn blacklist access + trả 200. Fail-open Redis blacklist check (B10): INTENTIONAL trade-off (có comment rõ trong filter), acceptable V1 scale <1000 users — risk: khi Redis down window, logged-out token còn valid đến natural expiry ≤1h. KHÔNG BLOCKING. Contract compliance: OAuthResponse có `boolean isNewUser` (primitive, khớp JSON `true/false` non-nullable); HTTP 200 cho OAuth+logout OK; error codes FIREBASE_TOKEN_INVALID+AUTH_FIREBASE_UNAVAILABLE match contract. revokeAllUserSessions (D14): KHÔNG gọi trong oauth() — chỉ ở reuse detection (line 261). @Transactional oauth (D15): bao đúng save user + save provider. FE items: popup-closed-by-user silent (F16) PASS; không log idToken (F17) PASS; logoutApi failure → finally clearAuth+navigate (F18) PASS; OAuthResponse extends AuthResponse + isNewUser (F19) PASS; signInWithPopup từ firebase/auth (F20) PASS (không phải compat). Non-blocking warnings: (1) FE check error code `PROVIDER_ALREADY_LINKED` trong Login/RegisterPage onError nhưng BE không emit code này và contract không define — dead code, fallback sang message chung OK; (2) contract dòng 256 nói AUTH_FIREBASE_UNAVAILABLE cho "timeout sau 5 giây" nhưng BE implement cho "SDK chưa init (null bean)" — semantics hơi khác, error code khớp, nên cập nhật contract cho rõ cả 2 case; (3) handleLogout HomePage: refreshToken có thể null sau rehydrate race — FE hiện skip API call → access token không được blacklist (chỉ clear local). Acceptable V1 (token tự expire); (4) generateUniqueUsername concurrent race — 2 OAuth user mới cùng email prefix có thể chọn trùng username → UNIQUE violate → 500. DB UNIQUE là guard cuối, V1 traffic thấp OK; (5) FirebaseConfig.initializeFirebase() fail → log.error nhưng app vẫn start → OAuth endpoint trả 503 AUTH_FIREBASE_UNAVAILABLE tường minh, tốt cho dev. Contract v0.3.0-auth → v0.4.0-auth-complete (OAuth + Logout implemented).

[2026-04-19 - W2D3.5 Review] POST /api/auth/refresh. Verdict: APPROVE WITH COMMENTS. Constant-time compare: PASS (MessageDigest.isEqual). Token rotation correctness: PASS (DELETE trước SAVE; buildAuthResponse sinh jti mới; rate limit counter không reset sau refresh thành công — acceptable vì window 60s ngắn, 10 calls đủ cho FE queue pattern). Reuse detection: PASS (revokeAllUserSessions trước throw; pattern `refresh:{userId}:*` đúng; log WARN có userId+jti, không log raw token). Error codes: PASS (INVALID cho malformed/signature/reused/user-not-found; EXPIRED cho quá TTL). Rate limit: PASS (key `rate:refresh:{userId}`, increment mỗi call). 23/23 tests pass. Non-blocking issues: (1) unused import `java.util.Arrays` — cleanup; (2) Contract drift nhẹ: dòng 337 API_CONTRACT.md nhắc tới error code `REFRESH_TOKEN_REUSED` (không có prefix AUTH_) nhưng implementation dùng `AUTH_REFRESH_TOKEN_INVALID` cho reuse case. Error table chỉ liệt kê `AUTH_REFRESH_TOKEN_INVALID` và `AUTH_REFRESH_TOKEN_EXPIRED` — nên sửa dòng 337 cho nhất quán; (3) Test 17 `refreshWithExpiredToken_returnsExpiredError` documented honestly là không test được EXPIRED path (integration test khó tạo expired token khi ttl config 7 ngày), fallback sang test INVALID signature — EXPIRED path chưa có integration coverage, chỉ có unit test ở JwtTokenProviderTest; (4) Rate limit pattern dùng userId từ token chưa validate Redis hash — attacker gửi nhiều token fake (cùng signature hợp lệ từ cùng user) vẫn consume counter của userId đó → potential DoS nhắm user cụ thể, nhưng cần lấy được raw token đã ký trước nên threat thấp; (5) Rate limit TTL=60s và limit=10 không khớp contract dòng 279 "30 requests/15 phút/IP" — implementation phòng brute force hiệu quả hơn nhưng LỆCH CONTRACT → cần hoặc update contract hoặc align impl. Recommend update contract (10/60s per-userId an toàn hơn 30/15min per-IP cho refresh flow); (6) X-Forwarded-For chưa sanitize (pre-existing). Constant-time compare đúng chuẩn, reuse detection đúng, rotation đúng thứ tự — 3 item BLOCKING-candidate đều PASS. Contract version: v0.2.1-auth → v0.3.0-auth (refresh endpoint implemented, 2 fixup items contract cần sync).

[2026-04-19 - W2D3 Phase C Review] Wire FE Login + Register with real API. Verdict: APPROVE WITH COMMENTS. W-FE-1 RESOLVED (regex `^[a-zA-Z_][a-zA-Z0-9_]{2,49}$` match exact BE, enforce 3-50 chars, first char not digit). Contract compliance PASS: registerApi payload strip confirmPassword explicit (RegisterPage.tsx:54-59); loginApi payload đúng `{username,password}`; UserDto types khớp (id as string UUID, avatarUrl nullable); setAuth nhận full AuthResponse và sync tokenStorage qua tokenStorage.setTokens TRƯỚC khi set Zustand. Error code handling: 6 BE codes đều được map (AUTH_INVALID_CREDENTIALS→field username, AUTH_ACCOUNT_LOCKED→toast, RATE_LIMITED→toast with retryAfter, AUTH_EMAIL_TAKEN/AUTH_USERNAME_TAKEN→field error, VALIDATION_FAILED→per-field qua details.fields). Security: password inputs giữ type="password"; show/hide toggle OK; không có console.log nào trong auth pages/utils; button disable khi loading. Non-blocking warnings: (1) handleAuthError case AUTH_ACCOUNT_DISABLED dead (BE không throw code này, chỉ AUTH_ACCOUNT_LOCKED); (2) registerSchema gộp length vào regex → error message không chính xác khi user gõ quá 50 ký tự (sẽ nói "bắt đầu bằng chữ cái" thay vì "quá dài"); (3) HomePage logout button là stub `onClick={() => {}}` — documented Ngày 5; (4) ProtectedRoute.tsx tồn tại nhưng chưa thấy wire vào App.tsx — có thể prep cho Ngày 4. Contract v0.2.1-auth không đổi.

[2026-04-19 - W2D1 Review] W-BE-3 + W-FE-2. Verdict: APPROVE WITH COMMENTS. W-BE-3: AuthMethod enum refactor clean, package đúng, getValue() đúng, getAuthMethodFromToken() có fallback an toàn, tests cover cả 2 enum values. W-FE-2: tokenStorage.ts pattern hoàn chỉnh, globalThis removed, circular dep phá sạch. Warning non-blocking: sau rehydrate accessToken=null → request đầu tiên không có Bearer header → server trả AUTH_REQUIRED → interceptor clear+redirect thay vì refresh. Cần authService.init() call /refresh ngay khi app load nếu có refreshToken. Bug pre-existing, không phải do diff này tạo ra.

[2026-04-19 - Ngày 4 Phase 3B Review] APPROVE WITH COMMENTS. Axios interceptor, Zustand store, registerSchema, useAuth hook đều solid — không có blocking issue. Hai warning đáng chú ý: (1) globalThis.__authStoreGetState acceptable cho V1 nhưng nên migrate sang lazy-import pattern ở tuần 2; (2) registerSchema thiếu validate username không bắt đầu bằng số theo contract. console.log trong onSubmit stub được chấp nhận vì có comment rõ ràng là placeholder. Contract v0.2.1-auth không đổi.

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
