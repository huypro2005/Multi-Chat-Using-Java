# Warnings Tracking
_Last updated: 2026-04-19 (W3-D5 consolidation)_

> File này tổng hợp mọi warning / TODO / tech debt đã biết trong codebase.
> **Quy tắc**: mỗi TODO trong code phải map với 1 ID trong file này hoặc có plan cụ thể. Orphan TODO không được phép.
>
> Format mới (W3-D5 restructure): mỗi section có bảng nhỏ, ưu tiên "tình trạng tech debt" để developer thấy ngay "phải fix gì trước deploy" vs "đã chấp nhận" vs "dọn sau".

---

## 🔴 Pre-production (BẮT BUỘC fix trước deploy V1)

Mọi ID dưới đây phải được resolve trước khi deploy V1 public. Effort ước lượng: XS <30p · S 0.5d · M 1d · L >1d.

| ID | Mô tả | File:Line | Effort | Fix khi nào |
|----|-------|-----------|--------|-------------|
| W-BE-4 | Race condition `existsByEmail` → `save` → 500 INTERNAL_ERROR thay vì 409 AUTH_EMAIL_TAKEN/AUTH_USERNAME_TAKEN. Cần catch `DataIntegrityViolationException` và map constraint name (`users_email_key`, `users_username_key`) sang `AppException` 409. | `backend/src/main/java/com/chatapp/auth/service/AuthService.java:~122` | S | Tuần 6 (hardening) |
| W-BE-5 | `passwordHash = null` cho OAuth-only user. Attacker gọi `/login` với username OAuth → `BCryptPasswordEncoder.matches()` throw `IllegalArgumentException` → 500. Guard `if (user.getPasswordHash() == null) throw AUTH_INVALID_CREDENTIALS` TRƯỚC khi gọi matches(). | `backend/src/main/java/com/chatapp/auth/service/AuthService.java` (login path) | XS | Tuần 6 |
| W-BE-6 | `extractClientIp()` lấy `X-Forwarded-For[0]` không sanitize → IP spoofing ghi Redis key rác `rate:login:{arbitrary}` bypass rate limit. Validate IP format (`InetAddressValidator` hoặc regex IPv4/IPv6) trước khi dùng làm key suffix; invalid → fallback `getRemoteAddr()`. | `backend/src/main/java/com/chatapp/auth/controller/AuthController.java:125-131` | S | Tuần 6 |
| W-BE-7 | Fail-open JWT blacklist khi Redis down → logged-out access token vẫn valid đến natural expiry (≤1h). V1 cần monitoring alert + runbook (rotate JWT_SECRET nếu nghi ngờ compromised token). V2 circuit breaker. | `backend/src/main/java/com/chatapp/security/JwtAuthFilter.java` (blacklist check) | M | Tuần 6 |
| W-BE-8 | `generateUniqueUsername` race OAuth concurrent: 2 request cùng displayName chọn cùng suffix → DB UNIQUE violate → 500. Retry loop max 3 lần bắt `DataIntegrityViolationException` → regenerate. Gộp fix với W-BE-4. | `backend/src/main/java/com/chatapp/auth/service/AuthService.java` (oauth create branch) | S | Tuần 6 (gộp W-BE-4) |
| W4-BE-1 | V5 schema conflict: `sender_id NOT NULL` + `ON DELETE SET NULL` → khi user bị xóa thật, PostgreSQL throw "null value in column violates not-null constraint" thay vì cascade SET NULL. V1 không có endpoint DELETE user nên KHÔNG trigger, nhưng nếu Tuần 6+ thêm hard-delete user → migration fix `sender_id` thành NULL hoặc dùng `ON DELETE NO ACTION`. Cần quyết policy: giữ message khi user xóa (NULL sender) hay xóa cùng (CASCADE). | `backend/src/main/resources/db/migration/V5__create_messages.sql:10` | XS (migration) | Khi mở DELETE user endpoint |

---

## 🟡 Documented acceptable (V1 chấp nhận, đã cân nhắc trade-off)

Các item này đã được review và chấp nhận cho V1. Có ADR hoặc rationale rõ ràng. Không bắt buộc fix.

| ID | Mô tả | Lý do chấp nhận |
|----|-------|-----------------|
| AD-1 | Redis fail SAU khi save user (register) → user tồn tại DB nhưng không có refresh token. `@Transactional` không bao Redis. | FE retry login. Không data corruption. `TransactionSynchronizationManager` rollback Redis phức tạp & không atomic thật. Traffic V1 thấp. |
| AD-2 | Rate limit counter KHÔNG reset sau refresh thành công. User legit refresh 10 lần/60s (hiếm) bị throttle. | Window 60s ngắn, tự hồi phục. FE refresh queue pattern đảm bảo không gọi /refresh song song. |
| AD-3 | Rate limit refresh lấy userId từ token chưa validate Redis hash trước. JWT reused (sig valid) vẫn consume counter → DoS nhắm user cụ thể. | Threat thấp; V1 scale nhỏ. Attacker có các vector dễ hơn. |
| AD-4 | `refresh()` DELETE trước SAVE: crash giữa 2 bước → user mất session phải login lại. | ADR-006. Trade-off chọn "cửa sổ 0 token" (security) thay "cửa sổ 2 token". V2: Redis MULTI/EXEC. |
| AD-5 | `email_verified` claim không check khi auto-link (Google luôn verified). | ADR-007. Google OAuth luôn email_verified=true. **BẮT BUỘC add check khi mở Facebook/Apple**. |
| AD-6 | `HomePage.handleLogout`: `tokenStorage.getRefreshToken()` null sau rehydrate race → skip /logout API → access token không blacklist. | Access token tự expire ≤1h. Edge case race rất hẹp. |
| AD-7 | `registerSchema` regex gộp length + format → error message khi >50 ký tự nói "bắt đầu bằng chữ cái". | UX trade-off nhỏ. Schema match contract BE chính xác. |
| AD-8 | Register rate limit tính MỌI request (10/15min/IP). User legit tạo 10 account hợp lệ bị chặn. | Anti-abuse intent. Không ai normal tạo 10 account/15min. |
| AD-9 | `users.last_seen_at` KHÔNG expose qua bất kỳ API V1 (UserSearchDto, GET /api/users/{id}, etc). Column chỉ dùng internal. | Privacy V1 chưa chốt policy (opt-in, granularity, block list). V2 expose khi có policy. Column có sẵn để không tốn migration sau. |
| AD-10 | `JwtAuthFilter` update `last_seen_at` dùng `userRepository.save(user)` full entity → lost-update window + auto-commit tx. | V1 traffic thấp, user hiếm concurrent self-update. V2 fix: partial `@Modifying @Query` hoặc Redis presence pattern (giảm write DB ~99%). |
| W3-BE-2 | `Conversation.createdBy` DB column `ON DELETE SET NULL` là dead code V1 (soft-delete pattern, không DELETE thật). | Future-proof khi V2 hard-delete. Không tốn gì giữ constraint. |
| W3-BE-4 | N+1 query risk khi list conversations (custom `@Query` aggregate member/last_message). | Traffic V1 <1000 users + index có sẵn. Optimize tuần 4-5 nếu query plan thực tế cho thấy tải cao. |
| W3-BE-5 (schema) | `conversation_members` không có `updated_at` + trigger BEFORE UPDATE. | `joined_at` immutable + `last_read_message_id` tự track thay đổi. V1 đủ. |
| ADR-013 | ONE_ON_ONE race no-lock V1: 2 request concurrent cùng pair → có thể tạo 2 conv duplicate. | P(collision) < 0.01%. SERIALIZABLE + advisory lock overhead không đáng. V2: partial UNIQUE index. Clean-up script merge dup. |
| AD-11 | `MessageService.sendMessage` cập nhật `conversation.lastMessageAt` không có optimistic lock (no `@Version`). 2 messages concurrent cùng conv có race window. | `touchLastMessage()` chỉ set khi `messageTime.isAfter(current)` → kết quả convergent (max), không inconsistent. Acceptable V1. V2 nếu cần audit "ai update cuối": thêm `@Version`. |
| AD-12 | Reply tới message đã soft-delete (`deletedAt != null`) KHÔNG bị block ở `MessageService.sendMessage`. `existsByIdAndConversation_Id` không filter `deletedAt IS NULL`. | V1 chưa có endpoint Edit/Delete message → soft-delete chỉ xảy ra khi DB ops manual. Tuần 6 (Edit/Delete) sẽ chốt policy: block reply hay cho phép với placeholder "[deleted]". Khi đó: thêm method `existsByIdAndConversation_IdAndDeletedAtIsNull` hoặc tương đương. |
| AD-13 | `MessageService.toMessageDto` mapping LAZY `sender` + `replyToMessage.sender` cho mỗi message → list 50 msg có thể fire 50-100 SELECT (N+1). | V1 traffic <1000 users + page size 50. Acceptable. Tuần 5 nếu query plan thực tế nóng → thêm `@Query("... LEFT JOIN FETCH m.sender LEFT JOIN FETCH m.replyToMessage rm LEFT JOIN FETCH rm.sender ...")` cho `findByConversation_IdAnd...`. |

---

## 🔵 Cleanup (tuần 8 hoặc khi có bandwidth)

Effort XS-S, không ảnh hưởng chức năng. Dọn khi có thời gian hoặc ghép vào PR gần đó.

| ID | Mô tả | File:Line | Effort | Ưu tiên |
|----|-------|-----------|--------|---------|
| CL-1 / TD-3 | `useAuth.ts` orphan TODO: comment "Tuần 2 sẽ call /api/auth/logout" + logic outdated. Logout đã implement ở `HomePage.tsx` (`logoutApi` trực tiếp). Quyết: (a) dẹp `useAuth.logout`; HOẶC (b) centralize logic vào hook. | `frontend/src/hooks/useAuth.ts:16-30` | S | Thấp — Tuần 3 nav refactor |
| CL-2 | `LoginPage.tsx` + `RegisterPage.tsx` check `apiErr?.error === 'PROVIDER_ALREADY_LINKED'` — BE không emit, contract không define. Dead branch. | `LoginPage.tsx:201`, `RegisterPage.tsx:277` | XS | Thấp — xóa |
| CL-3 | `handleAuthError.ts` case `AUTH_ACCOUNT_DISABLED` dead — BE chỉ throw `AUTH_ACCOUNT_LOCKED`. Xóa hoặc gộp. | `frontend/src/features/auth/utils/handleAuthError.ts:28` | XS | Thấp |
| CL-4 | Test 17 `refreshWithExpiredToken_returnsExpiredError` fallback sang INVALID signature vì không tạo expired token thật. EXPIRED path chỉ có unit test ở `JwtTokenProviderTest`. | `backend/.../AuthControllerTest.java:511` | S | Thấp — dùng `@SpyBean` stub `validateTokenDetailed()` |
| CL-5 | Comment "Tuần 2 sẽ truyền dynamic" về `auth_method` — AuthMethod enum đã implement từ W2D1 (W-BE-3 resolved). Xóa comment. | `backend/.../JwtTokenProvider.java:189` | XS | Thấp |
| CL-6 | Contract `AUTH_FIREBASE_UNAVAILABLE` nói "timeout 5s" — thực tế cũng cover "SDK chưa init". Mở rộng câu. | `docs/API_CONTRACT.md:256` | XS | Thấp |
| W3-BE-5 (code) | `UserController` inject `ConversationService` cross-package để tìm user. Nên có `UserService` riêng trong `com.chatapp.user`. | `backend/.../user/controller/UserController.java` | S | Thấp — refactor khi touch user module |
| W3-FE-1 | `ProtectedRoute.tsx` có `isHydrated` check dù `App.tsx` đã gate `isInitialized`. Branch spinner trong ProtectedRoute là dead code. Chọn: (a) xóa; (b) giữ với comment "defense-in-depth". | `frontend/src/components/ProtectedRoute.tsx:19` + `App.tsx:23` | XS | Thấp |
| W3-FE-4 | Inline arrow `onClick={() => navigate(...)}` trong `ConversationListSidebar` L106 phá `React.memo` của `ConversationListItem` → re-render hết list mỗi khi sidebar re-render. Fix: `useCallback` + pattern `onSelect(id)`. | `frontend/.../ConversationListSidebar.tsx:106` | S | Thấp — khi list scale >50 items |
| TD-1 | `AppLoadingScreen.tsx` text không dấu "Dang khoi dong...". | `frontend/src/components/AppLoadingScreen.tsx` | XS | Thấp |
| TD-2 | `AuthService.register` thiếu structured log cho security events (rate limit hit, account locked). | `backend/.../auth/service/AuthService.java` | S | Trung bình — phase observability tuần 7 |
| TD-4 | `AuthController` javadoc outdated (list 2 endpoints thay vì 5). | `backend/.../auth/controller/AuthController.java:25-28` | XS | Thấp |
| TD-5 | `JwtAuthFilter` query DB `userRepository.findById` mỗi request authenticated. Cache User vào Redis TTL ngắn khi load tăng. | `backend/.../security/JwtAuthFilter.java` | M | Trung bình — tuần 5-6 |
| TD-6 | `application-test.yml` JWT secret rõ trong file. OK cho test nhưng guard profile split không leak prod. | `backend/src/main/resources/application-test.yml` | XS | Thấp |
| TD-7 | `GlobalExceptionHandler.AppException` log level DEBUG → spam `AUTH_INVALID_CREDENTIALS` không thấy trong INFO → khó phát hiện brute force. Đổi WARN cho 4xx security (401, 403, 429). | `backend/.../exception/GlobalExceptionHandler.java` | XS | Trung bình |
| TD-9 | `ConversationDetailPage` error state chỉ có "Quay lại", không có "Thử lại" in-place. React Query đã tự retry 3 lần. | `frontend/src/pages/ConversationDetailPage.tsx` | XS | Thấp |
| TD-10 | `UserController.getUserById` nhận `@AuthenticationPrincipal User currentUser` không dùng trong method body → compiler warning. | `backend/.../user/controller/UserController.java` | XS | Thấp — giữ param cho V2 block-list filter, suppress warning |

---

## ✅ Resolved (W3)

| ID | Mô tả | Resolved khi | Fix |
|----|-------|-------------|-----|
| W3-BE-1 | `@GeneratedValue(UUID) + insertable=false` conflict Hibernate 6 cho Conversation/ConversationMember. | W3-D2 | Migrate sang `@PrePersist` Option B: `if (id == null) id = UUID.randomUUID()`. Test `savingConversation_shouldPersistWithNonNullId` confirm `save()` trả entity với id != null. |
| W3-BE-3 | `CREATE EXTENSION pgcrypto` thiếu migration V2 → developer clone fresh DB fail `gen_random_uuid()`. | W3-D2 | Thêm `CREATE EXTENSION IF NOT EXISTS pgcrypto;` vào đầu V2. `repair-on-migrate: true` trong application.yml cho DB cũ. |
| W3-BE-6 | `POST /api/conversations` không có rate limit → user tạo unlimited conv. | W3-D3 | Redis INCR `rate:conv_create:{userId}` TTL 60s, max 10/min/user. 429 RATE_LIMITED khi vượt. |
| TD-8 | `MethodArgumentTypeMismatchException` cho `@PathVariable UUID id` → 500 INTERNAL_ERROR thay vì 400 VALIDATION_FAILED. | W3-D5 | `GlobalExceptionHandler.handleTypeMismatch()` map sang `AppException(400, "VALIDATION_FAILED", "Invalid path parameter: " + name)`. Cover toàn bộ endpoint `@PathVariable UUID`. |
| W-C-4 | `ProtectedRoute` chưa wire vào router tree. | W3-D1 | ProtectedRoute wrap `/conversations/*` routes trong `App.tsx`; redirect `/login` nếu không auth. |

---

## ✅ Resolved (Tuần 2)

| ID | Vấn đề | Fix | Resolved |
|----|--------|-----|----------|
| W-BE-3 | `generateAccessToken` hardcode `auth_method="password"` → OAuth user cũng bị gắn password claim → sai nghiệp vụ. | Introduce `AuthMethod` enum (PASSWORD / GOOGLE) tại `com.chatapp.user.enums`. `generateAccessToken(User, AuthMethod)` nhận enum. Reader fallback PASSWORD khi claim unknown. | W2-D1 (ADR-010) |
| W-FE-1 | Username regex FE lệch BE constraint. | Sync regex `/^[a-zA-Z_][a-zA-Z0-9_]{2,49}$/` trong `registerSchema.ts`. First char không cho phép digit, total 3-50 ký tự. | W2-D3 |
| W-FE-2 | Circular dep `api.ts <-> authStore.ts` dùng `globalThis` workaround. | Migrate sang `tokenStorage.ts` pattern (module in-memory trung gian). `authStore.setAuth()` / `clearAuth()` sync 2 chiều. `onRehydrateStorage` restore refreshToken. accessToken không persist (ADR-003). | W2-D1 |

---

## Audit trail

- **2026-04-19 (W2 Final Audit)**: File tạo mới trước tag `v0.2.0-w2`. Tổng hợp từ reviewer-log W2D1-W2D4. 5 pre-production, 8 documented acceptable, 6 cleanup, 7 tech debt nhỏ.
- **2026-04-19 (W3D4)**: Thêm AD-9 (last_seen_at non-exposed), AD-10 (JwtAuthFilter save full entity), TD-8, TD-9, TD-10.
- **2026-04-19 (W3D5 consolidation)**: Restructure theo format mới (emoji priority + bảng effort + "Fix khi nào"). Mark resolved: W3-BE-1, W3-BE-3, W3-BE-6, TD-8. Thêm documented acceptable: W3-BE-2, W3-BE-4, W3-BE-5 (schema). Thêm cleanup: W3-BE-5 (code), W3-FE-1, W3-FE-4. Developer giờ nhìn là thấy ngay pre-production backlog = 5 items trước deploy V1.
- **2026-04-19 (W4D1)**: Thêm W4-BE-1 (V5 schema NOT NULL + ON DELETE SET NULL conflict — chỉ blocking khi mở DELETE user endpoint). Thêm AD-11 (lastMessageAt no optimistic lock — convergent OK), AD-12 (reply soft-deleted message không block — defer Tuần 6), AD-13 (N+1 message list LAZY — V1 acceptable). Pre-production backlog = 6 items.
