# Reviewer Knowledge — Tri thức chắt lọc cho code-reviewer

> File này là **bộ nhớ bền vững** của code-reviewer.
> Khác với BE và FE, reviewer có vai trò cross-cutting → knowledge tập trung vào:
> (1) Contract đã chốt, (2) Review standard đã áp dụng, (3) Architectural decision records.
> Giới hạn: ~400 dòng (được phép dài hơn BE/FE vì bao quát toàn cục).

---

## Architectural Decision Records (ADR)

*(Mỗi quyết định kiến trúc lớn ghi 1 record. Format ngắn gọn.)*

### ADR-001: JWT Strategy — Access + Refresh với rotation
- **Quyết định**: Access token 1 giờ (JWT), Refresh token 7 ngày (JWT), rotation mỗi lần /refresh
- **Bối cảnh**: Cần stateless auth cho Spring Boot API, cần revocation khi logout
- **Lý do**: Rotation + Redis blacklist cho phép force logout mà không cần session. JWT thay vì opaque token để tự chứa claims, tránh DB lookup cho mỗi request.
- **Trade-off**: FE phải implement refresh queue pattern để tránh race condition khi nhiều request cùng expire.
- **Ngày**: 2026-04-19 (Tuần 1)

### ADR-002: BCrypt strength = 12
- **Quyết định**: BCryptPasswordEncoder(12) cho password hashing
- **Bối cảnh**: Trade-off giữa security và performance (hash time tăng theo 2^n)
- **Lý do**: Strength 10 là minimum, 12 cho ~250ms hash time — chấp nhận được cho auth flow (không phải hot path). Scale <1000 users.
- **Trade-off**: Login sẽ chậm hơn strength 10 khoảng 4x. Không ảnh hưởng đến throughput vì auth không phải hot path.
- **Ngày**: 2026-04-19 (Tuần 1)

### ADR-003: FE Auth State — Zustand persist (refreshToken+user, không persist accessToken)
- **Quyết định**: Chỉ persist refreshToken và user info vào localStorage. accessToken không persist.
- **Bối cảnh**: Access token expire sau 1 giờ — persist nó vào localStorage tốn công hơn không làm gì vì lần sau mở app đã phải refresh rồi.
- **Lý do**: Giảm diện tích localStorage bị compromise. Mỗi lần app load, nếu có refreshToken → tự động lấy accessToken mới qua /refresh.
- **Trade-off**: App phải có network call ngay khi load (nếu có refreshToken). Acceptable — better than persisting short-lived secrets.
- **Ngày**: 2026-04-19 (Tuần 1)

### ADR-005: Rate limit pattern — Redis INCR + TTL (set on first increment)
- **Quyết định**: Rate limit dùng `redisTemplate.opsForValue().increment(key)`. Nếu return = 1L → set TTL lần đầu. Vượt ngưỡng → throw RATE_LIMITED với `details.retryAfterSeconds` lấy từ `getExpire(key)`.
- **Bối cảnh**: Cần counter-based rate limit cho register (10/15min/IP, mọi request) và login (5/15min/IP, chỉ fail).
- **Lý do**: INCR atomic — không cần lock. TTL chỉ set lần đầu tránh "slide" window (nếu set TTL mỗi lần thì user cố tình request đều đặn sẽ không bao giờ bị reset).
- **Trade-off**: Có race window ngắn giữa `increment` và `expire` — nếu Redis crash giữa 2 lệnh, counter sẽ persist vĩnh viễn. Acceptable vì Redis bền và PERSIST key hiếm.
- **Pattern login đặc biệt**: Tách `checkLoginRateLimit()` (chỉ GET + so sánh) khỏi `incrementLoginFailCounter()` (INCR + set TTL). Lý do: chỉ tăng counter khi fail, không tăng khi success. Thành công → `redisTemplate.delete(key)` reset counter.
- **Ngày**: 2026-04-19 (Tuần 2, Ngày 2)

### ADR-004: API Error Format — { error, message, timestamp, details }
- **Quyết định**: Mọi error response đều dùng shape: `{ "error": "ERROR_CODE", "message": "...", "timestamp": "ISO-8601", "details": {...} }`
- **Bối cảnh**: Cần FE dùng `error` field (string) để phân nhánh logic, `message` để hiển thị user.
- **Lý do**: error code machine-readable (không đổi khi i18n), message human-readable (có thể localize sau).
- **Trade-off**: Phải maintain list error codes. Documented trong API_CONTRACT.md.
- **Ngày**: 2026-04-19 (Tuần 1)

---

## Contract version hiện tại

- **API_CONTRACT.md**: v0.2.1-auth (5 Auth endpoints + note phân biệt AUTH_REQUIRED vs AUTH_TOKEN_EXPIRED — chốt 2026-04-19)
- **SOCKET_EVENTS.md**: v0.1 (skeleton, chưa có event)

*(Tăng minor version khi thêm endpoint/event, major khi breaking change.)*

## Auth contract — quyết định thiết kế đã chốt

- **Refresh token rotation**: mỗi lần `/refresh` phát token mới, invalidate token cũ trong Redis. BE phải implement atomic check-and-rotate.
- **Rate limit login**: chỉ tính lần thất bại (sai credentials), không tính thành công.
- **User enumeration protection**: `/login` trả `AUTH_INVALID_CREDENTIALS` cho cả sai username lẫn sai password, cùng 1 message.
- **OAuth auto-link**: nếu email từ Firebase đã có trong `users` table → link provider tự động, không tạo user mới. Thứ tự kiểm tra: `user_auth_providers` (by googleUid) → `users` (by email) → tạo mới.
- **Logout yêu cầu refreshToken trong body**: để server xóa đúng token khỏi Redis (single-device logout). Logout all devices là endpoint riêng, ngoài scope tuần 1.
- **isNewUser field**: `/oauth` response thêm field `isNewUser: boolean` ngoài token shape chuẩn — đây là exception có documented intent, không phải contract drift.

---

## Review standard đã áp dụng (bài học rút ra)

*(Ghi khi phát hiện vấn đề nào đó XUẤT HIỆN NHIỀU LẦN trong review — cần nâng thành quy tắc.)*

### Vấn đề thường gặp ở BE
- Luôn kiểm tra phân biệt token expired vs invalid — ảnh hưởng FE refresh logic. EXPIRED phải set request attribute riêng; INVALID để SecurityContext rỗng. Không gộp chung 1 catch block.
- **W-BE-3 RESOLVED**: AuthMethod enum tại com.chatapp.user.enums. generateAccessToken(User, AuthMethod) — không còn hardcode "password". getAuthMethodFromToken() có fallback về PASSWORD khi claim unknown.
- **Race condition uniqueness check (W2D2, non-blocking V1)**: Pattern `existsByEmail` → `save` có race window khi 2 request cùng lúc. DB UNIQUE constraint throw `DataIntegrityViolationException` → service hiện không catch → GlobalExceptionHandler Exception catch-all trả 500 INTERNAL_ERROR thay vì 409 AUTH_EMAIL_TAKEN/AUTH_USERNAME_TAKEN. Fix khi scale: bắt DataIntegrityViolationException trong register(), map sang AppException dựa trên constraint name. V1 scale <1000 users traffic thấp → acceptable, documented.
- **Transaction không bao Redis**: `@Transactional` chỉ quản lý JDBC/JPA. Write Redis SAU khi save user → nếu Redis fail, user đã tồn tại DB nhưng không có refresh token → FE phải login lại. Không dùng @Transactional với TransactionSynchronizationManager để "rollback Redis" vì phức tạp và không đảm bảo. Chấp nhận side effect.
- **X-Forwarded-For không sanitize**: extractClientIp() lấy header[0] split(","). Attacker forge header có thể ghi Redis key rác (rate:login:arbitrary_string). Về lý thuyết không phải Redis injection (RedisSerializer escape), nhưng có thể abuse counter space. Nên validate IP format bằng InetAddressValidator trước khi dùng làm key suffix.

### Vấn đề thường gặp ở FE
- **globalThis workaround** (api.ts <-> authStore.ts): RESOLVED trong W-FE-2. Đã migrate sang tokenStorage.ts pattern. globalThis hoàn toàn bị loại bỏ. Không còn cần check pattern này.
- **Zustand persist: không persist accessToken** — quy tắc bắt buộc. Nếu thấy accessToken trong `partialize`, đây là BLOCKING issue.
- **Axios interceptor loop** — khi retry /refresh phải dùng `axios.post` thuần (không phải api instance) và set `_retry` flag. Nếu không có 2 điều này, infinite retry loop.

### Contract lệch thường gặp
- (chưa có)

---

## Approved patterns (pattern đã review và OK, khuyên dùng)

### BE patterns
- `validateTokenDetailed()` trả enum VALID/EXPIRED/INVALID thay vì boolean. Tách biệt expired vs invalid để trả error code đúng cho FE.
- `GlobalExceptionHandler` + `AppException` — business exception pattern. Mọi business error throw `AppException(HttpStatus, errorCode, message)`, handler convert sang `ErrorResponse`.
- **Refresh token SHA-256 hash vào Redis (W2D2 APPROVED)**: `hashToken()` dùng MessageDigest SHA-256 + Base64. Lưu hash chứ không raw token vào Redis key `refresh:{userId}:{jti}`. Lý do: nếu Redis bị compromise, hash không dùng để forge. Khi /refresh, compare bằng cách hash lại token FE gửi và so sánh.
- **User enumeration protection (W2D2 APPROVED)**: `findByUsername(...).orElse(null)` + cùng 1 nhánh throw `AUTH_INVALID_CREDENTIALS` cho user-not-found và wrong-password. Cùng error code + cùng message "Tên đăng nhập hoặc mật khẩu không đúng". Check account status (AUTH_ACCOUNT_LOCKED) chỉ SAU khi verify credentials đúng — không tiết lộ "username tồn tại" qua timing khác nhau.
- **Client IP extraction pattern**: `X-Forwarded-For` header split(",")[0].trim() với fallback `getRemoteAddr()`. Chấp nhận vì reverse proxy sẽ prepend client IP. Caveat: chưa sanitize IP format (xem Vấn đề thường gặp BE).

### FE patterns
- RHF + zodResolver + mode:'onTouched' — validate khi blur, không mỗi keystroke. Ít re-render.
- isRefreshing flag + failedQueue[] — refresh queue pattern cho axios. Chỉ 1 request gọi /refresh, số còn lại queue.
- **tokenStorage.ts pattern** (W-FE-2 RESOLVED): module in-memory trung gian không import api.ts, phá circular dep api.ts <-> authStore.ts. authStore.setAuth() và clearAuth() sync 2 chiều với tokenStorage trong cùng action (sync trước set() Zustand để không có async gap). onRehydrateStorage chỉ restore refreshToken vào tokenStorage (accessToken không persist theo ADR-003).

---

## Rejected patterns (đã review và từ chối, không dùng)

*(Format: pattern là gì → tại sao từ chối → dùng gì thay thế)*

- (chưa có)

---

## Changelog contract

*(Log mỗi lần thay đổi contract, ngắn gọn. Chi tiết đầy đủ ở cuối API_CONTRACT.md và SOCKET_EVENTS.md.)*

| Ngày | Version | Thay đổi |
|------|---------|---------|
| 2026-04-19 | v0.2-auth | Khởi tạo contract 5 Auth endpoints: register, login, oauth, refresh, logout. Token shape chuẩn. Rate limits. |
| 2026-04-19 | v0.2-auth | Thêm Refresh Queue Pattern note vào /refresh. Xác nhận isNewUser field trong /oauth. |
| 2026-04-19 | v0.2.1-auth | Thêm AUTH_TOKEN_EXPIRED error code. Note phân biệt AUTH_REQUIRED vs AUTH_TOKEN_EXPIRED. |

---

## Changelog file này

- 2026-04-19 (Ngày 4): Điền ADR-001 đến ADR-004. Thêm FE review standards từ Phase 3B review. Điền contract changelog. Thêm approved patterns BE + FE.
- 2026-04-19 (W2D1): Mark W-BE-3 RESOLVED (AuthMethod enum). Mark W-FE-2 RESOLVED (tokenStorage pattern, globalThis removed). Thêm tokenStorage.ts vào approved FE patterns. Note warning post-rehydrate auth flow.
