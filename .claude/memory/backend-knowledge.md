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

### TransactionalEventListener Broadcast Pattern (W4-D4)
- Event record: `MessageCreatedEvent(UUID conversationId, MessageDto messageDto)` — DTO truyền trực tiếp, không cần load lại.
- Publisher: inject `ApplicationEventPublisher`, gọi trong `@Transactional` method SAU khi entity đã save và dto đã map.
- Listener: `@TransactionalEventListener(phase = AFTER_COMMIT)` trong separate `@Component`. Không đặt trong service.
- Envelope: `Map.of("type", "MESSAGE_CREATED", "payload", dto)` — FE parse `event.type` + `event.payload`.
- try-catch trong listener: broadcast fail không propagate → REST 201 không bị ảnh hưởng.
- MessageMapper: @Component riêng, tách DTO mapping khỏi service để broadcaster reuse.
- Test: `@MockBean SimpMessagingTemplate` trong integration test — broadcaster inject bean qua constructor, mock tự wire vào.
- T16 pitfall: khi broadcaster throws trong AFTER_COMMIT, stack trace xuất hiện trong log là bình thường (Spring log error nhưng không propagate sau try-catch). REST vẫn trả 201.

---

---

### Ephemeral Event Pattern (W5-D1 — Typing Indicator)

- Ephemeral STOMP events (typing) KHÔNG persist DB — pure broadcast. Không cần entity/migration.
- Package: `com.chatapp.websocket` cho handler ephemeral (không phải `message.controller`).
- `ChatTypingHandler`: member check → rate limit → load user → broadcast. Silent drop (return) thay vì throw exception cho typing vì non-critical.
- `TypingRateLimiter`: INCR + EXPIRE pattern, 1 event/2s/key. Fail-open khi Redis down.
- Rate limit key pattern: `rate:typing:{userId}:{convId}` — riêng biệt với `rate:msg:{userId}`.
- Broadcast shape theo contract SOCKET_EVENTS.md — khi contract và task spec mâu thuẫn, contract thắng. `fullName` không có trong contract 3.4, không thêm vào payload.
- Test pattern: `@MockitoSettings(strictness = Strictness.LENIENT)` khi `setUp` stubbing quá broad mà một số test không dùng hết (vd non-member test skip rate-limit/userRepo calls).

### Destination-Aware Auth Policy Pattern (W5-D1 Fix A)

- `AuthChannelInterceptor.handleSend()` dùng `DestinationPolicy` enum để phân biệt cách xử lý:
  - `STRICT_MEMBER`: throw `MessageDeliveryException("FORBIDDEN")` cho non-member → ERROR frame về client. Dùng cho `.message`.
  - `SILENT_DROP`: interceptor pass through, handler tự silent-drop. Dùng cho `.typing`, `.read`. KHÔNG làm DB query.
- `resolveSendPolicy(String destination)` — package-private, switch-by-suffix: `.message` → STRICT, `.typing`/`.read` → SILENT, unknown → STRICT (safe default).
- Tại sao SILENT_DROP cho typing: throw FORBIDDEN tạo ERROR frame → FE hiện lỗi → bad UX. Handler đã có member check riêng (defense-in-depth).
- Test pattern: `verifyNoInteractions(conversationMemberRepository)` trong SILENT_DROP tests để đảm bảo interceptor không query DB thừa.
- Enum đặt bên trong class (inner enum) — không cần file riêng. Package-private visibility đủ vì test cùng package.

---

### Unified ACK/ERROR Shape Pattern (W5-D2 — ADR-017)

- `AckPayload` unified shape: `{operation, clientId, message}`. `operation` = "SEND"|"EDIT"|"DELETE". `clientId` = UUID client sinh (tempId cho SEND, clientEditId cho EDIT).
- `ErrorPayload` unified shape: `{operation, clientId, error, code}`. Cùng discriminator để FE route handler.
- `sendAck(userId, tempId, dto)` → `AckPayload("SEND", tempId, dto)`. `sendEditAck(userId, clientEditId, dto)` → `AckPayload("EDIT", clientEditId, dto)`.
- Exception handlers trong STOMP controller phải pass `operation` string vào `ErrorPayload` — SEND handlers dùng "SEND", EDIT handlers dùng "EDIT".

### Anti-enumeration MSG_NOT_FOUND (W5-D2)

- Edit message merge 4 conditions thành 1 error code `MSG_NOT_FOUND`: message null, wrong conv, not owner, soft-deleted. Không leak message existence.
- AppException details phải chứa `clientEditId` (không phải `tempId`) để exception handler echo đúng field.

### Edit Dedup Pattern (W5-D2)

- Key: `msg:edit-dedup:{userId}:{clientEditId}` TTL 60s — atomic `SET NX EX` TRƯỚC mọi DB mutation.
- Duplicate frame: GET value → "PENDING" → silent drop; real messageId → re-send EDIT ACK idempotently.
- `editViaStomp()` sau khi save: `SET dedupKey messageId.toString() EX 60` (update value, TTL giữ 60s).
- Edit rate limit key tách biệt: `rate:msg-edit:{userId}` max 10/min (khác `rate:msg:{userId}` cho send).

### Edit Window Check (W5-D2)

- Edit window: `now() - message.createdAt > 300s` → MSG_EDIT_WINDOW_EXPIRED.
- Dùng `java.time.Duration.between(createdAt.atZoneSameInstant(UTC), now).getSeconds()` để normalize timezone.
- No-op check: `newContent.trim().equals(message.content.trim())` → MSG_NO_CHANGE (trước khi save).
- Order: validate → rate limit → dedup → load message → window check → no-op check → save.

---

### Soft Delete Pattern — Message (W5-D3)

- Soft delete cho messages: set `deletedAt = now(UTC)` + `deletedBy = userId` via domain method `message.markAsDeletedBy(UUID)`. KHÔNG xóa cứng.
- `deleted_by UUID NULL REFERENCES users(id) ON DELETE SET NULL` — thêm qua Flyway migration riêng (V6). `deleted_at` đã có từ V5.
- **Content strip tại mapper**: `MessageMapper.toDto()` check `message.getDeletedAt() != null` → `content = null` trong DTO. Áp dụng TẤT CẢ path (REST + WS). DB column vẫn lưu content gốc (nullable=false).
- `MessageDto` thêm 2 fields: `OffsetDateTime deletedAt`, `String deletedBy` (UUID string). Apply cho mọi DTO constructor call.
- DELETE ACK shape khác SEND/EDIT: trả **minimal map** `{id, conversationId, deletedAt, deletedBy}` (không full MessageDto). Dùng `Map<String,Object>` thay vì `AckPayload` để match contract §3d.3 chính xác.
- Rate limit key: `rate:msg-delete:{userId}` max 10/min — tách biệt với `rate:msg-edit:` và `rate:msg:`.
- Dedup key: `msg:delete-dedup:{userId}:{clientDeleteId}` TTL 60s. Idempotent re-send ACK khi duplicate frame.
- `MessageDeletedEvent(convId, messageId, Instant deletedAt, UUID deletedBy)` — dùng `Instant` (không `OffsetDateTime`) để truyền sang broadcaster tiện `.toString()` ISO8601.
- `Map.of()` throws NPE nếu value là null — đảm bảo tất cả field trong broadcast/ACK map là non-null trước khi truyền vào.

---

### Forward Pagination Pattern (W5-D4 — `after` param)

- `GET /messages?after=ISO8601` — forward pagination (catch-up), ORDER ASC, INCLUDE deleted messages (FE cần placeholder state).
- `cursor` và `after` mutually exclusive: cả hai non-null → throw 400 `VALIDATION_FAILED` tại controller trước khi vào service.
- Repository method: `findByConversation_IdAndCreatedAtAfterOrderByCreatedAtAsc` — KHÔNG filter `deletedAtIsNull` (khác backward cursor).
- `nextCursor` cho forward page = createdAt của **item mới nhất** (last item); backward page = item cũ nhất (first item sau reverse).
- Tách rõ 2 code path trong `getMessages(UUID, UUID, OffsetDateTime cursor, OffsetDateTime after, int limit)` — if after != null dùng forward branch; else dùng backward branch.
- `parseCursor()` trong controller reuse cho cả cursor lẫn after (cùng ISO8601 parse logic, cùng error message — acceptable).

### ReplyPreviewDto `deletedAt` field (W5-D4)

- `ReplyPreviewDto` thêm 4th field `String deletedAt` (ISO8601 | null). Record upgrade là breaking change với bất kỳ code tạo instance trực tiếp — kiểm tra toàn bộ `new ReplyPreviewDto(...)` calls.
- `MessageMapper.toReplyPreview(Message source)` — method riêng (public) để test và reuse dễ hơn. Logic: if source.deletedAt != null → contentPreview=null, deletedAt=string; else → contentPreview truncated, deletedAt=null.
- Quoting deleted source: ALLOWED (reply vào tin nhắn đã xóa OK) — validate chỉ check conv membership, không check deletedAt của source.

### STOMP sendViaStomp reply validation (W5-D4)

- Reply validation đặt SAU membership check, TRƯỚC rate limit (tránh waste rate limit quota khi reply invalid).
- `existsByIdAndConversation_Id` → false nhưng `existsById` → true: source thuộc conv khác → "Tin nhắn gốc thuộc conversation khác". Cả 2 false: "Tin nhắn gốc không tồn tại".
- Sử dụng `messageRepository.getReferenceById(replyToMessageId)` khi set lazy reference (không load entity ngay).
- Test pattern với T17 (after param): kiểm tra `item.get("deletedAt").isNull()` thay vì so sánh content (content bị strip thành null cho deleted messages → `asText()` trả "null" string, không phải null).

---

### File Upload W6-D2 — Thumbnail + Auth + Attachments

- **FileAuthService pattern**: `findAccessibleById(fileId, userId)` trả `Optional<FileRecord>` — rule (1) uploader, (2) member của conv chứa message attach file này. Merge 404 cho mọi case fail (not-found / expired / non-accessible) — anti-enumeration (API_CONTRACT §Files Management). JPQL: `SELECT COUNT(ma) > 0 FROM MessageAttachment ma JOIN Message m ON ma.id.messageId = m.id JOIN ConversationMember cm ON cm.conversation.id = m.conversation.id WHERE ma.id.fileId = :fileId AND cm.user.id = :userId`.
- **ThumbnailService pattern** (W6-D2): generate 200×200 image thumbnail qua Thumbnailator (0.4.20). Fail-open — thumbnail lỗi KHÔNG fail upload; DB field `thumbnail_internal_path` giữ null, GET `/thumb` trả 404. Path layout `{uuid}_thumb.{ext}` cùng thư mục với original. `FileDto.thumbUrl` chỉ non-null khi `record.thumbnailInternalPath != null` (KHÔNG dựa vào mime check — align với file thực tế trên disk).
- **Test pattern cho Thumbnailator**: dùng `ImageIO.write(bufferedImage, "jpg", baos)` để generate valid JPEG bytes cho test (20-byte `JPEG_MAGIC` magic-only sẽ fail Thumbnailator "Not a JPEG file: starts with 0xff 0xd9"). Giữ `JPEG_MAGIC` cho test chỉ validate Tika detection.
- **StorageService.resolveAbsolute(internalPath)**: W6-D2 thêm vào interface. Throw `SecurityException` (không `IllegalArgumentException`) khi path traversal — caller phân biệt attack signal vs args invalid. Dùng trong `ThumbnailService.generate()` (cần Path object cho Thumbnailator.toFile).
- **validateAndAttachFiles validation order** (W6-D1 STOMP attachments): (1) count > 5 → `MSG_ATTACHMENTS_TOO_MANY`; (2) existence qua `findAllById` size mismatch → `MSG_ATTACHMENT_NOT_FOUND`; (3) uploader != sender → `MSG_ATTACHMENT_NOT_OWNED`; (4) `expires_at < now()` → `MSG_ATTACHMENT_EXPIRED`; (5) file đã attach message khác (`existsByIdFileId`) → `MSG_ATTACHMENT_ALREADY_USED`; (6) group check (all images OR 1 PDF) → `MSG_ATTACHMENTS_MIXED`. Fail-fast, trong cùng `@Transactional` với message save — rollback message nếu attach fail.
- **MessageDto.attachments** (W6-D1): luôn `List<FileDto>` (không null). Mapper strip thành `Collections.emptyList()` khi deleted. Sử dụng `Collections.emptyList()` trong mapper thay vì `null` → FE không phải check null.
- **MessageMapper N+1 warning**: `toDto()` mỗi message → 1 query `findByIdMessageIdOrderByDisplayOrderAsc` + N query `findById` mỗi file. Page 50 messages worst-case ~51+50×N_attach queries. V2 optimize với `@EntityGraph` hoặc JOIN query trả Message+attachments+files batch. Documented trong class javadoc.
- **SendMessagePayload record** (W6-D1): thêm 5th field `List<UUID> attachmentIds` (nullable). BREAKING record constructor — grep-and-fix các `new SendMessagePayload(...)` call sites (5 spots trong test).
- **MessageDto record constructor order** (W6-D1): `(id, conversationId, sender, type, content, attachments, replyToMessage, editedAt, createdAt, deletedAt, deletedBy)`. `attachments` chèn sau `content`, TRƯỚC `replyToMessage`. Breaking change — grep `new MessageDto(` update tất cả.
- **MessageService constructor args** (W6-D1): +2 new deps `FileRecordRepository`, `MessageAttachmentRepository` → tăng từ 8 → 10 args. Update test constructor calls (3 tests: Stomp, Edit, Delete handlers).
- **Content XOR Attachments rule**: SEND blank content + empty attachments → `MSG_NO_CONTENT` (was `VALIDATION_FAILED` pre-W6). Content-only vẫn OK (text message); attachments-only OK (caption optional); cả hai rỗng REJECT. Check trong `validateStompPayload` trước rate-limit/dedup.
- **Content DB NOT NULL constraint pitfall**: attachments-only message có `payload.content = null`, nhưng column `messages.content` là NOT NULL. Fix: persist empty string `""` thay vì null khi attachments-only — mapper không strip empty content, FE dùng `attachments.length > 0` để render bubble.
- **Edit immutable attachments** (W6-D1 V1): edit message chỉ sửa content, KHÔNG thay attachments. Comment trong `editViaStomp` javadoc. V2 xem xét cho phép.

---

### File Upload Foundation (W6-D1)

- Package: `com.chatapp.file.{entity,repository,dto,service,controller,exception,storage}`.
- Files mới: `FileRecord`, `MessageAttachment` + `MessageAttachmentId` (composite key @EmbeddedId), `FileRecordRepository`, `MessageAttachmentRepository`, `FileDto`, `StorageService` (interface), `LocalStorageService`, `FileValidationService`, `FileService`, `FileController`, và 6 exception class riêng trong `com.chatapp.file.exception`.
- Migration V7: `files` + `message_attachments`. **CHỐT**: `uploader_id UUID` (không BIGINT) và `message_id UUID` vì `users.id`/`messages.id` là UUID (V2/V5). Task spec viết BIGINT là sai — luôn align với PK type thực tế. Partial index pattern: `... WHERE expired = FALSE`, `... WHERE attached_at IS NULL` — chỉ index rows cần scan cho cleanup job.
- `StorageService` interface 3 method (store/retrieve/delete) → `LocalStorageService` V1, dễ swap S3 V2 (ADR-019). Path layout `{base}/{yyyy}/{MM}/{uuid}.{ext}` — **KHÔNG** ghép originalName vào path (path traversal).
- `LocalStorageService` security: `assertWithinBase()` canonical-prefix check trên mọi resolve. Reject fileId chứa `/`, `\`, `..`; reject ext chứa `.` (client attempt "jpg.exe" bypass). Trả **relative path** (normalize `\`→`/`) để DB portable cross-OS.
- `FileValidationService` Tika: `new Tika().detect(InputStream)` — Tika chỉ đọc ~8KB peek, KHÔNG consume stream. MultipartFile.getInputStream() trả stream mới mỗi lần gọi nên không cần mark/reset. Alias normalization: `image/jpg → image/jpeg` (Firefox cũ).
- MIME→ext map **CỐ ĐỊNH** trong FileValidationService (không đọc từ filename) — jpg/png/webp/gif/pdf. Whitelist: 4 image MIME + application/pdf (ADR-019).
- `FileRateLimitedException` (không reuse `AppException` RATE_LIMITED vì có thêm field `retryAfterSeconds` typed getter). 6 exception class riêng để GlobalExceptionHandler map rõ ràng: FileEmpty/FileTooLarge/FileTypeNotAllowed/MimeMismatch/FileRateLimited/Storage + wire-in MaxUploadSizeExceeded + MissingServletRequestPart/Param.
- GlobalExceptionHandler: `MissingServletRequestPartException` → 400 FILE_EMPTY (khi client POST không có part "file"); `MissingServletRequestParameterException("file")` → FILE_EMPTY (edge case Content-Type non-multipart). MaxUploadSizeExceededException → 413 FILE_TOO_LARGE generic (không có actualBytes).
- application.yml: `spring.servlet.multipart.max-file-size: 20MB` + `max-request-size: 21MB` + `storage.local.base-path`. Test profile override: `./build/test-uploads` để không pollute repo working tree.
- Controller: `@AuthenticationPrincipal User user` (KHÔNG `UserDetails` — project không dùng UserDetailsService, xem JwtAuthFilter). `@PostMapping(consumes=MULTIPART_FORM_DATA_VALUE)`. Download dùng `InputStreamResource` + `CacheControl.maxAge(7d).cachePrivate()` + ETag=id + `X-Content-Type-Options: nosniff`.
- Anti-enumeration download: merge not-found / not-owner / expired → 404 NOT_FOUND đơn nhất. W6-D1 stub: chỉ uploader download được; W6-D2 sẽ thêm conv-member check.
- Test pattern MultipartFile: `MockMultipartFile("file", "name.jpg", "image/jpeg", JPEG_MAGIC)` + `mockMvc.perform(multipart("/api/files/upload").file(file)...)`. Test size limit mà không alloc 20MB: custom `SizedMockMultipartFile` với explicit `getSize()` override, `getInputStream()` trả head bytes.
- Test với `@TempDir` cho LocalStorageService — JUnit 5 tự cleanup, không pollute filesystem. Package test `com.chatapp.file` khác `com.chatapp.file.storage` → `getBasePath()` phải `public` (không package-private).
- Test Tika magic bytes: lưu hardcoded byte[] cho JPEG/PNG/PDF (FF D8 FF E0 / 89 50 4E 47 / %PDF-1.4) — không mock Tika vì chính nó là component cần verify.
- Orphan file concept: `attached_at NULL` = chưa gắn message. `markAttached()` domain method trên FileRecord sẽ dùng W6-D2 khi MessageService gắn attachments. Cleanup job orphan (1h) + expiry job (30d) làm ở W6-D3.

---

### @Scheduled Cleanup Job Pattern (W6-D3)

- `@ConditionalOnProperty(name="app.file-cleanup.enabled", havingValue="true", matchIfMissing=true)` — cho phép disable bean hoàn toàn qua property. Test profile dùng `enabled=true` + cron="-" (Spring disabled value) để bean load nhưng trigger không chạy.
- `expired-cron: "-"` và `orphan-cron: "-"` trong application-test.yml: cron "-" là giá trị Spring dùng để disable trigger — cron expression không hợp lệ → scheduler bỏ qua, bean vẫn tồn tại.
- Test pattern: `@SpringBootTest` + `@MockBean StorageService` để inject mock vào `FileCleanupJob`. Gọi `fileCleanupJob.cleanupExpiredFiles()` / `cleanupOrphanFiles()` trực tiếp trong test method (không chờ cron).
- Batch pagination: `findBy...(..., PageRequest.of(0, batchSize))` luôn query page 0. Đúng vì sau mỗi batch, records đã delete/update → page 0 tiếp theo chứa records mới (không bỏ sót).
- Per-record try-catch: 1 file fail IOException không làm cả job fail. Log error + continue. Đối với expired job, sau exception vẫn set `expired=true` để không bị query lại.
- stillAttached handling: physical delete trước → set `expired=true` + save DB → GET /files/{id} → `openStream()` → `StorageException` → controller catch → 404 graceful.
- `OffsetDateTime.now(ZoneOffset.UTC)` cho threshold — consistent với entity `@PrePersist` pattern.
- Orphan query: `findByAttachedAtIsNullAndCreatedAtBefore` — method naming đủ, không cần `@Query` vì `attachedAt` đã encode trạng thái.
- H2 timestamp test pitfall: dùng `minusDays(2)` thay vì `minusHours(2)` cho threshold diff trong test để tránh H2 sub-hour precision issue với TIMESTAMPTZ comparison. (Consistent với W4-D1 pattern "vd: plusDays(i)").
- JdbcTemplate timestamp override: `java.sql.Timestamp.from(offsetDateTime.toInstant())` + `CAST(? AS UUID)` cho WHERE clause — H2 cần explicit CAST cho UUID comparison trong native SQL.
- FileController graceful missing: catch `StorageException` (không `IOException` — FileService wrap IOException thành StorageException) → throw `AppException(404, "FILE_PHYSICALLY_DELETED", ...)`. Áp dụng cho cả `download` lẫn `downloadThumb` endpoint.

### FileCleanupJob V2 — Multi-instance coordination (document)

V1 assume single BE instance (ADR-015 SimpleBroker). Khi scale V2 với multi-instance:
- 2 instance cùng chạy @Scheduled cùng lúc → risk: 2 instance cùng delete FileRecord → StaleObjectStateException.
- V2 fix: Redis SETNX distributed lock:
  ```java
  Boolean acquired = redis.setIfAbsent("lock:file-cleanup:expired", instanceId, Duration.ofMinutes(30));
  if (Boolean.TRUE.equals(acquired)) { try { /* logic */ } finally { redis.delete(lockKey); } }
  ```
- Track trong WARNINGS.md V2 bucket.

---

### File Type Expansion Pattern (W6-D4-extend)

- Whitelist MIME set: dùng `Set.of()` vì Set.of() không giới hạn số args (không phải Map.of()). Nhưng `Map.ofEntries(Map.entry(...))` dùng khi > 10 entries cho MIME_TO_EXT.
- Charset strip: `tika.detect().split(";")[0].trim()` — text/plain và vài MIME khác có charset suffix.
- ZIP→Office override: DOCX/XLSX/PPTX là ZIP container → Tika không luôn detect đúng MIME nếu thiếu Office metadata. Dùng extension hint CHỈ KHI Tika trả `application/zip` (không phải mọi lúc).
- iconType: Server-computed từ MIME, FE đọc để chọn icon. Map trong `FileService.resolveIconType()`. `GENERIC` là fallback.
- Group validation update: `singlePdf` → `singleNonImage` (`files.size() == 1 && !allImages`). Bao gồm tất cả Group B types.
- Test V07 fix: text/plain đã vào whitelist → dùng EXE magic (MZ=0x4D5A) cho test "not in whitelist".
- Test F05 fix: tương tự dùng EXE bytes thay vì text/plain bytes.

---

### Group Chat Schema + CRUD Pattern (W7-D1, ADR-020)

**Enum with permission methods** (anti-pattern scatter if-else):
- `MemberRole` embed 6 permission methods: `canRename()`, `canAddMembers()`, `canRemoveMember(targetRole)`, `canChangeRole()`, `canDeleteGroup()`, `canTransferOwnership()`. Service gọi `member.getRole().canRename()` thay vì `if (member.getRole() == OWNER || ...)`. Khi spec thay đổi (vd MODERATOR), chỉ sửa enum.
- `canRemoveMember(target)` — logic 2-tham số: OWNER kick bất kỳ trừ OWNER; ADMIN kick MEMBER; MEMBER không kick được ai.

**CHECK constraint cho type-specific columns**:
- Pattern: `CHECK ((type='A' AND col1 IS NULL AND col2 IS NULL) OR (type='B' AND col1 IS NOT NULL))` — enforce shape invariant ở DB level. ONE_ON_ONE không có name/owner; GROUP bắt buộc name (owner có thể NULL sau ON DELETE SET NULL).
- H2 test profile `ddl-auto: create-drop` → Hibernate KHÔNG tạo CHECK constraint từ migration SQL. Validation chỉ ở Java layer trong test (assertion entity non-null, không assertion DB violation).

**Soft-delete filter pattern `deleted_at IS NULL`**:
- Repository thêm method `findActiveById(UUID)` + `findActiveByIdWithMembers(UUID)` filter `c.deletedAt IS NULL`. Caller dùng method active cho PATCH/DELETE/GET flows.
- Native queries list/count thêm `WHERE c.deleted_at IS NULL` — tránh hiển thị group đã xoá trong sidebar.
- Partial index `WHERE deleted_at IS NOT NULL` — chỉ index rows ít (audit query) thay vì full index.

**ON DELETE SET NULL cho owner_id**:
- `conversations.owner_id UUID REFERENCES users(id) ON DELETE SET NULL` — khi user bị xoá account, group vẫn tồn tại. V1 không auto-promote ADMIN; owner_id = NULL, response trả `owner: null` (FE fallback UI).
- Khác với `ON DELETE CASCADE` cho message_attachments.message_id → attachment đi theo message. Logic: group sống sót, message metadata không.

**Tristate DTO cho PATCH (absent / null / value)**:
- Jackson record deserialize KHÔNG phân biệt "field absent" vs "field = null" — cả hai đều set null. Dùng `@JsonAnySetter` + Map để giữ key-presence:
  ```java
  private final Map<String, Object> rawFields = new HashMap<>();
  @JsonAnySetter public void set(String k, Object v) { rawFields.put(k, v); }
  public boolean hasAvatarFileId() { return rawFields.containsKey("avatarFileId"); }
  public boolean isRemoveAvatar() { return hasAvatarFileId() && rawFields.get("avatarFileId") == null; }
  ```
- Contract semantics: `undefined → no change`, `null → remove`, `uuid → set`.

**LinkedHashMap cho broadcast payload với null values**:
- `Map.of()` throws NPE với null value. Broadcast `CONVERSATION_UPDATED` có `changes: {avatarUrl: null}` (remove) → phải dùng `LinkedHashMap` + `put()`. Apply cho mọi envelope cần null-tolerant.

**Avatar attach flow**:
- Validate avatar: (1) exists (merge anti-enum với not-owned), (2) uploader=caller, (3) MIME ∈ {jpeg,png,webp,gif}, (4) chưa expired. Dùng helper `validateGroupAvatar(fileId, callerId)` chung cho create + update.
- Sau validate pass → `fileRecord.markAttached()` + save → orphan cleanup job (1h) skip. Avatar đi qua `conversations.avatar_file_id`, KHÔNG qua `message_attachments` (tránh UNIQUE constraint conflict; avatar là metadata, không phải content).

**Broadcast via Event Publisher (reuse pattern W4-D4)**:
- `ApplicationEventPublisher.publishEvent(new ConversationUpdatedEvent(...))` trong @Transactional method.
- Broadcaster `@TransactionalEventListener(phase=AFTER_COMMIT)` + try-catch toàn bộ (broadcast fail không propagate, REST đã trả response).
- Events: `ConversationUpdatedEvent{convId, changes Map, actorId, actorFullName, occurredAt}`, `GroupDeletedEvent{convId, actorId, actorFullName, deletedAt}`.

**Naming drift V7 vs V9**:
- V7 đã dùng cho files (W6-D1). Docs ADR-020 viết `V7__add_group_chat.sql` nhưng thực tế dùng V9 vì V7/V8 đã occupied. Flyway filename là SOURCE OF TRUTH (DB history immutable), docs chấp nhận drift — ghi chú trong migration comment.

**Member sort rule**:
- GET /{id} trả members sort: `role ASC (ordinal)` → OWNER (0) đầu, ADMIN (1), MEMBER (2). Secondary: `joinedAt ASC` (cũ nhất trước). FE render không cần sort lại.
- Java: `Comparator.comparing(m -> m.getRole().ordinal()).thenComparing(m -> m.getJoinedAt().toInstant())`.

**Backward-compat cho DIRECT shape**:
- W7: `POST /conversations` ONE_ON_ONE dùng `targetUserId` (singular). Legacy W3 dùng `memberIds: [uuid]`. Service accept cả hai: `UUID targetUserId = req.targetUserId() != null ? req.targetUserId() : (memberIds != null && size==1 ? memberIds.get(0) : null)`.

**Schema column verification trong test**:
- Dùng `DataSource.getConnection().getMetaData().getColumns(null, null, TABLE, COLUMN)` để verify cột tồn tại (case-insensitive check vì H2 uppercase unquoted identifiers; thử cả UPPER và lower).
- Không dùng để test CHECK constraint (H2 create-drop không apply migration SQL).

---

## Changelog file này

- 2026-04-21 W7D1: Thêm Group Chat Schema + CRUD Pattern (ADR-020) — MemberRole permission methods, CHECK constraint shape, soft-delete filter, ON DELETE SET NULL cho owner_id, Tristate DTO qua @JsonAnySetter Map, LinkedHashMap cho broadcast null-tolerant, avatar attach flow (markAttached), Event Publisher broadcast, naming drift V7→V9, member sort role+joinedAt, targetUserId backward-compat, DataSource metadata cho column verification test.
- 2026-04-21 W6D2: Thêm FileAuthService (uploader OR conv-member rule, JPQL JOIN), ThumbnailService (Thumbnailator fail-open pattern), StorageService.resolveAbsolute interface extension, validateAndAttachFiles validation order (count→existence→ownership→expiry→unique→group), MessageDto.attachments field (always non-null List), MessageMapper N+1 warning. Test pattern cho Thumbnailator: ImageIO generate valid JPEG bytes. DB NOT NULL content pitfall → persist "" cho attachment-only messages.
- 2026-04-21 W6D1: Thêm File Upload Foundation pattern (Tika MIME detect, LocalStorageService path traversal defense, MIME→ext cố định, 6 exception class, anti-enumeration 404 cho download, MultipartFile test pattern). Migration V7 dùng UUID FK (không BIGINT như task spec).
- 2026-04-20 W5D4: Thêm Forward Pagination Pattern (after param), ReplyPreviewDto deletedAt field, STOMP reply validation pattern.
- 2026-04-20 W5D3: Thêm Soft Delete Pattern (Message), content strip tại mapper, DELETE ACK minimal map, Map.of() null pitfall.
- 2026-04-20 W5D2: Thêm Unified ACK/ERROR Shape Pattern (ADR-017), Anti-enumeration MSG_NOT_FOUND, Edit Dedup Pattern, Edit Window Check.
- 2026-04-20 W5D1 Fix A: Thêm Destination-Aware Auth Policy Pattern (DestinationPolicy enum, resolveSendPolicy, SILENT_DROP cho typing/read, STRICT_MEMBER cho message).
- 2026-04-20 W5D1: Thêm Ephemeral Event Pattern (TypingRateLimiter, ChatTypingHandler, silent drop, LENIENT strictness test).
- 2026-04-20 W4D4: Thêm TransactionalEventListener Broadcast Pattern, MessageMapper extraction.
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
