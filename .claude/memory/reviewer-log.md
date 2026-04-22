# Reviewer Log — Nhật ký review (consolidated)

> Consolidated 2026-04-21 (W6-D5). Tuần 6 entries giữ full; tuần trước đã summary thành 3-5 dòng.
> Mới nhất ở đầu file. Chi tiết file:line bỏ; tên file giữ khi cần.

---

## Recent activity

- [Phase A] W7-D5: read receipt contract v1.4.0-w7-read + SOCKET v1.9-w7. API_CONTRACT.md bump v1.1.0-w7 → v1.4.0-w7-read (skip v1.3 để sync nhịp với SOCKET bump). Thêm: (a) Schema V12 migration `V12__add_last_read_message_id.sql` — column `conversation_members.last_read_message_id UUID REFERENCES messages(id) ON DELETE SET NULL` + composite index, (b) MemberDto extended với `lastReadMessageId: uuid | null` trong GET /{id} members[] + MEMBER_ADDED broadcast, (c) `unreadCount` real compute (bỏ placeholder 0): `COUNT(messages WHERE conv_id=X AND created_at > lastRead.createdAt AND type != 'SYSTEM' AND deleted_at IS NULL)`, cap LEAST(count, 99), per-caller, **SYSTEM không count** (superseded rule v1.2.0-w7-system), (d) readBy compute CLIENT-SIDE (BE không fan-out per-message readers list). SOCKET_EVENTS.md bump v1.7-w7 → v1.9-w7 (skip v1.8). Thêm: §3.13 READ_UPDATED outbound `/topic/conv.{id}` payload `{conversationId, userId, lastReadMessageId, readAt}`; §3f inbound `/app/conv.{convId}.read` payload `{messageId}` KHÔNG có clientId, idempotent forward-only (compare `createdAt`, incoming <= current → silent no-op không broadcast), validation auth→member→rate-limit→UUID→exists→in-conv→forward-only, error codes `AUTH_REQUIRED / NOT_MEMBER / VALIDATION_FAILED / MSG_NOT_FOUND / MSG_NOT_IN_CONV (reserved) / MSG_RATE_LIMITED (1/2s) / INTERNAL`, FE handling silent-log (no toast — read không user-initiated explicit), retry chỉ INTERNAL 1 lần. Destination policy `.read` đổi SILENT_DROP → STRICT_MEMBER (persist DB + broadcast = không thể silent drop). 7 contract tests guidance + BE/FE impl pseudo-code non-normative. BLOCKING cho BE: idempotent compare createdAt (không compare UUID), filter SYSTEM+deleted, FK SET NULL, cross-conv validation application-level. BLOCKING cho FE: readBy client compute, optimistic unreadCount=0 self-echo, debounce 500ms trước STOMP .read.
- [W7-D4-fix] Model 4 hybrid file visibility + default avatars + ADMIN add fix — **NEEDS_FIXES** (2 BLOCKING). BE (V11 migration, FileRecord.isPublic, /public endpoint anti-enum + Cache-Control public 1d, SecurityConfig permitAll ORDER trước anyRequest, FileDto publicUrl+isPublic, FileConstants DEFAULT_USER/GROUP_AVATAR_ID + skip cleanup, @PostConstruct physical-file warn, AuthService register+OAuth default avatar, ConversationService create/update fallback DEFAULT_GROUP_AVATAR_ID, MessageMapper.toFileDto respect isPublic) — tất cả BE layer OK. ADMIN canAddMembers đã đúng trong MemberRole (`this != MEMBER`) — không có bug, report chính xác. **BLOCKING FE#1**: CreateGroupDialog `POST /api/files/upload` KHÔNG có `?public=true` → upload file is_public=false, nhưng ConversationDto.from publish `/public` URL → /public endpoint reject (anti-enum 404) → avatar tạo từ "Create Group" KHÔNG BAO GIỜ load được. EditGroupInfoDialog đã đúng. **BLOCKING BE#2** (tangent, cần fix cùng): `validateGroupAvatar` trong ConversationService KHÔNG enforce `avatarFile.isPublic()=true` → nếu client bỏ qua `?public=true` (hoặc client custom), BE vẫn accept → DTO publish /public URL trỏ file private → 404. Defense-in-depth: BE nên either (a) reject upload không `?public=true` cho avatar attach (GROUP_AVATAR_NOT_PUBLIC), hoặc (b) auto-flip `setIsPublic(true) + save` trong `validateGroupAvatar`. Option (b) UX mượt hơn. 3 patterns mới added vào knowledge: hybrid visibility, seed default + fixed UUID guard, SecurityConfig permitAll ORDER. 2 warnings non-blocking: (c) UserAvatar + ConversationListItem vẫn dùng useProtectedObjectUrl — chạy được (hook fetch via axios + JWT, permitAll cho /public), nhưng redundant, lệch ý intent "native img cacheable"; refactor sau fix BLOCKING; (d) `avatarFile.markAttached()` set attached_at cho default record nếu ai đó attach avatar lặp lại — đã có skip trong cleanup, acceptable.
- [W7-D4] SYSTEM messages (BE service + FE render + i18n + migration V10) — APPROVED. 270 tests pass (+13 SystemMessageTest). 8 event types wired đúng, ordering OWNER_TRANSFERRED trước MEMBER_LEFT verified (SM-06), avatar-only no-rename verified (SM-11), edit/delete guard trước anti-enum (SM-12/13). 5 patterns mới + 2 pitfalls documented.
- [Reviewer] review W7-D3 FE group UI: FAIL→PASS. Blocking: GROUP_DELETED payload drift (FE typedef vs BE event). Fix: read from cache. 5 warnings logged.
- [W6-D5] Security audit 18/18 PASS. Memory consolidate reviewer-log 689→313. WARNINGS W6-1/2/4 resolved.

---

## Template

```
## [W{n}-D{n}] {Feature} — {VERDICT}
Blocking: ...
Key decisions: ...
Patterns confirmed: ...
Contract: ...
```

---

## [W7-D4-fix] Model 4 hybrid file visibility + defaults + ADMIN bug — NEEDS_FIXES

Verdict: NEEDS_FIXES (2 BLOCKING, tangled). Context: agent claim 282/282 BE tests pass + FE build clean. BE core layer OK; FE Create-Group flow BROKEN + BE missing defense.

### BLOCKING items (fix trước commit)

**BLOCKING #1 — FE CreateGroupDialog thiếu `?public=true` (critical regression)**
`frontend/src/features/conversations/components/CreateGroupDialog.tsx` line 117:
```
const res = await api.post<{ id: string }>('/api/files/upload', formData, { ... })
```
So với EditGroupInfoDialog line 136 (ĐÚNG): `api.post('/api/files/upload?public=true', ...)`.

Hậu quả chain:
1. CreateGroup avatar upload → `FileService.upload(file, userId, isPublic=false)` → `files.is_public=FALSE`.
2. `ConversationService.createGroup` → `validateGroupAvatar()` không check `isPublic` → accept.
3. `avatarFile.markAttached() + save` → attached OK.
4. `ConversationDto.from` → `avatarUrl = FileConstants.publicUrl(avatarFileId)` → `/api/files/{id}/public`.
5. Broadcaster fire `CONVERSATION_UPDATED` + `/queue/conv-added` → các FE khác nhận `/public` URL.
6. FE (bất kỳ user nào) load URL → `FileController.downloadPublic` → `FileService.loadForPublicDownload` → check `isPublic()=false` → return null → 404.
7. **Mọi group tạo mới từ UI `CreateGroupDialog` với avatar custom → avatar không bao giờ hiển thị.**

Test không catch vì test BE trực tiếp gọi service với `upload(..., isPublic=true)` (bypass UI path).

Fix bắt buộc: Thêm `?public=true` vào CreateGroupDialog line 117.

**BLOCKING #2 — BE defense missing: `validateGroupAvatar` không enforce `isPublic=true`**
`backend/src/main/java/com/chatapp/conversation/service/ConversationService.java` method `validateGroupAvatar(avatarFileId, callerId)` line 675-699. Hiện check: exist + uploader == caller + !expired + MIME whitelist. KHÔNG check `file.isPublic()`.

Hậu quả: dù FE fix #1, bất kỳ client nào (curl, custom integration, future mobile) gọi API upload (không ?public=true) + PATCH avatar → same bug. BE publish `/public` URL trỏ file private.

Fix options (phải chọn 1):
- **Option A (strict)**: trong `validateGroupAvatar`, sau MIME check → `if (!file.isPublic()) throw new AppException(400, "GROUP_AVATAR_NOT_PUBLIC", "Avatar file phải upload với ?public=true");`. Thêm error code vào API_CONTRACT.md.
- **Option B (UX-friendly, recommended)**: trong `validateGroupAvatar`, nếu `!file.isPublic()` → `file.setIsPublic(true); fileRecordRepository.save(file);` trước khi trả về. BE auto-flip flag, client nào gọi cũng work. Logic: "file đã attach làm avatar → phải public → flip ngay". Safe vì avatar thuộc về caller (uploader check đã qua).

Suggest Option B — ít break client hơn + flip là thao tác an toàn (avatar bản chất public).

Áp dụng ở cả 2 call-site: `createGroup` (line 237) và `updateGroupInfo` (line 517).

### Checklist PASS (đủ 20 items trừ 2 BLOCKING ở trên)

1. **V11 migration (sql)**: uploader_id NULL OK, `is_public BOOLEAN NOT NULL DEFAULT FALSE` với comment ADR-021, DO-block backfill conditional (defense khi avatar_file_id chưa có ở user table), seed 2 UUID 001/002 với `ON CONFLICT (id) DO NOTHING` (idempotent), `expires_at=9999-12-31`, `attached_at=NOW()`, `is_public=TRUE`. Partial index `CREATE INDEX idx_files_public ON files(id) WHERE is_public = TRUE`. PASS.
2. **FileRecord entity**: `@Column(name="uploader_id")` NULLABLE, `isPublic boolean` với `@Builder.Default=false` + javadoc ADR-021. PASS.
3. **GET /api/files/{id}/public**: `FileController.downloadPublic` không `@AuthenticationPrincipal User`, anti-enum (not-found/not-public/expired merge → 404 NOT_FOUND), `Cache-Control: public, max-age=1d`, ETag + X-Content-Type-Options nosniff. PASS.
4. **SecurityConfig**: `/api/files/*/public` trong `.requestMatchers(...).permitAll()` TRƯỚC `.anyRequest().authenticated()` — đúng thứ tự; wildcard `*` match UUID single-segment. PASS.
5. **FileDto**: record `UUID id, String mime, String name, long size, String url, String thumbUrl, String iconType, OffsetDateTime expiresAt, boolean isPublic, String publicUrl` — khớp contract v1.3.0-w7. PASS.
6. **Upload `?public=true`**: controller `@RequestParam(value="public", defaultValue="false") boolean isPublic` → `fileService.upload(file, userId, isPublic)`. Backward-compat overload `upload(file, userId)` → `upload(..., false)` giữ cho legacy caller. PASS.
7. **FE UserAvatar**: VẪN dùng `useProtectedObjectUrl` — redundant nhưng KHÔNG broken. Hook fetch qua axios (kèm JWT); BE permitAll vẫn cho qua; response blob → URL.createObjectURL → `<img>`. Works. Intent contract là "native img cacheable" (browser HTTP cache khớp `Cache-Control: public, max-age=1d`) — nhưng hiện dùng blob URL → mất cacheability cross-session. **Non-blocking warning**: sau fix #1+#2, nên refactor UserAvatar + ConversationListItem + GroupInfoPanel.GroupAvatarDisplay + MessageItem (nếu có) để dùng `<img src={url}>` trực tiếp khi `url.endsWith('/public')`, chỉ dùng useProtectedObjectUrl cho private attachment preview. Agent khai báo "native img với public URL" nhưng diff không thay đổi UserAvatar/ConversationListItem — lệch report.
8. **FE ConversationListItem**: như #7 — vẫn dùng hook, redundant nhưng chạy được. Non-blocking.
9. **FE GroupInfoPanel.GroupAvatarDisplay**: ĐÃ đổi sang native `<img>` (line 457-464), fallback initial letter underneath + `onError` hide. PASS (đúng intent).
10. **FE EditGroupInfoDialog**: gọi `?public=true` đúng (line 136), blob preview cho pending upload, `onError` hide img khi public URL 404. PASS.
11. **FE CreateGroupDialog**: KHÔNG gọi `?public=true` → **BLOCKING #1 ở trên**.
12. **FE useProtectedObjectUrl vẫn cho private attachment**: hook không đụng file MIME check, chỉ fetch path any `/api/files/`. FE AttachmentGallery + FileCard chưa được review trong diff này nhưng không bị ảnh hưởng (attachment path là `/api/files/{id}` private, hook hoạt động như cũ). PASS assumption.
13. **User register default avatar**: `AuthService.register` line 139 `avatarUrl(FileConstants.DEFAULT_USER_AVATAR_URL)` → `/api/files/00000000-0000-0000-0000-000000000001/public`. Test AuthControllerTest expect exact string (line 145). PASS.
14. **createGroup default avatar**: `ConversationService.createGroup` line 242-244 `finalAvatarFileId = avatarFile != null ? avatarFile.getId() : FileConstants.DEFAULT_GROUP_AVATAR_ID`. PASS. **Caveat**: `updateGroupInfo` xử lý `isRemoveAvatar()` (line 502-509) → fallback về DEFAULT_GROUP_AVATAR_ID thay vì NULL — PASS, đúng intent "mọi group luôn có avatar".
15. **Cleanup job skip**: `FileCleanupJob.cleanupExpiredFiles` line 73-79 check `DEFAULT_AVATAR_IDS.contains(file.getId())` → `continue`; `cleanupOrphanFiles` line 143-148 cũng skip. Double-safeguard với `expires_at=9999-12-31` + `attached_at=NOW()` ở migration. PASS.
16. **@PostConstruct warn**: `FileService.validateDefaultAvatars()` line 242 iterate 2 path qua `storageService.resolveAbsolute()` + `Files.exists()`. Log WARN nếu thiếu, log INFO nếu OK. Try/catch toàn method (non-local storage không làm fail). PASS.
17. **MemberRole.canAddMembers()**: line 32-34 `return this != MEMBER` → OWNER true, ADMIN true, MEMBER false. ADMIN có quyền add — đã đúng. Agent report chính xác. **KHÔNG có bug** cần fix; focus #16 resolved.
18. **Regression 282/282**: không verify trực tiếp (build tool gradlew không available trong workspace agent). Trust báo cáo của agent kèm điều kiện fix BLOCKING trên sẽ không ảnh hưởng tests (chỉ thêm `?public=true` vào FE + thêm check/flip ở BE validateGroupAvatar — cần thêm integration test Group avatar round-trip end-to-end).
19. **ConversationDto.avatarUrl /public suffix**: line 63-69 `FileConstants.publicUrl(conv.getAvatarFileId())` → `/api/files/{id}/public`. Fallback legacy `conv.getAvatarUrl()` khi `avatar_file_id` null (backward-compat). PASS.
20. **CONVERSATION_UPDATED /public payload**: `ConversationService.updateGroupInfo` line 508 `changes.put("avatarUrl", FileConstants.DEFAULT_GROUP_AVATAR_URL)` cho remove, line 522 `FileConstants.publicUrl(newAvatarId)` cho set new. Broadcaster pass-through qua `changes` map. ConversationBroadcaster.onMemberAdded dùng `FileConstants.publicUrl(conv.getAvatarFileId())` ở 2 site (line 375 + 404). PASS.

### Non-blocking warnings

- **N-1 (architectural)**: ADR-021 trong knowledge đã có conflict — 2 ADR khác nhau cùng số (ADR-021 Content XOR Attachments + ADR-021 hybrid visibility mới). Agent nên rename ADR mới thành ADR-023 hoặc ADR-025. Không fix block review này nhưng next consolidation nên address.
- **N-2 (FE tech debt)**: UserAvatar + ConversationListItem chưa refactor sang native img. Hoạt động được nhưng intent contract (public cacheable URL) không đạt full (blob URL cross-session vẫn refetch). Gộp vào W7 cleanup.
- **N-3 (ops)**: Physical default avatars (`default/avatar_default.jpg` + `default/group_default.jpg`) PHẢI copy tay sau deploy. Post-deploy checklist chưa có trong docs — suggest thêm `docs/DEPLOYMENT.md` section V1 (chưa có file này, deferred).
- **N-4 (defense-in-depth)**: `validateGroupAvatar` fix (BLOCKING #2) nếu chọn Option B (auto-flip) → thêm log INFO khi flip để audit ("Avatar file {id} flipped is_public=false→true during attach").
- **N-5 (contract drift)**: `docs/API_CONTRACT.md` v1.3.0-w7 phải document error code nếu chọn Option A (`GROUP_AVATAR_NOT_PUBLIC`). Nếu chọn Option B → không cần.

### Patterns thêm vào knowledge (3)

1. Hybrid file visibility — per-file `is_public` flag (ADR-021 hybrid).
2. Seed default records + fixed UUID guard (migration + constant + cleanup skip triple-safeguard).
3. SecurityConfig permitAll ORDER matters (whitelist trước `.anyRequest().authenticated()`).

### Contract check

- `docs/API_CONTRACT.md` v1.3.0-w7 + ADR-021 Phase A đã có theo agent report (không diff trong git scope nhưng file sẽ reflect ở commit tiếp). Nếu chọn Option A cho BLOCKING #2 → cần thêm `GROUP_AVATAR_NOT_PUBLIC` error code.
- `docs/SOCKET_EVENTS.md` không đổi — CONVERSATION_UPDATED payload schema không thay đổi shape (vẫn `changes.avatarUrl: string | null`), chỉ giá trị đổi thành `/public` URL.

---

## [W7-D4] SYSTEM messages (service + render + immutability guard) — APPROVED

Verdict: APPROVED (0 BLOCKING). 270/270 tests pass (+13 SystemMessageTest SM-01 → SM-13). Contract v1.2.0-w7-system + SOCKET v1.7-w7 finalized.

Blocking: none.

Checklist PASS:
- BE hook đủ 8 event: GROUP_CREATED (createGroup), MEMBER_ADDED per added user (addMembers, skipped excluded — verified SM-03), MEMBER_REMOVED (removeMember, insert BEFORE hard-delete), MEMBER_LEFT (leaveGroup non-OWNER), OWNER_TRANSFERRED (autoTransferred=true leave, =false transfer), ROLE_PROMOTED/DEMOTED (changeRole), GROUP_RENAMED (updateGroupInfo chỉ khi changes.containsKey("name")).
- Event order OWNER leave: OWNER_TRANSFERRED TRƯỚC MEMBER_LEFT verified SM-06 (createdAt ASC comparator).
- Avatar-only PATCH NO system message (SM-11 verify count=0).
- Immutability guard: editViaStomp + deleteViaStomp check `type == SYSTEM` TRƯỚC anti-enum merge, throw SYSTEM_MESSAGE_NOT_EDITABLE / SYSTEM_MESSAGE_NOT_DELETABLE (403) — verified SM-12/13.
- REST endpoints: KHÔNG có PUT/DELETE /api/messages/{id} trong codebase (tất cả edit/delete qua STOMP), nên không có gap — document trong contract "nếu endpoint này có triển khai sau".
- V10 migration: sender_id DROP NOT NULL (SYSTEM không có user sender); CHECK chk_message_system_consistency (type=SYSTEM ↔ system_event_type non-null AND sender_id null); JSONB column systemMetadata qua JsonMapConverter (portable H2/Postgres).
- MessageDto extend systemEventType + systemMetadata (2 optional field, null cho mọi TEXT/IMAGE/FILE). MessageMapper.toDto pass through 2 field.
- FE SystemMessage component: centered italic pill, role="status", 8 event type i18n vi-VN + fallback "(sự kiện hệ thống)" + substitution "Bạn"/"bạn" actor/target.
- FE dispatcher: MessagesList branch theo type; MessageItem memo wrapper cũng dispatch (defense-in-depth). MessageItemInner tách khỏi wrapper tránh hooks order violation.
- FE null-safety audit sau đổi sender: MessageDto | null: useConvSubscription.appendToCache (self-dedup), MessagesList.shouldShowAvatar, isOwn, ReplyPreviewBox (sender?.fullName ?? 'hệ thống'), MessageItem avatar + sender name hiding. Toàn bộ dùng optional chaining.
- FE ACK error handler: SYSTEM_MESSAGE_NOT_EDITABLE toast + clear edit marker defensive; SYSTEM_MESSAGE_NOT_DELETABLE toast without revert (SYSTEM không có deleteStatus).
- SystemMessageService reuse existing MessageCreatedEvent + @TransactionalEventListener(AFTER_COMMIT) — KHÔNG tạo STOMP event type mới.
- @Transactional propagation REQUIRED (default) → join caller TX → atomic với action.

Key decisions:
- Anti-enum exception documented cho SYSTEM_MESSAGE_NOT_EDITABLE/NOT_DELETABLE — vì SYSTEM visible cho mọi member, distinguish không leak. Error code rõ giúp FE toast đúng ngữ cảnh.
- JsonMapConverter thay @JdbcTypeCode(SqlTypes.JSON) — H2 test mode fail với JdbcTypeCode (String→Map parse error), converter portable qua Jackson direct.
- content="" (empty string) thay vì null cho SYSTEM — FE render từ metadata, không cần null check content.
- sender=null thay vì "system user" UUID đặc biệt — tránh maintain row đặc biệt trong users table.
- V1 index idx_messages_system_type commented (V2 add khi query pattern emerge).

Patterns confirmed (5 mới):
1. Server-generated message subtype service (SystemMessageService.createAndPublish)
2. Immutable message subtype guard trước anti-enum (exception documented)
3. JPA JsonMapConverter cho JSONB portable H2/Postgres (PITFALL + FIX)
4. Dispatcher pattern cho message polymorphism (list-level + component-level defense)
5. SYSTEM message i18n với "Bạn"/"bạn" role substitution + fallback unknown enum

Pitfalls documented:
1. Mockito Boolean.FALSE default + Jackson bool unbox (assert wrapped không primitive)
2. Optional field defensive in TypeScript union (audit tất cả `.sender.` usage khi DTO lỏng)

Warnings non-blocking:
- SystemMessageService.createAndPublish gọi `messageRepository.findById(message.getId()).orElseThrow()` reload sau save — redundant DB roundtrip (save() đã trả managed entity). Micro-optimization V2.
- `SystemMessageService` @Transactional trên class + method — redundant annotation nhưng không sai. Clean up nếu refactor.
- Flyway migration V10 comment "Relax sender_id nullable" — documented rationale rõ, keep.

Contract: API v1.2.0-w7-system + SOCKET v1.7-w7 finalized. Full changelog table cập nhật trong cả 2 file.

---

## [W6-D5] Security Audit Tuần 6 — APPROVED

Verdict: APPROVED. 18/18 audit items PASS, 0 BLOCKING, 0 WARNINGS.

### Audit checklist (18/18 PASS)

**Path traversal (W6-D1)**
1. **LocalStorageService.store**: PASS. `assertWithinBase()` gọi sau `normalize()` + `toAbsolutePath()` → `startsWith(basePath)` check. Throw `IllegalArgumentException` nếu escape. Defense thêm: reject fileId chứa `/`, `\`, `..` ở entry; reject ext chứa `/`, `\`, `.`. `basePath` dùng `toRealPath()` resolve symlinks → robust prefix check.
2. **Internal filename UUID**: PASS. `UUID.randomUUID().toString() + "." + extensionFromMime(detectedMime)`. ext lấy từ `MIME_TO_EXT` map (14 entries cover whitelist), KHÔNG đọc từ `originalFilename`.
3. **sanitizeFilename**: PASS. Strip `[\x00-\x1F\x7F]` (control + DEL) + `[\\/]` (path separators) + truncate 255. Output CHỈ lưu vào `FileRecord.originalName` cho `Content-Disposition` khi download. KHÔNG dùng làm storage path.

**MIME validation (W6-D1 + W6-D4-extend)**
4. **Apache Tika magic bytes**: PASS. `tika.detect(InputStream)` đọc `MultipartFile.getInputStream()` (Tika peek ~8KB). KHÔNG trust `file.getContentType()` (header). Sau detect: charset strip `raw.split(";")[0].trim()` cho `text/plain; charset=UTF-8`.
5. **ZIP→Office override**: PASS. CHỈ trigger khi `"application/zip".equals(detectedMime)` + extension là docx/xlsx/pptx (lowercase). ZIP thật giữ nguyên `application/zip`. Sau override vẫn `ALLOWED_MIMES.contains(detectedMime)` check → không skip whitelist.
6. **Blacklist via whitelist**: PASS. ALLOWED_MIMES có 14 safe types. .exe → Tika magic detect `application/x-msdownload` → không trong whitelist → `FileTypeNotAllowedException` 415. Test V07 + F22 confirm.

**Authorization (W6-D2)**
7. **FileAuthService 2 rules**: PASS. Rule 1: `record.getUploaderId().equals(userId)` luôn pass. Rule 2: `messageAttachmentRepository.existsByFileIdAndConvMemberUserId(fileId, userId)`. Không có override/bypass condition.
8. **Anti-enumeration 404**: PASS. Mọi case (not-found, expired=true cleanup, expires_at past, not-uploader+not-member) → `Optional.empty()` → controller throw 404 NOT_FOUND. Không 403, không 410 Gone.
9. **JPQL query**: PASS. `SELECT COUNT(ma) > 0 FROM MessageAttachment ma JOIN Message m ... JOIN ConversationMember cm ...` — chỉ COUNT, không load entity. Index `idx_msg_attach_file(file_id)` (V7) + `(conversation_id, user_id)` (V3) cover query plan.

**Rate limit + Attachment validation (W6-D1/D2)**
10. **Upload rate limit**: PASS. Redis `INCR rate:file-upload:{userId}` + `EX 60s` first time. Max 20. Catch `DataAccessException` → log WARN + return (fail-open). Throw `FileRateLimitedException` với `retryAfter = TTL` khi exceed.
11. **validateAndAttachFiles**: PASS (6 rules — vượt requirement 5). Order rẻ→đắt: count → existence (findAllById size mismatch) → ownership (uploaderId == userId) → expiry (expires_at < now → 410 GONE) → unique (existsByIdFileId → 409 CONFLICT) → group type (all images OR singleNonImage → MSG_ATTACHMENTS_MIXED).
12. **Content XOR Attachments**: PASS. `validateStompPayload`: `if (!hasContent && !hasAttachments) throw MSG_NO_CONTENT 400`. ADR-021 confirmed.

**Cleanup safety (W6-D3)**
13. **stillAttached case**: PASS. `deletePhysical(file)` TRƯỚC → `existsByIdFileId(file.getId())` check → nếu attached: `setExpired(true) + save` (DB record giữ) + log WARN "physical deleted, DB kept with expired=true". GET /files/{id} subsequent → openStream() throw StorageException → controller catch → 404 FILE_PHYSICALLY_DELETED.
14. **Batch pagination**: PASS. `PageRequest.of(0, BATCH_SIZE=100)` luôn page 0 (sau xử lý records rời predicate, page 0 lần sau chứa records mới). Loop terminate khi `batch.isEmpty() || batch.getNumberOfElements() < BATCH_SIZE`. Không OOM.
15. **@ConditionalOnProperty**: PASS. Test profile `application-test.yml`: `app.file-cleanup.enabled=true` + `expired-cron="-"` + `orphan-cron="-"`. Spring "-" = disabled trigger value: bean load để inject + gọi method trực tiếp, scheduler KHÔNG fire trong test.

**FE security (W6-D4 + fix)**
16. **useProtectedObjectUrl**: PASS. `api.get<Blob>(path, { responseType: 'blob', signal: controller.signal })` → `URL.createObjectURL(res.data)`. Cleanup: `controller.abort() + URL.revokeObjectURL(currentUrl)` trong useEffect return. Catch CanceledError/AbortError silent. Refetch khi `path` thay đổi (dep array).
17. **No raw `<img src=/api/files`**: PASS. Grep toàn `frontend/src` chỉ tìm thấy:
    - `AttachmentGallery.tsx:51` `<img src={src}>` — `src` từ `useProtectedObjectUrl(attachment.thumbUrl ?? attachment.url)` → blob URL. SAFE.
    - `useUploadFile.ts:69` POST `/api/files/upload` qua axios — không phải `<img src>`. SAFE.
    - FileCard cũng dùng `useProtectedObjectUrl`. SAFE.

**Contract consistency (W6-D4-extend)**
18. **iconType 8 values**: PASS. `resolveIconType()` trong `FileService.java`:
    - IMAGE: `mime.startsWith("image/")` (jpeg/png/webp/gif)
    - PDF: `application/pdf`
    - WORD: contains `wordprocessingml` OR `application/msword`
    - EXCEL: contains `spreadsheetml` OR `application/vnd.ms-excel`
    - POWERPOINT: contains `presentationml` OR `application/vnd.ms-powerpoint`
    - TEXT: `text/plain`
    - ARCHIVE: contains `zip` OR `7z`
    - GENERIC: fallback (cover null + unknown)
    Cover đủ 14 MIME → 8 iconType. GENERIC là fallback an toàn.

### Verdict

**APPROVED — production-ready cho V1 launch**.
- 0 BLOCKING. 0 non-blocking warnings (đã document trong WARNINGS.md từ W6-D1→D4).
- Move 3 items WARNINGS RESOLVED bucket: W6-1 path traversal, W6-2 MIME spoofing, W6-4 orphan cleanup.
- Acceptable V1 confirmed: W6-3 (disk quota — rate limit + expiry mitigate), W6-5 (upload rate limit fail-open — consistent ADR-005).
- V2 Enhancement bucket expanded: signed URLs (FE perf), Office macro scanning (POI), per-user storage quota.

Tests: BE 210/210 pass. FE `npm run build` zero TS errors.

Contract: `docs/API_CONTRACT.md` v0.9.5-files-extended unchanged. `docs/SOCKET_EVENTS.md` v1.3-w5d2 unchanged.

---

## [W6-D4-extend] Implement expand file types — APPROVE

Verdict: APPROVE. Tests 210/210 pass (target 197+13). FE `npm run build` zero TS errors.

Checklist all PASS:
1. ALLOWED_MIMES = 14 (4 image + 10 non-image), khớp contract.
2. Charset strip `tika.detect().split(";")[0].trim()` cho `text/plain; charset=...`.
3. ZIP→Office override CHỈ áp dụng khi Tika trả `application/zip` + extension docx/xlsx/pptx; ZIP thật giữ `application/zip` (test V14 cover).
4. resolveIconType cover đủ 8 enum: IMAGE/PDF/WORD/EXCEL/POWERPOINT/TEXT/ARCHIVE/GENERIC.
5. singleNonImage rule = `files.size()==1 && !allImages` thay cho hardcode singlePdf. MAX_PDF_ATTACHMENTS xoá có comment giải thích.
6. FileDto field order: id, mime, name, size, url, thumbUrl, **iconType**, expiresAt — đúng vị trí giữa thumbUrl và expiresAt.
7. validateFiles 3-arg signature, được call ở cả handleFileChange + handleDrop với activePending.length + pendingMimes.
8. AttachmentDto.iconType non-optional. Optimistic message tạo `attachments: []` (length 0) → MessageItem có guard `attachments.length > 0` trước khi access `[0].iconType` → an toàn, không break.
9. FileCard dùng `attachment.iconType ?? 'GENERIC'` để chọn emoji + color, KHÔNG hard-code MIME.
10. PdfCard không còn import nào (grep clean).
11. Security blacklist verify: F22 test upload .exe (MZ magic) → 415 FILE_TYPE_NOT_ALLOWED (Tika detect application/x-msdownload, không trong whitelist).
12. MIME mismatch defense: Tika magic bytes detect → reject nếu không khớp whitelist (V07 EXE rename → reject).

Key decisions confirmed:
- ZIP→Office override chỉ kích hoạt cho `application/zip` (an toàn, không over-eager).
- iconType là non-optional ở FE type — buộc BE luôn populate (đã verify resolveIconType có default GENERIC).
- MessageItem fallback: nếu `iconType` null/undefined (defensive cho legacy), check `mime.startsWith('image/')` — vẫn safe khi BE deploy đúng.
- MAX_PDF_ATTACHMENTS removed thay vì rename, comment ghi rõ thay bằng singleNonImage logic.

Patterns confirmed:
- Group A/B mixing semantics: Group A 1-5 OR Group B exactly 1 alone, không trộn.
- `MSG_ATTACHMENTS_TOO_MANY` chỉ cho >5 ảnh; >1 file Group B fall vào `MSG_ATTACHMENTS_MIXED` (đúng theo error message mới "Chỉ được gửi 1-5 ảnh, hoặc 1 tệp khác").
- Map.ofEntries dùng cho >10 entries trong MIME_TO_EXT (Map.of() max 10).

Non-blocking suggestions (tuỳ chọn, không cần fix):
- `MessageInput.tsx` accept attribute liệt kê 14 MIME + 14 extension — verbose. Có thể trích thành const ALLOWED_FILE_PICKER_ACCEPT chung với validateFiles ALLOWED_MIMES để 1 nguồn truth ở FE.
- `useAckErrorSubscription` toast cho `MSG_ATTACHMENTS_MIXED` đang ghi "Không thể gửi lẫn PDF và ảnh cùng lúc" — message chưa update theo W6-D4-extend (Group A/B). Cosmetic, FE có thể đổi thành "Không thể trộn ảnh với tệp khác / chỉ gửi 1 tệp".
- FE `clientIconEmoji` ở PendingAttachmentItem trùng logic với BE resolveIconType → khi extend MIME phải sửa 2 nơi. Acceptable cho V1 (client preview chỉ là UI).

Contract: `docs/API_CONTRACT.md` v0.9.5-files-extended — ĐÃ CHỐT, khớp implementation.

---

## [W6-D4] FE File Upload UI + Attachment Display — APPROVE

Blocking: 0. Build clean (`npm run build` zero errors, 2 pre-existing warnings là chunk size + dynamic import authService — không cần fix). Diff: 5 file FE modified + 1 folder mới (`features/files/` có 2 hook + 3 component).

BLOCKING checklist 1-9 PASS hết:
1. **AbortController native** — `useUploadFile.ts` line 50 `new AbortController()` + `signal: controller.signal` line 74. KHÔNG dùng axios.CancelToken (deprecated v1+).
2. **revokeObjectURL 4 điểm** — VERIFIED tất cả: cancel (line 128), remove (line 136), clear loop (line 144), unmount cleanup useEffect via `pendingRef.current` (line 41). Pattern ref + cleanup đúng (state stale closure trap tránh được).
3. **Content-Type undefined cho FormData** — line 73 `headers: { 'Content-Type': undefined }` — comment giải thích lý do (browser auto-set boundary). KHÔNG hardcode `multipart/form-data` thủ công.
4. **`attachments: []` optimistic** — `hooks.ts:146` field present trong optimisticMsg, comment "ACK replaces with real AttachmentDto[]".
5. **RetryButton `attachmentIds: []`** — `MessageItem.tsx:62` `sendMessage(message.content ?? '', undefined, [])` — đúng signature mới (3rd arg attachmentIds empty cho retry, không upload lại).
6. **Guard uploading** — `MessageInput.tsx:88` `if (pending.some((p) => p.status === 'uploading'))` → `toast.error('Đang tải tệp...')` + return, BEFORE check disabled/connected.
7. **Send disable đúng** — `MessageInput.tsx:269` `disabled={isInputDisabled || (!content.trim() && !hasDoneAttachments) || isOverLimit}` — chỉ enable khi có text HOẶC có attachment status='done' (errors-only KHÔNG enable).
8. **DragLeave currentTarget.contains check** — `MessageInput.tsx:147` `if (!e.currentTarget.contains(e.relatedTarget as Node))` tránh flicker khi mouse di qua child elements.
9. **StompSendPayload.attachmentIds wired** — `stompClient.ts:23` field added to interface; `hooks.ts:169` truyền `attachmentIds: attachmentIds ?? []`; `publishConversationMessage` JSON.stringify toàn bộ payload (no field omission risk).

ACK error handler (W6-D4 bonus): `useAckErrorSubscription.ts` switch(code) thêm 7 attachment-specific error codes (MSG_ATTACHMENT_NOT_FOUND, MSG_ATTACHMENT_NOT_OWNED, MSG_ATTACHMENT_ALREADY_USED, MSG_ATTACHMENTS_MIXED, MSG_ATTACHMENTS_TOO_MANY, MSG_ATTACHMENT_EXPIRED, MSG_NO_CONTENT) → mỗi case `toast.error(...)` user-facing message tiếng Việt. AUTH refresh logic giữ nguyên trong cùng switch. Khớp 100% với SOCKET_EVENTS.md mục 305-311.

Validation client-side `validateFiles.ts` đúng business rule: all-images max 5 OR exactly-1-PDF (mixed → error), MIME whitelist (jpeg/png/webp/gif/pdf), max 20 MB. Defense-in-depth với BE (BE vẫn validate, FE chỉ improve UX).

MessageItem rendering: image-attachment + text caption tách riêng (Messenger pattern) — attachment KHÔNG có bubble bg, text bubble riêng dưới. PdfCard dùng cho 1 PDF, AttachmentGallery cho 1-5 images với lightbox + keyboard nav (←/→/Esc) + download button. Thumbnail fallback `att.thumbUrl ?? att.url` → fail-open khi BE thumb generate fail (ADR-020 consistency).

Patterns confirmed:
- **Cleanup blob URL ref-based pattern (FE)**: `pendingRef.current = pending` mỗi render → unmount useEffect đọc `pendingRef.current` (KHÔNG đọc `pending` state — sẽ stale closure). Forge pattern bắt buộc cho mọi cleanup `URL.createObjectURL` / EventSource / WebSocket có dynamic state. Reuse cho future audio/video preview.
- **FormData Content-Type undefined**: axios + multipart MUST set `headers: { 'Content-Type': undefined }` — KHÔNG omit headers (interceptor có thể inject `application/json` default), KHÔNG hardcode `multipart/form-data` (thiếu boundary → BE parse fail). Comment giải thích trong code BẮT BUỘC.
- **AbortController native, không CancelToken**: axios v1+ deprecated CancelToken. Mọi cancel-able request mới dùng `new AbortController()` + `signal: controller.signal`. Cancel: `controller.abort()` → catch `axios.isCancel(err)` filter silent (không hiện error toast).

Contract: SOCKET_EVENTS.md v1.3-w5d2 unchanged. Attachment error codes đã có từ W6-D1 contract draft → FE consume khớp.

Recommend: KHÔNG cần fix gì. Có thể commit + tag.

---

## [W6-D3] File Cleanup Jobs (@Scheduled expired + orphan) — APPROVE

Blocking: 0. Tests 197/197 BE pass (191 cũ + 6 mới CJ01-06).

Key findings:
- `@EnableScheduling` thêm vào `ChatAppApplication.java` (BLOCKING #1 pass).
- Cron format Spring 6 6-fields (`second minute hour day month weekday`) đúng:
  - `0 0 3 * * *` → 3:00:00 AM UTC mỗi ngày (expired)
  - `0 0 * * * *` → đầu mỗi giờ (orphan)
  - Externalize qua `${FILE_CLEANUP_EXPIRED_CRON}` / `${FILE_CLEANUP_ORPHAN_CRON}` — ops có thể tune.
- `@ConditionalOnProperty(name="app.file-cleanup.enabled", havingValue="true", matchIfMissing=true)` trên class — toàn bộ bean disable được qua flag duy nhất.
- Test profile dùng `enabled=true` + `cron="-"` (Spring disabled trigger value): bean vẫn load để test inject + gọi method trực tiếp, nhưng scheduler KHÔNG fire trong test → no race với assertion.
- Batch pagination: `findBy...(threshold, PageRequest.of(0, 100))` luôn page 0. Đúng vì sau mỗi batch records bị delete/update khỏi predicate (`expired=false → true` hoặc DELETE), page 0 tiếp theo chứa records mới — không bỏ sót, không OOM.
- Loop terminate khi `batch.isEmpty()` HOẶC `batch.getNumberOfElements() < BATCH_SIZE` — tránh extra query thừa khi đã hết records.
- Per-record try-catch trong loop: 1 file IOException không kill cả job, log error + continue. Sau exception expired-job vẫn `setExpired(true)` defensive để không bị query lại vô hạn (nested try-catch nếu cả save fail thì log đôi).
- `stillAttached` handling đúng spec: physical delete TRƯỚC → check `existsByIdFileId` → nếu attached → `setExpired(true) + save` (DB record giữ, log WARN); nếu không → `delete()` DB row.
- `FileController.download` + `FileController.downloadThumb` cả 2 catch `StorageException` (FileService wrap IOException → StorageException) → `AppException(404, "FILE_PHYSICALLY_DELETED", ...)` thay vì 500. Dovetail với stillAttached: GET /files/{id} của file expired-but-attached → 404 graceful.
- Repository methods Spring Data naming convention chuẩn: `findByExpiresAtBeforeAndExpiredFalse(threshold, Pageable)` + `findByAttachedAtIsNullAndCreatedAtBefore(threshold, Pageable)`. Cả 2 trả `Page<FileRecord>` đúng signature cho `PageRequest.of`.
- `LocalStorageService.delete()` dùng `Files.deleteIfExists()` — idempotent: file đã bị xóa (race với 2nd run) không throw, swallow gracefully.
- `deletePhysical` xóa cả `storagePath` + `thumbnailInternalPath` (nếu có) — không leak orphan thumbnail trên disk khi original bị xóa.

Test coverage (CJ01-06 — full Spring context để inject real repos + JdbcTemplate):
- CJ01 expired no-attachment → DB delete + storage.delete called
- CJ02 not-yet-expired → untouched (storage.delete never)
- CJ03 expired but still attached → physical delete + DB kept với expired=true
- CJ04 orphan >1h → DB delete
- CJ05 orphan <1h (5min) → untouched (grace period)
- CJ06 attached file (createdAt 2 days old) → orphan-job không touch (attachedAt non-null)

Patterns confirmed (added to knowledge):
- `@Scheduled` cleanup job pattern (cron format 6-field, ConditionalOnProperty disable, batch page-0 loop, per-record try-catch).
- stillAttached graceful flow (physical delete → DB mark expired → GET 404 graceful via StorageException → AppException).
- Test profile `cron="-"` để disable trigger (giữ bean load).

Contract: không thay đổi REST/SOCKET contract. Cleanup job là internal background — không expose public.

Verdict: APPROVE. Cleanup pipeline kiến trúc gọn, idempotent, defensive, test coverage đủ 6 case quan trọng. Pre-launch: thêm V2 multi-instance lock note vào WARNINGS.md.

---

## [W6-D2] Thumbnail + File Auth + Wire Attachments — APPROVE WITH COMMENTS

Blocking: 0. Tests 191/191 BE pass (172 cũ + 19 mới).

Key findings (VIỆC 1 — Thumbnail):
- `ThumbnailService.generate` dùng Thumbnailator `size(200, 200)` + outputQuality 0.85, giữ extension gốc. Path layout `{base}/yyyy/MM/{uuid}_thumb.{ext}` — cùng folder source.
- `StorageService.resolveAbsolute(internalPath)` → canonical prefix check qua `startsWith(basePath)`, throw SecurityException khi detect path traversal (tách với IllegalArgumentException để phân biệt attack signal vs args invalid). ThumbnailService delegate qua storage layer, KHÔNG tự resolve Path.
- `FileService.upload` step 6 thumbnail fail-open: try-catch WRAP quanh generate() — thumb fail → WARN log + `thumbnail_internal_path=null` → FileDto.thumbUrl=null. Upload vẫn 201. Không rollback toàn file vì feature phụ.
- `FileController.downloadThumb`: auth qua `FileAuthService.findAccessibleById(id, user.getId())` (uploader OR conv-member) + `.filter(r -> r.getThumbnailInternalPath() != null)` → anti-enum 404 NOT_FOUND cho mọi case (no-thumb/not-access/expired).
- Cache-Control: `CacheControl.maxAge(Duration.ofDays(7)).cachePrivate()` = 7 ngày. ETag `"{id}-thumb"`. X-Content-Type-Options nosniff.

Key findings (VIỆC 2 — Authorization):
- `FileAuthService` tách riêng khỏi `FileService` — Optional return thay vì throw, caller quyết định error code. JPQL `existsByFileIdAndConvMemberUserId` dùng COUNT > 0 (không load entity thừa). JOIN path `message_attachments → Message → ConversationMember`.
- Anti-enum 404 nhất quán: not-found, non-access, expired (expires_at < now), cleanup-deleted (expired=true) — ALL return Optional.empty() → 404 NOT_FOUND (không 403, không 410 Gone).
- Controller `sanitizeForHeader`: strip CRLF + `"` + non-ASCII cho Content-Disposition filename (chống header injection). Unicode filename mất dấu — V1 trade-off đơn giản, V2 cân nhắc RFC 5987 `filename*=UTF-8''encoded`.

Key findings (VIỆC 3 — Wire attachments):
- `SendMessagePayload` thêm `attachmentIds: List<UUID>` (nullable/empty OK). `MessageDto` thêm `attachments: List<FileDto>` (LUÔN non-null, empty list thay null).
- `validateStompPayload` XOR check: `hasContent || hasAttachments` — cả 2 rỗng → `MSG_NO_CONTENT`. Content length check CHỈ khi hasContent (không apply max với attachment-only).
- `validateAndAttachFiles` flow order (RẺ → ĐẮT): (1) Count > MAX_IMAGE_ATTACHMENTS (pre-check pre-DB), (2) findAllById → size mismatch → `MSG_ATTACHMENT_NOT_FOUND`, (3) per-file ownership `uploaderId == userId` → `MSG_ATTACHMENT_NOT_OWNED` (403), (4) per-file expiry → `MSG_ATTACHMENT_EXPIRED` (410 GONE), (5) unique `existsByIdFileId` → `MSG_ATTACHMENT_ALREADY_USED` (409), (6) group type check (all images OR 1 PDF) → `MSG_ATTACHMENTS_MIXED`. Sau pass: INSERT message_attachments rows + `file.markAttached()`.
- `validateAndAttachFiles` chạy SAU `messageRepository.save(message)` trong cùng `@Transactional` → throw sẽ rollback CẢ message save. Atomic guarantee: nếu attachment fail → message không được save.
- `MessageMapper.toDto` strip attachments khi deletedAt != null: `isDeleted ? Collections.emptyList() : loadAttachmentDtos(message)`. Applied TRUNG TÂM cho REST + ACK + broadcast. Privacy consistent với content strip (W5-D3).
- Edit (`editViaStomp`) KHÔNG process attachmentIds — contract §3c đã ghi "EDIT constraint V1: KHÔNG cho sửa attachments".
- `deriveMessageType` từ `attachmentIds.get(0)`: image → IMAGE, else FILE (PDF). TEXT khi attachments null/empty.
- N+1 risk trong `MessageMapper.loadAttachmentDtos` (JOIN + N findById per file) → documented trên class javadoc + AD-19 trong WARNINGS.md với V2 plan `@EntityGraph`.

Patterns confirmed (knowledge update):
- **FileAuthService pattern (ADR-019)**: uploader OR conv-member, Optional return, anti-enum 404. Tách auth rule khỏi business logic, reuse giữa download + thumb endpoints.
- **Fail-open thumbnail (ADR-020)**: upload vẫn 201 dù thumb fail. Feature phụ KHÔNG block hot-path. Log WARN, FE fallback với thumbUrl=null.
- **Content XOR Attachments (ADR-021)**: message phải có content hoặc attachments (cả 2 rỗng → MSG_NO_CONTENT). DB content NOT NULL → service trim+persist empty string khi attachment-only.
- **Soft-delete strip attachments (ADR-022, + W5-D3 extend)**: MessageMapper strip cả content + attachments khi deletedAt != null. Applied trung tâm ở mapper cho TẤT CẢ serialization path.
- **Validation order rẻ → đắt**: count → existence → ownership → expiry → unique → group. Fail-fast minimize DB hit.

Warnings (non-blocking):
1. Contract §947 "thumbnail luôn JPEG" vs code giữ ext gốc → AD-23. Fix contract.
2. Thumb endpoint không set Content-Disposition → AD-24. Minor UX.
3. Content-Type trả theo `record.mime` → nếu AD-23 fix sang JPEG PHẢI update → AD-25.
4. `MessageMapper.loadAttachmentDtos` silent-skip khi FileRecord not-found → AD-26. ON DELETE CASCADE từ files → không xảy ra V1.
5. `FileService.upload` step 6 `fileRecordRepository.save(record)` lần 2 redundant với JPA dirty tracking → AD-27. Non-blocking.
6. `sanitizeForHeader` strip non-ASCII filename → Unicode tên file mất dấu khi download. V1 acceptable. V2 RFC 5987 `filename*=UTF-8''encoded`.
7. GIF animated thumbnail chỉ lấy frame đầu (Thumbnailator default) — acceptable V1 per contract §964.
8. `existsByFileIdAndConvMemberUserId` JPQL — dùng index `idx_msg_attach_file(file_id)` đã có trong V7 migration. ConversationMember có index `(conversation_id, user_id)` từ V3. Query plan OK.

Kết luận: APPROVE WITH COMMENTS. 0 BLOCKING. 8 non-blocking warnings. Core logic + auth + validation + mapper strip đều đúng pattern. ADR-019/020/021/022 thêm vào knowledge.

---

## [W6-D1] File upload foundation — (implicit summary, contract draft)

Verdict: contract drafted v0.9.0-files (extended W6-D4 → v0.9.5). Pre-implement audit: 4 pre-production items added to WARNINGS.md (W6-1 path traversal, W6-2 MIME spoofing, W6-3 disk quota, W6-4 orphan cleanup). 4 AD items added (AD-19 N+1 attachments, AD-20 soft-delete strip both, AD-21 thumbnail lazy latency, AD-22 SimpleBroker frame size).

Key contract decisions:
- POST /api/files/upload (multipart/form-data) → 201 FileDto. Auth required (Bearer JWT).
- GET /api/files/{id} (download) — auth qua FileAuthService (uploader OR conv-member), 404 anti-enum.
- GET /api/files/{id}/thumb — same auth, image-only.
- attachmentIds payload trong /app/conv.{id}.message — nullable list, validate sau save.
- 7 error codes attachment: MSG_ATTACHMENT_NOT_FOUND, MSG_ATTACHMENT_NOT_OWNED, MSG_ATTACHMENT_EXPIRED, MSG_ATTACHMENT_ALREADY_USED, MSG_ATTACHMENTS_TOO_MANY, MSG_ATTACHMENTS_MIXED, MSG_NO_CONTENT.

---

## [W5] Tuần 5 summary — Edit/Delete/Reply/Reconnect (4 phases)

- **W5-D1 Typing Indicator** — APPROVED (sau Fix A applied). BLOCKING: AuthChannelInterceptor throw FORBIDDEN cho mọi `/app/conv.*` non-member → conflict silent-drop typing → reconnect loop. Fix: `DestinationPolicy` enum (STRICT_MEMBER vs SILENT_DROP) + `resolveSendPolicy(destination)`. Patterns: ephemeral event + silent drop + 3-timer FE hook.
- **W5-D2 Edit Message + Unified ACK (ADR-017)** — APPROVED sau fix FE BLOCKING. BLOCKING: `useEditMessage` optimistic ghi đè content+editedAt không revert ERROR → cache lệch DB. Fix Option A: bỏ optimistic content, chỉ mark saving, ACK patch từ server. Key decisions: ADR-016 STOMP-send Path B, ADR-017 unified ACK (1 queue + `operation` discriminator). Edit window 300s server, FE buffer 290s clock skew.
- **W5-D3 Delete Message + Facebook UI** — APPROVE WITH COMMENTS. ADR-018 (delete unlimited window). Anti-enum 4-case merge MSG_NOT_FOUND. Edit-after-delete regression guard. ACK minimal raw Map (không leak content/sender). Patterns: minimal ACK + defensive re-ACK.
- **W5-D4 Reconnect Catch-up + Reply UI** — APPROVE WITH COMMENTS. Forward pagination `after` param. wasDisconnectedRef trigger only on reconnect (not first connect). Reply state per-conversation scoping. Contract drift detected + fixed (replyToMessageId in payload). Tests 145/145 BE pass.
- **W5-D5 polish** — fix 3 warnings (status tick deleted, timeout toast singleton via sonner, log WARN duplicate frame). WARNINGS.md restructure.

ADRs added W5: ADR-016, ADR-017, ADR-018.
Contract: API v0.6.1-messages-stomp-shift + v0.6.2-after-param. SOCKET v1.3-w5d2.

---

## [W4] Tuần 4 summary — Realtime broadcast (4 phases)

- **W4-D1 Messages REST** — APPROVE WITH COMMENTS. Schema V5 messages + index `(conv_id, created_at DESC)`. Cursor pagination `limit+1` ASC return. Anti-enum 404 CONV_NOT_FOUND. Reply validation no-soft-deleted-filter (AD-12 defer). W4-BE-1 pre-prod (sender_id NOT NULL + ON DELETE SET NULL conflict, V1 không trigger).
- **W4-D2 Contract SOCKET v1.0-draft-w4 + Messages UI Phase A** — APPROVE. ADR-014 REST-gửi + STOMP-broadcast (publish-only, no tempId inbound). ADR-015 SimpleBroker V1 → RabbitMQ V2 trigger. Reuse MessageMapper (Rule Vàng: shape IDENTICAL REST + broadcast).
- **W4-D3 WebSocket foundation** — APPROVE. SockJS + SimpleBroker, `setAllowedOriginPatterns` config-driven, size limit 64KB. AuthChannelInterceptor JWT CONNECT + member check SUBSCRIBE. FE module-level singleton + manual reconnect 1s→30s exponential. Dynamic import authService phá circular dep.
- **W4-D4 Realtime broadcast wire** — APPROVE. `@TransactionalEventListener(AFTER_COMMIT)` + try-catch toàn bộ. FE `useConvSubscription` re-sub on CONNECTED. Dedupe cross-pages bằng id. sockjs-client global shim belt+suspenders.
- **Post-W4 Path B STOMP-send full wire** — APPROVE WITH COMMENTS. ADR-016 applied. Dedup `msg:dedup:*` SET NX EX 60s atomic. ACK afterCommit. FE timer 3 branches (ACK/ERROR/timeout). Retry = tempId mới mỗi lần.

ADRs added W4: ADR-014, ADR-015, ADR-016.
Patterns confirmed: Transactional broadcast pattern, STOMP subscription hook pattern, sockjs-client global shim.

---

## [W3] Tuần 3 summary — Conversations (5 phases)

- **W3-D1 V3 schema + entities + FE layout** — APPROVE WITH COMMENTS. ADR-012 UPPERCASE enum (ONE_ON_ONE/GROUP, OWNER/ADMIN/MEMBER) khác ARCHITECTURE.md lowercase, không sửa ARCHITECTURE. Bỏ left_at/leave_reason/is_hidden/cleared_at (out-of-scope V1). W3-BE-1 (UUID @GeneratedValue + insertable=false conflict) → migrate `@PrePersist` Option B (resolved W3-D2).
- **W3-D2 BE 4 endpoints + FE scaffold** — REQUEST CHANGES → APPROVE. 2 BLOCKING FE: `.code` vs `.error` field name; ConversationDto vs SummaryDto shape. Fix applied. ADR-013 ONE_ON_ONE race no-lock V1 (P<0.01%). Patterns: Error response field name drift + Summary vs Detail shape drift.
- **W3-D3 Conversation list UI + create dialog** — APPROVE WITH COMMENTS. 409 idempotency navigate existing. BE rate limit Redis INCR `rate:conv_create:{userId}` 10/min. Contract drift fix: API "30/giờ" → code "10/min" sync.
- **W3-D4 ConversationDetailPage + GET /users/{id} + last_seen_at** — APPROVE WITH COMMENTS. UserSearchDto reuse (không expose email). 404 merge anti-enum. V4 migration last_seen_at column nhưng KHÔNG expose V1 (AD-9).
- **W3-D5 Consolidation WARNINGS.md** — restructure 4 sections (Pre-prod / Acceptable V1 / Cleanup / V2 + Resolved).

ADRs added W3: ADR-012, ADR-013.

---

## [W2] Tuần 2 summary — Auth (5 phases + Final Audit)

- **W2-D1 W-BE-3 + W-FE-2 RESOLVED** — ADR-010 AuthMethod enum lowercase value JWT claim. tokenStorage.ts pattern phá circular dep `api.ts <-> authStore.ts`.
- **W2-D2 Phase A (FE init) + Phase B (BE register/login)** — APPROVE WITH COMMENTS. ADR-005 Rate limit Redis INCR + TTL on first. Login tách `checkRateLimit` (GET) khỏi `incrementFailCounter` (INCR+EX). Refresh token SHA-256 hash vào Redis. User enumeration protection (AUTH_INVALID_CREDENTIALS merge user-not-found + wrong-password).
- **W2-D3 Phase C wire FE Login+Register** — APPROVE WITH COMMENTS. W-FE-1 RESOLVED (username regex sync). Payload strip sensitive (RegisterPage explicit object). 6 BE error codes map FE.
- **W2-D3.5 POST /refresh** — APPROVE WITH COMMENTS. ADR-006 rotation + reuse detection (DELETE before SAVE, MessageDigest.isEqual constant-time, revokeAllUserSessions on reuse). Security standards formalized: hash compare, log format, error code distinction.
- **W2-D4 OAuth + Logout** — APPROVE WITH COMMENTS. ADR-007 OAuth auto-link by email (Google verified). ADR-011 fail-open Redis blacklist intentional. Firebase Admin SDK verifyIdToken bắt buộc.
- **W2 Final Audit** — Formalize ADR-008/009/010/011. WARNINGS.md created với 5 pre-prod + 8 acceptable + 6 cleanup + 7 tech-debt items.

ADRs added W2: ADR-005, ADR-006, ADR-007, ADR-008, ADR-009, ADR-010, ADR-011.

---

## [W1] Phase 3A+3B+Fix — Spring Security + JWT + FE auth scaffold — APPROVE

ADRs added W1: ADR-001 JWT strategy, ADR-002 BCrypt strength 12, ADR-003 FE persist refresh+user only, ADR-004 Error format `{error, message, timestamp, details}`. JWT validateTokenDetailed enum VALID/EXPIRED/INVALID. Axios interceptor isRefreshing flag + failedQueue. Contract: v0.2-auth → v0.2.1-auth (AUTH_TOKEN_EXPIRED code).

---

## [Contract W1] Initial Auth contract

5 endpoints register/login/oauth/refresh/logout. Token shape chuẩn. Rate limits. Refresh rotation. OAuth auto-link email (Firebase verified). Login rate limit chỉ tính fail. Logout body refreshToken.
