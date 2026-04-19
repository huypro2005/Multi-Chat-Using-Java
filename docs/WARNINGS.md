# Warnings Tracking

> File này tổng hợp mọi warning / TODO / tech debt đã biết trong codebase tính đến `v0.2.0-w2`.
> Được tạo trong audit cuối Tuần 2 (2026-04-19).
> **Quy tắc**: mỗi TODO trong code phải map với 1 ID trong file này hoặc có plan cụ thể. Orphan TODO không được phép.
>
> Phân loại ưu tiên:
> - **Pre-production**: phải fix trước khi deploy production (V1 public launch).
> - **Documented acceptable**: V1 chấp nhận, đã cân nhắc trade-off.
> - **Cleanup tuần 8**: dọn dẹp trong phase hardening cuối roadmap.
> - **Tech debt nhỏ**: có thể fix bất cứ khi nào tiện tay.

---

## Resolved (đã fix)

| ID | Vấn đề | Fix | Ngày |
|----|--------|-----|------|
| W3-BE-3 | Schema V2 không có `CREATE EXTENSION IF NOT EXISTS pgcrypto` — developer mới clone repo, fresh DB → `gen_random_uuid()` fail ở V2. | Thêm `CREATE EXTENSION IF NOT EXISTS pgcrypto;` vào đầu `V2__create_users_and_auth_providers.sql`. Thêm `repair-on-migrate: true` vào `application.yml` để developer có DB cũ tự động repair checksum khi start app. | 2026-04-19 |

---

## Pre-production (BẮT BUỘC fix trước deploy)

| ID | File:Line | Vấn đề | Solution | Effort |
|----|-----------|--------|----------|--------|
| W-BE-4 | `backend/src/main/java/com/chatapp/auth/service/AuthService.java:~122` (register + OAuth create branch) | Race condition `existsByEmail` → `save`. 2 request cùng email vượt check rồi cùng insert → DB UNIQUE violate → `DataIntegrityViolationException` → catch-all trả 500 `INTERNAL_ERROR` thay vì 409 `AUTH_EMAIL_TAKEN` / `AUTH_USERNAME_TAKEN`. | Catch `DataIntegrityViolationException` trong `register()` và `oauth()` create-new branch; map constraint name (`users_email_key`, `users_username_key`) sang `AppException` 409 với error code tương ứng. | S (0.5d) |
| W-BE-5 | `backend/src/main/java/com/chatapp/auth/service/AuthService.java` (login path) | `passwordHash = null` cho OAuth-only user. Nếu attacker gọi `/login` với username của OAuth user → `BCryptPasswordEncoder.matches()` throw `IllegalArgumentException` → 500. | Guard `if (user.getPasswordHash() == null) throw AUTH_INVALID_CREDENTIALS` TRƯỚC khi gọi `passwordEncoder.matches()`. Cùng error code để không leak "tài khoản OAuth-only". | XS (15 phút) |
| W-BE-6 | `backend/src/main/java/com/chatapp/auth/controller/AuthController.java:125-131` | `extractClientIp()` lấy `X-Forwarded-For[0]` không sanitize. Attacker forge header → ghi Redis key rác `rate:login:{arbitrary_string}`. Không phải command injection (serializer escape), nhưng abuse counter space & bypass rate limit IP. | Validate IP format bằng `InetAddressValidator` (commons-validator) hoặc regex IPv4/IPv6 trước khi dùng làm key suffix. IP invalid → fallback `getRemoteAddr()`. | S (0.5d) |
| W-BE-7 | `backend/src/main/java/com/chatapp/security/JwtAuthFilter.java` (blacklist check) | Fail-open khi Redis down: logged-out access token vẫn valid đến natural expiry (≤1h). | Option A: monitoring alert khi Redis down → ops rotate JWT secret. Option B (preferred V2): circuit breaker đếm failure rate, switch fail-closed sau ngưỡng. V1 acceptable vì Redis managed, nhưng document runbook cho incident. | M (1d — monitoring + runbook) |
| W-BE-8 | `backend/src/main/java/com/chatapp/auth/service/AuthService.java` (oauth generateUniqueUsername) | Race condition khi 2 OAuth concurrent tạo username từ cùng `displayName` → cả 2 chọn cùng suffix → DB UNIQUE violate → 500. | Dùng retry loop (max 3 lần) bắt `DataIntegrityViolationException` trong create branch, regenerate username với suffix mới. Gộp chung W-BE-4. | S (gộp W-BE-4) |

---

## Documented acceptable (V1 chấp nhận)

| ID | Vấn đề | Lý do chấp nhận |
|----|--------|-----------------|
| AD-1 | Redis fail SAU khi save user (register) → user tồn tại DB nhưng không có refresh token trong Redis. `@Transactional` không bao Redis. | FE sẽ nhận response lỗi → user retry login. Không có data corruption. `TransactionSynchronizationManager` để rollback Redis phức tạp & không atomic thật → không làm. Traffic V1 thấp, Redis bền. |
| AD-2 | Rate limit counter KHÔNG reset sau refresh thành công. User legit refresh 10 lần/60s (hiếm) sẽ bị throttle. | Window 60s ngắn, tự hồi phục. FE refresh queue pattern đảm bảo KHÔNG gọi /refresh song song. |
| AD-3 | Rate limit refresh lấy userId từ token chưa validate Redis hash trước. Attacker gửi JWT reused (signature valid) vẫn consume counter của userId đó → potential DoS nhắm 1 user cụ thể. | Cần lấy được raw refresh token đã ký trước → threat thấp. V1 scale nhỏ, nếu có attack chủ đích thì đã có nhiều vector khác dễ hơn. |
| AD-4 | `refresh()` DELETE trước SAVE refresh token: nếu crash giữa 2 bước → user mất session phải login lại. | Acceptable cho V1. V2 có thể dùng Redis MULTI/EXEC. Trade-off giữa "có cửa sổ 2 token" và "có cửa sổ 0 token" — chọn cái sau vì security. |
| AD-5 | `email_verified` claim không check khi auto-link (Google luôn verified). | Google OAuth: `email_verified=true` mặc định. An toàn cho V1. **BẮT BUỘC add check này khi mở rộng sang Facebook/Apple/email OAuth khác** (xem ADR-007 + Security standards). |
| AD-6 | `HomePage.handleLogout`: nếu `tokenStorage.getRefreshToken()` trả null sau rehydrate race → skip `/logout` API call → access token không blacklist. | Access token tự expire ≤1h. Edge case race giữa Zustand rehydrate và click logout rất hẹp. Acceptable. |
| AD-7 | `registerSchema` gộp length + format vào 1 regex `^[a-zA-Z_][a-zA-Z0-9_]{2,49}$` → error message khi user gõ >50 ký tự sẽ nói "bắt đầu bằng chữ cái" thay vì "quá dài". | UX trade-off nhỏ. Schema match contract BE chính xác (W-FE-1 resolved). |
| AD-8 | Register rate limit tính MỌI request, kể cả success (10/15min/IP). User legitimate tạo 10 account hợp lệ bị chặn. | Phù hợp intent anti-abuse. Không ai normal tạo 10 account/15min; nếu có nhu cầu (testing) dùng env khác. |

---

## Cleanup tuần 8

| ID | Vấn đề | Timeline |
|----|--------|----------|
| CL-1 | `frontend/src/hooks/useAuth.ts:16-30` — comment "Tuần 2 sẽ call /api/auth/logout" + `TODO Tuần 2` outdated. Logout đã implement trong `HomePage.tsx` dùng `logoutApi` trực tiếp, không qua hook. `useAuth.logout()` hiện chỉ clear local state. | Tuần 3-4 (khi refactor nav/header) — quyết định: (a) dẹp `useAuth.logout` và ép mọi component dùng `logoutApi + clearAuth` trực tiếp; HOẶC (b) đưa logic API call vào `useAuth.logout` để centralize. |
| CL-2 | `frontend/src/pages/LoginPage.tsx:201` & `RegisterPage.tsx:277` — check `apiErr?.error === 'PROVIDER_ALREADY_LINKED'`. BE không emit error code này và contract không define. Dead branch. | Tuần 3 (khi touch OAuth UX) — xóa 2 branch này; giữ fallback chung `handleAuthError`. |
| CL-3 | `frontend/src/features/auth/utils/handleAuthError.ts:28` — case `AUTH_ACCOUNT_DISABLED` dead. BE chỉ throw `AUTH_ACCOUNT_LOCKED`. | Tuần 3 — xóa case hoặc gộp vào `AUTH_ACCOUNT_LOCKED`. |
| CL-4 | `backend/src/test/java/com/chatapp/auth/AuthControllerTest.java:511` — Test 17 `refreshWithExpiredToken_returnsExpiredError` fallback test INVALID signature vì không tạo được expired token thật (TTL config 7 ngày). EXPIRED path chỉ có unit test ở `JwtTokenProviderTest`. | Tuần 8 — dùng `@SpyBean JwtTokenProvider` + stub `validateTokenDetailed()` trả EXPIRED; hoặc configurable TTL qua profile. |
| CL-5 | `backend/src/main/java/com/chatapp/security/JwtTokenProvider.java:189` — comment "Tuần 2 sẽ truyền dynamic" về `auth_method`. AuthMethod enum đã implement từ W2D1 (W-BE-3 resolved). | Tuần 3 — xóa comment lỗi thời. |
| CL-6 | Contract `docs/API_CONTRACT.md:256` — `AUTH_FIREBASE_UNAVAILABLE` chỉ nói "timeout sau 5 giây" nhưng thực tế cũng cover case "SDK chưa init (bean null)". Ngữ nghĩa đúng, câu chưa phản ánh đủ. | Tuần 3 — mở rộng câu: "Firebase Admin SDK timeout 5s HOẶC chưa init (credentials chưa config)". |

---

## Tech debt nhỏ

| ID | Vấn đề | Ưu tiên |
|----|--------|---------|
| TD-1 | `AppLoadingScreen.tsx` hiển thị text không dấu "Dang khoi dong..." trong khi toàn app dùng tiếng Việt có dấu. | Thấp — visual consistency. |
| TD-2 | `AuthService.register` logs `WARN` chỉ ở `GlobalExceptionHandler`, không có log riêng cho security events (rate limit hit, account locked). Khó trace. | Trung bình — thêm structured log khi phase observability (tuần 7). |
| TD-3 | `authService.init()` FE catch empty, nuốt mọi error bao gồm network timeout. Dev debug khó. | Thấp — thêm `console.warn('[authService.init] refresh failed', e)`. |
| TD-4 | Controller comment block chỉ list 2 endpoints (`register`, `login`) nhưng thực tế có 5. `AuthController.java:25-28`. | Thấp — update javadoc. |
| TD-5 | `JwtAuthFilter` query DB (`userRepository.findById`) mỗi request authenticated. Khi load tăng nên cache User vào Redis (TTL ngắn). | Trung bình — tuần 5-6 khi bắt đầu optimize. |
| TD-6 | `application-test.yml` JWT secret để rõ trong file. Chấp nhận cho dev/test nhưng đảm bảo không leak sang prod config. | Thấp — guard bằng profile split. |
| TD-7 | `GlobalExceptionHandler.AppException` log ở level DEBUG. Nếu client spam `AUTH_INVALID_CREDENTIALS` sẽ không thấy trong log level INFO → khó phát hiện brute force. | Trung bình — đổi WARN cho 4xx security errors (401, 403, 429). |

---

## Audit trail

- **2026-04-19**: File tạo mới trong audit cuối Tuần 2 (trước tag `v0.2.0-w2`). Tổng hợp từ:
  - `reviewer-log.md` entries W2D1, W2D2, W2D3, W2D3.5, W2D4.
  - Grep codebase tìm `TODO|FIXME|HACK|W-*-` → 1 TODO orphan (useAuth.ts) + 4 references "Tuần 2" trong comment (JwtTokenProvider, useAuth × 2, AuthController).
  - Grep controllers: 5 auth endpoints + 1 health, khớp 100% với contract `v0.4.0-auth-complete`.
