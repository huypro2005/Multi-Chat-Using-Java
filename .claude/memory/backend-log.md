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

## [W7-D5] feat: read receipt (markRead, unreadCount, READ_UPDATED broadcast) — 293 tests pass (11 new)

**Scope**: Read receipt — STOMP handler, ReadReceiptService, MessageBroadcaster (READ_UPDATED), MemberDto.lastReadMessageId, unreadCount real compute in ConversationService.

**Files created**:
- `V12__add_last_read_message_id.sql`: FK constraint + index (entity field đã có từ trước).
- `ReadReceiptPayload.java`: record `{messageId}`.
- `ReadUpdatedEvent.java`: record `{convId, userId, messageId, readAt}`.
- `ReadReceiptService.java`: markRead — NOT_MEMBER(403), MSG_NOT_FOUND(404), MSG_NOT_IN_CONV(403), forward-only idempotent (compare createdAt), AFTER_COMMIT publish event.
- `ChatReadReceiptHandler.java`: @MessageMapping("/conv.{convId}.read"), rate limit 1/2s Redis INCR TTL 2s, @MessageExceptionHandler → /queue/errors {operation:"READ"}, no ACK.
- `ReadReceiptTest.java`: 11 unit tests (Mockito).

**Files modified**:
- `MessageBroadcaster.java`: thêm `onReadUpdated()` — AFTER_COMMIT broadcast READ_UPDATED tới `/topic/conv.{convId}`.
- `MemberDto.java`: thêm `UUID lastReadMessageId`, `MemberDto.from()` include field.
- `MessageRepository.java`: thêm `countUnread(convId, lastReadId)` native query — LEAST(COUNT(*), 99), filter type!='SYSTEM' + deleted_at IS NULL, NULL-safe lastReadId subquery.
- `ConversationService.java`: inject MessageRepository, `buildSummary()` compute real unreadCount từ `myMembership.getLastReadMessageId()`.

**Pre-existing failures** (NOT caused by this commit): MemberManagementTest, GroupConversationTest, SystemMessageTest, FileControllerTest, FileVisibilityTest, ConversationControllerTest (8/20) — tất cả fail ở `setUp()` với `InvalidDataAccessApiUsage: Could not deserialize Map<String,Object>` từ Message.systemMetadata H2 column khi tests share in-memory DB state.

---

## [W7-D4-fix] feat: Model 4 hybrid file visibility (ADR-021) + default avatars + ADMIN permission — 282 tests pass (12 new)

**Scope**: Hybrid file visibility + default avatars + regression tests cho ADMIN add members.

**Item 1 — Model 4 Hybrid (ADR-021)**:
- `V11__file_visibility_and_defaults.sql`: ALTER files ADD is_public BOOLEAN default FALSE + DROP NOT NULL uploader_id; backfill existing avatars is_public=TRUE (DO block check column exists cho users.avatar_file_id future); seed 2 default rows (UUID 001/002, is_public=TRUE, expires_at=9999-12-31); partial index `idx_files_public WHERE is_public=TRUE`.
- `FileConstants` new class: DEFAULT_USER_AVATAR_ID/GROUP (fixed UUID), DEFAULT_*_URL (`/api/files/{id}/public`), helpers `publicUrl(id)` / `privateUrl(id)`.
- `FileRecord`: thêm `boolean isPublic` (`@Column nullable=false + @Builder.Default false`); `uploader_id` bỏ `nullable=false` (default files).
- `FileService.upload(file, userId, isPublic)` overload; backward-compat `upload(file, userId)` gọi với false.
- `FileService.toDto`: public → url `/public` + publicUrl=url + thumbUrl=null; private → url gốc + publicUrl=null + thumbUrl theo record.
- `FileService.loadForPublicDownload(fileId)`: check `record.isPublic() && !expired`; anti-enum trả null cho mọi fail case → controller 404.
- `FileService.@PostConstruct validateDefaultAvatars()`: soft-check log WARN nếu 2 default file thiếu trên disk (runbook post-deploy). KHÔNG fail startup.
- `FileController.upload(?public=...)`: query param default false.
- `FileController.downloadPublic`: GET `/api/files/{id}/public` — KHÔNG cần JWT; `Cache-Control: public, max-age=86400`.
- `SecurityConfig`: thêm `/api/files/*/public` vào permitAll whitelist TRƯỚC anyRequest().authenticated().

**Item 2 — Default avatars**:
- `AuthService.register`: set `user.avatarUrl = FileConstants.DEFAULT_USER_AVATAR_URL`.
- `AuthService.oauth`: fallback default URL khi photoUrl từ Google null/blank.
- `ConversationService.createGroup`: nếu `req.avatarFileId` null → set `DEFAULT_GROUP_AVATAR_ID`.
- `ConversationService.updateGroupInfo` (remove avatar): FALLBACK `DEFAULT_GROUP_AVATAR_ID` thay vì NULL.
- `MessageMapper.toFileDto`: respect `record.isPublic()` cho attachments.
- `FileCleanupJob`: skip `FileConstants.DEFAULT_AVATAR_IDS` (expired + orphan).

**Item 3 — ADMIN permission**: `MemberRole.canAddMembers()` đã đúng (`this != MEMBER`). Thêm 2 regression tests (P01/P02).

**Tests** — `FileVisibilityTest.java` (12 tests): V01-V06 visibility, D01-D04 defaults, P01-P02 ADMIN regression.

**Lombok pitfall**: Field `boolean isPublic` → Lombok `isPublic()` KHÔNG PHẢI `isIsPublic()`. Detect `is` prefix và strip.

**Sửa test cũ**: `AuthControllerTest.registerHappyPath` expect `$.user.avatarUrl value("/api/files/000...001/public")`.

**Test profile pitfall (H2)**: Flyway disabled + `ddl-auto=create-drop` → V11 KHÔNG chạy. FileVisibilityTest setUp() seed 2 default records programmatically.

All 282 tests pass.

---

## [W7-bugfix] fix: group avatar not visible in sidebar — 270 tests pass

- Root cause: `ConversationRepository.findConversationsByUserPaginated` không select `avatar_file_id`. `buildSummary()` đọc từ `row[3]` (legacy `avatar_url`) — null khi dùng avatar W7.
- Fix: Thêm `CAST(c.avatar_file_id AS VARCHAR)` vào SELECT → cột mới row[4]. Dịch chuyển: lastMessageAt → row[5], memberCount → row[7]. `ConversationService.buildSummary()`: resolve `avatarUrl = avatarFileId != null ? "/api/files/" + avatarFileId : legacyAvatarUrl`.

---

## [W7-D2] feat: Member Management + Owner Transfer — 26 new tests, 257 total pass

**Scope**: 5 endpoints theo API_CONTRACT v1.1.0-w7 — POST /members (batch 1-10, partial success), DELETE /members/{userId} (kick role-hierarchical), POST /leave (auto-transfer khi OWNER leave), PATCH /members/{userId}/role (OWNER-only, ADMIN↔MEMBER idempotent), POST /transfer-owner (atomic 2-way swap).

**Files added**:
- DTOs: `AddMembersRequest`, `AddMembersResponse` (added+skipped, @JsonInclude ALWAYS), `SkippedMemberDto`, `RoleChangeRequest`, `RoleChangeResponse` (changedBy ActorSummaryDto), `TransferOwnerRequest` (targetUserId rename), `OwnerTransferResponse` (previousOwner PreviousOwnerDto newRole="ADMIN" hardcode), `ActorSummaryDto`, `PreviousOwnerDto`.
- Events: `MemberAddedEvent`, `MemberRemovedEvent`, `RoleChangedEvent`, `OwnerTransferredEvent` records.
- `ConversationMemberService.java`: 5 @Transactional methods, tách khỏi ConversationService.
- `MemberManagementTest.java`: 26 tests (A01-A06 add, K01-K05 kick, L01-L06 leave, R01-R05 role, T01-T04 transfer).

**Files modified**:
- `ConversationMemberRepository.java` +4 methods: `lockMembersForUpdate` (native FOR UPDATE trả List<String>), `findByConversationIdAndUserIdForUpdate`, `findCandidatesForOwnerTransfer` (native CASE ORDER BY role priority + joinedAt), `findUserIdsByConversationId`.
- `ConversationBroadcaster.java` +4 listener: onMemberAdded/Removed/RoleChanged/OwnerTransferred. Dùng `@Transactional(REQUIRES_NEW, readOnly=true)` cho listener chạy AFTER_COMMIT cần load User/Conversation.
- `ConversationController.java` +5 endpoints, inject ConversationMemberService.

**Pattern key**:
- `MemberRole.canRemoveMember(targetRole)` 2-tham số encapsulate hierarchy (OWNER→ADMIN/MEMBER, ADMIN→MEMBER).
- Race-safe H2-compatible: SELECT rows native `FOR UPDATE`, count Java (PG `COUNT(*) FOR UPDATE` không portable vì H2 90145).
- Auto-transfer native CASE ORDER BY (JPQL CASE enum literal không portable).
- OWNER→ADMIN sau transfer (giữ quyền, không về MEMBER). OWNER→MEMBER chỉ trong leave flow (demote trước delete row).
- Partial-success: 201 với {added, skipped}. Skipped codes: ALREADY_MEMBER, USER_NOT_FOUND (merge anti-enum), BLOCKED (V1 reserved). MEMBER_LIMIT_EXCEEDED vẫn all-or-nothing (409).
- @TransactionalEventListener AFTER_COMMIT với DB access: cần `@Transactional(REQUIRES_NEW, readOnly=true)` để có TX mới.

**Pitfall**:
- H2 + Hibernate `PESSIMISTIC_WRITE` → emit `FOR NO KEY UPDATE` (PG-only) → H2 90232. Fix: native SQL `FOR UPDATE`.
- H2 `SELECT COUNT(*) ... FOR UPDATE` → 90145. Fix: SELECT rows + count Java.
- Native SQL UUID column H2 → `byte[]`, ConversionFailedException. Fix: `CAST(col AS VARCHAR)` + `List<String>`.
- `CAST(:convId AS UUID)` bắt buộc cho WHERE clause UUID comparison native H2.
- Broadcaster test: `reset(messagingTemplate)` trước no-op case (R03) để clear prior invocations.

---

## [W7-D1] feat: Schema V9 + Group Chat CRUD (ADR-020) — 21 new tests, 231 total pass

**Migration V9 (không V7 — V7 đã dùng cho files W6)**:
- ALTER conversations ADD owner_id, avatar_file_id, deleted_at (ON DELETE SET NULL cho FK).
- KHÔNG tạo ENUM `member_role` — giữ VARCHAR(20) + CHECK cho H2 tương thích. KHÔNG thêm role/joined_at (V3 đã có).
- CHECK constraint `chk_group_metadata`: ONE_ON_ONE name+owner NULL; GROUP name non-null (owner có thể NULL).
- Partial indexes: owner (WHERE owner_id NOT NULL), deleted (WHERE deleted_at NOT NULL), (conv_id, role), (conv_id, joined_at).

**Files changed**:
- `MemberRole.java` +6 permission methods theo API_CONTRACT Appendix (canRename/canAddMembers/canRemoveMember/canChangeRole/canDeleteGroup/canTransferOwnership).
- `Conversation.java` thêm ownerId, avatarFileId, deletedAt + domain methods markDeleted/isDeleted. Giữ legacy avatarUrl TEXT nhưng ưu tiên avatarFileId.
- `CreateConversationRequest.java` record 5 fields: type, name, targetUserId (W7), memberIds (backward-compat), avatarFileId.
- `UpdateGroupRequest.java` new — `@JsonAnySetter` + Map tristate cho avatarFileId.
- `OwnerDto.java` new minimal {userId, username, fullName}.
- `ConversationDto.java` thêm owner field. `from(conv, Function<UUID,User> resolver)` — caller inject lookup. Sort members `role ASC (ordinal), joinedAt ASC`. avatarUrl compute avatarFileId ? "/api/files/{id}" : legacy.
- `ConversationRepository.java` +findActiveByIdWithMembers/findActiveById filter deleted_at IS NULL. Update native list queries.
- `ConversationMemberRepository.java` +findByConversation_IdOrderByJoinedAtAsc/findByConversation_IdAndRoleOrderByJoinedAtAsc/countByConversation_Id/deleteByConversation_Id @Modifying.
- `ConversationUpdatedEvent.java`, `GroupDeletedEvent.java` new records.
- `ConversationBroadcaster.java` new — AFTER_COMMIT listeners fire CONVERSATION_UPDATED/GROUP_DELETED qua `/topic/conv.{id}`. LinkedHashMap cho null values.
- `ConversationService.java` refactor createOneOnOne accept cả targetUserId (W7) và memberIds[0]. Thêm updateGroupInfo, deleteGroup, validateGroupAvatar (exists+uploader+MIME image+chưa expired). markAttached file avatar.
- `ConversationController.java` +PATCH/DELETE /{id} (204).

**Error codes v1.0.0-w7**:
- GROUP_NAME_REQUIRED/GROUP_MEMBERS_MIN/MAX/MEMBER_NOT_FOUND/AVATAR_NOT_OWNED (merge anti-enum)/AVATAR_NOT_IMAGE/NOT_GROUP/INSUFFICIENT_PERMISSION/CONV_NOT_FOUND (anti-enum non-member + soft-deleted).

**Tests (21 new)**:
- S01-S03: schema column verify via DatabaseMetaData + createGroup persists owner_id/name.
- G01-G09: createGroup happy/exact-min/min/max/dedup/missing user/empty name/long name/avatar not owned.
- H01-H02: GET (owner info + sort members, non-member 404).
- U01-U04: updateGroupInfo OWNER/ADMIN rename, MEMBER deny, NOT_GROUP.
- D01-D03: deleteGroup OWNER soft-delete + broadcast, ADMIN deny, NOT_GROUP.

**Regression fix**: 3 test cũ ConversationControllerTest đổi expected VALIDATION_FAILED → GROUP_NAME_REQUIRED/GROUP_MEMBERS_MIN (breaking theo contract v1.0.0-w7).

**Pitfall**: `Map.of()` NPE với null → LinkedHashMap cho broadcast `avatarUrl: null` (remove avatar). UpdateGroupRequest `@JsonAnySetter` Map thay vì record tristate.

---

## [W6-D4-extend] feat: expand file types 5→14 MIME, iconType field, singleNonImage — 13 tests added (210 total pass)

## [W6-D3] feat: File cleanup jobs (expiry + orphan) + graceful 404 + @EnableScheduling

**Files changed**:
- `ChatAppApplication` +`@EnableScheduling`.
- `FileRecordRepository` +2 methods: `findByExpiresAtBeforeAndExpiredFalse`, `findByAttachedAtIsNullAndCreatedAtBefore`.
- `FileCleanupJob` new — 2 jobs: expired (3 AM daily) + orphan (hourly). `@ConditionalOnProperty(enabled=true, matchIfMissing=true)`.
- `FileController` wrap `openStream()` với try-catch `StorageException` → 404 FILE_PHYSICALLY_DELETED.
- `application.yml` section `app.file-cleanup: {enabled, expired-cron, orphan-cron}`.
- `application-test.yml` `cron="-"` disable trigger.
- `FileCleanupJobTest` 6 tests (CJ01-06).

**Key**:
- Cron "-" = Spring disabled value (bean load, trigger không fire).
- H2 test dùng `minusDays(2)` tránh sub-hour precision issue.
- FileController catch StorageException (không IOException — FileService wrap).

Test 197/197.

---

## [W6-D2] feat: Thumbnail + FileAuthService + STOMP attachments — 191/191 tests

**Files new**:
- pom.xml + thumbnailator 0.4.20.
- `V8__add_thumbnail_path.sql` ALTER files ADD thumbnail_internal_path VARCHAR(1024) NULL.
- `FileRecord.thumbnailInternalPath`.
- `StorageService.resolveAbsolute(String) throws SecurityException`.
- `LocalStorageService.resolveAbsolute` — canonical prefix check, SecurityException khi traversal.
- `ThumbnailService` mới — 200×200 Thumbnailator, suffix `_thumb`. Fail-open.
- `FileService` inject ThumbnailService, `toDto` đổi thumbUrl check sang thumbnailInternalPath != null. +`openThumbnailStream`.
- `FileAuthService` mới — `findAccessibleById(fileId, userId)` Optional, uploader OR conv-member, anti-enum 404.
- `MessageAttachmentRepository` +`existsByIdFileId`, `existsByFileIdAndConvMemberUserId` JPQL JOIN.
- `FileController` replace loadForDownload → `fileAuthService.findAccessibleById`. +`GET /{id}/thumb` Cache-Control 7d + ETag + nosniff. sanitizeFilename header CRLF+non-ASCII strip.
- `SendMessagePayload` +`attachmentIds: List<UUID>` (5th field).
- `MessageDto` +`attachments: List<FileDto>` (chèn sau content, position 6).
- `MessageMapper` inject 2 repo, load attachments JOIN ORDER BY display_order, strip emptyList khi deleted.
- `MessageService` inject FileRecordRepository + MessageAttachmentRepository (8→10 args). `validateStompPayload` +XOR rule MSG_NO_CONTENT. `sendViaStomp` derive MessageType + `validateAndAttachFiles` sau save. Persist `""` khi attachments-only (DB NOT NULL).

**Tests +19**: FileControllerTest F11-F18 (thumb path/200/PDF null/no-JWT/conv-member/non-member/expired). MessageServiceStompTest W6-T01-T11 (1-5 images, 6+ reject, 1 PDF, mixed, other-user file, already-used, empty content, null+image OK, non-existent). Update 3 constructor calls + 4 MessageDto calls + 5 SendMessagePayload calls.

**Key**: Thumbnail Content-Type giữ MIME gốc (không always JPEG). Content DB NOT NULL → persist "". Validation order count→existence→ownership→expiry→unique→group.

---

## [W6-D1] feat: File Upload Foundation — 172/172 tests (145 cũ + 27 mới)

**Files new**:
- pom.xml + tika-core 2.9.1.
- `V7__create_files_tables.sql` — tables files + message_attachments, FK UUID (không BIGINT như spec sai).
- `FileRecord` UUID PK + uploader_id + storage_path + expires_at + attached_at + markAttached/isImage.
- `MessageAttachment` + `MessageAttachmentId` composite key (@EmbeddedId).
- `FileDto` record 7 fields. `StorageService` interface (store/retrieve/delete). `LocalStorageService` canonical-prefix check, reject traversal.
- `FileValidationService` Tika detect magic, 5 MIME whitelist, MIME→ext cố định, alias image/jpg→jpeg.
- `FileService` upload flow validate→rate limit→UUID→store→persist→map. loadForDownload stub uploader-only. Rate limit 20/min Redis fail-open.
- `FileController` POST /upload 201, GET /{id} stream với Cache-Control + nosniff + ETag. `@AuthenticationPrincipal User`.
- 6 exception class (FileEmpty/TooLarge/TypeNotAllowed/MimeMismatch/RateLimited/Storage).
- `GlobalExceptionHandler` +8 handler (6 file + MaxUploadSizeExceeded + MissingPart/Param → FILE_EMPTY).
- application.yml multipart limits + storage.local.base-path. Test profile `./build/test-uploads`.
- Tests: FileValidationServiceTest (10), LocalStorageServiceTest (7 với @TempDir), FileControllerTest (10).

**Decisions**:
- FK UUID align users.id/messages.id (V2/V5). Task spec BIGINT sai.
- StorageService interface swap-ready S3 V2.
- 6 exception class riêng cho typed getter format details rõ.
- Download stub uploader-only W6-D1; W6-D2 mở conv-member.

**Pitfall**:
- Cross-package test (`com.chatapp.file` vs `storage`) → `getBasePath()` phải `public`.
- MultipartFile test >20MB: `SizedMockMultipartFile` wrapper (override getSize + getInputStream trả head bytes) tránh OOM.
- Tika alias image/jpg → image/jpeg (Firefox cũ).
- Path traversal test: `IllegalArgumentException` (assertWithinBase throw trước filesystem).

---

## [W5-D4] feat: after param + ReplyPreviewDto deletedAt + STOMP reply validation

**Files**: `MessageRepository` +`findByConversation_IdAndCreatedAtAfterOrderByCreatedAtAsc` (forward, no deletedAt filter). `MessageService.getMessages` signature +`after: OffsetDateTime`, forward branch mới; `sendViaStomp` +reply validation (conv membership, existence, allow deleted source). `MessageController.getMessages` +after param, mutex check cursor+after → 400. `MessageMapper.toReplyPreview(Message)` public; deleted source → contentPreview=null + deletedAt set. `ReplyPreviewDto` +`String deletedAt` (4th, breaking). `SendMessagePayload` +`UUID replyToMessageId` (4th nullable). 5 tests mới T17-T21.

**Pitfall**: `item.get("content").asText()` trả `"null"` string cho JSON null — phải check `item.get("deletedAt").isNull()`. Tests: 145 total.

## [W5-D5] fix: log WARN for duplicate WS requests — 3 one-liner addition trong MessageService. 140 tests BUILD SUCCESS.

## [W5-D3] Delete Message via STOMP + soft delete

**Files**: `V6__add_message_deleted_by.sql` deleted_by UUID NULL SET NULL. `DeleteMessagePayload`, `MessageDeletedEvent` (Instant), `MessageBroadcaster.onMessageDeleted`, `MessageService.deleteViaStomp`, `AuthChannelInterceptor` +.delete STRICT_MEMBER, `ChatDeleteMessageHandler`. 10 tests.

- DELETE ACK dùng `Map<String,Object>` (không AckPayload) — minimal metadata, không full DTO. `Map.of()` NPE null → HashMap.
- `MessageDeletedEvent` dùng `Instant` (không OffsetDateTime) cho ISO8601 `.toString()`.
- Content strip ở mapper (không DB) — DB lưu audit/admin. Mapper `content=null` khi deletedAt != null, áp dụng TẤT CẢ path.
- Anti-enum MSG_NOT_FOUND 4 cases: not-owner, wrong-conv, soft-deleted, non-existent.
- Rate `rate:msg-delete:{userId}` 10/min. Dedup `msg:delete-dedup:{userId}:{clientDeleteId}` NX EX 60.

## [W5-D2] Edit Message + Unified ACK shape (ADR-017)

**Files**: `EditMessagePayload`, `MessageUpdatedEvent`, `AckPayload` (breaking), `ErrorPayload`, `MessageBroadcaster.onMessageUpdated`, `MessageService.editViaStomp`, `ChatEditMessageHandler`. 12 tests. 130 total pass.

- `AckPayload` unified `(operation, clientId, message)`. `clientId` thay `tempId`. Breaking — mọi `ack.tempId()` đổi `ack.clientId()`.
- `editViaStomp` dùng `TransactionSynchronizationManager.isSynchronizationActive()` fallback để unit test (không Spring TX) vẫn chạy.
- Rate `rate:msg-edit:{userId}` 10/min. Dedup `msg:edit-dedup:{userId}:{clientEditId}` NX EX 60.
- Anti-enum MSG_NOT_FOUND 4 cases (not-owner/wrong-conv/soft-deleted/non-existent).
- Pitfall: `message.setCreatedAt()` trong setUp cho test 300s window manipulate.

## [W5-D1-FixA] Destination-aware Auth Policy

**Files**: `AuthChannelInterceptor` inner enum `DestinationPolicy`, 10 tests. 118 total pass.

- `.message/.edit/.delete` → STRICT_MEMBER (throw FORBIDDEN). `.typing/.read` → SILENT_DROP (pass through). Unknown → STRICT default.
- Pitfall: `.typing` PHẢI SILENT_DROP — throw FORBIDDEN tạo ERROR frame → UI lỗi bad UX. Handler có member check riêng (defense-in-depth).
- `verifyNoInteractions(conversationMemberRepository)` cho SILENT_DROP tests.

## [W5-D1] Typing Indicator STOMP Handler

**Files**: `TypingPayload`, `TypingRateLimiter`, `ChatTypingHandler`, 4 tests. 108 total pass.

- Ephemeral event — handler silent drop (return, không throw). Rate limit 1/2s Redis INCR+EXPIRE, fail-open Redis down.
- Contract (§3.4) không có `fullName` — task spec có nhưng contract thắng.
- Pitfall: `@MockitoSettings(LENIENT)` khi setUp stub rộng — test non-member return sớm không gọi rate limiter, Mockito strict fail UnnecessaryStubbingException.

## [W4-D4] Realtime Broadcast (TransactionalEventListener)

**Files**: `MessageMapper` (@Component extracted), `MessageCreatedEvent`, `MessageBroadcaster` (`@TransactionalEventListener(AFTER_COMMIT)`), updated MessageService. 104 tests.

- Pattern: AFTER_COMMIT broadcast SAU transaction commit. try-catch toàn bộ listener — broadcast fail không propagate REST controller.
- Pitfall: `@MockBean SimpMessagingTemplate` bắt buộc trong `MessageControllerTest` — thiếu → UnsatisfiedDependencyException.
- Broadcaster throw trong AFTER_COMMIT → Spring log ERROR nhưng KHÔNG propagate — REST 201 vẫn OK.

## [W4-D3] WebSocket + STOMP Auth Layer

**Files**: WebSocketConfig, StompPrincipal, AuthChannelInterceptor, StompErrorHandler, SecurityConfig update, WebSocketIntegrationTest (6 tests raw WS). 89 total pass.

- **Pitfall CRITICAL**: `WebSocketStompClient`/`DefaultStompSession` KHÔNG expose header "message" từ ERROR frame khi CONNECT reject — fire `handleTransportError(ConnectionLostException)`. Test phải raw `StandardWebSocketClient` + `AbstractWebSocketHandler.handleTextMessage` để đọc ERROR frame nguyên bản.
- Pitfall: Custom `StompSubProtocolErrorHandler` phải set qua `StompSubProtocolHandler.setErrorHandler()`. `SubProtocolWebSocketHandler` không expose setErrorHandler. Workaround: `@EventListener(ContextRefreshedEvent)` → unwrap `WebSocketHandlerDecorator` → loop `getProtocolHandlers()` → setErrorHandler.
- Pitfall: Mặc định Spring STOMP trả ERROR frame RỖNG khi ChannelInterceptor throw — error code mất. Custom error handler để ERROR frame mang header `message=errorCode`.
- Pitfall: `/ws/**` phải permitAll — SockJS info GET bị Security chặn 401 trước reach STOMP layer. Auth thực tại CONNECT frame.
- Decision: Đăng ký CẢ 2 endpoint cùng `/ws` (native WS + SockJS) — Spring ghép đúng handler theo request headers.

## [W4-D1] Messages Schema + REST (Cursor Pagination)

**Files**: `V5__create_messages.sql`, Message entity, MessageRepository, 5 DTOs, MessageService, MessageController, 13 tests. 83 total pass.

- Decision: Cursor-based — `nextCursor = oldest item UTC`. REST GET nhận `cursor` param, parse normalize UTC.
- **Pitfall CRITICAL**: H2 `MODE=PostgreSQL` không handle TIMESTAMPTZ đúng — timezone shift khi read back. Sub-ms timestamps + timezone shift FAIL. Fix: insert repo trực tiếp với explicit UTC `plusDays(i)`.
- Pattern: Spring Data method naming với `OffsetDateTime` parameter JPQL hoạt động đúng H2. Tránh `@Query` native cho TIMESTAMPTZ test.
- MessageType TEXT/IMAGE/FILE/SYSTEM. Soft delete `deleted_at`. Self-ref `replyToMessage`.

## [W3-D2] Conversation Endpoints + Validation

**Files**: AppException +details field, GlobalExceptionHandler update, Conversation/ConversationMember entities, repos, 7 DTOs, ConversationService, ConversationController, UserController, 15+ tests.

- Decision: Xóa `@GeneratedValue`, `@PrePersist if (id==null) id = UUID.randomUUID()` — tránh conflict khi build manual.
- **Pitfall CRITICAL**: H2 native UUID → `byte[]` thay UUID/String. Fix: `CAST(c.id AS VARCHAR)` SELECT + `CAST(:param AS UUID)` WHERE + `String` parameter.
- Pitfall: Flush+clear EntityManager sau save trong `@Transactional` khi custom JPQL — tránh stale 1st-level cache trả empty collection.
- `ConversationListResponse` theo contract: content/page/size/totalElements/totalPages (không items/total/pageSize).
- GROUP validation thứ tự: null-check → caller-in-members → distinct → size-min → size-max → existence.

## [W3-D1] V3 Migration Conversations + Entities

**Files**: `V3__create_conversations.sql`, ConversationType, MemberRole, Conversation, ConversationMember, repositories.

- Pattern: `@Builder.Default private List<ConversationMember> members = new ArrayList<>()`.
- Pattern: `findByIdWithMembers` JOIN FETCH tránh N+1.

## [W3-D3] Rate Limit POST /api/conversations + Fail-open Redis

- Redis INCR `rate:conv_create:{userId}` TTL 60s 10/window. `try/catch DataAccessException` fail-open (consistent với JWT blacklist).
- Khi vượt limit: query TTL, throw `AppException(429, "RATE_LIMITED", ..., Map.of("retryAfterSeconds", ttl))`. TTL fallback 60s Redis down.

## [W3-D5] Fix TD-8: MethodArgumentTypeMismatchException → 400 VALIDATION_FAILED với `{field, error}` details. Cover @PathVariable UUID endpoints.

## [W2-D4] OAuth (Firebase) + Logout (JWT Blacklist)

**Files**: FirebaseConfig, AuthService (oauth + logout), JwtAuthFilter (blacklist check), SecurityConfig (narrow whitelist), AuthController. 10 tests, 50 total.

- Decision: KHÔNG `FirebaseAuth.getInstance()` trực tiếp — không testable. Inject bean qua setter `@Autowired(required=false)`. `@MockBean FirebaseAuth` replace.
- Pattern: `@Bean` trả null → bean không đăng ký. `@Autowired(required=false)` nhận null → handle gracefully.
- Pattern: JWT blacklist `jwt:blacklist:{jti}` Redis. JwtAuthFilter check sau validate VALID. Fail-open Redis unavailable.
- Decision: `/api/auth/logout` KHÔNG permitAll — cần JWT.

## [W2-D3] Refresh Token Rotation + Reuse Detection

**Files**: AuthService.refresh(), JwtTokenProvider.getClaimsAllowExpired(), AuthController, 9 tests.

- Pattern: Refresh token hash SHA-256 Redis, constant-time compare `MessageDigest.isEqual` (String.equals short-circuit timing attack).
- Pattern: Reuse detection → `revokeAllUserSessions()` (security response).
- Decision: `getClaimsAllowExpired()` từ `ExpiredJwtException.getClaims()` — safety net extract userId/jti từ expired token.
- Decision: Error codes theo contract: AUTH_REFRESH_TOKEN_INVALID/EXPIRED/ACCOUNT_LOCKED.

## [W2-D2] Register + Login Endpoints

**Files**: AuthService (register + login), DTOs, AuthController, 14 tests. 31 total.

- **Pitfall CRITICAL**: Bean inject Redis vào Spring context → test class exclude Redis autoconfigure fail context load → `@MockBean StringRedisTemplate` từng class (JwtTokenProviderTest, SecurityConfigTest, ChatAppApplicationTests).
- Decision: Error codes contract thắng spec: AUTH_EMAIL_TAKEN/USERNAME_TAKEN/INVALID_CREDENTIALS/ACCOUNT_LOCKED. Register HTTP 200 (không 201).
- Rate limit register 10/15min per IP. Login rate limit fail count per IP. Reset on success.
- BCrypt strength 12. Password regex: min 8 chars, 1 uppercase, 1 digit.

## [W1-Fix] JWT Token Validation: EXPIRED vs INVALID

**Files**: JwtTokenProvider (`TokenValidationResult` enum + `validateTokenDetailed()`), JwtAuthFilter, SecurityConfig (authenticationEntryPoint).

- Pattern: TokenValidationResult VALID/EXPIRED/INVALID. Filter set `request.setAttribute("jwt_expired", true)` khi EXPIRED. EntryPoint check attribute → AUTH_TOKEN_EXPIRED vs AUTH_REQUIRED.
- Pitfall: `ExpiredJwtException` subclass `JwtException` — catch trước trong multi-catch.
- Pattern: Test package-private method — test class cùng package.

## [W1-D3] Spring Security + JWT Foundation

**Files**: JwtTokenProvider, JwtAuthFilter, SecurityConfig, ErrorResponse, AppException, GlobalExceptionHandler. 11 tests.

- Pitfall: `@SpringBootTest` KHÔNG có `excludeAutoConfiguration` → `properties = "spring.autoconfigure.exclude=..."`.
- Pitfall: `allowedOrigins("*")` + `allowCredentials(true)` = Spring Security exception. Dùng origins cụ thể hoặc `allowedOriginPatterns("*")`.
- Decision: jjwt 0.12.6, firebase-admin 9.4.1, Spring Boot 3.4.4, Java 21.
- `application-test.yml` với H2 in-memory + `flyway.enabled: false`.

## [W1-D2] V2 Migration + JPA Entities

**Files**: `V2__create_users_and_auth_providers.sql`, User, UserAuthProvider, UserBlock, repositories.

- Decision: UUID PK (DB generate), OffsetDateTime timestamps, `@PrePersist`/`@PreUpdate`, soft delete `deleted_at`.
- Pitfall: Port 8080 bị process cũ chiếm. Hibernate validate pass trước khi lỗi port.
- Pattern: Spring Data Redis WARN "Could not safely identify store assignment" khi JPA+Redis — bình thường, không phải lỗi.

## [W1-D1] Project Bootstrap

- Stack: Spring Boot 3.4.4, Java 21, Maven, jjwt 0.12.6, firebase-admin 9.4.1.
- Pitfall: `JAVA_HOME` trỏ JDK không tồn tại. Fix: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"`.
- Pattern: Exclude 5 autoconfigure classes khi chưa có DB/Redis. V1__placeholder.sql cho Flyway đầu.
