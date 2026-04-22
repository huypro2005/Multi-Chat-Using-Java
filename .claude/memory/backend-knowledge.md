# Backend Knowledge — Tri thức chắt lọc cho backend-dev

> File này là **bộ nhớ bền vững** của backend-dev.
> Quy tắc: chỉ ghi những gì có giá trị tái sử dụng. KHÔNG ghi nhật ký (cái đó ở `backend-log.md`).
> Giới hạn: file này không được dài quá ~400 dòng. Nếu vượt, phải rút gọn/gộp entries cũ.
> Ai được sửa: chỉ `backend-dev` (agent tự update khi học được điều mới), hoặc `code-reviewer` khi chốt quyết định kiến trúc.

---

## Quyết định kiến trúc đã chốt

### Security (W1-W2)
- SecurityConfig lambda DSL (Spring Security 6), KHÔNG WebSecurityConfigurerAdapter.
- JWT filter: JwtAuthFilter load User entity trực tiếp từ UserRepository → `UsernamePasswordAuthenticationToken(user, null, emptyList())`. Tránh double-lookup.
- jjwt 0.12.x API: `Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)`, `.signWith(secretKey)` không cần algorithm riêng. Secret `secret.getBytes(UTF_8)` >= 32 chars (HS256).
- CORS pitfall: KHÔNG `allowedOrigins("*")` khi `allowCredentials(true)` — Spring reject; dùng list origins cụ thể từ config.
- `authenticationEntryPoint` trả JSON (không HTML redirect).
- BCryptPasswordEncoder strength 12 (`@Bean PasswordEncoder`).
- `validateTokenDetailed()` trả enum VALID/EXPIRED/INVALID. `ExpiredJwtException` catch trước `JwtException` (subclass).
- `authenticationEntryPoint` check attribute `jwt_expired` → AUTH_TOKEN_EXPIRED vs AUTH_REQUIRED.
- `getClaimsAllowExpired()` extract từ `ExpiredJwtException.getClaims()` — safety net cho refresh flow.

### Database
- ddl-auto: validate. Mọi schema change qua Flyway migration. JAVA_HOME trỏ jdk-21.0.10.
- UUID PK pattern: từ W3 chuyển sang `@PrePersist if (id==null) id = UUID.randomUUID()` (thay `@GeneratedValue(UUID) + @Column(insertable=false)`). User entity vẫn giữ old pattern (không break test cũ).
- Timestamp: `OffsetDateTime` cho TIMESTAMPTZ, KHÔNG Date/Calendar. `OffsetDateTime.now(ZoneOffset.UTC)` trong `@PrePersist`.
- Soft delete users: `status VARCHAR(20)` ('active'/'suspended'/'deleted'), lưu `deleted_name` khi xóa.
- Spring Data nested property: `findByUser_Id(UUID)` (dấu `_`), không `findByUserId`.
- Migration naming: trước khi đặt số mới `ls db/migration/` xem số cao nhất +1. Flyway filename là source of truth.

### WebSocket / STOMP (W4-D3)
- `WebSocketConfig` enableSimpleBroker("/topic","/queue") + AppDestPrefix("/app") + UserDestPrefix("/user"). `/ws` endpoint đăng ký CẢ 2: native WebSocket + SockJS fallback.
- `setAllowedOriginPatterns` (KHÔNG `setAllowedOrigins`), đọc từ `app.websocket.allowed-origins` config.
- `configureWebSocketTransport`: setMessageSizeLimit(64KB), setSendTimeLimit(10s), setSendBufferSizeLimit(512KB).
- `AuthChannelInterceptor implements ChannelInterceptor`: CONNECT verify JWT + `accessor.setUser(StompPrincipal(userId))`; SUBSCRIBE check destination → member repo. Throw `MessageDeliveryException(errorCode)` để reject.
- `StompPrincipal` record implements `Principal`, name=userId UUID string.
- Custom `StompSubProtocolErrorHandler`: expose header `message=errorCode` + body. Register qua `@EventListener(ContextRefreshedEvent.class)` lookup `"subProtocolWebSocketHandler"` → unwrap `WebSocketHandlerDecorator.getDelegate()` → `SubProtocolWebSocketHandler.getProtocolHandlers()` → `StompSubProtocolHandler.setErrorHandler()`.
- `SecurityConfig` permitAll `/ws/**` — auth qua STOMP CONNECT frame, không HTTP filter (SockJS info endpoint cần public).
- Test WS: dùng raw `StandardWebSocketClient` + `AbstractWebSocketHandler`, gửi CONNECT TextMessage manual để đọc ERROR frame nguyên bản. `WebSocketStompClient`/`DefaultStompSession` wrap CONNECT rejection thành ConnectionLostException → không expose header.

---

## Pattern đã dùng trong codebase

### Entity + Package
- KHÔNG `@Data` (equals/hashCode lazy-loading issue). Dùng `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`. Domain method OK. `@PrePersist`/`@PreUpdate` cho timestamps. `@ManyToOne(fetch=LAZY)` luôn.
- `@Builder.Default` cho collection `= new ArrayList<>()` và enum default → tránh null khi dùng builder.
- Package: `com.chatapp.<domain>.{entity,repository,service,controller,dto}`.

### Error handling
- `ErrorResponse` record: `{error, message, timestamp, details}` với `@JsonInclude(NON_NULL)`. BE field là `error`, không `code`.
- `AppException(HttpStatus, String errorCode, String message [, Object details])` — `GlobalExceptionHandler` bắt và convert.
- Test `@SpringBootTest` dùng `properties = "spring.autoconfigure.exclude=..."`, không attribute `excludeAutoConfiguration`.
- `MethodArgumentTypeMismatchException` handler → 400 VALIDATION_FAILED với `details.{field, error}`.

### DTO convention
- `{Action}Request`, `{Action}Response` (ví dụ `LoginRequest`, `LoginResponse`).
- Migration: `V{n}__{snake_case_description}.sql`.

---

## Auth Domain (W2) — Summary

- Rate limit login: Redis `rate:login:{ip}` check >= 5 TRƯỚC verify. INCR khi fail, DELETE khi success.
- Refresh token: Redis `refresh:{userId}:{jti}` = SHA-256 hash, TTL = 7d. DELETE old TRƯỚC save new. Hash mismatch → revoke all sessions.
- Constant-time compare: `MessageDigest.isEqual()` (không String.equals() — timing attack).
- Firebase: inject FirebaseAuth `@Autowired(required=false)`, `@MockBean` trong test. OAuthResponse = AuthResponse + isNewUser.
- Blacklist JWT: `SET jwt:blacklist:{jti} '' EX ttl`. Fail-open khi Redis down.
- Pitfall: `@MockBean StringRedisTemplate` khi test class exclude Redis.
- AuthMethod enum: `com.chatapp.user.enums.AuthMethod` PASSWORD/OAUTH2_GOOGLE. JWT claim `auth_method` lowercase.

## Conversation Domain (W3) — Summary

- Package `com.chatapp.conversation.{enums,entity,repository,service,controller,dto,event,broadcast}`.
- `@PrePersist if (id==null) id = UUID.randomUUID()` (pattern W3-BE-1). UUID PK, NO @GeneratedValue.
- Anti-enum: trả 404 cho cả not-exist + not-member. Native SQL UUID → `CAST AS VARCHAR` + `List<String>`.
- findOrCreate 1-1: native SQL double-join. Trả `Optional<String>` tránh H2 `byte[]` mismatch.
- N+1 avoidance: 2-query (native SQL IDs → batch findByIdWithMembers). Flush+clear EntityManager tránh stale cache.
- displayName/displayAvatarUrl server-computed: DIRECT từ other member, GROUP từ conv.name/avatarUrl.

---

## Messages Domain (W4-W5)

### Schema + REST (W4-D1)
- Package `com.chatapp.message.{enums,entity,repository,dto,service,controller}`.
- `@PrePersist` normalize `createdAt = OffsetDateTime.now(ZoneOffset.UTC)` — tránh H2 timezone stripping.
- Cursor pagination: query `limit+1` → `hasMore = size > limit` → `items = subList(0, limit)` → reverse (DESC→ASC) → `nextCursor = items.get(0).createdAt` (oldest).
- `ReplyPreviewDto`: shallow nested `(id, senderName, contentPreview, deletedAt)`. Truncate 100 chars. Non-recursive.
- Repository: Spring Data naming `findByConversation_IdAndDeletedAtIsNullOrderByCreatedAtDesc` — tránh H2 timezone bug với `@Query` JPQL OffsetDateTime.
- Anti-enum: sendMessage + getMessages đều 404 `CONV_NOT_FOUND` cho non-member + conv-không-tồn-tại.
- Rate limit: `rate:msg:{userId}`, 30/60s, fail-open Redis down.
- FK defer: V3 tạo `last_read_message_id` chưa FK. V5 ADD CONSTRAINT `fk_members_last_read ON DELETE SET NULL`.
- H2 TIMESTAMPTZ pitfall: test pagination dùng `plusDays(i)` (không sub-second diffs) + insert repo trực tiếp (không REST endpoint).

### Transactional Broadcast (W4-D4)
- `MessageCreatedEvent(UUID convId, MessageDto dto)` record — immutable, DTO truyền trực tiếp.
- Publisher: `ApplicationEventPublisher.publishEvent(event)` trong `@Transactional` method sau save + map DTO.
- Listener `@Component` với `@TransactionalEventListener(phase = AFTER_COMMIT)` — tách biệt khỏi service. try-catch TOÀN BỘ body; broadcast fail KHÔNG propagate.
- Envelope `Map.of("type","MESSAGE_CREATED","payload",dto)`. MessageMapper `@Component` reuse giữa REST response và broadcast → shape IDENTICAL.
- Test: `@MockBean SimpMessagingTemplate`.

### Ephemeral Event (W5-D1) + Destination Policy
- Ephemeral STOMP (typing/read) KHÔNG persist DB. Package `com.chatapp.websocket` cho handler.
- `ChatTypingHandler`: member check → rate limit → load user → broadcast. Silent drop (return), không throw.
- `TypingRateLimiter`: INCR+EXPIRE 1/2s/key `rate:typing:{userId}:{convId}`. Fail-open Redis down.
- `AuthChannelInterceptor.DestinationPolicy` enum: `STRICT_MEMBER` (throw FORBIDDEN, dùng cho `.message/.edit/.delete`) vs `SILENT_DROP` (pass through, dùng cho `.typing`). `resolveSendPolicy(destination)` switch-by-suffix, unknown → STRICT (safe default).
- Test: `@MockitoSettings(LENIENT)` khi setUp stub broad; `verifyNoInteractions(memberRepo)` cho SILENT_DROP.

### Unified ACK/ERROR + Edit/Delete (W5-D2/D3)
- `AckPayload{operation, clientId, message}`, `ErrorPayload{operation, clientId, error, code}`. `operation` = "SEND"|"EDIT"|"DELETE"|"READ". ADR-017.
- Anti-enum `MSG_NOT_FOUND` merge: null, wrong conv, not owner, soft-deleted. `details.clientEditId` (không tempId).
- Edit dedup: `msg:edit-dedup:{userId}:{clientEditId}` TTL 60s atomic `SET NX EX` TRƯỚC DB mutation. Duplicate → GET value ("PENDING"→silent drop, real id→re-send ACK). Sau save: `SET key messageId EX 60`.
- Edit rate limit tách: `rate:msg-edit:{userId}` 10/min.
- Edit window: `Duration.between(createdAt.atZoneSameInstant(UTC), now).getSeconds() > 300` → `MSG_EDIT_WINDOW_EXPIRED`. No-op check: `newContent.trim().equals(message.content.trim())` → `MSG_NO_CHANGE`. Order: validate → rate limit → dedup → load → window → no-op → save.
- Delete: soft delete via `message.markAsDeletedBy(UUID userId)` set `deletedAt + deletedBy`. V6 migration `deleted_by UUID NULL REFERENCES users(id) ON DELETE SET NULL`.
- Content strip tại MAPPER: `MessageMapper.toDto` check `deletedAt != null` → `content = null`, `attachments = emptyList()`. Applied TẤT CẢ path (REST + WS).
- DELETE ACK shape khác: minimal map `{id, conversationId, deletedAt, deletedBy}` (không full DTO). Dùng `Map<String,Object>`.
- Rate limit delete: `rate:msg-delete:{userId}` 10/min. Dedup `msg:delete-dedup:{userId}:{clientDeleteId}` TTL 60s.
- `MessageDeletedEvent(convId, messageId, Instant deletedAt, UUID deletedBy)` — `Instant` để `.toString()` ISO8601. `Map.of()` NPE với null → đảm bảo non-null trước truyền.

### Forward Pagination `after` (W5-D4)
- `GET /messages?after=ISO8601` forward (catch-up), ORDER ASC, INCLUDE deleted (FE cần placeholder).
- `cursor` và `after` mutually exclusive: cả 2 non-null → 400 `VALIDATION_FAILED` tại controller.
- Repository `findByConversation_IdAndCreatedAtAfterOrderByCreatedAtAsc` — KHÔNG filter deletedAtIsNull.
- `nextCursor` forward = newest item (last); backward = oldest (first sau reverse).
- `ReplyPreviewDto.deletedAt` field mới: deleted source → contentPreview=null, deletedAt=string. `MessageMapper.toReplyPreview(Message)` public.
- STOMP reply validation: SAU membership, TRƯỚC rate limit. `existsByIdAndConversation_Id` false + `existsById` true → "thuộc conv khác"; cả 2 false → "không tồn tại". Dùng `getReferenceById` cho lazy ref.

---

## File Upload (W6)

### Foundation (W6-D1)
- Package `com.chatapp.file.{entity,repository,dto,service,controller,exception,storage,scheduler}`.
- `StorageService` interface 3 method (store/retrieve/delete) → `LocalStorageService` V1, swap-ready S3 V2 (ADR-019).
- Path layout `{base}/{yyyy}/{MM}/{uuid}.{ext}` — KHÔNG ghép originalName (path traversal).
- `assertWithinBase()` canonical-prefix check qua `normalize() + toAbsolutePath() + startsWith(basePath)`. Reject fileId chứa `/`, `\`, `..`; reject ext chứa `.`. Trả relative path `\`→`/`.
- MIME validate: Tika `new Tika().detect(InputStream)` — peek ~8KB, không consume. Alias `image/jpg → image/jpeg`. MIME→ext CỐ ĐỊNH trong `FileValidationService` (không đọc filename).
- 6 exception class riêng (FileEmpty/FileTooLarge/FileTypeNotAllowed/MimeMismatch/FileRateLimited/Storage) + wire-in `MaxUploadSizeExceededException`, `MissingServletRequestPartException` → FILE_EMPTY.
- application.yml: `spring.servlet.multipart.max-file-size: 20MB`, `max-request-size: 21MB`, `storage.local.base-path`. Test override `./build/test-uploads`.
- Controller: `@AuthenticationPrincipal User user` (KHÔNG `UserDetails` — project không dùng UserDetailsService). `@PostMapping(consumes=MULTIPART_FORM_DATA_VALUE)`.
- Download: `InputStreamResource + CacheControl.maxAge(7d).cachePrivate() + ETag=id + X-Content-Type-Options: nosniff`.
- Anti-enum download: merge not-found/not-owner/expired → 404 NOT_FOUND.
- Test: `MockMultipartFile`, `@TempDir` cho LocalStorageService. `SizedMockMultipartFile` custom cho test size limit không alloc 20MB. Hardcoded magic bytes cho JPEG/PNG/PDF.
- Orphan concept: `attached_at NULL` = chưa gắn message. Cleanup job orphan (1h) + expiry job (30d) ở W6-D3.

### Thumbnail + Auth + Attachments (W6-D2)
- `FileAuthService.findAccessibleById(fileId, userId)` → `Optional<FileRecord>`. Rule: (1) uploader, (2) conv-member qua JOIN `message_attachments → messages → conversation_members`. Merge 404 mọi fail (ADR-019). JPQL `COUNT(ma) > 0` không load entity thừa.
- `ThumbnailService`: Thumbnailator 0.4.20, 200×200 JPEG, suffix `_thumb`. Fail-open (ADR-020) — thumb lỗi không fail upload; DB `thumbnail_internal_path=null`; GET /thumb 404.
- `FileDto.thumbUrl` chỉ non-null khi `record.thumbnailInternalPath != null` (KHÔNG dựa MIME check).
- `StorageService.resolveAbsolute(internalPath)`: throw `SecurityException` (không `IllegalArgumentException`) khi path traversal — phân biệt attack vs args invalid.
- Test Thumbnailator: `ImageIO.write(bufferedImage, "jpg", baos)` cho valid JPEG bytes (20-byte JPEG_MAGIC fail "starts with 0xff 0xd9").
- `validateAndAttachFiles` order (rẻ→đắt) trong cùng `@Transactional`: (1) count >5 → `MSG_ATTACHMENTS_TOO_MANY`; (2) findAllById size mismatch → `MSG_ATTACHMENT_NOT_FOUND`; (3) uploader != sender → `MSG_ATTACHMENT_NOT_OWNED`; (4) expires_at < now → `MSG_ATTACHMENT_EXPIRED`; (5) `existsByIdFileId` → `MSG_ATTACHMENT_ALREADY_USED`; (6) group type (all images OR 1 non-image) → `MSG_ATTACHMENTS_MIXED`. Fail-fast rollback.
- `MessageDto.attachments` LUÔN `List<FileDto>` non-null (`Collections.emptyList()` khi deleted).
- `MessageMapper` N+1 warning: 1 query findByMessageId + N findById per file. Page 50 × 5 = ~250 queries worst-case. V1 acceptable (Hibernate 2nd cache, list không hot path). V2 `@EntityGraph` / JOIN FETCH. Documented javadoc.
- `SendMessagePayload` thêm 5th field `List<UUID> attachmentIds` nullable. BREAKING record — grep-fix call sites.
- `MessageDto` constructor order: `(id, convId, sender, type, content, attachments, replyToMessage, editedAt, createdAt, deletedAt, deletedBy)`. attachments chèn sau content.
- `MessageService` constructor +2 deps (`FileRecordRepository`, `MessageAttachmentRepository`), 8→10 args. Update test constructor calls.
- **Content XOR Attachments** (ADR-021): blank content + empty attachments → `MSG_NO_CONTENT`. Content `""` (empty string) khi attachments-only vì DB `content` NOT NULL. FE dùng `attachments.length > 0` render.
- Edit immutable attachments V1 — chỉ sửa content. Comment trong `editViaStomp` javadoc.

### @Scheduled Cleanup (W6-D3)
- `@EnableScheduling` trên `@SpringBootApplication`.
- `@ConditionalOnProperty(name="app.file-cleanup.enabled", havingValue="true", matchIfMissing=true)` — disable via property.
- Cron Spring 6 = 6 fields `second minute hour day month weekday`. Externalize `${ENV_VAR:default}`.
- Test profile `enabled=true` + `cron="-"` (Spring disabled value) → bean load, trigger không fire. Gọi method trực tiếp trong test.
- Batch pagination: `PageRequest.of(0, 100)` luôn page 0 (records processed rời predicate). Terminate `isEmpty() || getNumberOfElements() < BATCH_SIZE`.
- Per-record try-catch: 1 IOException không kill job. Log + continue. Expired job sau exception vẫn `setExpired(true)` defensive.
- stillAttached: physical delete trước → `existsByIdFileId` check → attached: `setExpired(true) + save`; else delete DB. GET /files/{id} → `openStream` → `StorageException` → controller catch → 404 `FILE_PHYSICALLY_DELETED`.
- `LocalStorageService.delete()` dùng `Files.deleteIfExists()` — idempotent race-safe.
- `OffsetDateTime.now(ZoneOffset.UTC)` cho threshold. Test dùng `minusDays(2)` (không `minusHours`) tránh H2 sub-hour precision.
- JdbcTemplate timestamp override: `java.sql.Timestamp.from(odt.toInstant())` + `CAST(? AS UUID)` WHERE clause.
- V2 multi-instance: Redis SETNX distributed lock `lock:file-cleanup:expired` TTL 30 phút. Track WARNINGS.md V2 bucket.

### File Type Expansion (W6-D4-extend)
- Whitelist 14 MIME (4 image + 10 non-image). `Set.of()` không giới hạn args; `Map.ofEntries(Map.entry(...))` cho MIME_TO_EXT >10 entries.
- Charset strip: `tika.detect().split(";")[0].trim()` cho `text/plain`.
- ZIP→Office override: Tika → `application/zip` + ext `.docx/.xlsx/.pptx` → override. ZIP thật giữ nguyên. Sau override vẫn `ALLOWED_MIMES.contains` check.
- `iconType` server-computed: IMAGE/PDF/WORD/EXCEL/POWERPOINT/TEXT/ARCHIVE/GENERIC. `GENERIC` fallback null + unknown.
- Group validation: `singleNonImage` = `files.size()==1 && !allImages`. Bao gồm tất cả Group B.

---

## Group Chat (W7)

### Schema + CRUD (W7-D1, ADR-020)

**Enum permission methods** (anti-scatter):
- `MemberRole` embed 6 methods: `canRename()`, `canAddMembers()`, `canRemoveMember(targetRole)`, `canChangeRole()`, `canDeleteGroup()`, `canTransferOwnership()`. Service gọi `member.getRole().canX()`. Khi spec đổi (vd MODERATOR), chỉ sửa enum.
- `canRemoveMember(target)` 2-tham số hierarchy: OWNER kick bất kỳ trừ OWNER; ADMIN kick MEMBER; MEMBER không kick được.

**CHECK constraint type-specific columns**:
- `CHECK ((type='A' AND col1 IS NULL) OR (type='B' AND col1 IS NOT NULL))` — shape invariant ở DB.
- H2 test profile `ddl-auto: create-drop` KHÔNG tạo CHECK từ migration SQL. Validate ở Java layer trong test.

**Soft-delete `deleted_at IS NULL`**:
- `findActiveById(UUID)` + `findActiveByIdWithMembers(UUID)` filter. Caller dùng cho PATCH/DELETE/GET flows.
- Native list queries thêm `WHERE c.deleted_at IS NULL`. Partial index `WHERE deleted_at IS NOT NULL` (audit).

**ON DELETE SET NULL cho owner_id**:
- `conversations.owner_id UUID REFERENCES users(id) ON DELETE SET NULL` — user xoá account, group sống. V1 không auto-promote; owner=null, FE fallback UI.
- Khác CASCADE cho message_attachments: attachment đi theo message.

**Tristate DTO cho PATCH (absent/null/value)**:
- Jackson record KHÔNG phân biệt "absent" vs "null". Dùng `@JsonAnySetter` + Map:
  ```java
  private final Map<String, Object> rawFields = new HashMap<>();
  @JsonAnySetter public void set(String k, Object v) { rawFields.put(k, v); }
  public boolean hasAvatarFileId() { return rawFields.containsKey("avatarFileId"); }
  public boolean isRemoveAvatar() { return hasAvatarFileId() && rawFields.get("avatarFileId") == null; }
  ```
- Semantics: `undefined→no change`, `null→remove`, `uuid→set`.

**LinkedHashMap cho broadcast null-tolerant**:
- `Map.of()` NPE với null value. `CONVERSATION_UPDATED` có `changes: {avatarUrl: null}` (remove) → LinkedHashMap `put()`.

**Avatar attach flow**:
- Validate: (1) exists (merge anti-enum với not-owned), (2) uploader=caller, (3) MIME ∈ {jpeg,png,webp,gif}, (4) chưa expired. Helper `validateGroupAvatar(fileId, callerId)` chung create + update.
- Sau validate → `fileRecord.markAttached()` + save → orphan cleanup skip. Avatar qua `conversations.avatar_file_id`, KHÔNG qua `message_attachments` (tránh UNIQUE conflict).

**Broadcast via Event Publisher**:
- `ConversationUpdatedEvent{convId, changes Map, actorId, actorFullName, occurredAt}`, `GroupDeletedEvent{convId, actorId, actorFullName, deletedAt}`.
- `ConversationBroadcaster` `@TransactionalEventListener(AFTER_COMMIT)` + try-catch toàn bộ.

**Naming drift V7→V9**: V7/V8 đã occupied bởi files (W6). Docs viết V7__add_group_chat nhưng thực tế V9. Flyway filename là source of truth.

**Member sort**: `role ASC (ordinal)` → OWNER(0), ADMIN(1), MEMBER(2). Secondary: `joinedAt ASC`. Java `Comparator.comparing(m->m.getRole().ordinal()).thenComparing(m->m.getJoinedAt().toInstant())`.

**Backward-compat DIRECT shape**: W7 `targetUserId` singular; legacy W3 `memberIds: [uuid]`. Service accept cả hai.

**Schema column verification test**: `DataSource.getConnection().getMetaData().getColumns(null, null, TABLE, COLUMN)` case-insensitive (H2 uppercase unquoted). Không test CHECK constraint (H2 create-drop không apply migration SQL).

### Member Management + Owner Transfer (W7-D2)

**Race-safe lock H2/Postgres portable**:
- Hibernate `@Lock(PESSIMISTIC_WRITE)` → `FOR NO KEY UPDATE` (Postgres-only), H2 90232 reject. Fix: native SQL `FOR UPDATE`.
- H2 từ chối `SELECT COUNT(*) ... FOR UPDATE` (90145). Postgres cho. Pattern portable: SELECT rows + count Java. Acceptable V1 (group max 50).
- Native SQL UUID column H2 → `byte[]`, ConversionFailedException. Dùng `CAST(col AS VARCHAR)` + `List<String>`.

**Auto-transfer query (OWNER leave)**:
- Native SQL vì JPQL CASE với enum literal không portable. Pattern: `ORDER BY CASE role WHEN 'ADMIN' THEN 0 WHEN 'MEMBER' THEN 1 ELSE 2 END ASC, joined_at ASC`. Trả `List` (không LIMIT 1) để test coverage fallback.
- OWNER→ADMIN sau `/transfer-owner` (giữ quyền). OWNER→MEMBER chỉ trong flow `/leave` (demote trước delete row — cho phép OWNER_TRANSFERRED fire trước MEMBER_REMOVED).

**Partial-success response (addMembers)**:
- `{added: List<MemberDto>, skipped: List<SkippedMemberDto{userId, reason}>}` với `@JsonInclude ALWAYS`. Reasons: `ALREADY_MEMBER`, `USER_NOT_FOUND` (merge anti-enum), `BLOCKED` (V1 reserved).
- `MEMBER_LIMIT_EXCEEDED` vẫn all-or-nothing (409) — tính trên validToAddCount TRƯỚC insert.

**@TransactionalEventListener với DB access**:
- Listener AFTER_COMMIT → request TX đã close. Cần `@Transactional(propagation=REQUIRES_NEW, readOnly=true)` — TX mới auto-commit khi listener return. Thiếu → LazyInitializationException.

**Unified actor shape**:
- `ActorSummaryDto{userId, username}` — response.
- Broadcast actor `{userId, username, fullName}` — FULLER (thêm fullName render). Build LinkedHashMap trực tiếp trong broadcaster.
- `PreviousOwnerDto{userId, username, newRole="ADMIN"}` — hardcode newRole.

**No-op idempotent (changeRole)**: Same role → 200 OK, KHÔNG publish event → KHÔNG broadcast. Test: `reset(messagingTemplate)` trước no-op case.

**User-specific destinations**:
- `/user/queue/conv-added`: `convertAndSendToUser(userIdString, "/queue/conv-added", ConversationSummaryDto)`. FE subscribe literal `/user/queue/conv-added`.
- `/user/queue/conv-removed`: CHỈ fire khi `reason="KICKED"` (LEFT không fire — user tự leave). Payload `{conversationId, reason: "KICKED"}`.
- Offline caveat V1: SimpleBroker không persist → offline user miss frame. FE mitigate qua GET /conversations reconnect.

### Hybrid File Visibility (W7-D4-fix, ADR-021)

**`is_public` flag + `/public` endpoint**:
- Column `files.is_public BOOLEAN NOT NULL DEFAULT FALSE` (V11). Upload `?public=true|false` (default false). `FileService.upload(file, userId, isPublic)` 3-param + backward-compat 2-param overload.
- Endpoint `GET /api/files/{id}/public` KHÔNG auth (SecurityConfig whitelist). Anti-enum 404 cho not-found/is_public=false/expired.
- `GET /api/files/{id}` (private) giữ JWT + uploader/member check.
- `Cache-Control: public, max-age=86400` cho `/public`. Avatar change = URL change = cache miss tự nhiên.
- FileDto +2 fields: `isPublic` + `publicUrl` (null nếu private). `url` resolve theo is_public. `thumbUrl` CHỈ private image V1.

**Seed default records + FileConstants**:
- V11 seed `(00000000-...-001, user default)` + `(00000000-...-002, group default)`, `is_public=TRUE`, `expires_at=9999-12-31`, `attached_at=NOW()`, `ON CONFLICT (id) DO NOTHING`.
- `FileConstants`: `DEFAULT_USER_AVATAR_ID`, `DEFAULT_GROUP_AVATAR_ID`, `DEFAULT_*_URL` (`/api/files/{id}/public`), helper `publicUrl(id)`/`privateUrl(id)`, `DEFAULT_AVATAR_IDS` Set cho cleanup check.
- Register → `user.setAvatarUrl(DEFAULT_USER_AVATAR_URL)`. User entity có `avatar_url` String (không `avatar_file_id`), set string trực tiếp.
- createGroup no avatarFileId → `DEFAULT_GROUP_AVATAR_ID`. updateGroup remove avatar → FALLBACK `DEFAULT_GROUP_AVATAR_ID` (KHÔNG để NULL).

**@PostConstruct validate deployment assets (graceful missing)**:
- `FileService.validateDefaultAvatars()`: check 2 physical files qua `storageService.resolveAbsolute` + `Files.exists`. Log WARN nếu thiếu, KHÔNG fail startup. Production runbook: manual `cp default-avatars/*.jpg ${STORAGE_PATH}/default/`.
- Wrap toàn bộ try-catch (non-local storage S3 V2 throw UnsupportedOperationException → skip).

**SecurityConfig order matters** (pitfall):
- `.requestMatchers("/api/files/*/public").permitAll()` PHẢI TRƯỚC `.anyRequest().authenticated()`. Đảo order → authenticated match trước → 401.
- Verify: GET `/api/files/{id}/public` không token → 200; GET `/api/files/{id}` không token → 401.

**Lombok boolean naming** (pitfall):
- Field `boolean isPublic` → Lombok @Getter sinh `isPublic()`, KHÔNG `isIsPublic()`. Lombok detect `is` prefix và strip.
- Dùng `record.isPublic()`.

**FileCleanupJob skip defaults**:
- `FileConstants.DEFAULT_AVATAR_IDS` Set<UUID> → cleanup `if (DEFAULT_AVATAR_IDS.contains(file.getId())) continue;`. Defense-in-depth.

**H2 test V11 Flyway disabled pitfall**:
- `flyway.enabled=false` + `ddl-auto=create-drop` → V11 KHÔNG chạy. Hibernate tạo schema từ entity annotation (is_public OK, nhưng thiếu seed rows).
- Test phải seed 2 default record programmatically trong `@BeforeEach` cho flow dùng default avatar.

### Read Receipt (W7-D5)

**Forward-only idempotent** (ReadReceiptService.markRead):
- Load current `lastReadMessageId` → findById → compare `currentLastRead.createdAt.isBefore(incoming.createdAt)`.
- `false` (current >= incoming) → no-op return, KHÔNG publish event, KHÔNG save.
- Current FK hard-deleted (findById empty) → treat null path → advance always.
- Event published ONLY when advancing → broadcaster AFTER_COMMIT fire chỉ khi real update.

**unreadCount native query (NULL-safe)**:
```sql
SELECT LEAST(COUNT(*), 99) FROM messages
WHERE conversation_id = CAST(:convId AS UUID)
  AND deleted_at IS NULL
  AND type != 'SYSTEM'
  AND (:lastReadId IS NULL
       OR created_at > (SELECT created_at FROM messages WHERE id = CAST(:lastReadId AS UUID)))
```
- Gọi với `String` params (UUID.toString() / null). Cap 99 ở SQL tránh COUNT toàn bộ.
- Fail-graceful trong `ConversationService.buildSummary()`: try-catch countUnread, return 0 exception (tránh break list endpoint).

**ChatReadReceiptHandler (fire-and-forget)**:
- Rate limit `rate:msg-read:{userId}:{convId}` TTL 2s, INCR > 1 → MSG_RATE_LIMITED.
- No ACK (khác SEND/EDIT/DELETE) — read không cần client confirm.
- ErrorPayload `{operation:"READ", clientId:null, ...}` — không clientId per contract §3f.3.
- `.read` destination policy: STRICT_MEMBER (persist DB + broadcast, không silent drop).

---

## Thư viện chính

| Library | Version | Lý do |
|---------|---------|-------|
| spring-boot-starter-parent | 3.4.4 | Stable |
| jjwt-api/impl/jackson | 0.12.6 | API builder mới |
| firebase-admin | 9.4.1 | Google OAuth |
| flyway-core + flyway-database-postgresql | BOM | Postgres Flyway 10+ |
| org.apache.tika:tika-core | 2.9.1 | MIME magic bytes |
| net.coobird:thumbnailator | 0.4.20 | Image thumb |

---

## Pitfalls đã gặp

- Spring Data Redis WARN log khi có JPA+Redis: "Could not safely identify store assignment" — bình thường, không phải lỗi.
- `MethodArgumentTypeMismatchException` subclass của `MethodArgumentConversionNotSupportedException` — handler phải trước catch-all.
- Lombok `boolean isPublic` → `isPublic()` (không `isIsPublic()`). Detect `is` prefix.
- `Map.of()` NPE null → LinkedHashMap cho broadcast.
- H2 `FOR UPDATE` với `COUNT(*)` / `DISTINCT` bị reject → SELECT rows + count Java.
- H2 native UUID column → `byte[]` → `CAST AS VARCHAR` + `List<String>`.
- H2 timestamp test dùng `plusDays` / `minusDays` tránh sub-hour/sub-ms precision.
- Jackson JSONB `@JdbcTypeCode(SqlTypes.JSON)` fail H2 → `@Converter` Jackson direct (`JsonMapConverter`).
- Boolean JSONB assert dùng `Boolean.FALSE/TRUE` wrapped (Mockito default null, primitive unbox NPE).

---

### Message Reactions (W8-D1)

**Package**: `com.chatapp.reaction.{entity,repository,dto,event,service,broadcast}`.

**Toggle pattern (ReactionService.react)**:
- No row → INSERT → broadcast ADDED (emoji=new, previousEmoji=null)
- Row with same emoji → DELETE → broadcast REMOVED (emoji=null, previousEmoji=old)
- Row with different emoji → UPDATE → broadcast CHANGED (emoji=new, previousEmoji=old)
- UPDATE (không DELETE+INSERT) cho CHANGED — giữ id stable, tránh race condition.

**Emoji validation (EmojiValidator)**:
- Byte-length check TRƯỚC regex — tránh regex bypass với compound emoji dài.
- VARCHAR(20) DB constraint → reject > 20 bytes UTF-8. Scotland flag = 28 bytes → REJECT.
- Pattern covers: Miscellaneous Symbols (U+2600), Supplemental (U+1F000+), ZWJ (U+200D), skin-tone, variation selectors.

**N+1 mitigation (batch load reactions)**:
- Trong `MessageService.buildDtosWithReactions()`: 1 query `findAllByMessageIdIn(messageIds)`.
- Group by messageId in Java memory → pass vào `messageMapper.toDto(m, currentUserId, reactions)`.
- KHÔNG `@OneToMany(fetch=LAZY)` rồi access trong loop.

**Aggregate helper (MessageMapper.aggregateReactions)**:
- Group reactions by emoji → List<ReactionAggregateDto>.
- Sort: count DESC, emoji ASC (codepoint — stable, deterministic).
- currentUserReacted: in-memory check `userIds.contains(currentUserId)` (không query thêm).

**STOMP handler (ChatReactHandler)**:
- Destination: `/app/msg.{messageId}.react` — messageId từ path variable, KHÔNG conv.
- HANDLER_CHECK policy: interceptor pass-through cho `/app/msg.*`, member check trong ReactionService.
- clientId = null trong ERROR frame (REACT không có clientId — contract §3.15).
- Không có ACK riêng — confirmation qua broadcast REACTION_CHANGED trên /topic/conv.{convId}.

**Broadcaster (ReactionBroadcaster)**:
- `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW, readOnly)` — TX mới để DB access sau TX gốc close.
- LinkedHashMap cho payload (Map.of() NPE khi emoji=null REMOVED case).

**AuthChannelInterceptor update**:
- Thêm `APP_MSG_PREFIX = "/app/msg."` → handleSend pass-through (return early) cho prefix này.
- `.read` suffix là STRICT_MEMBER (W7-D5 đã chốt, test cũ viết sai → đã fix test).

## Pin Message + User Block (W8-D2)

**Pin Message (PinService + ChatPinHandler)**:
- STOMP destination `/app/msg.{messageId}.pin`, action field `"PIN"|"UNPIN"` trong payload.
- Permission: ONE_ON_ONE → any member; GROUP → `MemberRole.isAdminOrHigher()` (OWNER|ADMIN).
- PIN_LIMIT = 3 per conversation. `countPinnedInConversation` query filter `deletedAt IS NULL`.
- Idempotency: re-pin already-pinned → 200 no-op; re-unpin → 200 no-op.
- Events `MessagePinnedEvent` / `MessageUnpinnedEvent` → `PinBroadcaster` `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW, readOnly)`.
- `pinnedAt Instant` + `pinnedByUserId UUID` trên Message entity (KHÔNG dùng OffsetDateTime cho pin).
- `AuthChannelInterceptor` APP_MSG_PREFIX `/app/msg.` → pass-through đã bao gồm `.pin`.
- `MessageDto` mở rộng: `pinnedAt Instant` + `pinnedBy Map<String,Object>` (null khi chưa pin/bị xóa).
- `ConversationDto` mở rộng: `pinnedMessages List<MessageDto>` — nullable, chỉ populate trong `getConversation` detail endpoint (overloaded `from(conv, ownerResolver, pinnedMessages)`).
- `MessageRepository` thêm: `countPinnedInConversation(convId)` + `findPinnedByConversation(convId)` cả hai filter `deletedAt IS NULL`.

**User Block (BlockService + BlockController)**:
- Bilateral check: `existsBilateral` query `(a→b) OR (b→a)`. Block là 1-chiều (người block → blocked), nhưng check 2 chiều để block messaging.
- Self-block guard: `CANNOT_BLOCK_SELF`. Idempotency: block đã có → 200 no-op (KHÔNG exception).
- `unblock`: dùng `@Modifying @Transactional @Query DELETE` thay vì `findBy` + `delete` để tránh extra SELECT.
- `UserBlockRepository.findByBlocker_IdOrderByCreatedAtDesc` (dấu `_`) + `deleteByBlockerIdAndBlockedId` native JPQL.
- Block check integration: `MessageService.sendViaStomp` (ONE_ON_ONE only), `ConversationService.createOneOnOne` — throw `MSG_USER_BLOCKED`.
- REST: `POST /api/users/{id}/block`, `DELETE /api/users/{id}/block`, `GET /api/users/blocked` → `List<UserSearchDto>`.

**Pitfall W8-D2**:
- `@Column(name="system_metadata", columnDefinition="jsonb")` KHÔNG thêm columnDefinition nếu field có `@Convert(JsonMapConverter)` — H2 test mode nhận jsonb type → double-quote JSON string → `JsonMapConverter` throw. Giữ `@Column(name="system_metadata")` không có columnDefinition.
- Record DTO mở rộng: khi thêm field vào record `MessageDto`, tất cả constructor calls trong test phải update. Grep `new MessageDto(` sau mỗi thay đổi.

## Changelog file này

- 2026-04-22 W8D2: Pin Message + Bilateral Block — PinService, BlockService, STOMP /app/msg.*.pin, pinnedAt Instant (không OffsetDateTime), bilateral existsBilateral query, columnDefinition="jsonb" H2 pitfall.
- 2026-04-22 W8D1: Reactions — toggle pattern, emoji byte-len check trước regex, batch N+1 mitigation, HANDLER_CHECK for /app/msg.*, clientId=null ERROR frame, LinkedHashMap null-safe.
- 2026-04-22 (Consolidation W7): Compress W1-W5 domain patterns, preserve W6-W7 full, ADRs inline.
- 2026-04-22 W7D5: Read Receipt Pattern — forward-only idempotent, countUnread NULL-safe native LEAST cap 99, ChatReadReceiptHandler fire-and-forget no ACK.
- 2026-04-22 W7D4-fix: Hybrid File Visibility (ADR-021) — is_public flag, /public endpoint, FileConstants, @PostConstruct validate defaults, SecurityConfig order, Lombok isPublic, FileCleanupJob skip defaults, H2 V11 seed pitfall.
- 2026-04-21 W7D2: Member Management — race-safe lock native FOR UPDATE + SELECT rows, role hierarchy canRemoveMember(target), auto-transfer CASE query, OWNER→ADMIN sau transfer, partial-success shape, REQUIRES_NEW cho listener DB access, /queue/conv-added|removed.
- 2026-04-21 W7D1: Group Chat Schema + CRUD (ADR-020) — MemberRole permission methods, CHECK constraint, soft-delete filter, ON DELETE SET NULL owner, Tristate DTO @JsonAnySetter Map, LinkedHashMap broadcast, avatar attach flow, Event Publisher, naming drift V7→V9, member sort, targetUserId backward-compat, DataSource metadata test.
- 2026-04-21 W6D2: FileAuthService (uploader OR conv-member, JPQL JOIN), ThumbnailService fail-open, StorageService.resolveAbsolute, validateAndAttachFiles order, MessageDto.attachments non-null, MessageMapper N+1 warning, Thumbnailator ImageIO test, DB NOT NULL content → persist "".
- 2026-04-21 W6D1: File Upload Foundation — Tika MIME, LocalStorageService path traversal defense, MIME→ext cố định, 6 exception class, anti-enum 404 download, MultipartFile test pattern. V7 UUID FK.
- 2026-04-20 W5 patterns: Forward pagination, Soft Delete Message, Unified ACK/ERROR (ADR-017), Edit Dedup, Edit Window, Destination Policy, Ephemeral Event.
- 2026-04-19 W4: TransactionalEventListener Broadcast, MessageMapper extraction, WebSocket/STOMP config, raw WS test pattern.
- 2026-04-19 W3: Conversation domain, UUID @PrePersist, findOrCreate SQL, anti-enum, flush+clear, H2 UUID native workaround.
- 2026-04-19 W2: Auth/Firebase/Logout, Refresh rotation, AuthMethod enum, user enumeration protection.
- 2026-04-19 W1: Security/JWT patterns, ErrorResponse, CORS, jjwt 0.12.x API.
