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

### ADR-014: STOMP model W4 — REST-gửi + STOMP-broadcast (publish-only), không tempId inbound
- **Quyết định**: Tuần 4 chọn mô hình **REST `POST /api/conversations/{id}/messages` để gửi + STOMP `/topic/conv.{id}` để broadcast MESSAGE_CREATED event cho các subscriber**. KHÔNG implement `/app/chat.send` với tempId ACK/ERROR flow như ARCHITECTURE.md mục 5 mô tả gốc.
- **Bối cảnh**: ARCHITECTURE.md thiết kế gốc cho mọi text message đi qua STOMP với tempId lifecycle (SENDING → SENT qua ACK hoặc FAILED qua ERROR, timeout 10s). Yêu cầu này tăng complexity đáng kể vì: (1) cần dedup server-side bằng Redis SET `msg:dedup:{userId}:{tempId}`, (2) cần timeout logic client-side + retry, (3) cần route ACK/ERROR qua `/user/queue/acks` + `/user/queue/errors`, (4) state machine FE phức tạp.
- **Lý do**:
  - REST-gửi đã có 201 response confirm save (W4D1 đã implement POST /messages). Sender biết message thành công qua HTTP status → không cần ACK riêng.
  - Broadcast qua STOMP chỉ để thông báo receiver realtime. Receiver không cần tempId (không phải họ gửi).
  - Sender cũng nhận broadcast → cần dedupe bằng message id (đơn giản hơn tempId dedup vì id đã có sau REST response).
  - Giảm surface area bug V1. Socket layer vẫn cần cho typing/presence/read receipts (Tuần 5) nhưng không phải cho send-message path.
  - Latency trade-off: REST POST thêm ~30-50ms vs pure STOMP SEND, nhưng V1 traffic thấp (<1000 concurrent) → không perceptible.
- **Trade-off**:
  - Mất ưu thế "fire-and-forget" của STOMP cho sender. Nếu REST POST fail → FE phải retry HTTP, không có socket-level retry.
  - Nếu sau này có offline queueing client-side (gửi khi offline, sync khi online) → STOMP model gốc phù hợp hơn. V1 không có offline mode → acceptable.
  - Yêu cầu BE broadcast PHẢI ở `@TransactionalEventListener(AFTER_COMMIT)` — broadcast trước commit → rollback → FE thấy message "ma". BLOCKING check khi review.
  - FE PHẢI dedupe theo message id khi sender tự nhận broadcast của chính mình (REST response đã set vào cache rồi).
- **Re-evaluation trigger**: Nếu Tuần 5-6 đo được latency REST POST > 100ms p50 hoặc user complain "tin nhắn chậm" → đánh giá migrate sang `/app/chat.send` với tempId.
- **Ngày**: 2026-04-19 (Tuần 4, Ngày 2 — formalized khi draft SOCKET_EVENTS.md v1.0-draft-w4)

### ADR-015: SimpleBroker V1 → RabbitMQ V2 khi scale >1 BE instance hoặc cần persistent queue
- **Quyết định**: Tuần 4-6 dùng Spring `SimpleBroker` (in-memory) với prefix `/topic`, `/queue`. KHÔNG setup RabbitMQ hoặc external broker. Migrate trigger: (1) scale horizontal BE (>1 instance) hoặc (2) cần persistent queue để offline user catch-up qua broker thay vì REST polling.
- **Bối cảnh**: SimpleBroker tương thích với multi-user single-instance, giảm infra dependency (không cần RabbitMQ container). ARCHITECTURE.md scale target V1 = 1 server Singapore, <1000 concurrent — khớp khả năng SimpleBroker.
- **Lý do**:
  - Infra simplicity: 1 Spring Boot + PostgreSQL + Redis, không thêm RabbitMQ → deploy đơn giản, debug dễ.
  - SimpleBroker pure in-memory → latency broadcast <1ms local, nhanh hơn external broker (~5-10ms RabbitMQ).
  - Không cần durable queue V1 vì offline catch-up đã có qua REST `GET /messages?cursor=...`.
- **Trade-off**:
  - **Không scale horizontal**: nếu thêm BE instance, 2 client connect 2 instance khác nhau → không nhận được broadcast của nhau vì SimpleBroker không sync cross-instance. BLOCKING khi scale.
  - **Mất message khi BE restart**: subscribe destinations bị flush, nhưng DB vẫn có message → FE catch-up OK.
  - **Không destination ACL tốt**: SimpleBroker không có ACL built-in → PHẢI custom trong `ChannelInterceptor` SUBSCRIBE. Đã document trong SOCKET_EVENTS.md mục 7.
- **Migration path V2**: `config.enableStompBrokerRelay("/topic", "/queue").setRelayHost("rabbitmq")...` + RabbitMQ deployment. Destinations không đổi → FE không cần thay.
- **Monitor signal**: nếu active WS sessions > 500 sustained → alert, chuẩn bị RabbitMQ migration.
- **Ngày**: 2026-04-19 (Tuần 4, Ngày 2 — formalized khi draft SOCKET_EVENTS.md v1.0-draft-w4)

### ADR-016: STOMP-send (Path B) — `/app/conv.{id}.message` với tempId, ACK/ERROR qua user queues
- **Quyết định**: Chuyển path gửi tin nhắn text từ REST `POST /messages` sang STOMP `/app/conv.{convId}.message` với payload `{tempId, content, type}`. Server ACK qua `/user/queue/acks`, ERROR qua `/user/queue/errors`. Redis dedup `msg:dedup:{userId}:{tempId}` TTL 60s atomic `SET NX EX`. REST `POST /messages` **không bị xoá** — giữ làm fallback + batch import + bot API + integration test.
- **Bối cảnh**: ADR-014 (W4) chọn REST-gửi cho đơn giản. Sau W4D4 wire xong, team đánh giá latency REST POST (~30-50ms overhead) + fragmented transport (REST+STOMP song song) không đáng để đánh đổi. Path B unify transport, giảm latency p50, và thống nhất FE flow cho mọi operation realtime (send, edit, delete, react sau này).
- **Lý do**:
  - Latency: STOMP publish <5ms vs REST POST 30-50ms. V1 chưa cần nhưng chuẩn bị cho scale.
  - Unified transport: 1 kênh WS cho gửi + nhận, giảm surface area bug + CORS + cookie-vs-token mismatch.
  - ACK/ERROR pattern sẵn sàng cho EDIT/DELETE (W5-D2, W6) — reuse `/user/queue/acks` + `/user/queue/errors`.
  - Redis dedup `SET NX EX` atomic chạy TRƯỚC save DB → chống race 2 frame cùng tempId (network retry) mà không tạo duplicate.
  - Optimistic + timeout 10s client-side: nếu ACK không về, mark failed, user retry với **tempId MỚI** (không reuse — dedup key còn sẽ re-send ACK sai).
- **Trade-off**:
  - Thêm complexity ở BE: `SendMessagePayload` DTO, `MessageStompController`, `@MessageExceptionHandler`, `sendViaStomp` service method, Redis dedup key, ACK via `TransactionSynchronizationManager.afterCommit()`.
  - Client timeout cố định 10s — request slow hợp lệ > 10s bị mark failed (server vẫn save → duplicate nếu user retry tempId khác). V1 acceptable.
  - Broker down (SimpleBroker restart) → REST POST 201 đã commit nhưng broadcast fail → FE không nhận broadcast. Mitigation: FE có sẵn dedup + catch-up qua REST cursor.
  - tempId PHẢI nằm trong PAYLOAD (không phải STOMP header) — `@Header("tempId")` sẽ null. Service propagate tempId qua `AppException.details` để handler echo.
- **BLOCKING checks khi review**:
  - `SET NX EX` PHẢI atomic (dùng `Duration` trong `setIfAbsent`, không tách SETNX + EXPIRE riêng).
  - Dedup PHẢI trước save DB.
  - ACK PHẢI gửi ở `afterCommit` (không trong `@Transactional` body — ACK trước rollback → client nhận ACK mà message biến mất khỏi DB).
  - FE dedupe broadcast PHẢI dùng **real id** sau ACK replace, không phải tempId.
  - FE timer PHẢI clear trong CẢ 3 branch: ACK, ERROR, timeout 10s. Leak timer = memory leak.
  - FE retry PHẢI tempId MỚI mỗi lần — không reuse.
- **Ngày**: 2026-04-20 (Post-W4 / W5 preparation)

### ADR-017: Unified ACK/ERROR queue với `operation` discriminator (thay vì tách queue per operation)
- **Quyết định**: Dùng **1 queue duy nhất** `/user/queue/acks` + `/user/queue/errors` cho TẤT CẢ operation client-initiated (SEND, EDIT, và tương lai DELETE, REACT, ...). Payload thống nhất shape với field `operation` làm discriminator:
  - ACK: `{operation: "SEND"|"EDIT"|"DELETE"|"REACT", clientId: UUID v4, message: MessageDto}`.
  - ERROR: `{operation, clientId, error: string, code: string}`.
  - `clientId` là tên generic thay cho `tempId` (SEND) / `clientEditId` (EDIT) / ... — giá trị vẫn là UUID v4 client sinh.
  - FE routing: `switch(operation)` ở 1 nơi trong `useAckErrorSubscription` hook.
- **Bối cảnh**: Khi implement W5-D2 edit message, cần nơi để server ACK sau khi edit save. 2 lựa chọn: (A) tách queue riêng `/user/queue/acks-edit`, `/user/queue/errors-edit`; (B) reuse queue cũ với discriminator.
- **Lý do**:
  - Tránh proliferation queues khi thêm operation: DELETE → `/acks-delete`; REACT → `/acks-react`; ... mỗi operation 2 queue → FE phải subscribe N*2 queues → N*2 listener → dễ quên cleanup khi logout/reconnect.
  - FE route qua 1 `switch` đơn giản hơn N hook per-operation. Tab-awareness check cũng single-point.
  - Payload shape IDENTICAL cho ACK (cùng MessageDto) và ERROR (cùng error+code shape) → dễ maintain type.
  - Server không cần tạo destinations mới khi thêm operation — chỉ cần add case vào switch handler.
- **Trade-off**:
  - **Breaking change**: SEND ACK shape cũ `{tempId, message}` đổi sang `{operation: "SEND", clientId: tempId, message}`. BE + FE phải deploy đồng bộ. Không có backward-compat window vì chỉ FE nội bộ consume.
  - FE phải check `operation` field first trước khi parse cụ thể. Missing `operation` → default case (ignore).
  - Nếu sau này có operation cần destination riêng (ví dụ high-volume bulk reactions), có thể vẫn tách queue riêng — discriminator không cản việc đó.
- **Migration applied W5-D2**:
  - `AckPayload` record: `{String operation, String clientId, MessageDto message}`.
  - `ErrorPayload` record: `{String operation, String clientId, String error, String code}`.
  - SEND path (`MessageService.sendViaStomp` + `MessageStompController`) update để dùng shape mới với `operation="SEND"`.
  - EDIT path (`MessageService.editViaStomp` + `ChatEditMessageHandler`) dùng shape mới với `operation="EDIT"`.
  - FE `AckEnvelope`/`ErrorEnvelope` type replace cho `AckPayload`/`ErrorPayload` cũ (deprecated nhưng giữ để không break import).
  - `useAckErrorSubscription` dùng `switch(operation)` routing — case `SEND` cho tempId lifecycle, case `EDIT` cho edit lifecycle (tab-awareness qua `editTimerRegistry.get(clientId)`).
- **Ngày**: 2026-04-20 (Tuần 5, Ngày 2 — W5-D2)

### ADR-019: FileAuthService pattern — uploader OR conv-member, anti-enum 404 (W6-D2)
- **Quyết định**: Tách riêng `FileAuthService.findAccessibleById(fileId, userId)` khỏi `FileService.loadForDownload`. Rule: uploader luôn pass; else JOIN `message_attachments → messages → conversation_members` check membership. Mọi fail-branch (not-found / not-accessible / expired / cleanup-deleted) → `Optional.empty()` để controller throw **404 NOT_FOUND** (không 403, không 410 Gone).
- **Bối cảnh**: W6-D1 stub chỉ uploader download được. W6-D2 cần mở cho conv-member (để B download ảnh A gửi trong chat). Nhưng không được leak "file tồn tại nhưng bạn không có quyền" → merge với "file không tồn tại".
- **Lý do**:
  - Anti-enumeration: attacker duyệt UUID không biết file có tồn tại hay không.
  - Separate service → FileController không tự query repo, FileService không lo auth logic.
  - Return `Optional` thay vì throw → caller quyết định error code (download endpoint trả 404, thumbnail endpoint reuse cùng service).
  - JOIN query JPQL `existsByFileIdAndConvMemberUserId` — COUNT > 0 để tránh load entity thừa.
- **Trade-off**:
  - JPQL JOIN chạy cho MỌI request download — N+1 nếu user scroll chat với 50 images × 50 download = 50 queries. V1 acceptable vì images cache browser (Cache-Control 7d, ETag) → chỉ fire 1 lần. Nếu contention cao → V2 cache `(fileId, userId) → bool` Redis 5min.
  - Expired file vẫn trả 404 (không 410 Gone) — user không biết "file từng tồn tại nhưng expire" vs "chưa bao giờ có". Acceptable privacy trade-off.
- **Ngày**: 2026-04-21 (W6-D2)

### ADR-020: Thumbnail fail-open khi generate fail — upload success dù thumb lỗi
- **Quyết định**: `FileService.upload()` step 6 call `thumbnailService.generate()` trong try-catch **ngoài** flow chính. Thumbnail fail (Thumbnailator exception, disk I/O, OOM) → log WARN + continue. DB column `thumbnail_internal_path` = null → `FileDto.thumbUrl` = null → `GET /thumb` trả 404.
- **Bối cảnh**: Thumbnailator có thể fail vì: (1) image corrupt/truncated, (2) exotic format Tika chấp nhận nhưng ImageIO không đọc được, (3) disk full tạm thời, (4) OOM với image 20MB.
- **Lý do**:
  - Upload flow chính (validate → store → persist) đã succeed → user expect file đã upload. Thumbnail là feature phụ, không đáng rollback cả upload.
  - DB path null → serialization (FileDto) tự động set `thumbUrl=null` → FE fallback hiển thị full-size hoặc placeholder.
  - Alternative (throw → rollback upload) worse UX: user upload 20MB xong thấy lỗi, phải upload lại.
- **Trade-off**:
  - Silent partial failure: user không biết thumb fail (chỉ log server). V2 thêm metric `thumbnail.generate.failed` để ops monitor spike.
  - Nếu thumb fail PHẢI consistent cross-request: lần sau GET /thumb vẫn 404 (không retry lazy-gen). Chấp nhận vì V1 eager gen upload-time, không lazy.
- **Applied pattern**: tương tự "fail-open rate limit Redis down" (ADR-005) — feature phụ không block hot path.
- **Ngày**: 2026-04-21 (W6-D2)

### ADR-021: Content XOR Attachments — message phải có 1 trong 2 (W6-D1/W6-D2)
- **Quyết định**: `validateStompPayload` check `hasContent || hasAttachments` — cả 2 rỗng → `MSG_NO_CONTENT`. Content trimmed non-blank hoặc attachmentIds non-empty. Không cho gửi "tin nhắn rỗng".
- **Bối cảnh**: Pre-W6 content required 1-5000 chars. W6 mở `content=null` khi có attachments → FE có thể gửi `{content: null, attachmentIds: [uuid]}` cho image-only message. Nhưng cũng có thể gửi `{content: "", attachmentIds: []}` (UI bug) → DB row vô nghĩa.
- **Lý do**:
  - 1 invariant rõ ràng: mọi message hiển thị được phải có content hoặc attachment.
  - DB column `content` NOT NULL (V5 migration) — service trim+persist empty string "" khi attachments-only (FE dùng attachments render, content="" không visible).
  - Error code `MSG_NO_CONTENT` riêng (không dùng VALIDATION_FAILED generic) → FE hiển thị toast cụ thể.
- **Trade-off**:
  - Message với attachments rỗng content → DB lưu content="" (waste 1 row byte). Alternative: đổi content schema NULL + check invariant ở service. V1 giữ NOT NULL vì migration cost > benefit.
- **Applied**: `MessageService.sendViaStomp` step 1 validate. FE cần guard trước khi submit (double-check).
- **Ngày**: 2026-04-21 (W6-D1 contract + W6-D2 review confirmed)

### ADR-022: Soft-deleted message strip content + attachments (privacy, W5-D3 + W6-D1)
- **Quyết định**: `MessageMapper.toDto` khi `message.deletedAt != null` → content=null + attachments=[]. Applied **nhất quán** cho REST list, REST get, STOMP broadcast, STOMP ACK (bất kỳ path nào serialize MessageDto).
- **Bối cảnh**: W5-D3 đã strip content cho soft-delete. W6-D1 thêm attachments → PHẢI strip cả attachments (không để lộ URL file). Lý do: nếu giữ attachments, receiver đã nhận broadcast MESSAGE_DELETED nhưng vẫn có URL thumbnail trong cache → click thumb vẫn download được file (FileAuthService cho conv-member).
- **Lý do**:
  - Defense-in-depth: strip ở mapper = strip ở TẤT CẢ output path (không sót). Nếu strip ở từng endpoint → dễ quên 1 path.
  - Privacy parity: content đã stripped, attachments phải cùng → user xóa tin nhắn = xóa cả văn bản lẫn file.
- **Trade-off**:
  - Receiver có tab đang mở (cache message pre-delete) → vẫn có URL trong React Query cache. Mitigation: broadcast MESSAGE_DELETED → FE invalidate/patch → cache update. Tuy nhiên, browser có thể đã cache thumbnail binary (Cache-Control 7d) → thumb vẫn render từ cache HTTP cho đến khi cache expire.
  - Giữ `attachments = []` (non-null) thay vì field mất — FE render consistent `message.attachments.length === 0` check OK.
- **Ngày**: 2026-04-21 (W6-D2 formalized, merge với AD-20 trong WARNINGS.md)

### ADR-018: Delete message window không giới hạn thời gian (khác Edit 5 phút)
- **Quyết định**: `deleteViaStomp` KHÔNG có edit-window check (không giống `editViaStomp` có 300s limit). Owner có thể xoá tin nhắn của mình bất cứ lúc nào, không giới hạn thời gian kể từ khi gửi. Anti-enum vẫn apply: non-owner → `MSG_NOT_FOUND` (không `FORBIDDEN`).
- **Bối cảnh**: W5-D3 implement delete message. So sánh với edit: edit có 5 phút window để giảm rối conversation history (user edit tin cũ gây confusion). Delete thì logic khác — user có thể muốn xoá tin nhắn gửi nhầm từ lâu (embarrassing content, tin nhắn riêng tư hỏi về mật khẩu, v.v.).
- **Lý do**:
  - UX parity với Messenger / Telegram / Zalo — các app lớn cho phép xoá bất cứ lúc nào.
  - Privacy: user có quyền quản lý content của mình không giới hạn thời gian.
  - Soft-delete (set `deletedAt + deletedBy`) — dữ liệu vẫn trong DB cho audit/compliance nếu cần, chỉ content bị strip khi serialize.
  - `MessageMapper.toDto` strip `content=null` khi `deletedAt != null` → consistent cho mọi consumer (REST list + WS broadcast + ACK).
- **Trade-off**:
  - Không có undo grace period (Gmail-style 5s). V1 dùng `window.confirm()` trước khi publish delete. V2 xem xét undo toast 5s pattern.
  - Receiver có thể đã đọc tin trước khi sender xoá → delete ≠ unsend (receiver đã nhận broadcast). UX: hiển thị "🚫 Tin nhắn đã bị xóa" placeholder thay vì ẩn hoàn toàn — receiver biết có tin nhưng không thấy nội dung.
  - Reply tới message đã deleted không bị block (AD-16) — reply preview có thể render rỗng / placeholder. UX minor, defer V2.
- **Implementation checks**:
  - `deleteViaStomp` không có `Duration.between(createdAt, now)` check.
  - Edit sau delete → MSG_NOT_FOUND regression guard: `editViaStomp` check `message.getDeletedAt() != null` → merge vào MSG_NOT_FOUND (test T-DEL-07).
  - Broadcast + ACK AFTER_COMMIT (không race rollback).
  - FE Option A: KHÔNG optimistic `deletedAt`, chỉ mark `deleteStatus='deleting'` + opacity-50. Chờ ACK patch từ server.
- **Ngày**: 2026-04-20 (Tuần 5, Ngày 3 — W5-D3)

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

- **API_CONTRACT.md**: v0.6.1-messages-stomp-shift (W5-D2: POST /messages marked deprecated cho FE hot path — ADR-016. Shape response không đổi.)
- **SOCKET_EVENTS.md**: v1.3-w5d2 (W4D2 → W5D2 accumulated: Path B ADR-016 (`/app/conv.{id}.message`) + Edit message W5-D2 (`/app/conv.{id}.edit`) + Unified ACK ADR-017 (`/user/queue/acks` + `/user/queue/errors` với `operation` discriminator). Broadcast `/topic/conv.{id}` MESSAGE_CREATED + MESSAGE_UPDATED. Dedup Redis `msg:dedup:*` + `msg:edit-dedup:*` TTL 60s. Implemented: W4D3+W4D4 send, W5D1 typing, W5D2 edit. Pending W5 presence/read, W6 delete/reaction.)

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

### Anti-enumeration pattern (STOMP EDIT, W5-D2 formalized)
- **Quy tắc vàng**: Khi user truy xuất tài nguyên (tin nhắn, conv, user) mà có thể fail ở nhiều lý do (không tồn tại / không phải owner / đã soft-delete / không phải member / thuộc về conv khác), PHẢI merge tất cả thành 1 error code chung (thường là `*_NOT_FOUND`) với cùng 1 message. Mục tiêu: attacker không enumerate được "tài nguyên tồn tại nhưng tôi không có quyền" vs "tài nguyên không tồn tại".
- **Ví dụ W5-D2 edit message**: `editViaStomp` check `message == null || message.conversationId != convId || message.sender.id != userId || message.deletedAt != null` → tất cả throw chung `MSG_NOT_FOUND`. KHÔNG phân biệt `FORBIDDEN` (not owner) với `NOT_FOUND`.
- **Ngoại lệ**: chỉ phân biệt khi FE cần UX khác biệt (ví dụ `AUTH_REQUIRED` vs `FORBIDDEN` để FE redirect login vs toast). Business resource thì merge.
- **Precedent**: tương tự `CONV_NOT_FOUND` (merge conv-không-tồn-tại + user-không-phải-member), `USER_NOT_FOUND` (merge user-không-tồn-tại + user-status-inactive), `AUTH_INVALID_CREDENTIALS` (merge user-không-tồn-tại + sai-password).

### Optimistic edit pattern (W5-D2) — BẮT BUỘC cân nhắc revert khi ERROR
- **Vấn đề**: Optimistic update cho EDIT operation phức tạp hơn CREATE — vì có giá trị CŨ (content gốc). Nếu optimistic ghi đè content mới rồi server trả ERROR (MSG_NOT_FOUND, MSG_EDIT_WINDOW_EXPIRED, MSG_CONTENT_TOO_LONG, MSG_NO_CHANGE, TIMEOUT), FE PHẢI có cơ chế revert về content cũ. Nếu không → cache lệch DB vĩnh viễn.
- **2 option đúng**:
  - **Option A (đơn giản, khớp contract §3c.6)**: KHÔNG optimistic content/editedAt ngay. Chỉ set marker "đang saving" (ví dụ `editStatus='saving'`). ACK về → patch content mới từ ack.message. ERROR về → chỉ set failureCode, content giữ nguyên bản cũ. **Không cần lưu originalContent.**
  - **Option B**: Optimistic content mới, nhưng lưu `originalContent + originalEditedAt` vào `editTimerRegistry.set(clientEditId, {timerId, messageId, convId, originalContent, originalEditedAt})`. ERROR/TIMEOUT → handler đọc từ registry → `patchMessageById(…, {content: originalContent, editedAt: originalEditedAt})`. ACK → chỉ clear registry.
- **Quy tắc**: Option A ưu tiên trừ khi UX đòi hỏi instant visual feedback (hiếm cho edit).
- **BLOCKING nếu vi phạm**: optimistic content ghi đè không có path revert = cache lệch DB = drift nghiêm trọng (đặc biệt MSG_NOT_FOUND / TIMEOUT — DB state không đổi nhưng UI nghĩ đã đổi).
- **Ngày**: 2026-04-20 (W5-D2 review, BLOCKING issue detected)

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
- **FileAuth service tách riêng khỏi FileService (W6-D2 APPROVED)**: `FileAuthService.findAccessibleById(fileId, userId)` trả `Optional<FileRecord>` — controller quyết định error code. Rule: uploader OR conv-member (JPQL `existsByFileIdAndConvMemberUserId` JOIN `message_attachments → messages → conversation_members`). Anti-enum: mọi not-access/not-found/expired/cleanup-deleted → empty → 404. Pattern này tách auth rule khỏi business logic, reuse giữa `GET /api/files/{id}` và `GET /api/files/{id}/thumb`. Xem `FileAuthService.java` + `MessageAttachmentRepository.existsByFileIdAndConvMemberUserId` (W6-D2).
- **@Scheduled cleanup job pattern (W6-D3 APPROVED)**: 6 thành phần BẮT BUỘC khi viết cleanup background job:
  (1) `@EnableScheduling` trên `@SpringBootApplication` class — không có thì @Scheduled silent ignore.
  (2) Cron format Spring 6 = 6 fields `second minute hour day month weekday` (NOT 5 như Unix cron). VD `0 0 3 * * *` = 3:00:00 AM mỗi ngày, `0 0 * * * *` = đầu mỗi giờ.
  (3) Externalize cron qua `${ENV_VAR:default}` trong `application.yml` — ops có thể tune mà không re-deploy.
  (4) `@ConditionalOnProperty(name="app.<feature>.enabled", havingValue="true", matchIfMissing=true)` trên class — disable bean qua flag duy nhất. Test profile dùng `enabled=true` + `cron="-"` (Spring's "disabled" trigger value): bean load để inject + gọi method trực tiếp, scheduler KHÔNG fire trong test.
  (5) Batch pagination loop: `findBy...(threshold, PageRequest.of(0, BATCH_SIZE))` luôn page 0 (records sau xử lý sẽ rời khỏi predicate → page 0 lần sau chứa records mới). Terminate khi `batch.isEmpty() || batch.getNumberOfElements() < BATCH_SIZE`. Tránh OOM khi cleanup nhiều records.
  (6) Per-record try-catch: 1 file fail KHÔNG kill cả job. Sau exception expired-job vẫn `setExpired(true)` defensive để không bị query lại vô hạn (nested try-catch nếu cả save fail). Counter `deleted/skipped/errors` log cuối job. Xem `FileCleanupJob.java` (W6-D3).
- **stillAttached graceful flow cho expired file (W6-D3 APPROVED)**: Khi file expired nhưng vẫn còn attachment trong message_attachments — KHÔNG hard-delete DB (FK constraint hoặc đánh mất audit trail). Pattern: physical `storage.delete()` TRƯỚC → check `existsByIdFileId` → nếu attached: `setExpired(true) + save` (DB record giữ); GET /api/files/{id} → `openStream()` → `StorageException` → controller catch → `AppException(404, "FILE_PHYSICALLY_DELETED", ...)`. FE thấy 404 thay vì 500. Áp dụng cho cả `download` lẫn `downloadThumb`. `LocalStorageService.delete()` dùng `Files.deleteIfExists()` — idempotent, race với 2nd job-run không throw. Lưu ý: `StorageException` (không phải `IOException`) vì `FileService.openStream` wrap I/O exception.
- **Multi-instance @Scheduled note (V2 enhancement, document)**: V1 single BE instance (ADR-015 SimpleBroker). Khi scale V2 multi-instance: 2 instance cùng chạy `@Scheduled` cùng lúc → risk double-delete + StaleObjectStateException. V2 fix Redis SETNX:
  ```java
  Boolean acquired = redis.setIfAbsent("lock:file-cleanup:expired", instanceId, Duration.ofMinutes(30));
  if (Boolean.TRUE.equals(acquired)) { try { /* logic */ } finally { redis.delete(lockKey); } }
  ```
  TTL 30 phút > worst-case job duration. **Reviewer rule**: mỗi `@Scheduled` mới thêm vào codebase phải có note này trong `WARNINGS.md` V2 bucket khi review.
- **Fail-open thumbnail (W6-D2 APPROVED)**: Upload flow try-catch BỌC quanh `thumbnailService.generate()` — thumb fail log WARN, `thumbnail_internal_path=null`, upload vẫn 201. FileDto.thumbUrl = null → FE hiện full-size hoặc placeholder. Rule pattern: feature phụ (thumb, rate-limit, blacklist) KHÔNG được block hot-path khi fail. Xem ADR-020 + `FileService.upload` step 6.
- **N+1 MessageMapper + V2 note (W6-D2 documented)**: `MessageMapper.loadAttachmentDtos` query 1 JOIN + N query `findById` per file. Cho page 50 message × 5 attach = 250 queries worst-case. V1 acceptable (traffic thấp + Hibernate 2nd cache + list-message không phải hot path). V2 optimize: `@EntityGraph(attributePaths="attachments")` trên repo method hoặc JOIN FETCH query. **Reviewer rule**: khi thấy N+1 trong mapper → DOCUMENT trên class javadoc + AD-item trong WARNINGS.md với V2 plan, không BLOCKING nếu không phải hot path.
- **Content XOR Attachments (W6-D1 + W6-D2 APPROVED)**: `validateStompPayload` check `hasContent || hasAttachments` — cả 2 rỗng → `MSG_NO_CONTENT`. DB column `content` NOT NULL → service persist empty string khi attachment-only message. FE dùng `attachments.length > 0` render image bubble, content="" không visible. Xem ADR-021 + `MessageService.validateStompPayload`.
- **Soft-delete strip content + attachments (W5-D3 + W6-D1 APPROVED)**: `MessageMapper.toDto` khi `deletedAt != null` → content=null + `attachments=[]`. Applied trung tâm ở mapper → REST + ACK + broadcast TẤT CẢ path đều strip. Receiver thấy placeholder "🚫 Tin nhắn đã bị xóa", không còn URL attachment trong DTO → không trigger thumb download từ cache. Xem ADR-022 + `MessageMapper.toDto` line 77.
- `validateTokenDetailed()` trả enum VALID/EXPIRED/INVALID thay vì boolean. Tách biệt expired vs invalid để trả error code đúng cho FE.
- `GlobalExceptionHandler` + `AppException` — business exception pattern. Mọi business error throw `AppException(HttpStatus, errorCode, message)`, handler convert sang `ErrorResponse`.
- **Refresh token SHA-256 hash vào Redis (W2D2 APPROVED)**: `hashToken()` dùng MessageDigest SHA-256 + Base64. Lưu hash chứ không raw token vào Redis key `refresh:{userId}:{jti}`. Lý do: nếu Redis bị compromise, hash không dùng để forge. Khi /refresh, compare bằng cách hash lại token FE gửi và so sánh.
- **User enumeration protection (W2D2 APPROVED)**: `findByUsername(...).orElse(null)` + cùng 1 nhánh throw `AUTH_INVALID_CREDENTIALS` cho user-not-found và wrong-password. Cùng error code + cùng message "Tên đăng nhập hoặc mật khẩu không đúng". Check account status (AUTH_ACCOUNT_LOCKED) chỉ SAU khi verify credentials đúng — không tiết lộ "username tồn tại" qua timing khác nhau.
- **Client IP extraction pattern**: `X-Forwarded-For` header split(",")[0].trim() với fallback `getRemoteAddr()`. Chấp nhận vì reverse proxy sẽ prepend client IP. Caveat: chưa sanitize IP format (xem Vấn đề thường gặp BE).
- **Transactional broadcast pattern (W4D4 APPROVED)**: Tách 3 component — (1) `@Component Mapper` tách DTO mapping (Singleton, stateless), (2) `record Event(id, dto)` — immutable, dùng Spring `ApplicationEventPublisher`, (3) `@Component Broadcaster` với `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` + try-catch toàn bộ (broadcast fail KHÔNG propagate → REST 201 đã trả về không thể rollback). Service trong `@Transactional` gọi `eventPublisher.publishEvent(event)` → Spring register synchronization → chỉ fire listener sau commit thật sự. Mapper dùng chung cho REST response và broadcast payload → đảm bảo shape IDENTICAL. Test: 2 unit isolated (mock SimpMessagingTemplate) + integration test với `@MockBean SimpMessagingTemplate` verify `convertAndSend(destination, envelope)` + `never()` khi REST fail + `doThrow()` để test broker down REST vẫn 201. Xem `MessageBroadcaster.java` + `MessageMapper.java` + `MessageCreatedEvent.java` (W4D4).
- **Ephemeral event pattern (W5-D1 APPROVED)**: Event real-time non-critical (typing, presence, read receipt) — NO DB persist, NO entity, NO event publisher (không cần transaction + AFTER_COMMIT). Handler nhận STOMP frame → validate member (defense-in-depth) → rate limit (Redis INCR + EX) → load minimal user info → `messagingTemplate.convertAndSend("/topic/conv.{id}", {type, payload})` trực tiếp. Fail-open rate limit khi Redis down (event không critical). Shape payload có `userId + username + conversationId` (KHÔNG fullName — FE lookup từ members cache nếu cần). Test mock SimpMessagingTemplate, capture envelope, verify `never()` cho non-member + rate limit case. Xem `ChatTypingHandler.java` + `TypingRateLimiter.java` (W5-D1).
- **Silent drop pattern cho ephemeral event (W5-D1 APPROVED concept, needs AuthChannelInterceptor split)**: Non-critical event (typing) khi fail auth / rate limit / user-not-found → log DEBUG/WARN nhưng KHÔNG throw → NO ERROR frame tới client. Lý do: (1) non-critical, client không cần biết; (2) ERROR frame với code lạ có thể trigger FE reconnect loop (nếu onStompError không whitelist code). **CAVEAT**: `AuthChannelInterceptor.handleSend` hiện throw FORBIDDEN cho mọi `/app/conv.*` non-member → conflict với silent drop. Fix: split destination trong interceptor — `.message` strict (throw), `.typing` / `.read` / `.presence` permissive (pass, handler tự silent drop). Điều này giữ defense-in-depth cho hot-path (message) trong khi cho event non-critical chạy qua handler.

### FE patterns
- **Blob URL cleanup ref-based (W6-D4 APPROVED)**: Mọi `URL.createObjectURL(file)` PHẢI có path `URL.revokeObjectURL` ở 4 điểm: (1) cancel upload (axios.isCancel branch), (2) remove pending từ UI, (3) clear all (loop), (4) component unmount. Unmount cleanup BẮT BUỘC dùng pattern `const pendingRef = useRef<...>(); pendingRef.current = pending;` → useEffect return loop `pendingRef.current` — NẾU đọc `pending` state trực tiếp trong cleanup → stale closure (snapshot lúc mount, không thấy items thêm sau). Reuse cho audio/video/PDF preview, EventSource, WebSocket có dynamic state. Xem `useUploadFile.ts` (W6-D4).
- **FormData Content-Type undefined (W6-D4 APPROVED)**: axios + multipart upload MUST set `headers: { 'Content-Type': undefined }`. KHÔNG omit headers field (axios interceptor có thể inject `application/json` default → BE parse fail). KHÔNG hardcode `'multipart/form-data'` (thiếu boundary → BE multipart parser không tách parts được → 400 hoặc empty file). Comment trong code BẮT BUỘC giải thích vì sao undefined. Reviewer rule: thấy `multipart/form-data` hardcode trong axios → BLOCKING. Xem `useUploadFile.ts:73` (W6-D4).
- **AbortController native cho cancel upload (W6-D4 APPROVED)**: axios v1+ deprecated CancelToken (still works nhưng warn). Mọi cancel-able request mới dùng: `const controller = new AbortController(); api.post(url, body, { signal: controller.signal })`. Cancel: `controller.abort()`. Catch silent: `if (axios.isCancel(err)) { /* filter, không toast */ return }`. Lưu controller vào pending item state để hỗ trợ multi-concurrent cancel độc lập từng upload. Reviewer rule: thấy `axios.CancelToken.source()` trong code mới → REQUEST CHANGES, migrate sang AbortController.
- RHF + zodResolver + mode:'onTouched' — validate khi blur, không mỗi keystroke. Ít re-render.
- isRefreshing flag + failedQueue[] — refresh queue pattern cho axios. Chỉ 1 request gọi /refresh, số còn lại queue.
- **tokenStorage.ts pattern** (W-FE-2 RESOLVED): module in-memory trung gian không import api.ts, phá circular dep api.ts <-> authStore.ts. authStore.setAuth() và clearAuth() sync 2 chiều với tokenStorage trong cùng action (sync trước set() Zustand để không có async gap). onRehydrateStorage chỉ restore refreshToken vào tokenStorage (accessToken không persist theo ADR-003).
- **STOMP subscription hook pattern (W4D4 APPROVED)**: `useConvSubscription(convId)` — useEffect dep `[convId, queryClient]`. Bên trong: (1) local ref `cleanup: (() => void) | null` lưu `sub.unsubscribe` fn, (2) inner `subscribe()` check `client?.connected` trước khi sub, (3) gọi `subscribe()` ngay lần đầu để bắt kịp khi đã connected, (4) `onConnectionStateChange` listener re-subscribe khi state → CONNECTED (cleanup sub cũ trước để tránh duplicate handler), cleanup khi DISCONNECTED/ERROR để tránh treo sub vào client dead, (5) effect teardown gọi `cleanup?.() + unsubState()`. Dedupe bằng `some(m => m.id === newMsg.id)` cross all pages của infinite cache (sender nhận lại broadcast của chính mình sau khi REST onSuccess đã set cache). Append vào page cuối (items sorted ASC). Invalidate `['conversations']` để sidebar refresh `lastMessageAt`. Xem `useConvSubscription.ts` (W4D4).
- **sockjs-client global shim belt+suspenders (W4D4 APPROVED)**: `main.tsx` runtime `window.global = window` + `vite.config.ts` `define: { global: 'globalThis' }`. Cả 2 cùng có là thừa 1 chút nhưng acceptable — define là build-time replace, shim là runtime fallback, bảo vệ khỏi edge case khi SockJS đọc `global` trong code đã qua bundler. Non-blocking, giữ pattern này cho projects dùng sockjs-client trong browser.
- **Typing indicator hook pattern (W5-D1 APPROVED)**: `useTypingIndicator(convId)` trả `{ typingUsers, startTyping, stopTyping }`. 3 timers riêng:
  (1) **debounceTimerRef**: publish START tối đa 1/2s — set sau khi publish, clear=null sau 2s.
  (2) **autoStopTimerRef**: tự publish STOP sau 3s im lặng — reset mỗi lần startTyping gọi (user đang tiếp tục gõ).
  (3) **autoRemoveTimersRef Map<userId, timerId>**: safety net — mỗi user nhận TYPING_STARTED có 1 timer 5s auto-remove phòng STOPPED bị miss (sender crash/network). Clear timer khi TYPING_STOPPED về hoặc khi new STARTED (re-trigger).
  Skip self (`user.userId === currentUserId`), subscribe chung topic `/topic/conv.{id}` (cùng topic với MESSAGE_CREATED). Cleanup tất cả 3 ref trong useEffect return. Clear typingUsers khi state DISCONNECTED/ERROR. Xem `useTypingIndicator.ts` (W5-D1).

---

## Rejected patterns (đã review và từ chối, không dùng)

*(Format: pattern là gì → tại sao từ chối → dùng gì thay thế)*

- (chưa có)

---

## W6 Security Patterns (learned)

*(Tóm tắt 7 pattern security/file-handling rút ra từ W6 audit. Mỗi pattern có 1-2 dòng, link đến file implementation và ADR liên quan.)*

1. **Magic bytes MIME validation (Tika)**: `tika.detect(InputStream)` không trust `Content-Type` header. Tika chỉ đọc ~8KB magic. `text/plain` cần strip charset suffix `split(";")[0]`. Xem `FileValidationService.java` (W6-D1) + W6-2 RESOLVED.

2. **ZIP→Office override**: DOCX/XLSX/PPTX là ZIP container → Tika có thể detect `application/zip`. Override MIME bằng extension hint CHỈ KHI Tika trả `application/zip` (không skip whitelist check sau đó). Xem `FileValidationService.validate()` step 3 (W6-D4-extend).

3. **Path traversal defense**: `LocalStorageService` canonical path check (`normalize() + toAbsolutePath() + startsWith(basePath)`). Internal filename = UUID (không user-controlled). `sanitizeFilename` chỉ dùng cho `Content-Disposition`, không dùng cho storage path. Xem `LocalStorageService.assertWithinBase()` (W6-D1) + W6-1 RESOLVED + ADR-019.

4. **FileAuthService anti-enumeration**: uploader OR conv-member, 404 cho tất cả non-access cases (not-found / expired / cleanup-deleted / non-member). Tách auth rule khỏi business logic, reuse cho download + thumb endpoints. Xem `FileAuthService.findAccessibleById()` (W6-D2) + ADR-019.

5. **`useProtectedObjectUrl` (FE)**: `api.get({ responseType: 'blob', signal })` → blob URL. Cleanup: `controller.abort() + URL.revokeObjectURL(currentUrl)` trong useEffect return. Pattern: KHÔNG dùng `<img src>` trỏ thẳng `/api/files/...` (Bearer token không gửi qua browser native fetch). Xem `useProtectedObjectUrl.ts` (W6-D4) + AD-30 (V2 signed URLs).

6. **stillAttached handling**: physical delete TRƯỚC, check attachment, set `expired=true` + save DB (KHÔNG hard delete record vì message history sẽ mất attachment ref → FE thấy "📎 [tệp đã hết hạn]" thay vì 500). Xem `FileCleanupJob.cleanupExpiredFiles()` (W6-D3) + W6-4 RESOLVED.

7. **iconType server-computed**: MIME → iconType (8 enum values: IMAGE/PDF/WORD/EXCEL/POWERPOINT/TEXT/ARCHIVE/GENERIC) trong BE. FE không duplicate MIME → icon map (đỡ phải sửa 2 nơi khi extend). `GENERIC` là fallback an toàn (cover null + unknown). Xem `FileService.resolveIconType()` (W6-D4-extend).

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
| 2026-04-19 | v0.5.1-conversations | POST /api/conversations rate limit đổi "30/giờ" → "10/phút/user" (W3D3 implement) để khớp code. Rate limit error kèm `details.retryAfterSeconds` lấy từ Redis TTL thực. |
| 2026-04-19 | v0.5.2-conversations | Thêm `GET /api/users/{id}` (W3D4). Dùng lại `UserSearchDto` shape (id, username, fullName, avatarUrl — không expose email/status/lastSeenAt). 404 `USER_NOT_FOUND` merge cả not-exist và status!='active' để chống enumeration (pattern giống CONV_NOT_FOUND). Documented V4 migration thêm `last_seen_at` column nhưng KHÔNG expose V1 (AD-9). |
| 2026-04-19 | v0.6.0-messages-rest | Thêm Messages API (W4D1): POST `/api/conversations/{convId}/messages` (gửi tin nhắn, validation 1-5000 chars, reply phải thuộc đúng conv) + GET cursor-based pagination (items ASC, nextCursor = createdAt cũ nhất, hasMore detect bằng limit+1 query). Rate limit 30/min/user (Redis INCR fail-open). Anti-enumeration 404 CONV_NOT_FOUND cho non-member. ReplyPreviewDto shallow 1-level (không recursive). Soft-delete via `deleted_at`. Reply tới soft-deleted message KHÔNG bị block V1 (AD-12, defer Tuần 6). |
| 2026-04-19 | SOCKET v1.0-draft-w4 | Draft SOCKET_EVENTS.md W4 (W4D2): chọn model REST-gửi + STOMP-broadcast (ADR-014), không dùng tempId inbound. Destination `/topic/conv.{convId}` broadcast MESSAGE_CREATED với payload = MessageDto shape IDENTICAL REST response (BẮT BUỘC reuse cùng MessageMapper). Auth JWT ở CONNECT frame, member check ở SUBSCRIBE. BE implement bằng `@TransactionalEventListener(AFTER_COMMIT)` (không broadcast trước commit). FE dedupe bằng message id. Security: size limit 64KB, origin từ config không "*", heartbeat 10s. Placeholder events W5/W6 (TYPING, PRESENCE, MESSAGE_UPDATED/DELETED). Limitations V1: SimpleBroker in-memory (ADR-015), offline catch-up qua REST cursor, at-most-once delivery. Pending BE implement W4D3, FE implement W4D4. |
| 2026-04-20 | SOCKET v1.3-w5d2 | **Edit Message STOMP (W5-D2) + Unified ACK (ADR-017)**. Thêm inbound `/app/conv.{convId}.edit` payload `{clientEditId, messageId, newContent}`. Fill §3.2 MESSAGE_UPDATED (trước placeholder W6, nay dời W5). ACK shape cũ `{tempId, message}` BREAKING migrate sang `{operation: "SEND"|"EDIT", clientId, message}` — BE + FE deploy đồng bộ. ERROR shape cũng unified `{operation, clientId, error, code}`. Error codes mới: `MSG_NOT_FOUND`, `MSG_EDIT_WINDOW_EXPIRED`, `MSG_NO_CHANGE`. Dedup Redis `msg:edit-dedup:{userId}:{clientEditId}` TTL 60s. Rate limit 10 edit/min via `rate:msg-edit:{userId}`. FE state machine idle→editing→saving→saved/error với timer 10s. Destination `.edit` vào §7.1 policy table (STRICT_MEMBER). Limitation: clock skew edit window (FE disable sớm 4:50 thay vì 5:00) + unified queue multi-session caveat (FE tab-awareness qua editTimerRegistry). Broadcast MESSAGE_UPDATED minimal payload (id + conversationId + content + editedAt). FE dedup theo editedAt timestamp. |
| 2026-04-20 | API v0.6.1-messages-stomp-shift | POST `/api/conversations/{convId}/messages` marked **deprecated** cho FE hot path (ADR-016). Endpoint KHÔNG xoá — giữ cho batch import / bot API / integration test / fallback. Shape response không đổi. |

---

## Changelog file này

- 2026-04-19 (Ngày 4): Điền ADR-001 đến ADR-004. Thêm FE review standards từ Phase 3B review. Điền contract changelog. Thêm approved patterns BE + FE.
- 2026-04-19 (W2D1): Mark W-BE-3 RESOLVED (AuthMethod enum). Mark W-FE-2 RESOLVED (tokenStorage pattern, globalThis removed). Thêm tokenStorage.ts vào approved FE patterns. Note warning post-rehydrate auth flow.
- 2026-04-19 (W2D3.5): Review POST /api/auth/refresh. Thêm ADR-006 (Refresh Token Rotation + Reuse Detection). Thêm Security review standards (constant-time compare, DELETE-before-SAVE, revoke-all-on-reuse, log format, error-code leak). Contract v0.2.1-auth → v0.3.0-auth. Ghi nhận 2 contract sync items cần FE/BE align (reuse error code + rate limit value).
- 2026-04-19 (W2D4): Review POST /api/auth/oauth + POST /api/auth/logout. Thêm ADR-007 (OAuth Auto-Link by Email). Thêm 4 security standards mới (Firebase SDK verify bắt buộc, email_verified check điều kiện, blacklist TTL = remaining token TTL, blacklist-check-trước-setSecurityContext, fail-open Redis trade-off documented). Contract v0.3.0-auth → v0.4.0-auth-complete. Auth foundation Tuần 2 COMPLETE: register/login/refresh/oauth/logout đều implement + review xong.
- 2026-04-19 (W3D1): Review V3 migration + Conversation entities/repositories + FE ProtectedRoute refactor + ConversationsLayout skeleton. APPROVE WITH COMMENTS (2 warning non-blocking: insertable=false trên @GeneratedValue UUID có thể lỗi Hibernate INSERT; CHECK constraint role `'MEMBER'` default trong SQL nhưng entity `@Builder.Default` = MEMBER → OK đồng bộ). Draft Conversations contract v0.5.0. Thêm ADR-012 (UPPERCASE enum). W-C-4 xác nhận RESOLVED.
- 2026-04-19 (W3D2): Review BE 4 endpoints Conversations (POST, GET list, GET detail, GET users/search) + FE API scaffold. REQUEST CHANGES — 2 BLOCKING ở FE: (1) `api.ts` đọc `.code` thay vì `.error` field của ErrorResponse → 409 CONV_ONE_ON_ONE_EXISTS handler sẽ luôn throw; (2) `types/conversation.ts` define `ConversationDto` có 4 field displayName/displayAvatarUrl/unreadCount/mutedUntil không có trong BE response (chỉ có ở SummaryDto) — runtime access sẽ undefined. Thêm ADR-013 (race ONE_ON_ONE acceptable V1, no lock). Mark W3-BE-1 RESOLVED (UUID @PrePersist Option B trong Conversation + ConversationMember; test assertion confirm). Thêm 2 contract-drift-patterns mới vào knowledge (error field name; Summary vs Detail shape). 5 BE warning non-blocking log vào WARNINGS.md nếu chưa có (dedupe memberIds, enforce max 49, rate limit TODO, N+1 batch load, UserController cross-package).
- 2026-04-19 (W2 Final Audit): Audit cuối Tuần 2 trước tag `v0.2.0-w2`. Formalize 4 ADR còn implicit: ADR-008 (HS256 + jjwt 0.12.x), ADR-009 (Redis key schema), ADR-010 (AuthMethod enum), ADR-011 (Fail-open blacklist trade-off). Tạo `docs/WARNINGS.md` tổng hợp 5 pre-production items (W-BE-4 race existsBy→save, W-BE-5 null passwordHash guard, W-BE-6 X-Forwarded-For sanitize, W-BE-7 fail-open monitoring, W-BE-8 generateUniqueUsername race), 8 documented-acceptable, 6 cleanup-tuần-8, 7 tech-debt-nhỏ. Controller audit: 5 auth endpoints + 1 health, 0 drift so với contract v0.4.0-auth-complete. 1 orphan TODO: `useAuth.ts:29` "TODO Tuần 2 call logout API" — logout đã implement nơi khác (HomePage.tsx), TODO lỗi thời, map vào CL-1.
- 2026-04-19 (W3D4): Review ConversationDetailPage + BE `GET /api/users/{id}` + migration V4 add `last_seen_at` + JwtAuthFilter update last_seen debounce 30s. APPROVE WITH COMMENTS (0 blocking). Contract v0.5.1 → v0.5.2-conversations (thêm GET /api/users/{id}). 5 entries vào WARNINGS.md: AD-9 (last_seen_at column có nhưng không expose V1 — privacy), AD-10 (JwtAuthFilter save full entity → lost-update window), TD-8 (MethodArgumentTypeMismatchException chưa map 400), TD-9 (error state không có retry button, V1 acceptable — React Query tự retry 3 lần), TD-10 (unused currentUser param). Không ADR mới. FE checklist pass hết: useConversation(id) với `enabled: !!id`, React Query key đổi khi id URL đổi → auto refetch; MessageInput `disabled` prop + `onSend?` optional ready cho W4; skeleton không flicker; 404 vs generic error tách rõ.
- 2026-04-19 (W4D2): Overwrite `docs/SOCKET_EVENTS.md` skeleton → v1.0-draft-w4. Chốt model REST-gửi + STOMP-broadcast cho Tuần 4 (không dùng tempId inbound — ADR-014, khác ARCHITECTURE.md mục 5 gốc). Chốt SimpleBroker V1 → RabbitMQ V2 migration trigger (ADR-015). Destination `/topic/conv.{convId}` + `MESSAGE_CREATED` event envelope `{type, payload}`. Payload IDENTICAL MessageDto REST response (bắt buộc reuse MessageMapper). BE implementation guide: `WebSocketConfig` (SockJS + SimpleBroker + allowedOrigins từ config), `AuthChannelInterceptor` (JWT ở CONNECT, member check ở SUBSCRIBE), `MessageBroadcaster` dùng `@TransactionalEventListener(AFTER_COMMIT)` (BLOCKING: không broadcast trước commit). FE implementation guide: `stompClient.ts` singleton, `useConvSubscription(convId)` hook với dedupe bắt buộc bằng message id + cleanup unsubscribe. Security: size limit 64KB, origin config-driven, heartbeat 10s. Known limitations V1: in-memory SimpleBroker, offline miss broadcast (catch-up qua REST cursor), at-most-once delivery, member check chỉ khi SUBSCRIBE. Placeholder W5 (TYPING, PRESENCE, READ_RECEIPT) + W6 (MESSAGE_UPDATED/DELETED). Checklist W4-D3 (BE) + W4-D4 (FE) đã liệt kê trong contract mục 10. Không có code thay đổi.
- 2026-04-20 (W5-D1): Review Typing Indicator (BE ChatTypingHandler + TypingRateLimiter + FE useTypingIndicator + TypingIndicator). REQUEST CHANGES — 1 BLOCKING architectural: `AuthChannelInterceptor.handleSend` throw FORBIDDEN cho mọi `/app/conv.*` non-member, conflict với spec silent-drop cho typing. Fix option (A) split destination trong interceptor — `.message` strict, `.typing/.read` permissive. Contract §3.4 KHÔNG thay đổi (payload giữ userId+username+conversationId, KHÔNG thêm fullName — FE lookup từ cache members nếu cần). 4 warnings non-blocking: FE stopTyping order, BE per-event user load, TypingRateLimiter no metric, useTypingIndicator dep currentUserId OK. Edge cases verified: 2-tab user skip self (OK), sender close tab → 5s auto-remove safety. 2 approved patterns mới: Ephemeral event pattern (typing/presence/read) + Silent drop pattern (non-critical event không throw ERROR). Tests 4/4 pass. Không thay đổi contract version.
- 2026-04-20 (W4D4): Review realtime broadcast wire (BE `MessageBroadcaster` + `MessageMapper` + `MessageCreatedEvent` + FE `useConvSubscription`). APPROVE (0 BLOCKING). Toàn bộ BLOCKING checklist pass: `@TransactionalEventListener(AFTER_COMMIT)` đúng, try-catch toàn bộ không propagate, envelope `{type:"MESSAGE_CREATED", payload}` đúng case, FE `sub.unsubscribe()` trong cleanup (cả state-listener path và effect teardown), dedupe `m.id === newMsg.id` cross all pages, invalidate `['conversations']`, MessageMapper reuse giữa REST và broadcast (shape IDENTICAL đạt Rule vàng). Test coverage tốt: T14 (destination + envelope), T15 (broadcaster NOT called khi validation fail), T16 (broker throws → REST 201 vẫn OK + message persisted) + 2 unit isolated broadcaster. Thêm 2 approved patterns: "Transactional broadcast pattern" (BE) + "STOMP subscription hook pattern" (FE) + "sockjs-client global shim belt+suspenders" (FE). Không thay đổi contract, gợi ý chuyển SOCKET_EVENTS.md "v1.0-draft-w4" → "v1.0-w4" cuối W4 khi bỏ draft suffix.
- 2026-04-20 (W5-D2): Review Edit Message STOMP + Unified ACK (ADR-017). REQUEST CHANGES — 1 BLOCKING ở FE: `useEditMessage.ts` optimistic ghi đè `content + editedAt` không có path revert khi ERROR/TIMEOUT → cache lệch DB (MSG_NOT_FOUND/WINDOW_EXPIRED/NO_CHANGE). BE 130/130 tests pass. BE checklist hoàn hảo: SETNX `msg:edit-dedup:*` TRƯỚC save (race-safe), anti-enum MSG_NOT_FOUND (null/wrong-conv/not-owner/deleted merge), edit window 300s check qua Duration.between server UTC, MessageMapper reuse REST+ACK+broadcast, ACK + broadcast AFTER_COMMIT qua TransactionSynchronization + @TransactionalEventListener, AckPayload + ErrorPayload unified shape. SEND path `MessageStompController` đã migrate sang `operation="SEND"`. `AuthChannelInterceptor.resolveSendPolicy` thêm `.edit → STRICT_MEMBER`. FE checklist 90% pass: `switch(operation)` unified đúng, timer clear 3 branch, `canEdit` 290s, `editTimerRegistry.clearAll()` trong logout, `patchMessageById` tách khỏi `patchMessageByTempId`, broadcast dedup theo `editedAt`, tab-awareness qua registry entry check. Chỉ fail: rollback content khi ERROR. Thêm ADR-016 (STOMP-send Path B) + ADR-017 (unified ACK) vào knowledge. Thêm 2 review standards: anti-enumeration pattern (merge NOT_FOUND) + optimistic-edit-rollback (option A vs B). Contract unchanged (v1.3-w5d2). Fix đề xuất option A: bỏ optimistic content + editedAt, chỉ mark saving, chờ ACK patch thật.
- 2026-04-20 (W5-D3): Review Delete Message + Facebook-style UI. APPROVE WITH COMMENTS (0 BLOCKING). 140/140 BE tests pass, FE tsc+eslint clean. V6 migration `deleted_by UUID NULL` (SET NULL). `deleteViaStomp` 8-step flow: validate → rate limit → SETNX dedup `msg:delete-dedup:*` → load+anti-enum → markAsDeletedBy → save → update dedup → ACK+broadcast AFTER_COMMIT. ACK minimal raw Map `{operation:"DELETE", clientId, message:{id, conversationId, deletedAt, deletedBy}}` — khác EDIT full DTO. `MessageMapper.toDto` strip `content=null` khi deletedAt != null áp dụng TRUNG TÂM (REST + ACK + broadcast). Anti-enum 4 case merge MSG_NOT_FOUND. Edit-after-delete regression guard. `AuthChannelInterceptor.resolveSendPolicy` thêm `.delete → STRICT_MEMBER`. FE Option A: KHÔNG optimistic deletedAt, chỉ `deleteStatus='deleting'` + opacity-50. ACK patch từ server → cache không lệch DB (áp dụng bài học W5-D2). `deleteTimerRegistry` module singleton, `clearAll()` trong logout. Facebook-style hover MessageActions (reply + More menu). DeletedMessagePlaceholder "🚫 Tin nhắn đã bị xóa". Thêm ADR-018 (Delete unlimited window, khác Edit 5 phút). Thêm 2 patterns: Minimal ACK raw Map + Defensive re-ACK check. Contract SOCKET §3.3 + §3d + §3e + §7.1 khớp. 3 warnings non-blocking (status tick UX, timeout toast, duplicate log) → WARNINGS.md.
- 2026-04-20 (Consolidation cuối W5-D3): Rotate `reviewer-log.md` 1407 → 467 dòng (giữ verdict + blocking + key decisions + patterns, bỏ file:line + checklist verify). Restructure `docs/WARNINGS.md` thành 4 section rõ (Pre-production / Acceptable V1 / Cleanup / V2 Enhancement + Resolved). Thêm AD-14 (clock skew), AD-15 (dedup TTL reset), AD-16 (reply deleted), AD-17 (catch-up limit), AD-18 (timerRegistry fail-open) + W5-D3 cleanup items. Thêm ADR-018 (Delete unlimited window) vào knowledge.
- 2026-04-21 (W6-D3): Review `FileCleanupJob` (expired + orphan @Scheduled). APPROVE (0 BLOCKING). 197/197 tests pass. Thêm 3 approved patterns BE: (1) @Scheduled cleanup job pattern (6-field cron Spring 6, ConditionalOnProperty disable, batch page-0 loop, per-record try-catch); (2) stillAttached graceful flow (physical delete → DB mark expired → GET /files/{id} → StorageException → 404 graceful via FileController catch); (3) Multi-instance @Scheduled V2 note (Redis SETNX distributed lock pattern, reviewer rule mọi @Scheduled mới phải có note V2 trong WARNINGS.md). Recommend orchestrator gọi BE add 1 dòng V2 multi-instance lock vào `docs/WARNINGS.md` V2 Enhancement bucket (chưa có, đã document trong backend-knowledge và reviewer-knowledge).
- 2026-04-21 (W6-D4): Review FE File Upload UI + Attachment Display. APPROVE (0 BLOCKING). Build clean (`npm run build` zero errors, 2 pre-existing warnings OK). 5 file FE modified + 1 folder mới `features/files/` (useUploadFile + validateFiles + 3 component PendingAttachmentItem/AttachmentGallery/PdfCard). 9/9 BLOCKING checklist PASS: AbortController native, revokeObjectURL 4 điểm (cancel/remove/clear/unmount via pendingRef), Content-Type undefined cho FormData, optimistic `attachments: []`, RetryButton truyền `attachmentIds: []`, guard uploading toast, send disable đúng (errors-only KHÔNG enable), DragLeave currentTarget.contains check, StompSendPayload.attachmentIds wired. ACK error handler thêm 7 attachment error codes khớp 100% SOCKET_EVENTS.md (MSG_ATTACHMENT_NOT_FOUND/NOT_OWNED/ALREADY_USED/EXPIRED, MSG_ATTACHMENTS_MIXED/TOO_MANY, MSG_NO_CONTENT). Thêm 3 FE approved patterns: Blob URL cleanup ref-based (avoid stale closure), FormData Content-Type undefined (axios + multipart), AbortController native (axios v1+ replace CancelToken). MessageItem render Messenger-style: attachment no-bubble + text caption bubble riêng. AttachmentGallery có lightbox + keyboard nav (←/→/Esc) + download. Contract unchanged (SOCKET v1.3-w5d2 + API v0.6.1).
- 2026-04-21 (W6-D5): Security audit Tuần 6 toàn diện (18 items). APPROVED — 18/18 PASS, 0 BLOCKING, 0 warnings. Verified: path traversal defense (canonical prefix + UUID filename + sanitizeFilename chỉ cho Content-Disposition), Tika magic bytes MIME validation (charset strip, ZIP→Office override gated), FileAuthService 2 rules + anti-enum 404, validateAndAttachFiles 6-rule order rẻ→đắt, Content XOR Attachments (MSG_NO_CONTENT), stillAttached graceful flow, batch pagination loop, @ConditionalOnProperty test profile cron="-", useProtectedObjectUrl cleanup (abort + revoke), no raw `<img src=/api/files`, iconType 8-value coverage. WARNINGS.md: move 3 RESOLVED W6 (W6-1/2/4), expand V2 bucket (signed URLs / Office macro / per-user quota), add AD-30 (blob URL lifecycle V2 signed URLs). Append W6 Security Patterns section (7 patterns). Memory consolidate reviewer-log 689→313 dòng. Tests BE 210/210 + FE build clean.
