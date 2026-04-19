# Backend Knowledge — Tri thức chắt lọc cho backend-dev

> File này là **bộ nhớ bền vững** của backend-dev.
> Quy tắc: chỉ ghi những gì có giá trị tái sử dụng. KHÔNG ghi nhật ký (cái đó ở `backend-log.md`).
> Giới hạn: file này không được dài quá ~300 dòng. Nếu vượt, phải rút gọn/gộp entries cũ.
> Ai được sửa: chỉ `backend-dev` (agent tự update khi học được điều mới), hoặc `code-reviewer` khi chốt quyết định kiến trúc.

---

## Quyết định kiến trúc đã chốt

*(Điền khi team chốt một lựa chọn quan trọng. Ghi ngắn gọn: quyết định là gì, lý do chính, tuần đã chốt.)*

### Security
- SecurityConfig dùng lambda DSL (Spring Security 6), KHÔNG dùng WebSecurityConfigurerAdapter. Tuần 1.
- JWT filter: KHÔNG implement `UserDetailsService` — `JwtAuthFilter` load `User` entity trực tiếp từ `UserRepository` rồi wrap bằng `UsernamePasswordAuthenticationToken(user, null, emptyList())`. Lý do: tránh double-lookup, không cần AuthenticationManager ở filter layer. `UserDetailsService` sẽ implement sau nếu cần `AuthenticationManager` cho auth endpoint. Ngày 3.
- jjwt 0.12.x API khác 0.11.x: parser dùng `Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)` (không còn `parseClaimsJws`). Sign dùng `.signWith(secretKey)` không cần truyền algorithm riêng. Ngày 3.
- Secret key: dùng `secret.getBytes(UTF_8)` trực tiếp thay vì Base64 decode. Keys.hmacShaKeyFor yêu cầu >= 32 bytes (256-bit) — secret trong config phải >= 32 ký tự. Ngày 3.
- CORS pitfall: KHÔNG set `allowedOrigins("*")` khi `allowCredentials(true)` — Spring Security throw `IllegalArgumentException` vì vi phạm CORS spec. Phải dùng list origins cụ thể từ property. Ngày 3.
- `authenticationEntryPoint` trong SecurityConfig phải trả JSON (không phải redirect HTML). Nếu không cấu hình, Spring Security mặc định trả HTML 401 redirect — FE không parse được. Ngày 3.
- BCryptPasswordEncoder strength 12 — `new BCryptPasswordEncoder(12)`. Khai báo làm `@Bean PasswordEncoder` trong SecurityConfig. Ngày 3.

### Database
- ddl-auto: validate — Hibernate KHÔNG tự alter schema. Mọi thay đổi qua Flyway migration. Tuần 1.
- JAVA_HOME phải trỏ đúng vào jdk-21.0.10 mới chạy được mvn: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"`.
- UUID primary key pattern: `@GeneratedValue(strategy = GenerationType.UUID)` + `@Column(insertable=false, updatable=false)`. Database generate bằng `gen_random_uuid()` (pgcrypto). Tuần 1.
- Timestamp: dùng `OffsetDateTime` (java.time) cho cột TIMESTAMPTZ. Không dùng Date/Calendar. Tuần 1.
- Soft delete users: dùng cột `status VARCHAR(20)` ('active'/'suspended'/'deleted') thay cho `deleted_at`. Lưu `deleted_name` khi xóa. Tuần 1.
- Repository method naming với ManyToOne: dùng `findByUser_Id(UUID)` không phải `findByUserId(UUID)` — Spring Data cần dấu `_` để phân biệt nested property. Tuần 1.

### WebSocket / STOMP
- `WebSocketConfig` enableSimpleBroker("/topic","/queue") + AppDestPrefix("/app") + UserDestPrefix("/user"). `/ws` endpoint có CẢ 2 registration: native WebSocket (raw `ws://`) + SockJS fallback — FE dùng SockJS prod, test dùng raw WS để đọc ERROR frame nguyên bản. W4-D3.
- `setAllowedOriginPatterns` (KHÔNG `setAllowedOrigins`) — cần pattern matching + credentials. Đọc từ `app.websocket.allowed-origins` (comma-separated), KHÔNG hardcode "*". W4-D3.
- `configureWebSocketTransport`: `setMessageSizeLimit(64*1024)` + `setSendTimeLimit(10_000)` + `setSendBufferSizeLimit(512*1024)` — chặn DoS/zombie connection. W4-D3.
- `AuthChannelInterceptor implements ChannelInterceptor`: preSend switch theo StompCommand — CONNECT verify JWT qua `JwtTokenProvider.validateTokenDetailed()` + `accessor.setUser(new StompPrincipal(userId))`; SUBSCRIBE check destination bắt đầu `/topic/conv.` → parse UUID → `conversationMemberRepository.existsByConversation_IdAndUser_Id`. Throw `MessageDeliveryException(errorCode)` khi reject. W4-D3.
- `StompPrincipal` record implements `java.security.Principal`, name=userId UUID string. Spring resolve principal cho `/user/*` destinations. W4-D3.
- `MessageDeliveryException` → ERROR frame: MẶC ĐỊNH Spring KHÔNG expose exception message vào header. Phải custom `StompSubProtocolErrorHandler.handleClientMessageProcessingError()` → `accessor.setMessage(errorCode)` + body = errorCode bytes. Unwrap exception cause chain lấy message gốc. W4-D3.
- Register custom error handler: SubProtocolWebSocketHandler KHÔNG phải bean autowire được trực tiếp (circular với DelegatingWebSocketMessageBrokerConfiguration). Dùng `@EventListener(ContextRefreshedEvent.class)` lookup bean `"subProtocolWebSocketHandler"` (type public là `WebSocketHandler`), unwrap `WebSocketHandlerDecorator.getDelegate()` đến `SubProtocolWebSocketHandler`, loop `getProtocolHandlers()` tìm `StompSubProtocolHandler` → `setErrorHandler()`. W4-D3.
- `SecurityConfig` permitAll `/ws/**` — auth qua STOMP CONNECT frame, không qua HTTP filter (SockJS info endpoint cần public). W4-D3.
- Test WS với raw WebSocket (`StandardWebSocketClient` + `AbstractWebSocketHandler`): gửi CONNECT frame bằng TextMessage `"CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:10000,10000\nAuthorization:Bearer <token>\n\n\u0000"`, đọc ERROR frame → extract header `message` hoặc body. Lý do: `WebSocketStompClient`/`DefaultStompSession` wrap CONNECT rejection thành `ConnectionLostException("Connection closed")` qua `handleTransportError` — không expose header nguyên bản. W4-D3.
- SockJS note cho test: SockJS fallback cho `handleTransportError` CloseStatus 1002 → test pass qua raw WS là cách duy nhất verify header error code.

### JWT Token Validation Pattern (W1 Fix)
- Dùng `validateTokenDetailed()` trả enum `TokenValidationResult` (VALID/EXPIRED/INVALID) thay vì boolean `validateToken()`.
- `JwtAuthFilter` set `request.setAttribute("jwt_expired", true)` khi result == EXPIRED, rồi tiếp tục filter chain (không authenticate).
- `authenticationEntryPoint` trong SecurityConfig check attribute để trả `AUTH_TOKEN_EXPIRED` vs `AUTH_REQUIRED`.
- FE dùng `AUTH_TOKEN_EXPIRED` để trigger refresh queue, `AUTH_REQUIRED` để redirect login lại.
- `ExpiredJwtException` phải catch TRƯỚC `JwtException` vì nó là subclass — dùng separate catch blocks để rõ ràng.
- Package-private helper `generateTokenWithExpiration(User, long)` trong JwtTokenProvider để test generate expired token mà không hardcode string.

---

## Pattern đã dùng trong codebase

*(Điền khi đã có code mẫu cho pattern đó, để các feature sau tuân theo.)*

### Controller → Service → Repository
- (chưa có)

### Entity pattern
- KHÔNG dùng `@Data` cho entity — gây vấn đề equals/hashCode với JPA lazy-loading.
- Dùng `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`.
- Entity có thể có domain method (vd `user.markAsDeleted()`) — không phải POJO chay.
- `@PrePersist` / `@PreUpdate` để tự set createdAt/updatedAt thay vì Spring Auditing (giữ đơn giản).
- `@ManyToOne(fetch = FetchType.LAZY)` — luôn LAZY, không bao giờ EAGER.

### Package structure (đã dùng)
- `com.chatapp.user.entity` — User, UserAuthProvider, UserBlock
- `com.chatapp.user.repository` — UserRepository, UserAuthProviderRepository, UserBlockRepository
- `com.chatapp.config` — SecurityConfig
- `com.chatapp.controller` — HealthController
- Pattern: `com.chatapp.<domain>.entity`, `com.chatapp.<domain>.repository`, `com.chatapp.<domain>.service`, `com.chatapp.<domain>.controller`

### DTO vs Entity
- (chưa có — sẽ có từ Ngày 4 khi implement auth endpoints)

### Error handling
- `ErrorResponse` record: `{ error, message, timestamp, details }` với `@JsonInclude(NON_NULL)` để không serialize `details` khi null. Package: `com.chatapp.exception`. Ngày 3.
- `AppException(HttpStatus, String errorCode, String message)` — business exception dùng chung, `GlobalExceptionHandler` bắt và convert. Ngày 3.
- Test: `@SpringBootTest` KHÔNG có attribute `excludeAutoConfiguration` (đó là attribute của `@DataJpaTest`). Để exclude autoconfigure trong `@SpringBootTest`, dùng `properties = "spring.autoconfigure.exclude=..."`. Ngày 3.

---

## Pitfall đã gặp (đừng lặp lại)

*(Format: triệu chứng → giải pháp → gặp lần đầu ở đâu. Quan trọng: chỉ thêm bug đã tốn >1h debug, không thêm bug vặt.)*

- Spring Data Redis WARN log khi có JPA + Redis cùng project: "Could not safely identify store assignment for JPA repositories" — đây là INFO log bình thường, Spring Data đang xác nhận repository nào thuộc module nào. Không phải lỗi, không cần sửa. Gặp lần đầu Ngày 2.

---

## Thư viện đã chọn

*(Chốt một lần, không tự ý đổi. Nếu cần đổi, phải có lý do và báo reviewer.)*

| Library | Version | Lý do chọn |
|---------|---------|------------|
| spring-boot-starter-parent | 3.4.4 | Latest stable Spring Boot 3.x tại thời điểm khởi tạo (Tuần 1) |
| jjwt-api/impl/jackson | 0.12.6 | Latest stable jjwt 0.12.x, API mới dùng builder pattern rõ ràng |
| firebase-admin | 9.4.1 | Latest stable 9.x, verify Google OAuth token |
| flyway-core + flyway-database-postgresql | BOM managed | Cần cả 2 artifact cho PostgreSQL support trong Flyway 10+ |

---

## Convention đặt tên

*(Ghi khi phát hiện team hay nhầm lẫn.)*

- Package: `com.chatapp.<domain>` (tất cả thường dùng `com.chatapp.auth`, `com.chatapp.message`, ...)
- DTO: `{Action}Request`, `{Action}Response` — ví dụ `LoginRequest`, `LoginResponse`
- Service method: verb + noun — `createUser()`, `sendMessage()`
- Migration: `V{n}__{snake_case_description}.sql`

---

### Auth Service Pattern (Tuần 2, W2D2 Phase B)
- Package: `com.chatapp.auth.{controller,service,dto.request,dto.response}`
- Exception: dùng AppException có sẵn (KHÔNG tạo class riêng).
- Error codes theo contract: `AUTH_EMAIL_TAKEN`, `AUTH_USERNAME_TAKEN`, `AUTH_INVALID_CREDENTIALS`, `AUTH_ACCOUNT_LOCKED` (không dùng tên ngắn EMAIL_TAKEN...).
- Security: `AUTH_INVALID_CREDENTIALS` cho cả user-not-found lẫn wrong-password — cùng message, tránh user enumeration.
- Rate limit login: Redis GET key `rate:login:{ip}`, check >= 5 TRƯỚC khi verify password, INCR khi fail, DELETE khi success.
- Rate limit register: Redis INCR key `rate:register:{ip}` mỗi request, check > 10, set TTL khi current == 1.
- Refresh token Redis: key `refresh:{userId}:{jti}`, value = SHA-256 hash của raw token (không lưu raw), TTL = refreshExpirationMs/1000.
- IP extraction: X-Forwarded-For header first (split(",")[0].trim()), fallback getRemoteAddr().
- Pitfall quan trọng: khi thêm bean inject StringRedisTemplate vào production context, tất cả test class có `exclude=RedisAutoConfiguration` sẽ fail context load (UnsatisfiedDependency). Fix: thêm `@MockBean StringRedisTemplate` vào từng test class đó.

---

### AuthMethod enum (Tuần 2, W-BE-3)
- Enum tại `com.chatapp.user.enums.AuthMethod`: PASSWORD("password"), OAUTH2_GOOGLE("oauth2_google").
- `generateAccessToken(User, AuthMethod)` — luôn truyền enum, không truyền string thô.
- `getAuthMethodFromToken(String)` — extract từ JWT claim "auth_method", fallback về PASSWORD nếu unknown.
- JWT claim key: "auth_method", value là string lowercase từ `enum.getValue()`.

---

---

### Refresh Token Rotation Pattern (Tuần 2, W2D3.5)

QUAN TRỌNG: DELETE old token TRƯỚC khi generate new, rồi SAVE new.
Sequence: validateToken → checkRateLimit → hashCompare → deleteOld → generateNew → saveNew.

Token Reuse Detection:
- Token valid nhưng hash không khớp trong Redis → đã bị rotate trước đó → REFRESH_TOKEN_REUSED.
- Khi detect: revokeAllUserSessions(userId) dùng redisTemplate.keys() + delete(Set).
- Log WARN với userId (KHÔNG log raw token).

Constant-time comparison: dùng `MessageDigest.isEqual(a.getBytes(), b.getBytes())` thay vì `String.equals()` để tránh timing attack khi compare token hash.

Rate limit: per-userId (không per-IP), vì user có thể refresh từ nhiều device cùng IP.
Redis key: `rate:refresh:{userId}` TTL 60s, max 10 calls/window.

getClaimsAllowExpired(): cần thiết để extract userId/jti từ expired token trong flow refresh.
Lấy từ `ExpiredJwtException.getClaims()` — jjwt vẫn parse claims kể cả khi expired.
`getUserIdFromToken()` và `getJtiFromToken()` dùng method này để an toàn.

Error codes theo contract: `AUTH_REFRESH_TOKEN_INVALID`, `AUTH_REFRESH_TOKEN_EXPIRED`, `AUTH_ACCOUNT_LOCKED`.
Pitfall: DELETE → crash → SAVE không xảy ra → user mất session. Acceptable V1.
Workaround V2: dùng Redis MULTI/EXEC để atomic DELETE + SAVE.

---

### Firebase Admin SDK + OAuth (W2D4)
- FirebaseConfig: @PostConstruct init, kiểm tra file tồn tại trước, log WARN nếu thiếu credentials. Expose `FirebaseAuth` làm `@Bean` (trả null nếu chưa init).
- AuthService inject FirebaseAuth qua setter `@Autowired(required=false)` — KHÔNG dùng `FirebaseAuth.getInstance()` trực tiếp (không testable). Nếu firebaseAuth null → throw 503 AUTH_FIREBASE_UNAVAILABLE.
- Error code OAuth: `AUTH_FIREBASE_TOKEN_INVALID` (cả expired lẫn invalid), `AUTH_FIREBASE_UNAVAILABLE` (SDK chưa init).
- Auto-link order: provider_uid → email → create new. Check user.isActive() sau mỗi bước tìm thấy.
- username generation: email prefix → sanitize [^a-z0-9_] → underscore → lowercase → prefix "_" nếu bắt đầu số → thử 4 suffix ngẫu nhiên 4 số → fallback UUID 8 chars.
- Test mock: `@MockBean FirebaseAuth` — Spring inject vào AuthService qua setter. KHÔNG cần mockStatic.

### Logout Pattern (W2D4)
- Best-effort: delete refresh token Redis, không fail nếu token invalid.
- Blacklist access token: `SET 'jwt:blacklist:{jti}' '' EX {remaining_ttl_seconds}`.
- JwtAuthFilter check: `redisTemplate.hasKey('jwt:blacklist:{jti}')` NGAY SAU khi validate VALID — nếu true, set `jwt_expired=true` attribute và skip authentication.
- Fail-open: nếu Redis unavailable khi check blacklist → log warn, tiếp tục xử lý (không block user vì Redis down).
- SecurityConfig: `/api/auth/logout` KHÔNG trong permitAll whitelist — cần JWT valid.
- Endpoint whitelist đầy đủ: /api/auth/register, /api/auth/login, /api/auth/oauth, /api/auth/refresh, /api/health, /actuator/health.

### OAuthResponse vs AuthResponse
- AuthResponse: record 5 fields (accessToken, refreshToken, tokenType, expiresIn, user).
- OAuthResponse: record 6 fields = AuthResponse fields + isNewUser boolean.
- Dùng toOAuthResponse(AuthResponse, boolean) helper trong AuthService để convert.

---

### Conversation Domain Pattern (W3-D1)

- Package: `com.chatapp.conversation.{enums,entity,repository}` — tuân theo pattern `com.chatapp.<domain>.*`.
- Enum ConversationType: ONE_ON_ONE, GROUP — `@Enumerated(EnumType.STRING)`, CHECK constraint tương ứng trong migration.
- Enum MemberRole: OWNER, ADMIN, MEMBER — `@Enumerated(EnumType.STRING)`.
- Conversation entity: KHÔNG dùng `@Data`. Dùng `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`. `@PrePersist`/`@PreUpdate` cho timestamps.
- ConversationMember: có `@Builder.Default private MemberRole role = MemberRole.MEMBER` để giữ default khi dùng builder.
- `@OneToMany(mappedBy="conversation", fetch=LAZY)` với `@Builder.Default private List<ConversationMember> members = new ArrayList<>()` — cần Builder.Default để tránh null list khi dùng builder.
- Repository: `findByIdWithMembers(UUID)` dùng `@Query("SELECT c FROM Conversation c LEFT JOIN FETCH c.members m LEFT JOIN FETCH m.user WHERE c.id = :id")` — tránh N+1, cần LEFT JOIN FETCH cả `m.user`.
- Spring Data method với nested FK: `findByUser_IdOrderByJoinedAtDesc` và `existsByConversation_IdAndUser_Id` — dấu `_` để phân biệt nested property (đã chốt pattern này từ tuần 1).
- Migration V3: index đặt tên `idx_conversations_last_message`, `idx_conversations_created_by`, `idx_members_user`, `idx_members_conv`. Convention: `idx_{table}_{columns}`.
- DROP/CREATE database cho data reset giữa các tuần: terminate sessions trước bằng `pg_terminate_backend`, sau đó DROP.

---

### Conversation Service Pattern (W3-D2)

- W3-BE-1 RESOLVED: `@GeneratedValue(strategy=UUID) + @Column(insertable=false)` conflict → `@PrePersist` set `if (id==null) id = UUID.randomUUID()`. Xóa `@GeneratedValue`, giữ `@Column(updatable=false)`. Database vẫn dùng `DEFAULT gen_random_uuid()` cho direct SQL insert.
- Anti-enumeration pattern: `getConversation` trả 404 cho cả not-exist lẫn not-member (KHÔNG trả 403 — tránh leak conversation existence). Implement bằng check membership trước, sau đó load conversation.
- findOrCreate 1-1 với native SQL double-join: query `findExistingOneOnOne` JOIN m1+m2 theo userId1 và userId2 — hiệu quả hơn JPQL subquery. Trả `Optional<String>` (không phải UUID) để tránh H2/PG JDBC driver mapping differences (H2 byte[] vs PG UUID).
- N+1 avoidance cho list: dùng 2-query approach — native SQL lấy IDs paginated, sau đó batch load details qua `findByIdWithMembers`. Chấp nhận cho V1 (list nhỏ ≤ vài trăm).
- Flush+clear EntityManager sau save trong @Transactional để force reload từ DB: `entityManager.flush(); entityManager.clear()` trước `findByIdWithMembers` — tránh stale 1st-level cache trả empty members list.
- Native query UUID parameter: dùng `:userId` là `String` + `CAST(:userId AS UUID)` trong SQL để tránh H2 không nhận `UUID` type parameter trong native queries. `CAST(c.id AS VARCHAR)` trong SELECT để tránh H2 trả `byte[]` thay vì UUID string.
- `ConversationListResponse` shape: `content/page/size/totalElements/totalPages` — tuân theo contract v0.5.0-conversations, KHÔNG dùng `items/total`.
- `displayName`/`displayAvatarUrl`: server-computed — ONE_ON_ONE lấy từ other member's fullName/avatarUrl, GROUP lấy từ conversation.name/avatarUrl.

---

### Messages Domain Pattern (W4-D1)

- Package: `com.chatapp.message.{enums,entity,repository,dto,service,controller}`.
- UUID pattern: `@PrePersist` với `if (id == null) id = UUID.randomUUID()` + `if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC)` — LUÔN normalize createdAt về UTC để tránh H2 timezone stripping.
- Cursor pagination: query `limit+1` rows → `hasMore = results.size() > limit` → `items = subList(0, limit)` → reverse (DESC→ASC) → `nextCursor = items.get(0).getCreatedAt().atZoneSameInstant(UTC).toString()`.
- nextCursor = createdAt của item CŨ NHẤT trong page (sau reverse = index 0). FE dùng cursor này để lấy page tiếp theo (messages cũ hơn cursor).
- Shallow nested DTO: `ReplyPreviewDto(id, senderName, contentPreview)` — không recursive, contentPreview cắt 100 chars.
- Repository: dùng Spring Data method naming `findByConversation_IdAndDeletedAtIsNullOrderByCreatedAtDesc` thay vì `@Query` với `OffsetDateTime` parameter — tránh H2 timezone comparison bug với `@Query` JPQL.
- Anti-enumeration: sendMessage + getMessages đều trả 404 CONV_NOT_FOUND cho cả không-phải-thành-viên lẫn conv-không-tồn-tại.
- Rate limit: key `rate:msg:{userId}`, limit 30/60s, fail-open khi Redis down.
- FK defer pattern: V3 tạo `last_read_message_id` column chưa có FK. V5 thêm `ALTER TABLE conversation_members ADD CONSTRAINT fk_members_last_read FOREIGN KEY(last_read_message_id) REFERENCES messages(id) ON DELETE SET NULL`.
- H2 TIMESTAMPTZ pitfall: test cursor pagination KHÔNG dùng REST endpoint để send messages (timestamps có timezone shift giữa store/read). Thay vào đó insert trực tiếp qua repository với explicit UTC timestamps cách nhau rõ ràng (vd: `plusDays(i)` thay vì sub-second diffs).

---

## Changelog file này

- 2026-04-19 W4D3: Thêm WebSocket/STOMP config pattern (AuthChannelInterceptor, StompErrorHandler, SubProtocolWebSocketHandler lookup qua ContextRefreshedEvent), raw WebSocket test pattern cho CONNECT/SUBSCRIBE error verification.
- 2026-04-19 W4D1: Thêm Messages domain pattern, cursor pagination logic, H2 TIMESTAMPTZ pitfall cho test.
- 2026-04-19 W3D2: Thêm W3-BE-1 fix pattern, findOrCreate 1-1 SQL, anti-enumeration 404, flush+clear pattern, H2 UUID native query workaround.
- 2026-04-19 W3D1: Thêm Conversation domain pattern, enum string mapping, JOIN FETCH repo pattern, index naming convention, DROP/recreate DB flow.
- 2026-04-19 W2D4: Thêm Firebase OAuth pattern, Logout + blacklist, OAuthResponse shape, JwtAuthFilter blacklist check.
- 2026-04-19 W2D3.5: Thêm Refresh Token Rotation Pattern, constant-time comparison, getClaimsAllowExpired pattern.
- 2026-04-19 W2D1: Thêm AuthMethod enum pattern (W-BE-3).
- 2026-04-19 W1 Fix: Thêm JWT Token Validation Pattern (validateTokenDetailed, AUTH_TOKEN_EXPIRED vs AUTH_REQUIRED).
- 2026-04-19 Ngày 3: Thêm Security/JWT patterns, ErrorResponse shape, pitfall @SpringBootTest excludeAutoConfiguration, CORS gotcha, jjwt 0.12.x API notes.
- 2026-04-19: Thêm UUID/timestamp pattern, entity pattern, package structure, pitfall Spring Data Redis WARN.
