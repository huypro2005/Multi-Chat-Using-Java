# Backend Log — Nhật ký chi tiết backend-dev

> Quy tắc: append-only, mới nhất ở ĐẦU file.
> Chỉ đọc 20 dòng đầu khi cần biết tiến độ. Tra cứu pitfall/pattern theo tên section.

---

## Template cho mỗi entry

```
## [W{n}] {Feature} — key decisions + pitfalls
- Decision: ...
- Pitfall: ...
- Pattern: ...
```

---

## [W6-D2] feat: Thumbnail + FileAuthService + STOMP attachments

**Files changed:**
- `backend/pom.xml` — thêm `net.coobird:thumbnailator:0.4.20`.
- `backend/src/main/resources/db/migration/V8__add_thumbnail_path.sql` — ALTER TABLE files ADD COLUMN `thumbnail_internal_path VARCHAR(1024) NULL`.
- `com.chatapp.file.entity.FileRecord` — thêm field `thumbnailInternalPath`.
- `com.chatapp.file.storage.StorageService` — thêm method `resolveAbsolute(String) throws SecurityException` vào interface.
- `com.chatapp.file.storage.LocalStorageService` — implement `resolveAbsolute` với canonical prefix check, throw SecurityException khi traversal.
- `com.chatapp.file.service.ThumbnailService` — mới. 200×200 fit-in-box + 0.85 quality JPEG-style output (giữ format gốc), suffix `_thumb`. Fail-open — upload thành công dù thumbnail fail.
- `com.chatapp.file.service.FileService` — inject ThumbnailService, sau save FileRecord gọi generate (fail-open). `toDto` đổi `thumbUrl` check sang `thumbnailInternalPath != null` thay vì `isImage()`. Thêm `openThumbnailStream(record)`.
- `com.chatapp.file.service.FileAuthService` — mới. `findAccessibleById(fileId, userId)` rule uploader OR conv-member, merge 404 cho mọi fail case (expired, not-accessible, not-found).
- `com.chatapp.file.repository.MessageAttachmentRepository` — thêm `existsByIdFileId(UUID)` + `existsByFileIdAndConvMemberUserId(UUID, UUID)` JPQL JOIN query.
- `com.chatapp.file.controller.FileController` — thay `loadForDownload` bằng `fileAuthService.findAccessibleById`. Thêm endpoint `GET /{id}/thumb` (200 với Cache-Control 7d + ETag `{id}-thumb` + nosniff). Sanitize filename header (strip CRLF + non-ASCII).
- `com.chatapp.message.dto.SendMessagePayload` — thêm field `List<UUID> attachmentIds` (nullable).
- `com.chatapp.message.dto.MessageDto` — thêm field `List<FileDto> attachments` (chèn sau content, trước replyToMessage).
- `com.chatapp.message.service.MessageMapper` — inject MessageAttachmentRepository + FileRecordRepository, load attachments qua JOIN ORDER BY display_order, strip thành emptyList khi deleted.
- `com.chatapp.message.service.MessageService` — inject FileRecordRepository + MessageAttachmentRepository (+2 args). `validateStompPayload` thêm XOR rule content/attachments → `MSG_NO_CONTENT`. `sendViaStomp` derive MessageType (TEXT/IMAGE/FILE) + gọi `validateAndAttachFiles` sau save message. Content NOT NULL pitfall → persist `""` thay vì null cho attachments-only message. Edit giữ nguyên, thêm javadoc note attachments immutable V1.

**Tests:**
- `FileControllerTest` thêm 8 tests (F11–F18): thumbnail path DB, PDF null thumb, GET /thumb 200, PDF /thumb 404, no-JWT 401, conv-member download OK, non-member 404, expired 404. Update F01/F08 dùng valid JPEG bytes (ImageIO) thay vì 20-byte magic để thumbnail generate thành công.
- `MessageServiceStompTest` thêm 11 tests (W6-T01–T11): 1 image, 5 images, 6 images rejected, 1 PDF, 2 PDFs mixed, PDF+image mixed, other-user file rejected, already-used file rejected, empty content+no-attach → MSG_NO_CONTENT, null content+1 image OK, non-existent file rejected. T-STOMP-04 blank content test: đổi expect từ VALIDATION_FAILED → MSG_NO_CONTENT.
- `ChatDeleteMessageHandlerTest.messageMapper_stripsContentWhenDeleted` thêm assertion `attachments` empty list khi deleted. Constructor call `new MessageMapper(null, null)` — deleted path không query repos.
- Update 3 test `new MessageService(...)` constructor calls (ChatEdit, ChatDelete, MessageServiceStomp) thêm 2 mocks FileRecordRepository + MessageAttachmentRepository.
- Update 4 `new MessageDto(...)` call sites để thêm `attachments=Collections.emptyList()` vào vị trí 6.
- Update 5 `new SendMessagePayload(...)` call sites thêm `null` cho attachmentIds.

**Test count**: 191 pass (172 baseline + 19 new: 5 thumbnail + 3 auth + 11 attachments).

**Key decisions:**
- Thumbnail Content-Type giữ MIME gốc (image/jpeg/png/webp/gif) thay vì always JPEG như contract nói → align với Thumbnailator default behavior + tránh phải map Content-Type riêng. Contract note "thumbnail always JPEG" là V2 concern.
- Content DB column NOT NULL giữ nguyên V1 → persist empty string cho attachment-only message. V2 có thể migrate cho nullable nếu cần rõ ràng hơn.
- Validation order: count → existence → ownership → expiry → unique → group. Count trước để fail-fast không load DB thừa. Group check cuối vì cần load đủ file records.

**Git**: chưa commit, user sẽ commit thủ công.

---

## [W6-D1] feat: File Upload Foundation (upload + download stub)

**Files changed (new unless noted):**
- `backend/pom.xml` — thêm `org.apache.tika:tika-core:2.9.1` dependency.
- `backend/src/main/resources/db/migration/V7__create_files_tables.sql` — tables `files` + `message_attachments`. FK type UUID (khớp users.id, messages.id), KHÔNG BIGINT như task spec.
- `com.chatapp.file.entity.FileRecord` — UUID PK + uploaderId UUID + storage_path + expires_at + attached_at + domain method `markAttached()`/`isImage()`.
- `com.chatapp.file.entity.MessageAttachment` + `MessageAttachmentId` — composite key (@EmbeddedId) cho M2M messages↔files. W6-D1 setup sẵn; flow link message gắn ở W6-D2.
- `com.chatapp.file.repository.FileRecordRepository` — findByIdAndExpiredFalse + orphan query method.
- `com.chatapp.file.repository.MessageAttachmentRepository` — lookup by messageId / fileId.
- `com.chatapp.file.dto.FileDto` — record 7 fields; url computed `/api/files/{id}`, thumbUrl null cho non-image.
- `com.chatapp.file.storage.StorageService` — interface (store/retrieve/delete), swap-ready cho S3 V2.
- `com.chatapp.file.storage.LocalStorageService` — V1 disk implementation. Canonical-prefix check, reject path traversal + fileId/ext chứa separator/dot, trả relative path normalize `/`.
- `com.chatapp.file.service.FileValidationService` — Tika detect magic bytes, whitelist 5 MIME, MIME→ext cố định, alias image/jpg→image/jpeg.
- `com.chatapp.file.service.FileService` — upload flow (validate → rate limit → generate UUID → store → persist DB → map), loadForDownload (W6-D1 stub uploader-only), rate limit 20/min Redis fail-open.
- `com.chatapp.file.controller.FileController` — POST /upload (201 FileDto), GET /{id} (stream với Cache-Control + Content-Disposition + nosniff + ETag). `@AuthenticationPrincipal User` (không UserDetails — đồng bộ JwtAuthFilter).
- `com.chatapp.file.exception.*` — 6 class (FileEmpty, FileTooLarge, FileTypeNotAllowed, MimeMismatch, FileRateLimited, StorageException).
- `com.chatapp.exception.GlobalExceptionHandler` — thêm 8 handler (6 file exception + MaxUploadSizeExceeded + MissingServletRequestPart + MissingServletRequestParameter mapping to FILE_EMPTY).
- `backend/src/main/resources/application.yml` — `spring.servlet.multipart` limits + `storage.local.base-path`.
- `backend/src/test/resources/application-test.yml` — test storage base-path `./build/test-uploads`.
- `backend/src/test/java/com/chatapp/file/FileValidationServiceTest.java` — 10 tests (JPEG/PNG/PDF happy, size/empty/null, whitelist/mismatch, ext map, alias).
- `backend/src/test/java/com/chatapp/file/LocalStorageServiceTest.java` — 7 tests (@TempDir based: path format, roundtrip, traversal reject, delete, non-existent, fileId/ext rejection).
- `backend/src/test/java/com/chatapp/file/FileControllerTest.java` — 10 integration tests (upload happy JPEG/PDF, missing/empty/bad MIME, no JWT, rate limit, download happy/404/anti-enum).

**Test result:** 172 / 172 pass (145 cũ + 27 mới). `mvn test` 52s.

**Decisions:**
- FK UUID (không BIGINT): align với schema hiện tại users.id/messages.id UUID (V2/V5). Task spec sai — tôi override theo thực tế.
- StorageService interface thay vì concrete class: ADR-019 yêu cầu S3 swap V2. Interface mỏng 3 method.
- 6 exception class riêng (không extend AppException): GlobalExceptionHandler có typed getter để format details rõ ràng (maxBytes, allowedMimes, retryAfterSeconds, …).
- Download W6-D1 stub chỉ uploader-only: FE có thể test upload → preview tạm thời; W6-D2 sẽ mở conv-member check khi MessageService gắn attachments xong.

**Pitfall:**
- Cross-package test (`com.chatapp.file` vs `com.chatapp.file.storage`) → `getBasePath()` phải `public` (không package-private). Đã đổi.
- MultipartFile test size > 20MB: viết `SizedMockMultipartFile` wrapper custom (override `getSize()` + `getInputStream()` trả head bytes) thay vì alloc 20MB byte[] (OOM test).
- Tika alias: browser Firefox cũ gửi `image/jpg` thay `image/jpeg` → phải normalize trước khi reject MIME_MISMATCH. Thêm test V10.
- Path traversal trên retrieve: test đúng bằng `IllegalArgumentException` (không IOException) vì `assertWithinBase()` throw trước khi chạm filesystem.

**Next (W6-D2):**
- Thumbnail generation (Thumbnailator) + `/api/files/{id}/thumb` endpoint với lazy cache.
- Authorization mở rộng: uploader OR member-of-conv-with-attachment.
- MessageService integration: attach file vào message khi sendViaStomp (set attached_at).

---

## [W5-D4] feat: after param + ReplyPreviewDto deletedAt + STOMP reply validation

**Files changed:**
- `MessageRepository.java` — thêm `findByConversation_IdAndCreatedAtAfterOrderByCreatedAtAsc` (forward, no deletedAt filter).
- `MessageService.java` — `getMessages` signature thêm `after: OffsetDateTime`, forward branch mới; `sendViaStomp` thêm reply validation (check conv membership, check existence, allow deleted source).
- `MessageController.java` — `getMessages` thêm `after` param, mutex check cursor+after → 400, `ResponseEntity<>` wrapper.
- `MessageMapper.java` — `toReplyPreview(Message)` method mới (public), logic: deleted source → contentPreview=null + deletedAt set.
- `ReplyPreviewDto.java` — thêm `String deletedAt` field (4th). Breaking change — cần update tất cả `new ReplyPreviewDto(...)` calls.
- `SendMessagePayload.java` — thêm `UUID replyToMessageId` field (4th, nullable).
- `MessageControllerTest.java` — 5 tests mới (T17–T21): after param forward pagination, mutex 400, reply-to-deleted 201 với null preview + deletedAt, cross-conv STOMP reply 400, non-existent STOMP reply 400.
- `MessageServiceStompTest.java` — fix 4 `SendMessagePayload` constructor calls (3→4 args).
- `docs/API_CONTRACT.md` — v0.6.2: after param docs, ReplyPreviewDto deletedAt field, version bump.
- `backend-knowledge.md` — thêm Forward Pagination Pattern, ReplyPreviewDto deletedAt, STOMP reply validation patterns.

**Tests:** 145 total, 0 failures, BUILD SUCCESS.

**Pitfall gặp:** `item.get("content").asText()` trả `"null"` string (không phải null) khi JSON node là null — phải check `item.get("deletedAt").isNull()` để identify deleted message thay vì check content value.

---

## [W5-D5] fix: log WARN for duplicate WS requests

**Files:** `MessageService.java` — 3 one-liner additions only.

- Change: Added `log.warn("[DEDUP] Duplicate SEND/EDIT/DELETE request: userId={}, tempId/clientEditId/clientDeleteId={}, convId={}")` immediately before each `handleDuplicate*Frame` call in `sendViaStomp`, `editViaStomp`, `deleteViaStomp`.
- Verified: `mvn test` — 140 tests, 0 failures, BUILD SUCCESS.

---

## [W5-D3] Delete Message via STOMP + soft delete

**Files:** `V6__add_message_deleted_by.sql`, `DeleteMessagePayload`, `MessageDeletedEvent`, `MessageBroadcaster.onMessageDeleted`, `MessageService.deleteViaStomp`, `AuthChannelInterceptor` (.delete → STRICT_MEMBER), `ChatDeleteMessageHandler`, 10 tests.

- Decision: DELETE ACK dùng `Map<String,Object>` thay vì `AckPayload` — DELETE trả minimal metadata (không full MessageDto), tránh overloaded constructor. `Map.of()` không chấp nhận null values → dùng `new HashMap<>()` nếu có null.
- Decision: `MessageDeletedEvent` dùng `Instant` (không `OffsetDateTime`) — gọn hơn khi broadcast ISO8601.
- Pattern: Content strip ở mapper (không ở DB) — DB vẫn lưu content để audit/admin. Mapper set `content = null` khi `deletedAt != null`, áp dụng TẤT CẢ path.
- Pattern: Anti-enumeration — MSG_NOT_FOUND cho cả not-owner, wrong-conv, soft-deleted, non-existent.
- Pattern: Rate limit key `rate:msg-delete:{userId}` max 10/min. Dedup key `msg:delete-dedup:{userId}:{clientDeleteId}` NX EX 60.
- Status: Tests chưa chạy qua `mvn test` (cần user/CI confirm). 10 tests written.

---

## [W5-D2] Edit Message via STOMP + Unified ACK shape (ADR-017)

**Files:** `EditMessagePayload`, `MessageUpdatedEvent`, `AckPayload` (breaking change), `ErrorPayload`, `MessageBroadcaster.onMessageUpdated`, `MessageService.editViaStomp`, `ChatEditMessageHandler`, 12 tests. `mvn test`: 130 pass.

- Decision (ADR-017): `AckPayload` unified shape `(operation, clientId, message)`. `clientId` thay cho `tempId`. Breaking change — tất cả existing `ack.tempId()` call phải đổi thành `ack.clientId()`.
- Pattern: `editViaStomp()` dùng `TransactionSynchronizationManager.isSynchronizationActive()` fallback để unit test (không có Spring transaction) vẫn chạy — sendEditAck gọi ngay trong test context.
- Pattern: Rate limit key `rate:msg-edit:{userId}` max 10/min. Dedup key `msg:edit-dedup:{userId}:{clientEditId}` NX EX 60.
- Pitfall: `message.setCreatedAt()` cần gọi trong setUp để test 300s window check có thể manipulate timestamp.
- Pattern: Anti-enumeration — MSG_NOT_FOUND dùng cho cả not-owner, wrong-conv, soft-deleted, non-existent.

---

## [W5-D1-FixA] Destination-aware Auth Policy in AuthChannelInterceptor

**Files:** `AuthChannelInterceptor` (inner enum `DestinationPolicy`), `AuthChannelInterceptorTest` (10 tests). `mvn test`: 118 pass.

- Decision: `.message` → STRICT_MEMBER (throw FORBIDDEN), `.typing` + `.read` → SILENT_DROP (pass through), unknown suffix → STRICT_MEMBER default.
- Pitfall: `.typing` phải SILENT_DROP không phải FORBIDDEN — throw FORBIDDEN tạo ERROR frame về client, UI hiện lỗi bad UX. Handler (`ChatTypingHandler`) đã có member check + silent drop riêng, interceptor KHÔNG cần DB query cho ephemeral events.
- Pattern: `verifyNoInteractions(conversationMemberRepository)` trong SILENT_DROP tests — verify interceptor không làm DB query thừa.

---

## [W5-D1] Typing Indicator STOMP Handler

**Files:** `TypingPayload`, `TypingRateLimiter`, `ChatTypingHandler`, `ChatTypingHandlerTest` (4 tests). `mvn test`: 108 pass.

- Pattern: Ephemeral event — handler dùng silent drop (return, không throw), rate limit 1 event/2s/key via Redis INCR+EXPIRE, fail-open khi Redis down.
- Decision: Contract (SOCKET_EVENTS.md §3.4) không có `fullName` trong typing payload — task spec có nhưng contract thắng, không thêm field.
- Pitfall: `@MockitoSettings(LENIENT)` cần khi `setUp()` stub quá rộng — test non-member return sớm không gọi rate limiter + userRepo, Mockito strict mode sẽ fail `UnnecessaryStubbingException`.

---

## [W4-D4] Realtime Broadcast on Message Created (TransactionalEventListener)

**Files:** `MessageMapper` (extracted @Component), `MessageCreatedEvent`, `MessageBroadcaster` (`@TransactionalEventListener(AFTER_COMMIT)`), updated `MessageService`. `mvn test`: 104 pass.

- Pattern: `@TransactionalEventListener(phase = AFTER_COMMIT)` — broadcast SAU khi transaction commit. try-catch trong listener để broadcast fail không propagate lên REST controller.
- Pitfall: `@MockBean SimpMessagingTemplate` bắt buộc trong `MessageControllerTest` — `MessageBroadcaster` inject qua constructor, thiếu MockBean → `UnsatisfiedDependencyException` khi Spring context load.
- Decision: Khi broadcaster throw trong AFTER_COMMIT listener, Spring log ERROR nhưng KHÔNG propagate — REST 201 vẫn thành công. Behavior đúng per SOCKET_EVENTS.md.

---

## [W4-D3] WebSocket + STOMP Auth Layer

**Files:** `WebSocketConfig`, `StompPrincipal`, `AuthChannelInterceptor`, `StompErrorHandler`, `SecurityConfig` update, `WebSocketIntegrationTest` (6 tests raw WS). `mvn test`: 89 pass.

- Pitfall (CRITICAL): `WebSocketStompClient`/`DefaultStompSession` KHÔNG expose header "message" từ ERROR frame khi CONNECT bị reject — fire `handleTransportError(ConnectionLostException)`. Test phải dùng raw `StandardWebSocketClient` + `AbstractWebSocketHandler.handleTextMessage` để đọc ERROR frame nguyên bản.
- Pitfall: Custom `StompSubProtocolErrorHandler` phải set qua `StompSubProtocolHandler.setErrorHandler()`. `SubProtocolWebSocketHandler` KHÔNG expose setErrorHandler. Workaround: `@EventListener(ContextRefreshedEvent)` → unwrap `WebSocketHandlerDecorator` → loop `getProtocolHandlers()` → `StompSubProtocolHandler.setErrorHandler()`.
- Pitfall: Mặc định Spring STOMP trả ERROR frame RỖNG khi ChannelInterceptor throw — error code bị mất. Phải có custom error handler để ERROR frame mang header `message` = errorCode.
- Pitfall: `/ws/**` phải nằm trong SecurityConfig permitAll — SockJS info GET request bị Spring Security chặn 401 trước cả khi reach STOMP layer. Auth thực tế xảy ra tại STOMP CONNECT frame.
- Decision: Đăng ký CẢ 2 endpoint cùng path `/ws` (native WS + SockJS) — Spring ghép đúng handler theo request headers. FE dùng SockJS, test dùng raw WS.
- Pattern: `StompPrincipal` là record implements Principal, `name` = userId UUID string. Inject vào handler qua `Principal` param.

---

## [W4-D1] Messages Schema + REST Endpoints (Cursor Pagination)

**Files:** `V5__create_messages.sql`, `Message`, `MessageRepository`, 5 DTOs, `MessageService`, `MessageController`, 13 tests. `mvn test`: 83 pass.

- Decision: Cursor-based pagination — `nextCursor = oldest item UTC`. REST GET endpoint nhận `cursor` param, parse normalize to UTC.
- Pitfall (CRITICAL): H2 `MODE=PostgreSQL` không handle `TIMESTAMP WITH TIME ZONE` đúng — timezone shift khi read back. Cursor pagination test FAIL vì sub-ms timestamps + timezone shift sai. Fix: test insert messages trực tiếp qua repository với explicit UTC timestamps cách nhau rõ ràng (`plusDays`).
- Pattern: Spring Data method naming với `OffsetDateTime` parameter trong JPQL hoạt động đúng trong H2. Tránh `@Query` native cho TIMESTAMPTZ queries trong test.
- Pattern: `OffsetDateTime.now(ZoneOffset.UTC)` trong `@PrePersist` — normalize về UTC ngay lúc tạo.
- Decision: `MessageType` enum TEXT/IMAGE/FILE/SYSTEM. Soft delete via `deleted_at`. Self-ref `replyToMessage`.

---

## [W3-D5] Fix TD-8: MethodArgumentTypeMismatchException → 400

- Pattern: `@ExceptionHandler(MethodArgumentTypeMismatchException.class)` trong `GlobalExceptionHandler` → 400 `VALIDATION_FAILED` với details `{field, error}`. Cover mọi `@PathVariable UUID` endpoint.
- Pitfall: `MethodArgumentTypeMismatchException` là subclass của `MethodArgumentConversionNotSupportedException` — handler phải đặt trước catch-all để Spring dispatch đúng.

---

## [W3-D3] Rate Limit POST /api/conversations + Fail-open Redis

- Pattern: Redis INCR rate limit key `rate:conv_create:{userId}` TTL 60s, limit 10/window. Wrap trong `try/catch DataAccessException` — fail-open nếu Redis down (consistent với JWT blacklist pattern).
- Pattern: Khi vượt limit, query TTL thực từ Redis, throw `AppException(429, "RATE_LIMITED", ..., Map.of("retryAfterSeconds", ttl))`. TTL fallback = 60 khi Redis down.
- Pattern: `DataAccessException` bao phủ mọi Redis/JPA network error — dùng nhất quán cho fail-open.

---

## [W3-D2] Conversation Endpoints + Validation

**Files:** `AppException` (thêm details field), `GlobalExceptionHandler` update, `Conversation`/`ConversationMember` entities, repositories, 7 DTOs, `ConversationService`, `ConversationController`, `UserController`, 15+ tests.

- Decision: `Conversation`/`ConversationMember` entity xóa `@GeneratedValue`, thêm `@PrePersist if (id==null) id = UUID.randomUUID()` — tránh conflict khi build manually.
- Pitfall (CRITICAL): H2 native query UUID trả `byte[]` thay vì `UUID`/`String`. Fix: `CAST(c.id AS VARCHAR)` trong SELECT, `CAST(:param AS UUID)` trong WHERE, dùng `String` parameter.
- Pitfall: Flush+clear EntityManager sau save trong `@Transactional` khi cần reload với custom JPQL — tránh stale 1st-level cache trả empty collection.
- Decision: `ConversationListResponse` shape theo contract: `content/page/size/totalElements/totalPages` (không phải `items/total/pageSize`).
- Pattern GROUP validation thứ tự: null-check → caller-in-members → distinct → size-min → size-max → existence. Thứ tự quan trọng để error rõ ràng.

---

## [W3-D1] V3 Migration Conversations + Entities

**Files:** `V3__create_conversations.sql`, `ConversationType`, `MemberRole`, `Conversation`, `ConversationMember`, `ConversationRepository`, `ConversationMemberRepository`.

- Pattern: `@Builder.Default private List<ConversationMember> members = new ArrayList<>()` — builder không trả null list.
- Pattern: `findByIdWithMembers` với `JOIN FETCH` tránh N+1.

---

## [W2-D4] OAuth (Firebase) + Logout (JWT Blacklist)

**Files:** `FirebaseConfig`, `AuthService` (oauth + logout), `JwtAuthFilter` (blacklist check), `SecurityConfig` (narrow whitelist), `AuthController`. 10 tests, tổng 50 pass.

- Decision: KHÔNG dùng `FirebaseAuth.getInstance()` trực tiếp — không testable. Dùng injected bean qua setter `@Autowired(required=false)`. `@MockBean FirebaseAuth` trong test replace bean.
- Pattern: `@Bean` trả null → bean không đăng ký. `@Autowired(required=false)` nhận null → handle gracefully (dùng cho optional Firebase).
- Pattern: JWT blacklist key `jwt:blacklist:{jti}` trong Redis. JwtAuthFilter check sau validate VALID. Fail-open nếu Redis unavailable.
- Decision: `/api/auth/logout` KHÔNG trong Security whitelist — cần JWT auth để gọi logout.

---

## [W2-D3] Refresh Token Rotation + Reuse Detection

**Files:** `AuthService.refresh()`, `JwtTokenProvider.getClaimsAllowExpired()`, `AuthController`, 9 tests.

- Pattern: Refresh token hash — SHA-256 hash stored in Redis, constant-time compare via `MessageDigest.isEqual()` (tránh timing attack, String.equals() short-circuit).
- Pattern: Reuse detection — nếu refresh token đã revoke nhưng vẫn dùng, gọi `revokeAllUserSessions()` (security response).
- Decision: `getClaimsAllowExpired()` extract claims kể cả khi EXPIRED (từ `ExpiredJwtException.getClaims()`) — safety net khi cần userId/jti từ expired token.
- Decision: Error codes theo contract: `AUTH_REFRESH_TOKEN_INVALID`, `AUTH_REFRESH_TOKEN_EXPIRED`, `AUTH_ACCOUNT_LOCKED` (không phải spec names).

---

## [W2-D2] Register + Login Endpoints

**Files:** `AuthService` (register + login), DTOs, `AuthController`, 14 tests. Tổng 31 pass.

- Pitfall (CRITICAL): Khi thêm bean inject Redis vào Spring context, tất cả test class exclude Redis autoconfigure sẽ fail context load → phải thêm `@MockBean StringRedisTemplate` vào từng class đó (`JwtTokenProviderTest`, `SecurityConfigTest`, `ChatAppApplicationTests`).
- Decision: Error codes theo contract thắng task spec: `AUTH_EMAIL_TAKEN`, `AUTH_USERNAME_TAKEN`, `AUTH_INVALID_CREDENTIALS`, `AUTH_ACCOUNT_LOCKED`. Register response HTTP 200 (không phải 201).
- Pattern: Rate limit register 10/15min per IP. Login rate limit theo fail count per IP. Reset counter on success.
- Pattern: BCrypt strength 12. Password regex: min 8 chars, 1 uppercase, 1 digit.

---

## [W1-Fix] JWT Token Validation: EXPIRED vs INVALID

**Files:** `JwtTokenProvider` (`TokenValidationResult` enum + `validateTokenDetailed()`), `JwtAuthFilter`, `SecurityConfig` (authenticationEntryPoint).

- Pattern: `TokenValidationResult` enum (VALID/EXPIRED/INVALID). Filter set `request.setAttribute("jwt_expired", true)` khi EXPIRED. EntryPoint check attribute → trả `AUTH_TOKEN_EXPIRED` vs `AUTH_REQUIRED`.
- Pitfall: `ExpiredJwtException` là subclass của `JwtException` — phải catch trước trong multi-catch nếu refactor sang single try-catch.
- Pattern: Test package-private method — test class cùng package truy cập được mà không cần reflection.

---

## [W1-D3] Spring Security + JWT Foundation

**Files:** `JwtTokenProvider`, `JwtAuthFilter`, `SecurityConfig`, `ErrorResponse`, `AppException`, `GlobalExceptionHandler`. 11 tests pass.

- Pitfall: `@SpringBootTest` KHÔNG có `excludeAutoConfiguration` attribute → phải dùng `properties = "spring.autoconfigure.exclude=..."`.
- Pitfall: `allowedOrigins("*")` + `allowCredentials(true)` = Spring Security exception. Phải dùng origins cụ thể hoặc `allowedOriginPatterns("*")`.
- Decision: jjwt 0.12.6, firebase-admin 9.4.1, Spring Boot 3.4.4, Java 21.
- Pattern: `application-test.yml` với H2 in-memory + `flyway.enabled: false` cho test profile.

---

## [W1-D2] V2 Migration + JPA Entities

**Files:** `V2__create_users_and_auth_providers.sql`, `User`, `UserAuthProvider`, `UserBlock`, repositories.

- Decision: UUID PK (DB generate), `OffsetDateTime` timestamps, `@PrePersist`/`@PreUpdate`, soft delete via `deleted_at`.
- Pitfall: Port 8080 có thể bị process cũ chiếm. Hibernate validate vẫn pass trước khi lỗi port.
- Pattern: Spring Data Redis log WARN "Could not safely identify store assignment" khi có cả JPA + Redis module — bình thường, không phải lỗi.

---

## [W1-D1] Project Bootstrap

- Stack chốt: Spring Boot 3.4.4, Java 21, Maven, jjwt 0.12.6, firebase-admin 9.4.1.
- Pitfall: `JAVA_HOME` trỏ vào JDK không tồn tại. Fix: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` trước `mvn`.
- Pattern: Exclude 5 autoconfigure classes trong `application.yml` khi chưa có DB/Redis để app start sạch. Remove exclusion sau khi infra sẵn sàng.
- Pattern: V1__placeholder.sql cho Flyway migration đầu tiên.
