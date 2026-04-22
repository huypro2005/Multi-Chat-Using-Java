# Reviewer Knowledge — Tri thức chắt lọc cho code-reviewer

> File này là **bộ nhớ bền vững** của code-reviewer.
> Khác với BE và FE, reviewer có vai trò cross-cutting → knowledge tập trung vào:
> (1) Contract đã chốt, (2) Review standard đã áp dụng, (3) Architectural decision records.
> Giới hạn: ~450 dòng (được phép dài hơn BE/FE vì bao quát toàn cục).

---

## Architectural Decision Records (ADR)

### ADR-001: JWT Strategy — Access + Refresh với rotation (W1)
- Access token 1h (JWT), Refresh token 7d (JWT), rotation mỗi /refresh.
- Rotation + Redis blacklist cho phép force logout mà không cần session. Stateless.
- Trade-off: FE phải implement refresh queue pattern để tránh race condition.

### ADR-002: BCrypt strength = 12 (W1)
- BCryptPasswordEncoder(12) cho password hashing. ~250ms hash time (~4x chậm hơn strength 10 nhưng vẫn OK cho auth flow non-hot-path).

### ADR-003: FE Auth State — persist refreshToken+user only (W1)
- Chỉ persist `refreshToken` + `user` vào localStorage, KHÔNG `accessToken` (TTL 1h — persist vô nghĩa).
- Trade-off: app phải có network call ngay load (nếu có refreshToken). Acceptable.

### ADR-004: API Error Format — `{error, message, timestamp, details}` (W1)
- `error` string machine-readable (không đổi khi i18n), `message` human-readable (có thể localize). Mọi error response đều dùng shape này.

### ADR-005: Rate limit — Redis INCR + TTL on first (W2)
- `opsForValue().increment(key)` → nếu return=1L set TTL lần đầu. Vượt ngưỡng → throw RATE_LIMITED với `details.retryAfterSeconds = getExpire(key)`.
- Login tách: `checkLoginRateLimit()` GET+compare khỏi `incrementLoginFailCounter()` INCR+EX. Chỉ tăng khi fail; success → DELETE key.
- Race window giữa increment và expire acceptable (Redis bền, PERSIST hiếm).

### ADR-006: Refresh Token Rotation + Reuse Detection (W2)
- DELETE old Redis key TRƯỚC buildAuthResponse sinh mới. Hash mismatch → detect reuse → `revokeAllUserSessions(userId)` trước throw `AUTH_REFRESH_TOKEN_INVALID`.
- Constant-time compare: `MessageDigest.isEqual` tránh timing attack.
- `revokeAllUserSessions` dùng `redisTemplate.keys("refresh:{userId}:*")` — O(N) OK cho <10 sessions V1.
- Reuse case trả `AUTH_REFRESH_TOKEN_INVALID` (không `REFRESH_TOKEN_REUSED`) — không tiết lộ "token từng tồn tại".
- V2: Redis MULTI/EXEC atomic DELETE+SAVE; SET members thay KEYS.

### ADR-007: OAuth Auto-Link by Email (W2)
- Thứ tự khi nhận Firebase ID token: (1) `user_auth_providers` by `(provider='google', providerUid)`; (2) `users` by email → AUTO-LINK; (3) không thấy → tạo mới.
- Google `email_verified=true` mặc định → an toàn auto-link. Nếu sau này thêm Facebook/Apple → PHẢI check `firebaseToken.isEmailVerified()` trước auto-link (provider không verify email → attacker chiếm tài khoản).
- Trade-off: `password_hash=null` cho user OAuth-only → `login()` phải guard null trước `passwordEncoder.matches()`.

### ADR-008: JWT algorithm = HS256 + jjwt 0.12.x (W1)
- Symmetric sign, secret từ env `JWT_SECRET`. Single-instance V1 không cần distribute public key. HS256 nhanh hơn RS256 ~10x.
- V2: multi-instance hoặc 3rd party verify → migrate RS256.

### ADR-009: Redis key schema (W2)
- 3 prefix đã chốt — KHÔNG đổi sau deploy (mất state):
  - `rate:{scope}:{id}` — counter. `rate:register:{ip}`, `rate:login:{ip}`, `rate:refresh:{userId}`, `rate:msg:{userId}`, `rate:msg-edit:{userId}`, `rate:msg-delete:{userId}`, `rate:typing:{userId}:{convId}`, `rate:msg-read:{userId}:{convId}`, `rate:file-upload:{userId}`, `rate:conv_create:{userId}`.
  - `refresh:{userId}:{jti}` — SHA-256 hash, EX 604800.
  - `jwt:blacklist:{jti}` — empty value, TTL = remaining access TTL.
  - `msg:dedup:{userId}:{tempId}`, `msg:edit-dedup:{userId}:{clientEditId}`, `msg:delete-dedup:{userId}:{clientDeleteId}` — dedup keys TTL 60s.
- V2 migrate `KEYS refresh:{userId}:*` sang SET `SADD user_sessions:{userId} jti` + SREM.

### ADR-010: AuthMethod enum (W2)
- `com.chatapp.user.enums.AuthMethod` với PASSWORD("password"), OAUTH2_GOOGLE. `generateAccessToken(User, AuthMethod)`. JWT claim `auth_method` lowercase. Fallback PASSWORD khi unknown (backward compat token cũ).

### ADR-011: Blacklist check fail-open Redis down (W2)
- `JwtAuthFilter` check `hasKey("jwt:blacklist:{jti}")` trước SecurityContext. Redis throw → LOG warn + SKIP check → token vẫn authenticate.
- Trade-off: Redis blip 30s vs logout token valid đến natural expiry (≤1h). V1 chọn availability.
- BẮT BUỘC comment `// fail-open intentional: xem ADR-011`. Không comment = bug.
- V2: circuit breaker fail-closed sau ngưỡng failure rate.

### ADR-012: Conversation enum UPPERCASE (W3)
- `type` = `ONE_ON_ONE|GROUP`, role = `OWNER|ADMIN|MEMBER` (khác ARCHITECTURE.md gốc lowercase). Khớp Java enum convention + Jackson default (không custom converter). TypeScript union `"ONE_ON_ONE"|"GROUP"`.

### ADR-013: ONE_ON_ONE idempotency — race acceptable V1 (W3)
- Giữ `@Transactional` mặc định (READ_COMMITTED). 2 requests concurrent có thể tạo 2 dup cùng cặp user (P<0.01%). Fix V2: partial UNIQUE index `(LEAST(a,b), GREATEST(a,b)) WHERE type='ONE_ON_ONE'` hoặc `pg_advisory_xact_lock`.
- Monitor: >1 dup/day production → escalate.

### ADR-014: STOMP W4 — REST-gửi + STOMP-broadcast (publish-only, no tempId inbound)
- W4 KHÔNG implement `/app/chat.send` với tempId ACK/ERROR. REST POST /messages đã có 201 confirm; STOMP `/topic/conv.{id}` broadcast MESSAGE_CREATED cho receiver.
- Bối cảnh: Thiết kế gốc ARCHITECTURE.md cho tempId lifecycle (SENDING→SENT via ACK hoặc FAILED via ERROR, timeout 10s) tăng complexity đáng kể: dedup Redis, timeout+retry, route queues, state machine FE.
- Lý do W4 chọn REST-gửi: REST đã có 201 confirm (không cần ACK riêng); receiver không cần tempId; giảm surface bug V1; socket layer vẫn cần cho typing/presence (W5) nhưng không cho send path.
- Sender cũng nhận broadcast → dedupe bằng message id (đơn giản hơn tempId vì id đã có sau REST response).
- BE broadcast PHẢI `@TransactionalEventListener(AFTER_COMMIT)` — broadcast trước commit → rollback → FE thấy message "ma". BLOCKING check.
- Trade-off: mất "fire-and-forget" STOMP cho sender. Nếu có offline queue → STOMP model gốc phù hợp hơn. V1 không offline → acceptable.
- Re-evaluation trigger: latency REST POST > 100ms p50 hoặc user complain "tin nhắn chậm" → migrate `/app/chat.send` (→ ADR-016 W5 đã chuyển).

### ADR-015: SimpleBroker V1 → RabbitMQ V2
- W4-6 dùng Spring `SimpleBroker` (in-memory) với prefix `/topic`, `/queue`. KHÔNG setup RabbitMQ/external broker. Migrate trigger: (1) scale horizontal BE >1 instance, (2) cần persistent queue offline catch-up qua broker thay vì REST polling.
- Bối cảnh: SimpleBroker tương thích multi-user single-instance, giảm infra (không container RabbitMQ). ARCHITECTURE.md scale target V1 = 1 server Singapore, <1000 concurrent — khớp.
- Lý do: infra simplicity (1 Spring Boot + PG + Redis); latency <1ms local (nhanh hơn RabbitMQ 5-10ms); không cần durable queue V1 (catch-up có REST cursor).
- Trade-off: **không scale horizontal** (2 client connect 2 instance không nhận broadcast của nhau) → BLOCKING khi scale. Mất subscribe destinations khi restart (DB OK, catch-up OK). Không ACL built-in → custom ChannelInterceptor SUBSCRIBE check (đã document SOCKET_EVENTS.md mục 7).
- Migration path V2: `config.enableStompBrokerRelay("/topic", "/queue").setRelayHost("rabbitmq")...`. Destinations không đổi → FE không cần thay.
- Monitor signal: active WS sessions >500 sustained → alert, chuẩn bị migrate.

### ADR-016: STOMP-send Path B (W5 post-W4)
- Chuyển send text từ REST sang `/app/conv.{convId}.message` với `{tempId, content, type}`. Server ACK qua `/user/queue/acks`, ERROR qua `/user/queue/errors`.
- REST `POST /messages` KHÔNG bị xoá — giữ làm fallback/batch import/bot API/integration test.
- Bối cảnh: W4D4 wire xong ADR-014 REST-gửi, đánh giá latency REST (~30-50ms overhead) + fragmented transport (REST+STOMP song song) không đáng. Path B unify + giảm latency + thống nhất FE flow cho mọi operation realtime.
- Lý do: latency STOMP <5ms vs REST 30-50ms; unified transport 1 kênh WS cho gửi+nhận; ACK/ERROR sẵn sàng cho EDIT/DELETE/REACT reuse; Redis dedup `SET NX EX` atomic trước save DB → chống race 2 frame cùng tempId (network retry); optimistic + timeout 10s client-side.
- Dedup `msg:dedup:{userId}:{tempId}` TTL 60s atomic `SET NX EX` TRƯỚC save DB.
- ACK via `TransactionSynchronizationManager.afterCommit()` — không race rollback.
- FE timeout 10s → retry với tempId MỚI (không reuse — dedup key còn sẽ re-send ACK sai).
- Trade-off: thêm complexity BE (`SendMessagePayload`, `MessageStompController`, `@MessageExceptionHandler`, dedup Redis, ACK afterCommit). Timeout cố định 10s — request slow hợp lệ >10s bị mark failed (server vẫn save → duplicate nếu user retry). V1 acceptable. Broker down (SimpleBroker restart) → REST POST 201 commit nhưng broadcast fail → FE không nhận; mitigate qua catch-up REST cursor.
- tempId PHẢI trong PAYLOAD (không STOMP header) — `@Header("tempId")` sẽ null. Service propagate tempId qua `AppException.details` để handler echo.
- **BLOCKING checks**: SET NX EX atomic (Duration trong setIfAbsent, không tách SETNX+EXPIRE); dedup TRƯỚC save DB; ACK PHẢI afterCommit (không trong @Transactional body — ACK trước rollback → client nhận ACK mà message biến mất); FE dedupe broadcast PHẢI dùng real id sau ACK replace (không tempId); FE timer clear cả 3 branch (ACK/ERROR/timeout 10s) — leak timer = memory leak; FE retry PHẢI tempId MỚI mỗi lần.

### ADR-017: Unified ACK/ERROR queue với `operation` discriminator (W5-D2)
- 1 queue `/user/queue/acks` + `/user/queue/errors` cho TẤT CẢ client-initiated (SEND, EDIT, DELETE, READ, tương lai REACT...).
- Bối cảnh: W5-D2 implement edit message cần nơi ACK. Lựa chọn: (A) tách `/user/queue/acks-edit`, `/user/queue/errors-edit`; (B) reuse queue cũ với discriminator.
- Lý do: Tránh proliferation queues khi thêm operation (DELETE→acks-delete, REACT→acks-react... → FE phải subscribe N*2 queues → N*2 listener → dễ quên cleanup). FE route qua 1 `switch` đơn giản hơn N hook per-operation. Payload shape IDENTICAL (cùng MessageDto, cùng error+code) → dễ maintain. Server không cần tạo destinations mới.
- ACK `{operation, clientId, message}`, ERROR `{operation, clientId, error, code}`. `operation` = "SEND"|"EDIT"|"DELETE"|"READ". `clientId` = UUID v4 (tên generic thay `tempId` SEND / `clientEditId` EDIT).
- FE route qua 1 `switch(operation)` trong `useAckErrorSubscription` — tab-awareness qua `{send,edit,delete}TimerRegistry.get(clientId)`: registry undefined nếu tab này không phát → ignore ACK/ERROR từ tab khác.
- Trade-off: **Breaking change** — SEND ACK cũ `{tempId, message}` → `{operation:"SEND", clientId:tempId, message}`. BE+FE deploy đồng bộ (không có backward-compat window vì chỉ FE nội bộ consume). FE phải check `operation` field trước parse; missing → default case ignore.
- Migration W5-D2: `AckPayload` record `{String operation, String clientId, MessageDto message}`; `ErrorPayload` `{String operation, String clientId, String error, String code}`. SEND path + EDIT path dùng shape mới. FE `AckEnvelope`/`ErrorEnvelope` type replace; useAckErrorSubscription `switch(operation)` routing.

### ADR-018: Delete message không giới hạn thời gian (W5-D3)
- `deleteViaStomp` KHÔNG có window check (khác editViaStomp 300s). Owner có thể xoá bất cứ lúc nào. Anti-enum `MSG_NOT_FOUND` vẫn apply (non-owner → NOT_FOUND, không FORBIDDEN).
- Bối cảnh: so sánh edit (5 phút window tránh rối history) vs delete (user muốn xoá tin cũ gửi nhầm — embarrassing content, tin nhắn riêng tư).
- Lý do: UX parity Messenger/Telegram/Zalo (cho phép xoá bất cứ lúc nào). Privacy — user có quyền quản content không giới hạn. Soft-delete giữ DB audit/compliance.
- `MessageMapper.toDto` strip `content=null` khi `deletedAt != null` — consistent mọi consumer (REST list + WS broadcast + ACK).
- Trade-off: không có undo grace (Gmail 5s). V1 dùng `window.confirm()` trước publish. V2 xem xét undo toast 5s. Receiver có thể đã đọc trước khi sender xoá → delete ≠ unsend — UX hiển thị "🚫 Tin nhắn đã bị xóa" thay ẩn hoàn toàn.
- Edit-after-delete regression guard: `editViaStomp` check `message.getDeletedAt() != null` → merge MSG_NOT_FOUND (test T-DEL-07).
- FE Option A (đã apply): KHÔNG optimistic deletedAt, chỉ `deleteStatus='deleting'` + opacity-50. Chờ ACK patch từ server → cache không lệch DB.

### ADR-019: FileAuthService — uploader OR conv-member, anti-enum 404 (W6-D2)
- Tách `FileAuthService.findAccessibleById(fileId, userId)` khỏi `FileService.loadForDownload`. Rule uploader → JOIN `message_attachments → messages → conversation_members` membership check.
- Mọi fail (not-found/not-accessible/expired/cleanup-deleted) → `Optional.empty()` → controller 404 (không 403, không 410).
- N+1 concern cho scroll: V1 acceptable (browser cache 7d ETag → 1 fire/file). V2 Redis cache `(fileId, userId) → bool` 5min nếu contention cao.

### ADR-020: Thumbnail fail-open (W6-D2)
- `FileService.upload()` thumbnail gen trong try-catch NGOÀI flow chính. Fail → WARN + continue. DB `thumbnail_internal_path=null` → FileDto.thumbUrl=null → GET /thumb 404.
- Upload đã succeed → không rollback cả upload vì feature phụ. Similar pattern ADR-005 Redis down fail-open.
- Silent partial failure — V2 metric `thumbnail.generate.failed` monitor spike.

### ADR-020 (Group Chat Architecture - W7-D1)
- 3 roles (`OWNER`/`ADMIN`/`MEMBER`), min 3 / max 50 members. Auto-transfer ownership khi OWNER leave (oldest-ADMIN → oldest-MEMBER → NULL).
- 7 REST endpoints mới + 6 STOMP events mới (CONVERSATION_UPDATED, MEMBER_ADDED, MEMBER_REMOVED, ROLE_CHANGED, OWNER_TRANSFERRED, GROUP_DELETED).
- Authorization matrix enforced ở service layer qua enum methods.
- Schema V7 migration (thực tế V9 vì V7/V8 đã dùng cho files): enum `member_role`, column `role`+`joined_at` conversation_members, columns `name`+`avatar_file_id`+`owner_id` conversations, CHECK `chk_group_metadata` (GROUP phải có name+owner, ONE_ON_ONE cả 2 NULL).
- Avatar reuse files table, image-only (Group A), qua `conversations.avatar_file_id` KHÔNG qua `message_attachments` (tránh UNIQUE conflict).
- `owner_id ON DELETE SET NULL`: OWNER xoá account → group tồn tại (không auto-transfer). V1 keep simple.
- **BLOCKING checks BE**: Role enum permission methods embedded (canAddMembers, canRemoveMember(target), canChangeRole, canTransferOwnership); service gọi `role.canX()` — KHÔNG scatter if-else; anti-enum non-member → `CONV_NOT_FOUND`; `SELECT ... FOR UPDATE` trong leave + add members + transfer-owner; broadcast order auto-transfer fire ROLE_CHANGED TRƯỚC MEMBER_REMOVED; MEMBER_REMOVED + GROUP_DELETED broadcast TRƯỚC hard-delete; CHECK constraint test; avatar MIME validate image; `attached_at` set cho avatar tránh orphan.

### ADR-021: Content XOR Attachments (W6-D1/W6-D2)
- `validateStompPayload`: `hasContent || hasAttachments` — cả 2 rỗng → `MSG_NO_CONTENT`.
- DB `content` NOT NULL → service trim+persist `""` khi attachments-only. FE dùng `attachments.length > 0` render.

### ADR-021 (Hybrid File Visibility - W7-D4-fix)
- Per-file `is_public` flag + `/public` endpoint (không tách 2 table).
- 1 column `files.is_public BOOLEAN DEFAULT FALSE`; serve qua 2 endpoint: `GET /api/files/{id}` (JWT) + `GET /api/files/{id}/public` (no JWT, anti-enum 404).
- Upload param `?public={true|false}` default false (safe). FileDto có `isPublic` + `publicUrl` (null khi private).
- **SecurityConfig ORDER**: `/api/files/*/public` permitAll TRƯỚC `.anyRequest().authenticated()`. Đảo order → authenticated thắng → 401.
- Anti-enum public: not-found/is_public=false/expired → 404 NOT_FOUND (không leak).
- Cache headers: private `Cache-Control: private, max-age=7d`; public `Cache-Control: public, max-age=1d` (browser+CDN).
- **Reviewer rules**: (a) permitAll path mới → verify TRƯỚC authenticated; (b) public endpoint không dùng error code phân biệt "not found" vs "private" (leak enum); (c) FE upload cho public resource KHÔNG `?public=true` → BLOCKING.

### ADR-022: Soft-deleted strip content + attachments (W5-D3 + W6-D1) [LEGACY memory note — không có entry ARCHITECTURE.md §12]
- `MessageMapper.toDto` khi `deletedAt != null` → content=null + attachments=[]. Applied nhất quán REST list/get, STOMP broadcast, ACK (bất kỳ path nào serialize MessageDto).
- Defense-in-depth: strip ở mapper = strip ở TẤT CẢ output path (không sót).
- Browser Cache-Control 7d thumb binary có thể còn cache — acceptable trade-off.
- **Naming conflict**: Số ADR-022 canonical thuộc về "Message Reactions with Any Emoji" (W8-D1, ARCHITECTURE.md §12) — mục bên dưới. Note này giữ lại cho lookup lịch sử, sẽ rename/consolidate sang ADR-023+ trong đợt next consolidation.

## ADR-022: Message Reactions (W8-D1, 2026-04-22) [CANONICAL — ARCHITECTURE.md §12]
- **Schema**: `message_reactions` — `UNIQUE(message_id, user_id)` — 1 user 1 emoji per message. Columns: id UUID PK, message_id FK CASCADE, user_id FK CASCADE, emoji VARCHAR(20), created_at. Migration V13.
- **Toggle semantics**: no-existing → INSERT (ADDED); same emoji → DELETE (REMOVED); different → UPDATE (CHANGED). Atomic `@Transactional` — UPDATE (không DELETE+INSERT) để id stable + tránh race.
- **Aggregate**: `ReactionAggregateDto {emoji, count, userIds[], currentUserReacted}` embed `MessageDto.reactions[]`. Sort `count DESC, emoji ASC` stable. Empty `[]` cho SYSTEM + soft-deleted (không null).
- **STOMP**: `/app/msg.{messageId}.react` payload `{emoji}` KHÔNG clientId (fire-and-forget, confirmation qua broadcast `REACTION_CHANGED` `/topic/conv.{convId}`).
- **Broadcast payload**: `{messageId, userId, userName (snapshot), action: ADDED|REMOVED|CHANGED, emoji, previousEmoji}`. Matrix: ADDED → emoji non-null/previousEmoji null; REMOVED → emoji null/previousEmoji non-null; CHANGED → cả 2 non-null.
- **Validation chain (rẻ→đắt)**: auth → emoji regex (≤20 bytes UTF-8) → rate limit 5/s → message exists → member of conv → type != SYSTEM → deleted_at IS NULL → toggle DB.
- **Policy `.react`**: HANDLER_CHECK (không STRICT_MEMBER interceptor) — destination messageId không có FK convId để resolve trong interceptor; handler query message cho validation → piggyback member check cùng transaction. Trade-off: FORBIDDEN qua user queue ERROR thay STOMP ERROR frame (consistent với `.typing` SILENT_DROP pattern).
- **N+1 BLOCKING mitigation**: list messages (50/page) → batch query `WHERE message_id IN (:ids)` 1 round-trip → group in-memory Map<UUID, List<Aggregate>>. KHÔNG @OneToMany LAZY trong loop.
- **Emoji validation**: server-side regex Unicode (library `emoji-java` hoặc regex `\p{So}\p{Emoji}*`). Defense-in-depth: KHÔNG tin FE picker. Edge: compound emoji (family ZWJ, flag tag sequence, skin-tone modifier) — test set representative 👍/👍🏽/👨‍👩‍👧/🏴󠁧󠁢󠁳󠁣󠁴󠁿. Flag tag sequence 28 bytes reject vì VARCHAR(20).
- **Rate limit 5/s/user**: Redis `rate:msg-react:{userId}` INCR EX 1. Looser hơn send (30/phút) vì quick-react batch UX hợp lệ. Pattern ADR-005.
- **ADR-017 reuse**: unified ACK queue đã reserve `operation: "REACT"` từ W5-D2. ERROR frame `{operation:"REACT", clientId:null, code}` — FE MUST tolerate null clientId (không registry.get(null) crash).
- **FE**: `@emoji-mart/react` ~300KB gzipped — V2 lazy-load nếu bundle budget; ReactionBar hover preview + click aggregate toggle. Optimistic patch → broadcast reconcile idempotent (check `userIds.includes` trước push/remove).
- **Error codes**: `REACTION_INVALID_EMOJI`, `REACTION_NOT_ALLOWED_FOR_SYSTEM` (supersede placeholder `SYSTEM_MESSAGE_NO_REACTIONS` từ W7-D4 rule 4), `REACTION_MSG_DELETED`. Ngoại lệ anti-enum documented (visible type không leak, giống SYSTEM_MESSAGE_NOT_EDITABLE pattern).
- **Race edge cases**: reaction trên message soft-delete race acceptable V1 (orphan row, MessageMapper filter `deletedAt != null → reactions=[]` bỏ qua); user kick race orphan userId FE tolerate (giống §3f.4 READ_UPDATED kick race).
- **V2 path**: custom reactions (image upload as emoji) với `custom_emoji_file_id` FK files + moderation queue; multi-emoji per user per message (drop UNIQUE, composite PK); reaction trên thread replies (W9+ threads, reuse schema).
- **Review BLOCKING BE W8-D1**: UNIQUE constraint toggle atomic, emoji regex server-side, batch load N+1, error code rename supersede, VARCHAR(20) vs compound emoji edge case.
- **Review BLOCKING FE W8-D1**: ERROR handler tolerate `clientId:null`, optimistic + idempotent reconcile, sort count DESC stable re-apply sau patch, emoji-mart lazy-load option.

### ADR-023: Pin Message (W8-D2, 2026-04-22)
- **Schema**: V14 migration — `messages.pinned_at TIMESTAMPTZ` + `pinned_by_user_id UUID FK ON DELETE SET NULL`. Partial index `WHERE pinned_at IS NOT NULL`.
- **Max 3 per conv**: count query `WHERE pinned_at IS NOT NULL AND deleted_at IS NULL`. V1 single-instance count check acceptable (no FOR UPDATE — race window nhỏ). V2 distributed lock.
- **Authorization**: GROUP → `MemberRole.isAdminOrHigher()` (OWNER/ADMIN). DIRECT → any member. Reuse W7-D1 role enum pattern.
- **STOMP**: `/app/msg.{messageId}.pin` payload `{action: "PIN"|"UNPIN"}`, policy HANDLER_CHECK (cùng pattern .react W8-D1). Rate 5/s/user.
- **Broadcast**: `MESSAGE_PINNED` / `MESSAGE_UNPINNED` trên `/topic/conv.{convId}`. Fire-and-forget (no clientId). AFTER_COMMIT pattern.
- **Idempotent**: already pinned → no-op (không broadcast). Pin deleted → reject `MSG_DELETED` (ngoại lệ anti-enum, cùng pattern REACTION_MSG_DELETED).
- **Soft-deleted pinned**: query filter `deleted_at IS NULL` trong count + findPinned → ghost row không hiện banner. KHÔNG auto-unpin (V2 DB trigger).
- **DTO extend**: MessageDto `pinnedAt` + `pinnedBy: {userId, userName}`. ConversationDetailDto `pinnedMessages: MessageDto[]` sort `pinned_at DESC`.
- **Error codes**: `PIN_LIMIT_EXCEEDED` (details currentCount+limit), `MESSAGE_NOT_PINNED`, `MSG_DELETED`, `INVALID_ACTION`. Reuse FORBIDDEN, MSG_NOT_FOUND, NOT_MEMBER, MSG_RATE_LIMITED.
- **BLOCKING checks BE W8-D2**: role check GROUP vs DIRECT (isAdminOrHigher), pin limit count filter deleted_at, AFTER_COMMIT broadcast, idempotent no-op. 
- **BLOCKING checks FE W8-D2**: PinnedMessagesBanner collapsed/expanded + scrollToMessage, canPin() role check, error toast.

### ADR-024: Bilateral User Block (W8-D2, 2026-04-22)
- **Schema**: V15 migration — `user_blocks` table UNIQUE(blocker_id, blocked_id), CHECK(blocker != blocked), ON DELETE CASCADE.
- **Bilateral**: `existsBilateral(a, b)` query OR cả 2 chiều trong 1 query. A block B → cả 2 không direct msg. A unblock B → chỉ bỏ chiều A→B, B→A vẫn block (nếu có).
- **REST**: `POST /api/users/{id}/block` (201/200 idempotent), `DELETE /api/users/{id}/block` (204), `GET /api/users/blocked` (200 `{items: UserDto[]}`).
- **Integration**: sendViaStomp DIRECT → check bilateral before insert msg. createDirect → check bilateral before create conv. GROUP send KHÔNG check. addMembers reuse W7 USER_BLOCKED skip.
- **Privacy**: `UserDto.isBlockedByMe` (viewer-aware). KHÔNG expose `hasBlockedMe`.
- **Error codes**: `CANNOT_BLOCK_SELF`, `BLOCK_NOT_FOUND`, `MSG_USER_BLOCKED` (bilateral, không tiết lộ ai block ai).
- **BLOCKING checks BE W8-D2**: bilateral query OR, idempotent block (no-op), integration sendViaStomp DIRECT-only + createDirect, ON DELETE CASCADE cleanup.
- **BLOCKING checks FE W8-D2**: block confirm dialog, error toast, isBlockedByMe flag UI.

---

## Contract version hiện tại

- **API_CONTRACT.md**: **v1.6.0-w8-pin-block** (W8-D2: Pin Message + Bilateral User Block — V14 `messages.pinned_at` + `pinned_by_user_id`, V15 `user_blocks` UNIQUE(blocker_id, blocked_id). Pin: STOMP `/app/msg.{messageId}.pin` payload `{action}` HANDLER_CHECK, role GROUP OWNER/ADMIN DIRECT any, limit 3, broadcast MESSAGE_PINNED/UNPINNED, MessageDto extend `pinnedAt` + `pinnedBy`, ConversationDetailDto extend `pinnedMessages[]`. Block: REST 3 endpoints, `existsBilateral` OR, integrate sendViaStomp DIRECT + createDirect, UserDto extend `isBlockedByMe`. Error codes: PIN_LIMIT_EXCEEDED, MESSAGE_NOT_PINNED, MSG_DELETED, INVALID_ACTION, CANNOT_BLOCK_SELF, BLOCK_NOT_FOUND, MSG_USER_BLOCKED).
- **SOCKET_EVENTS.md**: **v1.11-w8** (W8-D2: §3.17 `MESSAGE_PINNED` broadcast + §3.18 `MESSAGE_UNPINNED` broadcast trên `/topic/conv.{convId}`. §2 thêm `/app/msg.{messageId}.pin` inbound HANDLER_CHECK. §7.1 thêm .pin policy. ERROR `{operation:"PIN", clientId:null, code}` tolerate null. ADR-023 + ADR-024 references §9).
- _Previous_: API v1.5.0-w8-reactions + SOCKET v1.10-w8 (W8-D1 reactions); API v1.4.0-w7-read + SOCKET v1.9-w7 (W7-D5 read receipts).

## Auth contract — đã chốt

- **Refresh rotation**: mỗi /refresh phát token mới, invalidate cũ Redis. BE atomic check-and-rotate.
- **Rate limit login**: chỉ tính lần fail.
- **User enumeration protection**: /login trả `AUTH_INVALID_CREDENTIALS` cho cả sai username/password, cùng message.
- **OAuth auto-link**: Firebase verified email → link provider, không tạo user mới. Thứ tự: providerUid → email → new.
- **Logout**: refreshToken trong body, server xóa đúng token (single-device).
- **isNewUser field**: /oauth response thêm — documented exception.

---

## Review standards (bài học rút ra)

### Security (bắt buộc cho auth-related)
- **Hash/token compare constant-time**: `MessageDigest.isEqual(bytesA, bytesB)`, KHÔNG `String.equals()`/`Arrays.equals()` (timing leak). BLOCKING.
- **Token rotation DELETE trước SAVE**: tránh cửa sổ 2 token hợp lệ.
- **Reuse detection revoke ALL sessions user đó** (không chỉ token hiện tại): `redisTemplate.keys("refresh:{userId}:*")` → delete.
- **Log SECURITY events**: WARN + userId + jti + action, KHÔNG raw token/password. Format `"[SECURITY] Refresh token reuse/invalid for userId={}, jti={}. Revoking all sessions."`.
- **Error code không leak**: INVALID (malformed/sig-sai/reused/user-not-found) cùng code; EXPIRED riêng (FE biết login lại).
- **Firebase ID token**: PHẢI `FirebaseAuth.verifyIdToken(idToken)` (Admin SDK) — KHÔNG tự parse JWT jjwt (bỏ qua Google public key rotation + audience/issuer check). BLOCKING.
- **OAuth auto-link theo email**: CHỈ an toàn khi provider verify email. Google luôn verified. Facebook/Apple V2 → phải check `isEmailVerified()`.
- **Access token blacklist TTL = remaining**: `SET jwt:blacklist:{jti} '' EX {remainingSeconds}`.
- **Blacklist check TRƯỚC set SecurityContext**: extract → validate VALID → check Redis → (blacklisted → set `jwt_expired` + doFilter + return) → load User. Ngược lại BLOCKING.
- **Fail-open blacklist Redis**: accept với comment intent rõ (ADR-011). Không comment = bug.

### BE patterns
- **AuthMethod enum (ADR-010)**: không hardcode string claim.
- **Race condition uniqueness check (W2D2 non-blocking V1)**: `existsByEmail` → `save` race window. DB UNIQUE throw `DataIntegrityViolationException` → hiện 500. V2 map 409 AUTH_EMAIL_TAKEN/USERNAME_TAKEN. Acceptable V1 traffic thấp.
- **Transaction không bao Redis**: `@Transactional` chỉ quản JDBC/JPA. Save Redis SAU user save → nếu Redis fail, user tồn DB nhưng không refresh token → FE login lại. Acceptable.
- **X-Forwarded-For không sanitize**: attacker forge header → Redis key rác. Nên validate IP format (`InetAddressValidator`) trước làm key suffix.

### Anti-enumeration pattern (W5-D2 formalized)
- **Quy tắc vàng**: User truy xuất resource có thể fail nhiều lý do (not-exist / not-owner / soft-deleted / not-member / thuộc conv khác) → merge thành 1 error code (`*_NOT_FOUND`) với cùng message. Attacker không enumerate được "tồn tại nhưng không có quyền" vs "không tồn tại".
- **Ví dụ**: `editViaStomp` check `message == null || message.convId != convId || message.sender.id != userId || message.deletedAt != null` → chung `MSG_NOT_FOUND`. KHÔNG phân biệt FORBIDDEN vs NOT_FOUND.
- **Ngoại lệ**: FE cần UX khác biệt (`AUTH_REQUIRED` vs `FORBIDDEN` để redirect vs toast). Business resource thì merge.
- Precedent: `CONV_NOT_FOUND`, `USER_NOT_FOUND`, `AUTH_INVALID_CREDENTIALS`.

### Optimistic edit pattern (W5-D2) — BẮT BUỘC cân nhắc revert
- Vấn đề: optimistic EDIT ghi đè content cũ. ERROR/TIMEOUT không revert → cache lệch DB vĩnh viễn.
- **Option A (đơn giản, đúng contract §3c.6)**: KHÔNG optimistic content/editedAt. Set marker `editStatus='saving'`. ACK → patch từ ack.message. ERROR → set failureCode, content giữ nguyên.
- **Option B**: Optimistic content mới + lưu `originalContent/originalEditedAt` vào registry. ERROR/TIMEOUT → revert. ACK → clear registry.
- Ưu tiên Option A trừ khi UX cần instant feedback.
- BLOCKING nếu optimistic không có revert path.

### FE patterns
- **globalThis workaround RESOLVED W-FE-2**: migrate tokenStorage.ts pattern.
- **Zustand persist KHÔNG accessToken**: nếu thấy `accessToken` trong `partialize` → BLOCKING.
- **Axios interceptor loop**: retry /refresh phải axios.post thuần + `_retry` flag. Thiếu → infinite loop.
- **Form payload strip sensitive/client-only**: explicit object, KHÔNG spread `...data` (leak `confirmPassword`/`acceptTerms`). BLOCKING nếu thấy `registerApi(data)` trực tiếp với RegisterFormData có field extra.
- **Zod regex length+format gộp**: UX trade-off. Error message khi fail length hiển thị format. Non-blocking, gợi ý tách `.min(3).max(50).regex(...)`.

### Contract drift thường gặp
- **Error field name BE↔FE (W3D2)**: BE `ErrorResponse` dùng `"error"`, KHÔNG `"code"`. FE đọc `err.response.data.error`. Gợi ý FE define `interface ApiErrorBody` 1 chỗ (types/api.ts).
- **DTO shape Summary vs Detail**: `ConversationDto` (full — POST 201 + GET detail) vs `ConversationSummaryDto` (GET list — có `displayName/displayAvatarUrl/unreadCount/mutedUntil` computed). FE không được copy toàn bộ Summary fields vào Detail. Check mỗi endpoint contract có shape khác nhau → 2 interface riêng.

---

## Approved patterns

### BE
- **Role enum embedded permission methods (W7-D1)**: `canRename/canAddMembers/canRemoveMember(targetRole)/canChangeRole/canDeleteGroup/canTransferOwnership`. Service gọi `role.canX()`. Khi V2 custom permissions, chỉ đổi implementation. **Reviewer rule**: thấy `if (role.equals("ADMIN") && ...)` scatter → REQUEST CHANGES refactor enum.
- **Authorization matrix as appendix (W7-D1)**: feature role-based PHẢI có matrix table (rows=actions, cols=roles, ✓/✗). Dễ verify, single source BE+FE.
- **Auto-transfer ORDER BY joined_at (W7-D1)**: OWNER leave → `CASE role WHEN 'ADMIN' THEN 0 WHEN 'MEMBER' THEN 1 ELSE 2 END ASC, joined_at ASC`. Native SQL (JPQL CASE với enum literal không portable). `FOR UPDATE` chống race (W7-1).
- **CHECK constraint type-specific cols (W7-D1)**: table với rows khác type, column chỉ nghĩa 1 type (name/owner_id GROUP only) → `CHECK ((type='A' AND col IS NULL) OR (type='B' AND col IS NOT NULL))`. Catch FE bugs sớm, self-documenting.
- **FileAuth tách FileService (ADR-019, W6-D2)**: Optional return (caller quyết định error), JPQL JOIN 3 table COUNT>0, anti-enum 404 merged, reuse download+thumb.
- **@Scheduled cleanup (W6-D3)**: (1) `@EnableScheduling`; (2) Cron 6-field Spring 6; (3) Externalize cron `${ENV:default}`; (4) `@ConditionalOnProperty(enabled=true, matchIfMissing=true)` — test `cron="-"` disable trigger; (5) Batch page-0 loop (records xử lý rời predicate); (6) Per-record try-catch (1 fail không kill job). Multi-instance V2: Redis SETNX distributed lock `lock:file-cleanup:expired` TTL 30min. Mọi @Scheduled mới phải có note V2 WARNINGS.md.
- **stillAttached graceful (W6-D3)**: physical delete TRƯỚC → check attachment → attached: `setExpired(true) + save` (DB giữ); GET /files/{id} → `openStream()` throw StorageException → controller catch → 404 FILE_PHYSICALLY_DELETED. `LocalStorageService.delete()` dùng `deleteIfExists()` idempotent.
- **Fail-open thumbnail (ADR-020)**: upload flow try-catch WRAP generate(), thumb fail → WARN + continue, FileDto.thumbUrl=null.
- **Content XOR Attachments (ADR-021)**: validateStompPayload check `hasContent || hasAttachments` → MSG_NO_CONTENT nếu cả 2 rỗng. DB NOT NULL → persist "" cho attach-only.
- **Soft-delete strip trung tâm (ADR-022)**: MessageMapper.toDto `deletedAt != null` → content=null + attachments=[]. Applied REST+ACK+broadcast.
- **N+1 MessageMapper documented (W6-D2)**: page 50×5 = ~250 queries worst-case. V1 acceptable (cache + không hot path). V2 `@EntityGraph`. Javadoc + WARNINGS.md.
- **validateTokenDetailed enum** VALID/EXPIRED/INVALID tách thay boolean.
- **GlobalExceptionHandler + AppException**: business error throw `AppException(HttpStatus, errorCode, message)`.
- **Refresh token SHA-256 hash Redis**: không lưu raw. Compare qua hash lại token FE gửi.
- **User enumeration protection**: `AUTH_INVALID_CREDENTIALS` merge user-not-found + wrong-password. Check account status SAU verify credentials.
- **Client IP extraction**: `X-Forwarded-For[0].trim()` fallback `getRemoteAddr()`.
- **Transactional broadcast (W4-D4)**: 3 component — (1) `@Component Mapper` (Singleton), (2) `record Event`, (3) `@Component Broadcaster @TransactionalEventListener(phase=AFTER_COMMIT)` + try-catch toàn bộ. Service `@Transactional` `eventPublisher.publishEvent(event)`. Mapper reuse REST+broadcast → shape IDENTICAL. Test `@MockBean SimpMessagingTemplate`.
- **Ephemeral event (W5-D1)**: Event non-critical (typing/presence/read) — NO DB persist, NO publisher. Handler STOMP frame → member check → rate limit INCR+EX → broadcast trực tiếp. Fail-open rate limit Redis down. Shape `userId+username+conversationId` (không fullName).
- **Silent drop ephemeral (W5-D1 with AuthChannelInterceptor split)**: non-critical fail auth/rate → log DEBUG, KHÔNG throw ERROR frame (FE reconnect loop). Destination split: `.message/.edit/.delete` STRICT, `.typing` SILENT.
- **Tristate PATCH via @JsonAnySetter Map (W7-D1)**: Record/POJO không phân biệt "absent" vs "null". Map `rawFields` + `@JsonAnySetter` + helpers `hasField(key)`, `isRemoveField(key)`, `getField(key, Type)`. Caveat: unknown keys ignored; malformed UUID silent null → service re-validate throw VALIDATION_FAILED.
- **AFTER_COMMIT reconfirmed (W7-D1)**: broadcast pattern chuẩn mọi STOMP event DB side-effect. Plain `@EventListener` → BLOCKING (risk broadcast trước commit / sau rollback).
- **User-specific STOMP queue (W7-D2)**: `convertAndSendToUser(userId.toString(), "/queue/<event>", payload)`. FE subscribe literal `/user/queue/<event>`. Use: `/queue/conv-added` (user được add, FE miss topic), `/queue/conv-removed` (user bị kick). Caveats: SimpleBroker không persist (offline miss), multiple sessions (FE dedupe conv), ordering không đảm bảo (FE idempotent).
- **canRemoveMember(targetRole) hierarchical (W7-D2)**: enum 2-tham số khi rule "ai làm gì với AI". `if (this == OWNER) return targetRole != OWNER; if (this == ADMIN) return targetRole == MEMBER; return false`. Service 1 dòng thay matrix 6 nhánh. Apply kick/reply-to/mute/any 2-party action.
- **REQUIRES_NEW readOnly trong AFTER_COMMIT listener (W7-D2)**: listener fire sau outer TX close → listener cần DB access PHẢI `@Transactional(propagation=REQUIRES_NEW, readOnly=true)`. Listener không có repo call → @Transactional là overhead, REQUEST remove.
- **Partial-success (added[]+skipped[]) batch (W7-D2)**: item-level validation fail (already-member, not-found, blocked) → skip với reason enum, NOT throw. Race errors (MEMBER_LIMIT_EXCEEDED, RATE_LIMITED) vẫn fail-all. Response LUÔN non-null (`@JsonInclude ALWAYS`). Broadcast CHỈ cho items trong added. HTTP 201 dù tất cả skip.
- **Auto-transfer native CASE ORDER BY portable (W7-D2)**: JPQL CASE enum literal không portable H2/PG. Native SQL `CASE role WHEN 'ADMIN' THEN 0 ...` + `CAST(:id AS UUID)` cho H2. Trả `List<Entity>` (không LIMIT 1) cho caller inspect.
- **Server-generated message subtype service (W7-D4)**: Tách `SystemMessageService.createAndPublish(convId, actorId, eventType, metadata)` thay nhồi MessageService. Propagation REQUIRED join caller TX (atomic). Build Message với invariants (`sender=null, content="", type=SYSTEM, systemEventType non-null, systemMetadata non-null`). Metadata inject `actorId+actorName` ở service. Publish `MessageCreatedEvent` REUSE pipe broadcast có sẵn (không event STOMP mới).
- **Immutable message subtype guard trước anti-enum (W7-D4)**: SYSTEM/AUDIT không cho edit/delete. Guard `type == SYSTEM` PHẢI TRƯỚC anti-enum MSG_NOT_FOUND merge. SYSTEM visible mọi member (không hidden), distinguish không leak. Error `SYSTEM_MESSAGE_NOT_EDITABLE/NOT_DELETABLE` (403). Pattern: check type trước null/conv/owner/deletedAt.
- **JPA JsonMapConverter JSONB H2/PG (W7-D4)**: `@JdbcTypeCode(SqlTypes.JSON)` FAIL H2 (store VARCHAR, read raw String). Fix: `@Converter` Jackson direct: `convertToDatabaseColumn: MAPPER.writeValueAsString(attr)`, `convertToEntityAttribute: MAPPER.readValue(dbData, MAP_TYPE)`. Entity `@Convert(converter = JsonMapConverter.class)`. Giữ `JSONB` migration cho PG native (GIN index + `->>` V2).

### FE
- **Blob URL cleanup ref-based (W6-D4)**: `URL.createObjectURL(file)` PHẢI `URL.revokeObjectURL` 4 điểm (cancel / remove / clear / unmount). Unmount BẮT BUỘC pattern `const pendingRef = useRef(); pendingRef.current = pending;` → cleanup loop `pendingRef.current` — đọc `pending` state → stale closure.
- **FormData Content-Type undefined (W6-D4)**: axios + multipart MUST `headers: { 'Content-Type': undefined }`. KHÔNG omit (interceptor inject `application/json`). KHÔNG hardcode `multipart/form-data` (thiếu boundary). Comment BẮT BUỘC. BLOCKING nếu hardcode.
- **AbortController native (W6-D4)**: axios v1+ deprecated CancelToken. `new AbortController()` + `signal: controller.signal`. Catch silent `axios.isCancel(err)`. BLOCKING nếu thấy `CancelToken.source()`.
- RHF + zodResolver + `mode:'onTouched'` — validate blur, ít re-render.
- **isRefreshing + failedQueue**: refresh queue pattern axios. Chỉ 1 request /refresh, còn lại queue.
- **tokenStorage.ts (W-FE-2)**: module in-memory không import api.ts → phá circular dep. Sync 2 chiều trong cùng action.
- **STOMP subscription hook (W4D4)**: `useConvSubscription(convId)` — dep `[convId, queryClient]`. Local ref `cleanup: (()=>void) | null`. `subscribe()` check `client?.connected` trước sub; gọi ngay để bắt kịp connected; `onConnectionStateChange` re-sub khi CONNECTED (cleanup cũ trước). Dedupe `some(m => m.id === newMsg.id)` cross pages. Append page cuối ASC. Invalidate `['conversations']`.
- **sockjs-client global shim belt+suspenders**: `main.tsx` `window.global = window` + `vite.config.ts` `define: { global: 'globalThis' }`. Runtime fallback + build-time replace.
- **Typing indicator hook (W5-D1)**: `useTypingIndicator(convId)` 3 timers: debounceTimerRef (START 1/2s), autoStopTimerRef (STOP 3s), autoRemoveTimersRef Map<userId,timerId> (5s safety). Skip self. Cleanup tất cả 3 ref. Clear khi DISCONNECTED/ERROR.
- **Role-based UI filtering (W7-D3)**: Authorization matrix qua `canActOn(currentRole, targetRole, targetUserId, currentUserId)`. Server là source of truth — FE filter chỉ pre-UX. Rule: FE allow action X nhưng BE reject → toast rõ, KHÔNG silently revert.
- **Global user-queue mount (W7-D3)**: Mọi `/user/queue/*` mount 1 lần `GlobalSubscriptions` (App root), KHÔNG per-component (tránh duplicate subscription). Re-subscribe after STOMP reconnect. `/topic/conv.{id}` vẫn per-conversation hook.
- **Tristate PATCH FE serialization (W7-D3)**: body `Record<string, unknown>`, CHỈ set field khi marker 'changed'/'removed'. 'unchanged' → KHÔNG set. Empty body `{}` → close dialog không call API. Pair BE `@JsonAnySetter` tristate.
- **Dispatcher pattern message polymorphism (W7-D4)**: List-level (`MessagesList`): `msg.type === 'SYSTEM' ? <SystemMessage /> : <MessageItem />`. Component-level defense (`MessageItem` memo wrapper): internal check. Tách `MessageItemInner` khỏi wrapper tránh hooks order violation. Cache `sender: MessageSenderDto | null` — `msg.sender?.id` null-safe.
- **SYSTEM message i18n (W7-D4)**: "Bạn"/"bạn" substitution: `isActor = !!meta.actorId && meta.actorId === currentUserId`; `actorName = isActor ? 'Bạn' : (meta.actorName ?? 'Ai đó')`; target lowercase. Fallback missing metadata → generic "Ai đó đã ..." + warn. Unknown enum (FE<BE) → "(sự kiện hệ thống)" + warn, KHÔNG crash.
- **Optional field defensive TS union (W7-D4)**: khi DTO thêm `sender: UserDto | null`, grep toàn bộ `.sender.` → thêm `?.` hoặc guard. Audit: useConvSubscription.appendToCache, MessagesList.shouldShowAvatar + isOwn, MessageItem render, ReplyPreviewBox.
- **Idempotent broadcast handling (W7-D3)**: 2 frames overlap (topic + user queue) → cả 2 handler check-then-mutate. (1) topic handler check `isSelf` skip; (2) user queue handler setQueryData early-return nếu no change. Tránh duplicate navigate/toast.
- **GROUP_DELETED cache-read fallback (W7-D3)**: event thiếu display field (không gửi `name`) → FE đọc từ cache TRƯỚC xóa: `conv?.name ?? "Nhóm"` trong toast. Pattern: broadcast minimal payload (ID+actor), FE đọc cache cho display. Reviewer rule: toast đọc field STOMP payload → check contract có field đó; nếu KHÔNG → read from cache.

---

## Rejected patterns

*(Chưa có)*

---

## W6 Security Patterns (learned, W6-D5 audit)

1. **Magic bytes MIME (Tika)**: `tika.detect(InputStream)` không trust Content-Type. Peek ~8KB. Charset strip `split(";")[0]`. W6-2 RESOLVED.
2. **ZIP→Office override**: DOCX/XLSX/PPTX là ZIP container → override CHỈ khi Tika trả `application/zip`.
3. **Path traversal defense**: LocalStorageService canonical prefix check. Internal filename UUID (không user-controlled). `sanitizeFilename` chỉ cho `Content-Disposition`. W6-1 RESOLVED + ADR-019.
4. **FileAuthService anti-enum**: uploader OR conv-member, 404 mọi non-access. Tách auth khỏi business, reuse download+thumb.
5. **useProtectedObjectUrl (FE)**: `api.get` blob + AbortController + revokeObjectURL cleanup. KHÔNG `<img src>` thẳng `/api/files/` (Bearer không gửi). V2: signed URLs.
6. **stillAttached graceful**: physical delete → expired=true giữ DB → GET 404 graceful. W6-4 RESOLVED.
7. **iconType server-computed**: 8 enum values (IMAGE/PDF/WORD/EXCEL/POWERPOINT/TEXT/ARCHIVE/GENERIC). FE không duplicate MIME map.

---

## Changelog contract

| Ngày | Version | Thay đổi |
|------|---------|---------|
| 2026-04-19 | v0.2-auth → v0.4.0-auth-complete | 5 Auth endpoints: register/login/oauth/refresh/logout. Token rotation. Firebase Admin SDK. Blacklist fail-open. ADR-001→ADR-011. |
| 2026-04-19 | v0.5.0-conversations → v0.5.2 | 4 Conversations endpoints + GET /users/{id}. UPPERCASE enum (ADR-012). 409 idempotency. Rate limit conv_create 10/min. last_seen_at column V4 (không expose V1). |
| 2026-04-19 | v0.6.0-messages-rest → v0.6.2 | Messages REST (cursor pagination ASC, limit+1, nextCursor oldest). Anti-enum 404 CONV_NOT_FOUND. ReplyPreviewDto shallow + deletedAt. Forward `after` param. |
| 2026-04-19 | SOCKET v1.0-draft-w4 | REST-gửi + STOMP-broadcast (ADR-014). `/topic/conv.{id}` MESSAGE_CREATED. SimpleBroker V1 (ADR-015). Reuse MessageMapper. |
| 2026-04-20 | SOCKET v1.3-w5d2 + API v0.6.1 | Edit STOMP `/app/conv.{id}.edit`. Unified ACK/ERROR shape ADR-017. Dedup `msg:edit-dedup`. Rate limit 10/min edit. FE state machine. `POST /messages` deprecated (ADR-016). |
| 2026-04-21 | v0.9.0-files → v0.9.5-files-extended | File upload foundation. 7 error codes attachment. 14 MIME expand. iconType 8 values. FileAuth anti-enum. @Scheduled cleanup. |
| 2026-04-21 | API v1.0.0-w7 + SOCKET v1.5-w7 | W7-D1 Group Chat backfill: V9 migration, 7 endpoints mới, 6 events mới, 15 error codes, Authorization Matrix appendix, ADR-020. |
| 2026-04-21 | API v1.1.0-w7 + SOCKET v1.6-w7 | W7-D2 member management: partial success shape, MEMBER_LIMIT_EXCEEDED rename, CANNOT_KICK_SELF, /queue/conv-added|removed, OWNER_TRANSFERRED autoTransferred field. |
| 2026-04-22 | API v1.2.0-w7-system + SOCKET v1.7-w7 | W7-D4 SYSTEM messages: JSONB systemMetadata, V10 DROP NOT NULL sender_id, CHECK chk_message_system_consistency, 8 event types, SYSTEM_MESSAGE_NOT_EDITABLE/NOT_DELETABLE. |
| 2026-04-22 | API v1.4.0-w7-read + SOCKET v1.9-w7 | W7-D5 Read receipt: V12 migration, unreadCount real compute LEAST 99 filter SYSTEM, READ_UPDATED broadcast, `/app/conv.{id}.read` inbound no clientId forward-only idempotent. |
| 2026-04-22 | API v1.6.0-w8-pin-block + SOCKET v1.11-w8 | W8-D2 Pin Message + Bilateral Block: V14 messages.pinned_at + pinned_by_user_id, V15 user_blocks UNIQUE(blocker,blocked). Pin STOMP `/app/msg.{messageId}.pin` HANDLER_CHECK, role GROUP OWNER/ADMIN, limit 3, broadcast MESSAGE_PINNED/UNPINNED. Block REST 3 endpoints, existsBilateral OR, integrate sendViaStomp DIRECT + createDirect. MessageDto + pinnedAt/pinnedBy, ConversationDetailDto + pinnedMessages[], UserDto + isBlockedByMe. 7 error codes mới. ADR-023 + ADR-024 ARCHITECTURE.md §12. |
| 2026-04-22 | API v1.5.0-w8-reactions + SOCKET v1.10-w8 | W8-D1 Message Reactions: V13 migration `message_reactions` UNIQUE(message_id, user_id), MessageDto + reactions[], ReactionAggregateDto {emoji, count, userIds, currentUserReacted}, `/app/msg.{messageId}.react` no clientId toggle 3 semantic, REACTION_CHANGED broadcast action ADDED/REMOVED/CHANGED, policy HANDLER_CHECK, rate 5/s/user, 3 error codes REACTION_*, supersede SYSTEM_MESSAGE_NO_REACTIONS → REACTION_NOT_ALLOWED_FOR_SYSTEM, ADR-022 canonical ARCHITECTURE.md §12 (legacy ADR-022 soft-delete strip marked). |

---

## Changelog file này

- 2026-04-22 W8-D2 contract: Pin Message + Bilateral Block — API v1.5.0-w8-reactions → v1.6.0-w8-pin-block + SOCKET v1.10-w8 → v1.11-w8. ADR-023 Pin (max 3/conv, GROUP OWNER/ADMIN, DIRECT any, HANDLER_CHECK, broadcast MESSAGE_PINNED/UNPINNED, MessageDto pinnedAt+pinnedBy, ConversationDetailDto pinnedMessages[]). ADR-024 Block (V15 user_blocks, bilateral existsBilateral OR, REST 3 endpoints, integrate sendViaStomp DIRECT + createDirect, isBlockedByMe privacy no hasBlockedMe). V14+V15 migrations. 7 new error codes (4 pin + 3 block). 3 WARNINGS (pin-deleted-race, pin-limit-race, block-existing-conv). ARCHITECTURE.md §12 append 2 ADRs.
- 2026-04-22 W8-D1 review: **REQUEST CHANGES — 1 BLOCKING regression 100 tests**. Feature W8-D1 Reactions bản thân: 31/31 checklist items PASS, 40/40 reaction tests mới PASS (EmojiValidator 18, ReactionService 15, MessageMapperReaction 7), contract alignment PASS (API v1.5.0-w8-reactions + SOCKET v1.10-w8 exact match BE/FE). BLOCKING: `Message.java:112-117` thêm `@ColumnTransformer(write = "?::jsonb")` + `columnDefinition = "jsonb"` phá H2 deserialization — 100 test errors trên 6 class (SystemMessageTest 13/13, MemberManagementTest 26/26, FileControllerTest 24/24, FileVisibilityTest 9/12, GroupConversationTest 20/21, ConversationControllerTest 8/20). Root cause: JsonMapConverter read-back double-encoded khi H2 store qua `?::jsonb` cast — pattern W7-D4 knowledge "JPA JsonMapConverter JSONB H2/PG" (`@Convert` + `@Column(name)` không columnDefinition) đã portable sẵn → thêm `@ColumnTransformer` là regression. Scope creep — `Message.java` change không liên quan reactions feature, mix vào cùng branch làm block toàn bộ. Fix required: revert 2 dòng `@ColumnTransformer` + `columnDefinition`. Nếu PG thật sự cần cast → tách commit riêng + ADR + Spring profile. Patterns mới (khi fix xong): HANDLER_CHECK policy cho destination messageId-based (không conv_id FK), batch-load reactions tránh N+1 (`findAllByMessageIdIn` 1 query group-by Map<UUID, List>), fire-and-forget STOMP pattern (no clientId confirmation qua broadcast, unified ERROR queue với clientId=null tolerate). Pitfall: `@ColumnTransformer` + `columnDefinition="jsonb"` BREAK H2 với AttributeConverter — H2 trả JSON-quoted string thay raw JSON.
- 2026-04-22 W8-D1 contract: Message Reactions — API v1.4.0-w7-read → v1.5.0-w8-reactions + SOCKET v1.9-w7 → v1.10-w8. Migration V13 `message_reactions` UNIQUE(message_id, user_id). Toggle 3 case (INSERT/DELETE/UPDATE chọn UPDATE thay DELETE+INSERT để id stable). ReactionAggregateDto `{emoji, count, userIds, currentUserReacted}` embed MessageDto. STOMP §3.15 `.react` no clientId fire-and-forget + §3.16 REACTION_CHANGED broadcast action ADDED/REMOVED/CHANGED. Policy HANDLER_CHECK (không STRICT_MEMBER interceptor) — messageId không có FK convId resolve trong handler. Rate 5/s/user loose. 3 error codes REACTION_* (ngoại lệ anti-enum documented). Supersede placeholder `SYSTEM_MESSAGE_NO_REACTIONS` từ W7-D4 rule 4 → `REACTION_NOT_ALLOWED_FOR_SYSTEM`. ADR-022 canonical ARCHITECTURE.md §12 — legacy ADR-022 (Soft-delete strip) + ADR-021 (Content XOR / Hybrid Visibility) marked legacy, consolidation phase sau. BLOCKING INCONSISTENCY flagged: VARCHAR(20) vs compound emoji rare (flag-tag 28 bytes) — chốt V1 VARCHAR(20) reject flag-tag qua validation.
- 2026-04-22 (Consolidation W7): Compress ADR details + older review standards, preserve W7 content full.
- 2026-04-22 W7D5: Read Receipt contract v1.4.0-w7-read + SOCKET v1.9. Schema V12 migration `last_read_message_id UUID REFERENCES messages(id) ON DELETE SET NULL` + composite index. MemberDto extended với `lastReadMessageId`. unreadCount real compute (bỏ placeholder 0): COUNT messages > lastRead.createdAt filter SYSTEM + deleted_at, cap LEAST 99. readBy compute CLIENT-SIDE (BE không fan-out readers list per-message). `/app/conv.{convId}.read` KHÔNG clientId, idempotent forward-only compare createdAt (incoming <= current → silent no-op). .read destination policy SILENT_DROP → STRICT_MEMBER (persist DB + broadcast). BLOCKING BE: idempotent compare createdAt (không UUID), filter SYSTEM+deleted, FK SET NULL, cross-conv app-level. BLOCKING FE: readBy client compute, optimistic unreadCount=0 self-echo, debounce 500ms trước STOMP .read.
- 2026-04-22 W7D4-fix: Model 4 hybrid visibility + default avatars + ADMIN regression — NEEDS_FIXES (2 BLOCKING). BLOCKING #1: CreateGroupDialog FE thiếu `?public=true` → avatar 404 anti-enum trên /public endpoint. BLOCKING #2: BE `validateGroupAvatar` thiếu `isPublic` enforcement → defense missing (Option B recommend: auto-flip `setIsPublic(true) + save` trong validate, không break client). 3 patterns mới: hybrid visibility (ADR-021), seed defaults fixed UUID guard (triple-safeguard expires_at=9999 + attached_at NOW + DEFAULT_AVATAR_IDS Set cleanup skip), SecurityConfig permitAll ORDER matters.
- 2026-04-22 W7D4: SYSTEM messages APPROVED (0 BLOCKING). 270/270 tests (+13 SM-01→13). 8 event types wired, ordering OWNER_TRANSFERRED trước MEMBER_LEFT verify (SM-06), avatar-only no-rename (SM-11), edit/delete guard trước anti-enum (SM-12/13). 5 patterns: server-generated subtype service, immutable guard trước anti-enum, JsonMapConverter JSONB H2/PG portable, dispatcher list+component defense, SYSTEM i18n Bạn/bạn substitution. 2 pitfalls: Mockito Boolean.FALSE wrapped assert, Optional field TS union audit .sender. usage.
- 2026-04-21 W7-D3 FE: FAIL→PASS. Blocking: GROUP_DELETED payload drift (FE typedef vs BE event). Fix: read from cache fallback `conv?.name ?? "Nhóm"`. 5 warnings logged.
- 2026-04-21 W7-D2 impl: member management APPROVED W/COMMENTS (0 BLOCKING). 257/257 tests (+26). Race safety: native SQL `FOR UPDATE` portable (H2 từ chối COUNT FOR UPDATE), auto-transfer CASE portable, REQUIRES_NEW readOnly listener. Non-blocking: findCandidatesForOwnerTransfer không FOR UPDATE (actor lock serialize), target kick không lock (rare), AFTER_COMMIT ordering deterministic nhưng consider @Order(1/2). Thêm 4 patterns BE.
- 2026-04-21 W7-D1 contract + impl: Group Chat (ADR-020) APPROVED W/COMMENTS. 231/231 tests (+21 new). V9 migration (không V7 — V7/V8 đã files W6). **Convention**: trước khi đặt số migration, `ls db/migration/` xem cao nhất +1. Enum permission methods match matrix. CHECK constraint chk_group_metadata. findActiveById filter deleted_at IS NULL. Tristate DTO via @JsonAnySetter Map. ConversationBroadcaster AFTER_COMMIT + try-catch envelope shape match SOCKET v1.5-w7. 4 patterns + V9 naming drift.
- 2026-04-21 W6-D5: Security audit 18/18 PASS. Move 3 resolved (W6-1/2/4), expand V2 bucket (signed URLs, Office macro, per-user quota). 7 W6 Security Patterns append.
- 2026-04-21 W6-D4: FE File Upload UI + Attachment Display APPROVED (0 BLOCKING). 9/9 BLOCKING checklist: AbortController native, revokeObjectURL 4 điểm via pendingRef, Content-Type undefined, attachments: [] optimistic, RetryButton attachmentIds [], guard uploading, send disable đúng (errors-only KHÔNG enable), DragLeave currentTarget.contains, StompSendPayload.attachmentIds wired. 7 attachment error codes ACK handler. Messenger-style render. 3 FE patterns.
- 2026-04-21 W6-D3: File Cleanup @Scheduled APPROVED. 197/197 tests. Multi-instance V2 note (Redis SETNX distributed lock).
- 2026-04-21 W6-D2: Thumbnail + FileAuth + attachments APPROVED W/COMMENTS. 191/191 tests. 5 patterns (ADR-019 FileAuthService, ADR-020 fail-open thumb, ADR-021 Content XOR, ADR-022 soft-delete strip, validation rẻ→đắt).
- 2026-04-21 W6-D1: File Upload contract v0.9.0-files. 4 pre-prod WARNINGS + 4 AD items.
- 2026-04-20 W5 summary: Typing (Fix A AuthChannelInterceptor split destination STRICT vs SILENT), Edit ADR-016 Path B + ADR-017 unified ACK, Delete ADR-018 unlimited window, Reconnect catch-up wasDisconnectedRef, Reply UI scoped state.
- 2026-04-20 W4-D4: Transactional broadcast pattern (Mapper reuse + @TransactionalEventListener AFTER_COMMIT + try-catch), STOMP subscription hook (re-sub CONNECTED cleanup cũ trước), sockjs global shim belt+suspenders.
- 2026-04-19 W1-W3: JWT + Security, Refresh rotation (ADR-006 DELETE-before-SAVE + constant-time compare + revoke-all-on-reuse), OAuth auto-link (ADR-007 Firebase verified email required), Conversation domain (ADR-012 UPPERCASE + ADR-013 race acceptable V1), Anti-enumeration pattern formalized.
