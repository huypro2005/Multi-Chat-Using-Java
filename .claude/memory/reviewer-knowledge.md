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

### ADR-007: OAuth Auto-Link by Email (Firebase Google)
- **Quyết định**: Thứ tự kiểm tra khi nhận Firebase ID token đã verify: (1) `user_auth_providers` by `(provider='google', providerUid)` → returning OAuth user; (2) `users` by `email` → existing password user, AUTO-LINK bằng cách insert thêm row vào `user_auth_providers`; (3) không tìm thấy cả hai → tạo user mới (users + user_auth_providers), `isNewUser=true`.
- **Bối cảnh**: Người dùng đăng ký email `foo@gmail.com` bằng password, sau đó click "Login with Google" cùng email. Nếu không auto-link sẽ tạo 2 user riêng biệt cùng email → UNIQUE email violate hoặc phân tách danh tính.
- **Lý do**:
  - Google account có `email_verified=true` mặc định (Google đã sở hữu email) → an toàn auto-link bằng email.
  - Giảm friction cho user — không cần "đã có tài khoản? click đây để link".
  - Password hash vẫn giữ nguyên — user có thể login cả 2 cách.
- **Điều kiện bắt buộc nếu sau này thêm provider khác (Facebook, Apple, v.v.)**: PHẢI check `firebaseToken.isEmailVerified()` hoặc `email_verified` claim trước khi auto-link. Provider không verify email → attacker có thể claim email không thuộc họ → chiếm tài khoản của user khác. Hiện V1 chỉ có Google nên chưa implement check này, DOCUMENTED trong knowledge (reviewer standards).
- **Trade-off**:
  - `password_hash = null` cho user tạo mới qua OAuth-only → `login()` endpoint phải guard null trước khi gọi `passwordEncoder.matches()` (nếu không BCrypt throw IllegalArgumentException → 500). Hiện chưa implement guard → pending fix ở touch login tiếp theo.
  - generateUniqueUsername race: 2 OAuth concurrent có thể chọn trùng username → UNIQUE violate 500. V1 traffic thấp acceptable; DB UNIQUE là guard cuối.
- **Ngày**: 2026-04-19 (Tuần 2, Ngày 4)

### ADR-006: Refresh Token Rotation + Reuse Detection
- **Quyết định**: Mỗi lần `/refresh` thành công → DELETE old redis key TRƯỚC khi buildAuthResponse sinh refresh token mới với jti mới. Hash mismatch (storedHash=null hoặc không match) → detect reuse → revokeAllUserSessions(userId) trước khi throw `AUTH_REFRESH_TOKEN_INVALID`.
- **Bối cảnh**: Refresh token nếu dùng lại (replay) là dấu hiệu attacker đã có token → phải revoke all sessions của user đó, không chỉ token đó.
- **Lý do**:
  - DELETE trước SAVE đảm bảo không có cửa sổ attacker dùng đồng thời 2 token cũ + mới.
  - Constant-time compare hash (MessageDigest.isEqual) tránh timing attack.
  - revokeAllUserSessions dùng `redisTemplate.keys("refresh:{userId}:*")` — O(N) scan, OK cho V1 vì 1 user hiếm khi có >10 sessions.
- **Trade-off**:
  - Nếu crash giữa DELETE và SAVE → user mất session, phải login lại. Acceptable cho V1. V2 dùng Redis MULTI/EXEC.
  - Rate limit counter KHÔNG reset sau refresh thành công — nếu user legit refresh 10 lần trong 60s (hiếm) sẽ bị throttle. Window ngắn (60s) nên tự hồi phục nhanh.
- **Reuse case trả `AUTH_REFRESH_TOKEN_INVALID`**: contract ở dòng 337 nhắc tới `REFRESH_TOKEN_REUSED` nhưng error table chỉ có `AUTH_REFRESH_TOKEN_INVALID` và `AUTH_REFRESH_TOKEN_EXPIRED`. Thống nhất: dùng `AUTH_REFRESH_TOKEN_INVALID` cho cả malformed + reused + user-not-found (tránh tiết lộ "token từng tồn tại"). Sửa contract dòng 337 cho khớp.
- **Ngày**: 2026-04-19 (Tuần 2, Ngày 3.5)

### ADR-004: API Error Format — { error, message, timestamp, details }
- **Quyết định**: Mọi error response đều dùng shape: `{ "error": "ERROR_CODE", "message": "...", "timestamp": "ISO-8601", "details": {...} }`
- **Bối cảnh**: Cần FE dùng `error` field (string) để phân nhánh logic, `message` để hiển thị user.
- **Lý do**: error code machine-readable (không đổi khi i18n), message human-readable (có thể localize sau).
- **Trade-off**: Phải maintain list error codes. Documented trong API_CONTRACT.md.
- **Ngày**: 2026-04-19 (Tuần 1)

### ADR-008: JWT algorithm = HS256 + jjwt 0.12.x
- **Quyết định**: JWT sign bằng HS256 (HMAC SHA-256) với secret key từ env `JWT_SECRET`. Library: `io.jsonwebtoken:jjwt-api:0.12.x` (+ `jjwt-impl` + `jjwt-jackson` runtime).
- **Bối cảnh**: Cần symmetric sign vì BE là single-instance (1 server Singapore), không cần distribute public key. RS256 không cần thiết cho scale V1.
- **Lý do**: HS256 nhanh hơn RS256 (~10x), secret chỉ cần bảo mật 1 chỗ. jjwt 0.12.x là API mới (parserBuilder + verifyWith) — cleaner hơn 0.11.x.
- **Trade-off**: Nếu mở rộng sang multi-instance hoặc cần 3rd party verify token (OAuth2 resource server) → phải migrate RS256. Acceptable cho V1, documented migration path.
- **Ngày**: 2026-04-19 (Tuần 1)

### ADR-009: Redis key schema (namespacing cho rate limit / refresh token / JWT blacklist)
- **Quyết định**: Dùng 3 prefix đã chốt — KHÔNG được đổi sau khi deploy production (sẽ mất state):
  - `rate:{scope}:{id}` — counter rate limit. Ví dụ `rate:register:192.168.1.1`, `rate:login:192.168.1.1`, `rate:refresh:{userId}`. INCR + EX (set TTL lần đầu).
  - `refresh:{userId}:{jti}` — hash SHA-256 của refresh token. EX 604800 (7d).
  - `jwt:blacklist:{jti}` — empty value, TTL = remaining TTL của access token tại thời điểm logout.
- **Bối cảnh**: Scale V1 nhỏ (1 Redis instance), cần prefix rõ ràng để debug (MONITOR, KEYS) và tránh conflict với future namespace (presence:, typing:, cache:user:).
- **Lý do**:
  - `rate:` tách scope (register/login/refresh) giúp tune TTL độc lập.
  - `refresh:{userId}:{jti}` cho phép revokeAllUserSessions bằng `KEYS refresh:{userId}:*` — O(N) OK cho user <10 sessions.
  - `jwt:blacklist:` prefix riêng (không phải `blacklist:`) để tương lai có thể có `ws:blacklist:`, `ip:blacklist:` mà không va nhau.
- **Trade-off**: `KEYS` command blocking trong Redis. V1 acceptable (1 user hiếm >10 sessions). V2 migrate sang SET members (SADD `user_sessions:{userId}` jti) để dùng SREM thay KEYS.
- **Ngày**: 2026-04-19 (Tuần 2, Ngày 3.5 — formalized Tuần 2 audit cuối)

### ADR-010: AuthMethod enum (PASSWORD | GOOGLE) trong JWT claim `auth_method`
- **Quyết định**: Enum `com.chatapp.user.enums.AuthMethod` với 2 value `PASSWORD` ("password") và `GOOGLE` ("google"). `JwtTokenProvider.generateAccessToken(User, AuthMethod)` nhận enum, ghi `auth_method` claim là string lowercase. Reader side: `getAuthMethodFromToken()` parse với fallback về `PASSWORD` khi claim unknown (backward compat).
- **Bối cảnh**: Trước khi refactor (pre-W2D1), `generateAccessToken` hardcode `"password"` trong claim → OAuth login cũng bị gắn `auth_method=password` → sai nghiệp vụ + mở đường cho bug khi phân nhánh flow theo auth method.
- **Lý do**: Enum guarantee type-safety ở compile time. Callers không thể truyền string tự do. Fallback về PASSWORD đảm bảo token cũ (trước refactor) vẫn valid sau deploy.
- **Trade-off**: Thêm 1 file enum + refactor 2-3 call sites. Acceptable cost cho đúng business semantics.
- **Ngày**: 2026-04-19 (Tuần 2, Ngày 1 — W-BE-3 resolved)

### ADR-012: Conversation enum — UPPERCASE `ONE_ON_ONE` / `GROUP` (khác ARCHITECTURE.md gốc)
- **Quyết định**: `conversations.type` enum dùng UPPERCASE `ONE_ON_ONE` và `GROUP`. Role enum UPPERCASE `OWNER` / `ADMIN` / `MEMBER`. ARCHITECTURE.md mục 3.2 viết lowercase `direct`/`group`/`owner`/... nhưng team chọn UPPERCASE để khớp Java enum convention và Jackson mặc định không phải custom converter.
- **Bối cảnh**: V3 migration + Conversation entity + JSON contract đều cần thống nhất. Nếu DB lowercase nhưng Java enum UPPERCASE → `@Enumerated(EnumType.STRING)` sẽ lỗi Hibernate vì string không match.
- **Lý do**:
  - Java enum convention = UPPERCASE. Giữ lowercase đòi hỏi custom `AttributeConverter` hoặc viết enum name lowercase (xấu, vi phạm code style Java).
  - Jackson serialize enum → UPPERCASE mặc định → FE TypeScript union type viết `"ONE_ON_ONE" | "GROUP"` (khớp). Đổi sang lowercase cần `@JsonValue` hoặc `@JsonCreator` cho mỗi enum → overhead.
  - `ONE_ON_ONE` rõ hơn `direct` (dễ nhầm với "direct message" khác "direct route"). Semantics self-documenting.
- **Trade-off**:
  - ARCHITECTURE.md dòng 393 + 406-407 bị lệch với implementation — cần 1 ghi chú ở contract + knowledge, KHÔNG sửa ARCHITECTURE (nó là tài liệu thiết kế gốc; sửa sẽ rewrite history). WARNINGS.md sẽ log để future-self biết lý do lệch.
  - Nếu tương lai tích hợp hệ thống khác (API gateway, export event) expect lowercase → thêm converter ở edge, không đổi DB.
- **Ngày**: 2026-04-19 (Tuần 3, Ngày 1 — formalized khi draft Conversations contract)

### ADR-013: ONE_ON_ONE idempotency — race duplicate acceptable V1, no advisory lock
- **Quyết định**: Giữ `@Transactional` mặc định (READ_COMMITTED) cho `createOneOnOne`. Không dùng `Isolation.SERIALIZABLE`, không dùng `pg_advisory_xact_lock`. Chấp nhận race window 2 requests concurrent có thể tạo 2 ONE_ON_ONE duplicate cùng cặp user. Fix V2 bằng partial UNIQUE index.
- **Bối cảnh**: W3D2 BE implement `findExistingOneOnOne` rồi save mới. 2 requests concurrent đều pass check → dup.
- **Lý do**:
  - Traffic V1 <1000 users, xác suất collision < 0.01% (2 user tap "Chat" cùng ms cho cùng peer).
  - `SERIALIZABLE` gây retry overhead và rối code (PG throw SerializationFailureException → phải catch + retry). Không đáng cho hot path.
  - `pg_advisory_xact_lock(hash(LEAST(a,b) || GREATEST(a,b)))` sạch hơn nhưng thêm 1 round-trip DB + lock contention khi bulk create. Complexity > giá trị V1.
  - Partial UNIQUE index `CREATE UNIQUE INDEX ... ON conversations(LEAST(user_a, user_b), GREATEST(user_a, user_b)) WHERE type='ONE_ON_ONE'` đòi hỏi denormalize 2 columns user_a/user_b vào conversations — migration đáng kể. Để V2.
- **Trade-off**:
  - V1 có thể có vài conversation "orphan" duplicate. UX hơi lạ (user mở chat thấy 2 conv với cùng người) nhưng không phá integrity — tin nhắn chỉ vào conv đang mở.
  - Clean-up script V2: scan, merge messages của các dup vào conv sớm nhất, xóa dup.
- **Monitor signal**: nếu sau production thấy > 1 dup/ngày → escalate sang advisory lock hoặc partial UNIQUE index sớm.
- **Ngày**: 2026-04-19 (Tuần 3, Ngày 2 — formalized khi review BE createConversation)

### ADR-011: Blacklist check fail-open khi Redis down (trade-off intentional)
- **Quyết định**: `JwtAuthFilter` check `redisTemplate.hasKey("jwt:blacklist:{jti}")` trước khi set SecurityContext. Nếu Redis throw `RedisConnectionFailureException` → LOG warning + SKIP check (fail-open) → token vẫn authenticate nếu JWT signature valid & chưa expire.
- **Bối cảnh**: Redis có thể crash / maintenance / network blip → nếu fail-closed (reject mọi request khi Redis down) sẽ downtime toàn bộ app.
- **Lý do**: Trade-off giữa availability và security. V1 scale <1000 users, Redis managed, downtime hiếm. Nếu fail-closed → Redis blip 30s = toàn bộ user bị logout = tệ hơn risk "logged-out token tiếp tục valid trong window đó".
- **Trade-off**:
  - **Risk**: Trong window Redis down, access token đã logout vẫn valid đến natural expiry (≤1h). Attacker có token bị revoke → vẫn truy cập được cho đến khi token expire.
  - **Mitigation V1**: monitoring alert khi Redis down → ops rotate JWT_SECRET (nuclear option — invalidate toàn bộ session) nếu nghi ngờ có compromised token.
  - **Mitigation V2**: Circuit breaker đếm Redis failure rate, switch sang fail-closed sau ngưỡng. Bỏ scope V1.
- **BẮT BUỘC**: comment rõ intent `// fail-open intentional: xem ADR-011` trong code filter. Không comment = treat như bug.
- **Ngày**: 2026-04-19 (Tuần 2, Ngày 4 — formalized trong audit cuối Tuần 2)

---

## Contract version hiện tại

- **API_CONTRACT.md**: v0.5.0-conversations (Conversations API đã draft W3D1: POST/GET list/GET detail/users.search — pending BE+FE implement W3D2+. Auth v0.4.0-auth-complete giữ nguyên.)
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

### Security review standards (bắt buộc áp dụng cho mọi endpoint auth-related)
- **Hash/token comparison phải constant-time**: dùng `MessageDigest.isEqual(bytesA, bytesB)`, KHÔNG dùng `String.equals()` hay `Arrays.equals()` cho sensitive data. `String.equals()` short-circuit khi ký tự khác đầu tiên → leak độ dài prefix khớp qua timing. BLOCKING nếu vi phạm.
- **Token rotation phải DELETE trước SAVE**: DELETE old token khỏi Redis TRƯỚC khi generate+SAVE token mới. Ngược lại → có cửa sổ 2 token cùng hợp lệ, attacker lợi dụng race.
- **Reuse detection phải revoke ALL sessions của user đó**: phát hiện 1 token bị reuse = giả định toàn bộ sessions của user đó đã bị compromise. KHÔNG chỉ delete token hiện tại. Pattern: `redisTemplate.keys("refresh:{userId}:*")` → `redisTemplate.delete(keys)`.
- **Log SECURITY events với context**: WARN level, có userId + jti + action, KHÔNG có raw token/password. Format: `"[SECURITY] Refresh token reuse/invalid detected for userId={}, jti={}. Revoking all sessions."`.
- **Error code phân biệt phải rõ ràng và KHÔNG leak**: INVALID (malformed/sig-sai/reused/user-not-found) — tất cả dùng cùng 1 code để không tiết lộ "token tồn tại nhưng đã reused" vs "token không bao giờ tồn tại"; EXPIRED (valid sig + valid signature, chỉ exp quá hạn) — code riêng để FE biết đăng nhập lại thay vì retry.
- **Firebase ID token phải verify qua Admin SDK, KHÔNG tự parse JWT**: bắt buộc gọi `FirebaseAuth.getInstance().verifyIdToken(idToken)` (hoặc method tương đương). Tự parse JWT với jjwt sẽ bỏ qua check signature với Google public keys (rotation) + audience check + issuer check. BLOCKING nếu vi phạm. Pattern đúng: inject `FirebaseAuth` qua `@Bean`, nullable với `@Autowired(required=false)`; null-check trước khi gọi → throw `AUTH_FIREBASE_UNAVAILABLE` 503 nếu chưa init.
- **OAuth auto-link theo email CHỈ an toàn khi provider verify email**: với Google luôn verified. Nếu sau V2 thêm Facebook/Apple/email OAuth khác → phải check `firebaseToken.isEmailVerified()` (hoặc claim tương đương) TRƯỚC khi auto-link vào user hiện có. Không check → attacker tạo Facebook account với email chưa xác nhận, claim chiếm account password user khác.
- **Access token blacklist TTL phải = remaining TTL của token**: `SET "jwt:blacklist:{jti}" "" EX {remainingSeconds}` — không dài hơn (lãng phí Redis), không ngắn hơn (token hết blacklist trước khi tự expire → attacker dùng lại). Lấy remaining bằng `exp - now()` từ JWT claims.
- **Blacklist check trong JwtAuthFilter phải CHẠY TRƯỚC set SecurityContext**: thứ tự đúng: extract token → validateTokenDetailed VALID → check Redis blacklist → (nếu blacklisted set attribute 'jwt_expired' + filterChain.doFilter + return) → load User + set Authentication. Ngược lại → logged-out token vẫn authenticate được. BLOCKING nếu sai thứ tự.
- **Fail-open vs fail-closed cho Redis blacklist**: có thể accept fail-open (Redis down → skip blacklist check, token vẫn valid đến natural expiry) với comment rõ trong code. Trade-off: service tiếp tục hoạt động khi Redis down, nhưng blacklist không enforce trong window đó. Phải documented intent trong log + comment. Nếu không comment → treat như bug (unintentional fail-open).

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
- **Form payload strip sensitive/client-only fields** — RegisterPage.tsx pattern: tạo `payload` object explicit chỉ chứa field BE expect (email, username, password, fullName), KHÔNG spread `...data` vì sẽ leak `confirmPassword`. Nếu thấy `registerApi(data)` trực tiếp mà RegisterFormData có field extra (confirmPassword, acceptTerms...) → BLOCKING. Reviewer pattern: `const payload = { field1: data.field1, ... }; api(payload)`.
- **Zod schema dùng regex gộp length + format**: UX trade-off. Nếu gộp `[a-zA-Z_][a-zA-Z0-9_]{2,49}` vào 1 regex thay vì tách `.min(3).max(50).regex(format)`, error message khi fail length sẽ hiển thị message format (không chính xác). Non-blocking nếu schema match contract BE, nhưng gợi ý tách ra cho UX tốt hơn.
- **W-FE-1 RESOLVED (W2D3)**: Username regex `/^[a-zA-Z_][a-zA-Z0-9_]{2,49}$/` trong registerSchema.ts khớp exact với BE constraint. First char không cho phép digit, total 3-50 ký tự. Không còn lệch với BE.
- **W3-BE-1 RESOLVED (W3D2)**: `Conversation` và `ConversationMember` entity đã migrate `@GeneratedValue(UUID) + insertable=false/updatable=false` sang pattern `@PrePersist` với `if (id == null) id = UUID.randomUUID()` (Option B). Test `savingConversation_shouldPersistWithNonNullId` confirm `save()` trả entity với id != null. Pattern này đã apply cho 2 entity conversations; `User` entity vẫn giữ `@GeneratedValue(UUID) + insertable=false` — OK với test pre-W3 đã pass, nhưng nếu tương lai thêm test insert User qua `repository.save(new User())` → nên migrate cùng pattern để nhất quán.

### Contract lệch thường gặp
- **Error response field name drift BE↔FE (W3D2 BLOCKING)**: BE `ErrorResponse` dùng field `"error"`, không phải `"code"`. Contract dòng 13-18 `API_CONTRACT.md` đã chốt. FE khi đọc `err.response.data` phải dùng `.error`. Pattern đã xuất hiện 1 lần (FE `api.ts:31` của W3D2 conversations) → nhắc reviewer mỗi lần review FE axios error handling: check field name khớp BE ErrorResponse. Gợi ý: FE define `interface ApiErrorBody { error: string; message: string; timestamp: string; details?: Record<string, unknown> }` ở 1 chỗ (ví dụ `types/api.ts`) và import khắp nơi, đừng inline cast.
- **DTO shape drift: Summary vs Detail nhầm lẫn field (W3D2 BLOCKING)**: BE tách rõ `ConversationDto` (full — POST 201 và GET detail) và `ConversationSummaryDto` (GET list — có `displayName/displayAvatarUrl/unreadCount/mutedUntil` server-computed). FE khi type BE response phải bám sát contract per-endpoint, **không copy toàn bộ field của Summary vào Detail**. Đã xảy ra 1 lần (W3D2 `types/conversation.ts`). Reviewer check: mỗi endpoint contract có shape khác nhau → FE types phải có 2 interface riêng, derive field ở FE runtime nếu cần.

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
| 2026-04-19 | v0.3.0-auth | POST /api/auth/refresh implemented (W2D3.5). Rotation + reuse detection + revokeAllUserSessions. Constant-time hash compare. Note: cần sync contract dòng 337 (dùng `AUTH_REFRESH_TOKEN_INVALID` thay cho `REFRESH_TOKEN_REUSED`) và rate limit mới 10 calls/60s per-userId (contract hiện là 30/15min/IP). |
| 2026-04-19 | v0.4.0-auth-complete | POST /api/auth/oauth + POST /api/auth/logout implemented (W2D4). Firebase Admin SDK verifyIdToken (không self-parse). FirebaseConfig lazy init — bean null khi FIREBASE_CREDENTIALS_PATH chưa set → endpoint trả 503 AUTH_FIREBASE_UNAVAILABLE. Auto-link by email thứ tự providerUid → email → new. JwtAuthFilter thêm blacklist check (Redis hasKey "jwt:blacklist:{jti}") trước set SecurityContext; fail-open khi Redis down (intentional, commented). Logout: blacklist access TTL=remaining, DELETE refresh key best-effort. Note contract: dòng 256 AUTH_FIREBASE_UNAVAILABLE hiện nói "timeout 5s" — nên mở rộng câu điều kiện cho cả case "SDK chưa init". FE dead code: check `PROVIDER_ALREADY_LINKED` error nhưng BE không emit — FE tự fallback message chung OK. |
| 2026-04-19 | v0.5.0-conversations | Draft 4 Conversations endpoints (W3D1, pending implement): POST /api/conversations (type UPPERCASE, memberIds exclude caller, GROUP name required + 1..100, ONE_ON_ONE idempotency → 409 CONV_ONE_ON_ONE_EXISTS kèm conversationId); GET list (offset pagination page/size, displayName/displayAvatarUrl computed, unreadCount placeholder=0 V1, sort lastMessageAt DESC NULLS LAST); GET detail (merge 404 CONV_NOT_FOUND cho cả not-exist + not-member để chống enumeration); GET /api/users/search (q ≥2 sau trim, exclude caller + non-active, sort username ASC, không trả email). Rate limits riêng từng endpoint. Documented soft-leave/soft-hide out-of-scope V1, documented race dup ONE_ON_ONE acceptable V1. |

---

## Changelog file này

- 2026-04-19 (Ngày 4): Điền ADR-001 đến ADR-004. Thêm FE review standards từ Phase 3B review. Điền contract changelog. Thêm approved patterns BE + FE.
- 2026-04-19 (W2D1): Mark W-BE-3 RESOLVED (AuthMethod enum). Mark W-FE-2 RESOLVED (tokenStorage pattern, globalThis removed). Thêm tokenStorage.ts vào approved FE patterns. Note warning post-rehydrate auth flow.
- 2026-04-19 (W2D3.5): Review POST /api/auth/refresh. Thêm ADR-006 (Refresh Token Rotation + Reuse Detection). Thêm Security review standards (constant-time compare, DELETE-before-SAVE, revoke-all-on-reuse, log format, error-code leak). Contract v0.2.1-auth → v0.3.0-auth. Ghi nhận 2 contract sync items cần FE/BE align (reuse error code + rate limit value).
- 2026-04-19 (W2D4): Review POST /api/auth/oauth + POST /api/auth/logout. Thêm ADR-007 (OAuth Auto-Link by Email). Thêm 4 security standards mới (Firebase SDK verify bắt buộc, email_verified check điều kiện, blacklist TTL = remaining token TTL, blacklist-check-trước-setSecurityContext, fail-open Redis trade-off documented). Contract v0.3.0-auth → v0.4.0-auth-complete. Auth foundation Tuần 2 COMPLETE: register/login/refresh/oauth/logout đều implement + review xong.
- 2026-04-19 (W3D1): Review V3 migration + Conversation entities/repositories + FE ProtectedRoute refactor + ConversationsLayout skeleton. APPROVE WITH COMMENTS (2 warning non-blocking: insertable=false trên @GeneratedValue UUID có thể lỗi Hibernate INSERT; CHECK constraint role `'MEMBER'` default trong SQL nhưng entity `@Builder.Default` = MEMBER → OK đồng bộ). Draft Conversations contract v0.5.0. Thêm ADR-012 (UPPERCASE enum). W-C-4 xác nhận RESOLVED.
- 2026-04-19 (W3D2): Review BE 4 endpoints Conversations (POST, GET list, GET detail, GET users/search) + FE API scaffold. REQUEST CHANGES — 2 BLOCKING ở FE: (1) `api.ts` đọc `.code` thay vì `.error` field của ErrorResponse → 409 CONV_ONE_ON_ONE_EXISTS handler sẽ luôn throw; (2) `types/conversation.ts` define `ConversationDto` có 4 field displayName/displayAvatarUrl/unreadCount/mutedUntil không có trong BE response (chỉ có ở SummaryDto) — runtime access sẽ undefined. Thêm ADR-013 (race ONE_ON_ONE acceptable V1, no lock). Mark W3-BE-1 RESOLVED (UUID @PrePersist Option B trong Conversation + ConversationMember; test assertion confirm). Thêm 2 contract-drift-patterns mới vào knowledge (error field name; Summary vs Detail shape). 5 BE warning non-blocking log vào WARNINGS.md nếu chưa có (dedupe memberIds, enforce max 49, rate limit TODO, N+1 batch load, UserController cross-package).
- 2026-04-19 (W2 Final Audit): Audit cuối Tuần 2 trước tag `v0.2.0-w2`. Formalize 4 ADR còn implicit: ADR-008 (HS256 + jjwt 0.12.x), ADR-009 (Redis key schema), ADR-010 (AuthMethod enum), ADR-011 (Fail-open blacklist trade-off). Tạo `docs/WARNINGS.md` tổng hợp 5 pre-production items (W-BE-4 race existsBy→save, W-BE-5 null passwordHash guard, W-BE-6 X-Forwarded-For sanitize, W-BE-7 fail-open monitoring, W-BE-8 generateUniqueUsername race), 8 documented-acceptable, 6 cleanup-tuần-8, 7 tech-debt-nhỏ. Controller audit: 5 auth endpoints + 1 health, 0 drift so với contract v0.4.0-auth-complete. 1 orphan TODO: `useAuth.ts:29` "TODO Tuần 2 call logout API" — logout đã implement nơi khác (HomePage.tsx), TODO lỗi thời, map vào CL-1.
