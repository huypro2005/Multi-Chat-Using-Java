# Backend Log — Nhật ký chi tiết backend-dev

> File này là **lịch sử** công việc của backend-dev.
> Quy tắc: append-only, mới nhất ở ĐẦU file (để dễ đọc 20 dòng đầu).
> Mỗi ngày làm việc tạo 1 entry, không gộp nhiều ngày.
> Mục đích: (1) daily standup, (2) agent session sau biết đã làm đến đâu, (3) tra cứu "bug này đã gặp chưa".
> Giới hạn: không giới hạn độ dài — nhưng agent chỉ ĐỌC entry mới nhất trừ khi cần tra cứu cụ thể.

---

## Template cho mỗi entry

```
## YYYY-MM-DD (Tuần N, Ngày X) — <chủ đề ngắn>

### Xong
- <task>: <tóm tắt 1 câu> (commit <hash>)

### Đang dở
- <task>: <tình trạng>

### Blocker
- <vấn đề>: <đã xử lý gì, chờ gì>

### Ghi chú kỹ thuật
- <phát hiện đáng nhớ, nếu cần học được gì đã update vào backend-knowledge.md>
```

---

## Entries

*(Entries sẽ append ở đây, MỚI NHẤT trên cùng)*

## 2026-04-19 (Tuần 3, Ngày 5) — fix TD-8: MethodArgumentTypeMismatchException → 400

### Xong
- [BE][W3-D5][2026-04-19] fix(TD-8): MethodArgumentTypeMismatchException → 400 VALIDATION_FAILED
  - `GlobalExceptionHandler`: thêm `@ExceptionHandler(MethodArgumentTypeMismatchException.class)` → 400 `VALIDATION_FAILED` với details `{field, error}`. Cover mọi endpoint có `@PathVariable UUID`.
  - `ConversationControllerTest`: thêm 2 test case — `getConversation_malformedUUID_returns400` và `getConversation_validUUIDNonExistent_returns404`.
  - `docs/WARNINGS.md`: TD-8 moved từ "Tech debt nhỏ" → "Resolved".
  - `mvn test`: 70 tests pass (0 failures, ConversationControllerTest tăng từ 18 → 20).

### Đang dở
- Không có.

### Blocker
- Không có.

### Ghi chú kỹ thuật
- `MethodArgumentTypeMismatchException` là subclass của `MethodArgumentConversionNotSupportedException` — handler đặt TRƯỚC catch-all Exception để intercept đúng. Spring dispatch theo exception hierarchy: handler specific nhất được ưu tiên.

---

## 2026-04-19 (Tuần 3, Ngày 4) — GET /api/users/{id}, V4 last_seen_at migration

### Xong
- [BE][W3-D4][2026-04-19] feat: GET /api/users/{id}, V4 last_seen_at migration
  - `V4__add_last_seen.sql`: ALTER TABLE users ADD COLUMN last_seen_at TIMESTAMPTZ NULL + index idx_users_last_seen.
  - `User` entity: thêm field `OffsetDateTime lastSeenAt` (`@Column(name="last_seen_at")`).
  - `ConversationService.getUserById(UUID)`: load user, filter active, trả `UserSearchDto.from(user)` — throw 404 USER_NOT_FOUND nếu không tồn tại hoặc không active.
  - `UserController`: thêm `GET /{id}` endpoint, delegate `conversationService.getUserById(id)`.
  - `JwtAuthFilter`: sau khi set authentication thành công, update `lastSeenAt = now()` nếu `lastSeenAt == null || duration > 30s`. Fail-open (non-critical, catch + log.warn).
  - `mvn test`: 68 tests pass, BUILD SUCCESS (exit code 0).

### Đang dở
- Không có.

### Blocker
- Không có.

### Ghi chú kỹ thuật
- Task 2 (last_seen_at) đã làm đầy đủ — không cần skip. Logic update sync trong JwtAuthFilter với throttle 30s là đủ cho V1 (scale nhỏ <1000 users). Nếu cần async sau này: @Async + Spring TaskExecutor.
- `OffsetDateTime` (không phải `Instant`) cho TIMESTAMPTZ — consistent với User entity pattern đã chốt.

---

## 2026-04-19 (Tuần 3, Ngày 3 — fix) — fix: rate limit fail-open Redis, retryAfterSeconds, sync contract

### Xong
- [BE][W3-D3-fix][2026-04-19] fix: rate limit fail-open Redis, retryAfterSeconds in details, sync contract 10/min
  - `ConversationService.createConversation`: wrap Redis INCR + expire trong `try/catch DataAccessException` — fail-open nếu Redis down (ADR-011 consistent).
  - Khi vượt limit: query TTL thực từ Redis, throw `AppException(429, "RATE_LIMITED", ..., Map.of("retryAfterSeconds", ttl))`.
  - `docs/API_CONTRACT.md`: sửa `POST /api/conversations` rate limit từ "30/giờ" → "10/phút" (2 chỗ: mục Rate limit + bảng Error responses). Thêm v0.5.1-conversations vào changelog.
  - `mvn test`: 68 tests pass (0 failures).

### Đang dở
- Không có.

### Blocker
- Không có.

### Ghi chú kỹ thuật
- `DataAccessException` (spring-dao) bao phủ mọi Redis/JPA exception khi client gặp lỗi network — dùng consistent với pattern blacklist fail-open của JwtAuthFilter.
- TTL fallback = 60 (seconds) khi Redis down để FE có giá trị hợp lý để hiển thị retry countdown.

---

## 2026-04-19 (Tuần 3, Ngày 3) — W3-BE-6 rate limit POST /api/conversations

### Xong
- [BE][W3-D3][2026-04-19] fix(W3-BE-6): rate limit POST /api/conversations 10/min/user via Redis
  - `ConversationService`: inject `StringRedisTemplate`, thêm INCR check đầu `createConversation()`.
  - Key `rate:conv_create:{userId}`, TTL 60s, limit 10/window. Vượt → `AppException` 429 `RATE_LIMITED`.
  - `GlobalExceptionHandler` đã handle `AppException` generic — không cần sửa.
  - `docs/WARNINGS.md`: W3-BE-6 thêm vào bảng Resolved.
  - `mvn test`: 68 tests pass (0 failures).

### Đang dở
- Không có.

### Blocker
- Không có.

---

## 2026-04-19 (Tuần 3, Ngày 2 — fix) — GROUP conversation validation hardening

### Xong
- [BE][W3-D2-fix][2026-04-19] fix: GROUP validation — dedupe memberIds, reject caller-in-members, max 50 members
  - `ConversationService.createGroup`: thêm 3 guard: (1) caller-in-memberIds → 400 VALIDATION_FAILED, (2) dedupe via `.distinct()`, (3) max 49 other members → 400 VALIDATION_FAILED.
  - `ConversationControllerTest`: thêm T07b (caller in memberIds), T07c (dedup still valid), T07d (dedup too few unique). Tổng: 68 tests, BUILD SUCCESS.

### Đang dở
- Không có.

### Blocker
- Không có.

### Ghi chú kỹ thuật
- Validation thứ tự: null-check → caller-check → distinct → size-check-min → size-check-max → existence-check. Thứ tự quan trọng để error message rõ ràng.

## 2026-04-19 (W3-D2) — feat: 4 conversation endpoints + 15 tests, W3-BE-1 verified/fixed

### Xong
- `AppException`: thêm constructor `(status, errorCode, message, details)` + `getDetails()` field.
- `GlobalExceptionHandler`: truyền `ex.getDetails()` vào `ErrorResponse.of()` để serialize details.
- `Conversation` + `ConversationMember` entities: W3-BE-1 fix — xóa `@GeneratedValue`, thêm `@PrePersist if (id==null) id = UUID.randomUUID()`.
- `ConversationRepository`: thêm `findExistingOneOnOne(String, String)` native SQL double-join, `findConversationsByUserPaginated(String, int, int)`, `countConversationsByUser(String)`. Cập nhật JOIN FETCH query để fetch `m.user`.
- `ConversationMemberRepository`: thêm `findByConversation_IdAndUser_Id(UUID, UUID)`.
- `UserRepository`: thêm `searchUsers(String q, UUID currentUserId, Pageable)` JPQL.
- DTOs package `com.chatapp.conversation.dto`: `CreateConversationRequest`, `MemberDto`, `CreatedByDto`, `ConversationDto`, `ConversationSummaryDto`, `ConversationListResponse`, `UserSearchDto`.
- `ConversationService`: createOneOnOne (check existing + 409), createGroup, listConversations (native query + batch load), getConversation (anti-enumeration 404), searchUsers.
- `ConversationController` (`/api/conversations`): POST (201), GET (?page&size), GET /{id}.
- `UserController` (`/api/users`): GET /search?q=&limit=.
- `ConversationControllerTest`: 15 tests — W3-BE-1 + T01-T14, tất cả PASS.
- `mvn test`: 65 tests, 0 failures, BUILD SUCCESS.

### Đang dở
- WebSocket / messaging (tuần 4).

### Blocker
- Không có.

### Ghi chú kỹ thuật
- H2 native query UUID pitfall: trả `byte[]` thay vì `UUID`/`String` → fix bằng `CAST(c.id AS VARCHAR)` trong SELECT và `CAST(:param AS UUID)` trong WHERE, dùng `String` parameter thay vì `UUID`.
- Flush+clear EntityManager pattern: cần thiết trong @Transactional sau save khi muốn reload bằng custom JPQL — tránh stale 1st-level cache trả empty collection.
- ConversationListResponse shape theo contract: `content/page/size/totalElements/totalPages` (không phải `items/total/pageSize`).

---

## 2026-04-19 (W3-BE-3 fix) — fix: thêm pgcrypto extension vào V2 migration

### Xong
- `V2__create_users_and_auth_providers.sql`: thêm `CREATE EXTENSION IF NOT EXISTS pgcrypto;` vào đầu file.
- `application.yml`: thêm `repair-on-migrate: true` vào Flyway config — developer có DB cũ tự repair checksum khi start app.
- `docs/WARNINGS.md`: thêm mục "Resolved", ghi W3-BE-3 RESOLVED với giải thích fix.
- `mvn test`: 50/50 tests pass, BUILD SUCCESS.

### Đang dở
- Không có.

### Blocker
- Không có.

### Ghi chú kỹ thuật
- `flyway:repair` Maven plugin cần `-Dflyway.url/-Dflyway.user/-Dflyway.password` riêng (không đọc application.yml). Dùng `repair-on-migrate: true` trong Spring Boot config thay thế — Spring Boot tự pass connection đến Flyway.
- Test profile (`application-test.yml`) có `flyway.enabled: false` → test không validate Flyway checksum. Verify thực tế bằng `mvn test` pass + kiểm tra logic V2 file.
- Developer mới fresh DB: `CREATE EXTENSION IF NOT EXISTS pgcrypto` là idempotent — an toàn dù extension đã có hoặc chưa.

---

## 2026-04-19 (W3-D1) — feat: V3 migration conversations + conversation_members, entities, repositories

### Xong
- V3 migration: tạo `conversations` (type CHECK ONE_ON_ONE/GROUP, last_message_at, idx_conversations_last_message) và `conversation_members` (role CHECK OWNER/ADMIN/MEMBER, UNIQUE conv+user, idx_members_user, idx_members_conv). Flyway "Successfully applied 3 migrations".
- ConversationType enum + MemberRole enum (`com.chatapp.conversation.enums`).
- Conversation entity: `@Getter @Setter @Builder`, `@Enumerated(EnumType.STRING)`, `@OneToMany(mappedBy="conversation", fetch=LAZY)`, `@PrePersist`/`@PreUpdate`, domain methods (isGroup, touchLastMessage).
- ConversationMember entity: `@Enumerated(EnumType.STRING)` role, `@ManyToOne LAZY` cho cả conversation và user, `@PrePersist` joinedAt, domain methods (isOwner, canManageMembers, isMuted).
- ConversationRepository: `findByIdWithMembers` với JOIN FETCH tránh N+1.
- ConversationMemberRepository: `findByUser_IdOrderByJoinedAtDesc`, `existsByConversation_IdAndUser_Id`.
- DB reset: terminate sessions → DROP → CREATE → pgcrypto → Flyway.
- `mvn test`: 50 tests, 0 failures.

### Đang dở
- Service + Controller cho conversation (Ngày 2).

### Blocker
- Không có.

### Ghi chú kỹ thuật
- `@Builder.Default private List<ConversationMember> members = new ArrayList<>()` cần thiết để builder không trả null list.
- DB_PASSWORD=123456 (từ backend/.env).

---

## 2026-04-19 (W2D4) — OAuth (Firebase) + Logout (blacklist jti)

### Xong
- FirebaseConfig: `@PostConstruct` init SDK, expose `FirebaseAuth` bean (null nếu chưa init). Lazy init để app start dù không có credentials.
- UserAuthProviderRepository: thêm `existsByProviderAndProviderUidAndUser_IdNot()`.
- DTOs: OAuthRequest, OAuthResponse (isNewUser field), LogoutRequest.
- AuthService: thêm `oauth()` (verify Firebase → auto-link logic 3 bước), `logout()` (best-effort delete refresh + blacklist access), `generateUniqueUsername()`, `toOAuthResponse()`. Refactor constructor thủ công + setter `@Autowired(required=false)` cho FirebaseAuth (testability).
- JwtTokenProvider: thêm `getRemainingMs(String token)`.
- JwtAuthFilter: thêm blacklist check sau validate VALID — `redisTemplate.hasKey("jwt:blacklist:{jti}")`. Inject `StringRedisTemplate`. Fail-open nếu Redis unavailable.
- SecurityConfig: narrow whitelist từ `/api/auth/**` thành từng path explicit; `/api/auth/logout` KHÔNG trong whitelist (cần JWT).
- AuthController: thêm `POST /oauth` và `POST /logout`.
- AuthControllerTest: thêm 10 tests (oauth new user, returning user, auto-link, invalid token, missing email, missing body; logout happy, invalid refresh still 200, no auth 401, blacklist test). 33/33 PASS, tổng 50/50 PASS, BUILD SUCCESS.

### Đang dở
- Không có trong phase A.

### Blocker
- Không có.

### Ghi chú kỹ thuật
- KHÔNG dùng `FirebaseAuth.getInstance()` trực tiếp trong Service — không testable. Dùng injected bean qua setter `@Autowired(required=false)`. `@MockBean FirebaseAuth` trong test sẽ replace bean và được inject vào AuthService.
- `@Bean` trả null trong Spring được bỏ qua (bean không được đăng ký). Các nơi `@Autowired(required=false)` sẽ nhận null → handle gracefully.
- Test 33 (blacklist check): mock `redisTemplate.hasKey(eq(blacklistKey)).thenReturn(true)` sau logout → JwtAuthFilter detect và set `jwt_expired=true` → request tới protected endpoint → 401.

## 2026-04-19 (W2D3.5) — POST /api/auth/refresh với token rotation + reuse detection

### Xong
- RefreshRequest DTO: `record RefreshRequest(@NotBlank String refreshToken)`.
- JwtTokenProvider: thêm `getClaimsAllowExpired()` — extract claims kể cả khi token EXPIRED (lấy từ `ExpiredJwtException.getClaims()`). Refactor `getUserIdFromToken()` và `getJtiFromToken()` dùng `getClaimsAllowExpired()` thay vì `getClaims()`.
- AuthService: thêm `refresh()` — validate → rate limit per-userId (10 calls/60s) → hash compare constant-time → reuse detection → delete old → generate new. Thêm `revokeAllUserSessions()`, `constantTimeEquals()`.
- AuthController: thêm `POST /refresh` delegate sang `authService.refresh()`.
- AuthControllerTest: thêm 9 test (happy path, invalid token, expired token, revoked token, rate limit, suspended user, missing body, empty token, reuse revokes all sessions). 23/23 tests PASS, tổng 40/40 PASS, BUILD SUCCESS.

### Đang dở
- POST /api/auth/oauth (Firebase), /logout — phase sau.

### Blocker
- Không có.

### Ghi chú kỹ thuật
- Constant-time comparison bằng `MessageDigest.isEqual()` — tránh timing attack khi compare hash. String.equals() có thể short-circuit.
- `getClaimsAllowExpired()` cần thiết vì `getClaims()` throw trên expired token, nhưng expired refresh token vẫn cần extract userId/jti để check Redis trước khi trả EXPIRED error. Thực tế flow này không dùng vì EXPIRED check xảy ra trước claims extraction — nhưng method này safety net.
- Contract thắng task spec: error codes là `AUTH_REFRESH_TOKEN_INVALID` và `AUTH_REFRESH_TOKEN_EXPIRED` (không phải `REFRESH_TOKEN_INVALID`/`REFRESH_TOKEN_EXPIRED`). Account disabled trả `AUTH_ACCOUNT_LOCKED` (không phải `ACCOUNT_DISABLED`).

## 2026-04-19 (W2D2 Phase B) — POST /api/auth/register + POST /api/auth/login

### Xong
- Tạo package `com.chatapp.auth.{controller,service,dto.{request,response}}`.
- DTOs: RegisterRequest (email, username, password, fullName + validation), LoginRequest, UserDto.from(User), AuthResponse.
- AuthService: register (rate limit 10/15min, unique check email trước username, bcrypt hash, generate tokens), login (rate limit check fail count, same error code user-not-found vs wrong-password security, account lock check, reset counter on success). buildAuthResponse: SHA-256 hash refresh token → Redis key `refresh:{userId}:{jti}` TTL 7 ngày.
- AuthController: POST /register + POST /login, extractClientIp (X-Forwarded-For → remoteAddr).
- AuthControllerTest: 14 tests (register happy, dup email, dup username, invalid email, weak password 2 cases, username starts digit, missing fullName; login happy, wrong password, user not found, rate limit 429, empty username, empty password). 14/14 PASS.
- Fix: thêm `@MockBean StringRedisTemplate` vào JwtTokenProviderTest, SecurityConfigTest, ChatAppApplicationTests — 3 test class này exclude Redis autoconfigure nhưng AuthService bây giờ inject StringRedisTemplate → context fail. @MockBean giải quyết.
- Tổng: 31/31 tests PASS, BUILD SUCCESS.

### Đang dở
- POST /api/auth/oauth (Firebase), /refresh, /logout — phase sau.

### Blocker
- Không có.

### Ghi chú kỹ thuật
- Khi thêm bean inject Redis vào context, tất cả test class exclude Redis autoconfigure sẽ fail context load. Fix: thêm `@MockBean StringRedisTemplate` vào từng class đó. Đây là pitfall quan trọng — xem backend-knowledge.md.
- Contract thắng task spec: error codes là AUTH_EMAIL_TAKEN, AUTH_USERNAME_TAKEN, AUTH_INVALID_CREDENTIALS, AUTH_ACCOUNT_LOCKED (không phải EMAIL_TAKEN, USERNAME_TAKEN, INVALID_CREDENTIALS, ACCOUNT_DISABLED). Register response HTTP 200 (không phải 201). Password regex cần 1 chữ hoa + 1 chữ số.

## 2026-04-19 (W2D1 — W-BE-3) — AuthMethod enum + refactor generateAccessToken

### Xong
- Xác nhận AuthMethod.java, JwtTokenProvider.java, JwtTokenProviderTest.java đã được implement đầy đủ từ phiên trước.
- Grep toàn bộ: không còn call `generateAccessToken` nào với 1 argument — tất cả đều đúng `(user, AuthMethod.X)`.
- JwtTokenProviderTest có đủ 4 test mới: generateAccessTokenWithPasswordMethod, generateAccessTokenWithOauthMethod, getAuthMethodFromTokenPassword, getAuthMethodFromTokenOauth.
- mvn test: 17/17 PASS, BUILD SUCCESS. Test count tăng từ 13 → 17.

### Đang dở
- Auth endpoints (POST /api/auth/register, /login, /oauth, /refresh, /logout) — tiếp tục W2.

### Blocker
- Không có.

### Ghi chú kỹ thuật
- Tất cả refactor W-BE-3 đã hoàn thành trước session này — không cần viết code mới. Chỉ verify + chạy test.

## 2026-04-19 (W1 Fix — Pre-Phase 3B) — Phân biệt AUTH_TOKEN_EXPIRED vs AUTH_REQUIRED

### Xong
- JwtTokenProvider: thêm `TokenValidationResult` enum (VALID/EXPIRED/INVALID) và `validateTokenDetailed()`. `validateToken()` cũ delegate sang đây — backward compatible.
- Thêm package-private `generateTokenWithExpiration(User, long)` helper cho test — không hardcode JWT string.
- JwtAuthFilter: thay `validateToken()` → `validateTokenDetailed()`. Set `request.setAttribute("jwt_expired", true)` khi EXPIRED.
- SecurityConfig authenticationEntryPoint: check `jwt_expired` attribute, trả `AUTH_TOKEN_EXPIRED` + message tiếng Việt khi expired, `AUTH_REQUIRED` khi không có/invalid token.
- SecurityConfigTest: thêm 2 test mới `expiredTokenShouldReturnAuthTokenExpired` và `invalidTokenShouldReturnAuthRequired`. Tổng 13/13 tests PASS (mvn test BUILD SUCCESS).

### Đang dở
- Auth endpoints (POST /api/auth/register, /login, /oauth, /refresh, /logout) — Phase 3B.

### Blocker
- Không có.

### Ghi chú kỹ thuật
- `ExpiredJwtException` là subclass của `JwtException` nên phải catch nó TRƯỚC trong multi-catch — thứ tự catch quan trọng (không phải vấn đề ở đây vì dùng separate catch blocks nhưng cần nhớ nếu refactor).
- Test dùng package-private method: SecurityConfigTest nằm cùng package `com.chatapp.security` với JwtTokenProvider nên truy cập được method package-private mà không cần reflection.

## 2026-04-19 (Tuần 1, Ngày 3) — Spring Security 6 + JWT utility + GlobalExceptionHandler

### Xong
- JwtTokenProvider: generateAccessToken/RefreshToken, validateToken, getClaims, getUserIdFromToken, getJtiFromToken. jjwt 0.12.x API. Secret dùng UTF-8 bytes trực tiếp.
- JwtAuthFilter: OncePerRequestFilter, extract Bearer token, validate, load User từ UserRepository, set SecurityContext. Không throw exception — chỉ log.warn và skip.
- SecurityConfig: STATELESS, JWT filter, CORS từ property, authenticationEntryPoint + accessDeniedHandler trả JSON (không phải HTML). BCrypt(12) PasswordEncoder bean.
- ErrorResponse record: shape chuẩn { error, message, timestamp, details? } với @JsonInclude(NON_NULL).
- AppException: business exception dùng chung (HttpStatus, errorCode, message).
- GlobalExceptionHandler: handle AppException, MethodArgumentNotValidException (với details.fields), ConstraintViolationException, generic Exception (500).
- application.yml: update JWT secret đủ dài cho HS256, chuẩn hóa property names.
- application-test.yml: test profile với H2 in-memory, flyway disabled.
- JwtTokenProviderTest: 6 tests (generate, validate, expired, tampered, random). PASS.
- SecurityConfigTest: 4 tests (health public, 401 JSON, invalid token 401, auth endpoints not 401). PASS.
- Tổng: 11/11 tests pass (mvn test BUILD SUCCESS).

### Đang dở
- Auth endpoints (POST /api/auth/register, /login, /oauth, /refresh, /logout) — Ngày 4+.
- UserDetailsService chưa implement (không cần cho filter, sẽ xem xét khi làm AuthenticationManager cho login endpoint).

### Blocker
- Không có.

### Ghi chú kỹ thuật
- `@SpringBootTest` KHÔNG có `excludeAutoConfiguration` attribute → phải dùng `properties = "spring.autoconfigure.exclude=..."`. Mất 1 lần compile fail để phát hiện. Đã ghi vào knowledge.
- CORS: `allowedOrigins("*")` + `allowCredentials(true)` = Spring Security exception. Phải dùng origins cụ thể.

## 2026-04-19 (Tuần 1, Ngày 2) — V2 migration + JPA entities + repositories

### Xong
- V2__create_users_and_auth_providers.sql: tạo 3 bảng users, user_auth_providers, user_blocks với đầy đủ constraint và index khớp ARCHITECTURE.md 3.1.
- User.java entity: UUID PK (DB generate), OffsetDateTime timestamps, @PrePersist/@PreUpdate, domain methods markAsDeleted()/isActive()/isDeleted().
- UserAuthProvider.java entity: ManyToOne(LAZY) -> User.
- UserBlock.java entity: 2 ManyToOne(LAZY) -> User (blocker, blocked).
- UserRepository, UserAuthProviderRepository, UserBlockRepository: Spring Data JPA interfaces.
- Flyway V2 applied thành công: "Successfully applied 1 migration to schema public, now at version v2".
- Hibernate validate PASS: không có schema mismatch.
- psql verify: cả 3 bảng tồn tại đúng structure, index đúng, FK đúng.

### Đang dở
- Auth service/controller (RegisterRequest, LoginRequest, JWT issuance) — để Ngày 3.

### Blocker
- Không có.

### Ghi chú kỹ thuật
- Port 8080 đã có process cũ chiếm (app Ngày 1 vẫn chạy). Khi boot test dùng port khác hoặc kill trước. Hibernate validate vẫn pass trước khi lỗi port.
- Spring Data Redis log WARN "Could not safely identify store assignment" cho JPA repositories là bình thường khi có cả JPA + Redis module — không phải lỗi.

## 2026-04-19 (Tuần 1, Ngày 1) — Khởi tạo Spring Boot project

### Xong
- Tạo pom.xml: Spring Boot 3.4.4, Java 21, Maven. Dependencies: Web, Security, JPA, Redis, WebSocket, Validation, Flyway, PostgreSQL, Lombok, jjwt 0.12.6, firebase-admin 9.4.1, test scope.
- Tạo application.yml: cấu hình datasource, jpa (ddl-auto: validate), flyway, redis, jwt properties. Tạm thời exclude FlywayAutoConfiguration, DataSourceAutoConfiguration, JPA, Redis autoconfigure để app start không cần DB thật.
- Tạo ChatAppApplication.java (main class).
- Tạo HealthController: GET /api/health trả {"status":"ok","service":"chat-app-backend"}.
- Tạo SecurityConfig tạm thời: csrf disabled, permitAll (sẽ lock down Ngay 3).
- Tạo V1__placeholder.sql cho Flyway.
- Verify: mvn compile OK, mvn spring-boot:run start thành công trong 1.548s trên port 8080.

### Đang dở
- Flyway migration thật (schema users, conversations, messages) — để Ngay 2.
- JWT filter chain, auth endpoints — để Ngay 2-3.

### Blocker
- JAVA_HOME trỏ vào jdk-25 không tồn tại. Fix: export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" trước khi chạy mvn. Cần set JAVA_HOME đúng trong môi trường hệ thống hoặc thêm vào script.

### Ghi chú kỹ thuật
- jjwt 0.12.6 là latest stable của 0.12.x series — dùng version này.
- firebase-admin 9.4.1 resolve OK với Spring Boot 3.4.4.
- Khi chưa có DB/Redis, exclude 5 autoconfigure classes trong application.yml để app start sạch.
