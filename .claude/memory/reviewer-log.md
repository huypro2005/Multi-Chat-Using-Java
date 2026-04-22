# Reviewer Log — Nhật ký review (consolidated)

> Consolidated 2026-04-22 (W7 MEMORY). W7 entries giữ full; tuần trước đã summary.
> Mới nhất ở đầu file. Chi tiết file:line bỏ; tên file giữ khi cần.

---

## Recent activity

- [W8-D2] Pin Message + Bilateral Block — APPROVED sau recheck. Scope hoàn tất: docs contract-first (ADR-023/024, API v1.6.0-w8-pin-block, SOCKET v1.11-w8, WARNINGS W8-D2), BE migrations V14/V15 + PinService/BlockService + STOMP `/app/msg.{messageId}.pin` + broadcasts AFTER_COMMIT + integration sendViaStomp/createDirect, FE pinned banner + scroll-to-message + pin/unpin actions + block/unblock actions + blocked list component + realtime handlers. Initial review có 2 issue (PIN ERROR thiếu case, header blocked state stale) đã fix và verify PASS. Regression: backend 355/355 pass, frontend build + lint pass. Non-blocking: chunk-size warning Vite giữ nguyên.
- [Phase A] W7-D5: read receipt contract v1.4.0-w7-read + SOCKET v1.9-w7. API_CONTRACT.md bump v1.1.0-w7 → v1.4.0-w7-read. Thêm: (a) Schema V12 migration `V12__add_last_read_message_id.sql` — column `conversation_members.last_read_message_id UUID REFERENCES messages(id) ON DELETE SET NULL` + composite index, (b) MemberDto extended với `lastReadMessageId: uuid | null`, (c) `unreadCount` real compute (bỏ placeholder 0): `COUNT(messages WHERE conv_id=X AND created_at > lastRead.createdAt AND type != 'SYSTEM' AND deleted_at IS NULL)`, cap LEAST(count, 99), per-caller, **SYSTEM không count** (superseded rule v1.2.0-w7-system), (d) readBy compute CLIENT-SIDE (BE không fan-out per-message readers list). SOCKET_EVENTS.md bump v1.7-w7 → v1.9-w7. Thêm: §3.13 READ_UPDATED outbound `/topic/conv.{id}` payload `{conversationId, userId, lastReadMessageId, readAt}`; §3f inbound `/app/conv.{convId}.read` payload `{messageId}` KHÔNG có clientId, idempotent forward-only (compare `createdAt`, incoming <= current → silent no-op), validation auth→member→rate-limit→UUID→exists→in-conv→forward-only, error codes `AUTH_REQUIRED / NOT_MEMBER / VALIDATION_FAILED / MSG_NOT_FOUND / MSG_NOT_IN_CONV / MSG_RATE_LIMITED (1/2s) / INTERNAL`, FE handling silent-log (no toast), retry chỉ INTERNAL 1 lần. Destination policy `.read` SILENT_DROP → STRICT_MEMBER (persist DB + broadcast). BLOCKING BE: idempotent compare createdAt (không UUID), filter SYSTEM+deleted, FK SET NULL, cross-conv application-level. BLOCKING FE: readBy client compute, optimistic unreadCount=0 self-echo, debounce 500ms trước STOMP .read.
- [W7-D4-fix] Model 4 hybrid file visibility + default avatars + ADMIN add fix — **NEEDS_FIXES** (2 BLOCKING). BE V11 migration, FileRecord.isPublic, /public endpoint anti-enum + Cache-Control 1d, SecurityConfig permitAll ORDER, FileDto publicUrl+isPublic, FileConstants DEFAULT_USER/GROUP_AVATAR_ID + skip cleanup, @PostConstruct physical-file warn, AuthService default avatar, ConversationService fallback DEFAULT_GROUP_AVATAR_ID — BE layer OK. ADMIN canAddMembers đã đúng (`this != MEMBER`). **BLOCKING FE#1**: CreateGroupDialog `POST /api/files/upload` KHÔNG có `?public=true` → upload is_public=false, nhưng ConversationDto.from publish `/public` URL → 404 anti-enum → avatar custom KHÔNG hiển thị. EditGroupInfoDialog đúng. **BLOCKING BE#2**: `validateGroupAvatar` không enforce `isPublic=true` → dù FE fix #1, bất kỳ client bypass. Recommend Option B: `setIsPublic(true) + save` trong validate (UX mượt, không break client). 3 patterns mới: hybrid visibility, seed defaults fixed UUID guard, SecurityConfig permitAll ORDER. 2 warnings non-blocking: UserAvatar + ConversationListItem vẫn dùng useProtectedObjectUrl (redundant, chạy được), markAttached set attached_at cho default record (cleanup đã skip).
- [W7-D4] SYSTEM messages (BE service + FE render + i18n + migration V10) — APPROVED. 270 tests pass (+13). 8 event types wired, ordering OWNER_TRANSFERRED trước MEMBER_LEFT (SM-06), avatar-only no-rename (SM-11), edit/delete guard trước anti-enum (SM-12/13). 5 patterns + 2 pitfalls documented.
- [Reviewer] review W7-D3 FE group UI: FAIL→PASS. Blocking: GROUP_DELETED payload drift (FE typedef vs BE event). Fix: read from cache fallback. 5 warnings logged.
- [W6-D5] Security audit 18/18 PASS. WARNINGS W6-1/2/4 resolved. Memory consolidate 689→313.

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

Verdict: NEEDS_FIXES (2 BLOCKING, tangled). BE core OK; FE Create-Group flow BROKEN + BE missing defense.

### BLOCKING #1 — FE CreateGroupDialog thiếu `?public=true` (critical regression)

`frontend/src/features/conversations/components/CreateGroupDialog.tsx`: `api.post<{ id: string }>('/api/files/upload', formData, {...})`. So với EditGroupInfoDialog đúng: `api.post('/api/files/upload?public=true', ...)`.

Hậu quả chain: upload → `files.is_public=FALSE` → createGroup accept (validate không check isPublic) → DTO publish `/public` URL → GET /public anti-enum 404 → **mọi group tạo mới với avatar custom → avatar KHÔNG hiển thị**. Test không catch vì BE test trực tiếp gọi service với `upload(..., isPublic=true)` bypass UI path.

Fix: Thêm `?public=true` vào CreateGroupDialog.

### BLOCKING #2 — BE `validateGroupAvatar` không enforce `isPublic=true`

Hiện check: exist + uploader == caller + !expired + MIME whitelist. KHÔNG check `file.isPublic()`. Dù FE fix #1, curl/mobile client bypass → same bug.

Options: (A) throw `GROUP_AVATAR_NOT_PUBLIC` 400 — cần thêm contract error code. (B, recommended) auto-flip `file.setIsPublic(true); fileRecordRepository.save(file);` trong validate — UX mượt, safe (avatar là public bản chất, uploader check qua).

Apply cả `createGroup` + `updateGroupInfo`.

### Checklist PASS (20 items trừ 2 BLOCKING)

1. V11 migration: uploader_id NULL, is_public NOT NULL DEFAULT FALSE, DO-block backfill conditional, seed 2 UUID 001/002 ON CONFLICT DO NOTHING, expires_at=9999-12-31, attached_at=NOW(), partial index `idx_files_public WHERE is_public=TRUE`. PASS.
2. FileRecord entity: uploader_id NULLABLE, isPublic boolean @Builder.Default=false + javadoc ADR-021. PASS.
3. GET /public: FileController.downloadPublic không `@AuthenticationPrincipal`, anti-enum 404, `Cache-Control: public, max-age=1d`, ETag + nosniff. PASS.
4. SecurityConfig: `/api/files/*/public` permitAll TRƯỚC `.anyRequest().authenticated()` — đúng thứ tự. PASS.
5. FileDto shape khớp contract v1.3.0-w7. PASS.
6. Upload `?public=true`: controller `@RequestParam(value="public", defaultValue="false") boolean isPublic`. Backward-compat overload. PASS.
7. **FE UserAvatar**: VẪN dùng useProtectedObjectUrl — redundant nhưng không broken. Non-blocking warning (nên refactor sang native `<img>`).
8. FE ConversationListItem: tương tự #7.
9. FE GroupInfoPanel.GroupAvatarDisplay: ĐÃ đổi native `<img>` + fallback initial letter + onError hide. PASS.
10. FE EditGroupInfoDialog: gọi `?public=true` đúng, blob preview, onError hide. PASS.
11. FE CreateGroupDialog: KHÔNG gọi `?public=true` → **BLOCKING #1**.
12. FE useProtectedObjectUrl vẫn cho private attachment: PASS.
13. User register default avatar: `avatarUrl(FileConstants.DEFAULT_USER_AVATAR_URL)`. AuthControllerTest expect exact string. PASS.
14. createGroup default: `finalAvatarFileId = avatarFile != null ? avatarFile.getId() : FileConstants.DEFAULT_GROUP_AVATAR_ID`. updateGroupInfo `isRemoveAvatar()` fallback DEFAULT_GROUP_AVATAR_ID thay NULL. PASS.
15. Cleanup job skip: `DEFAULT_AVATAR_IDS.contains(file.getId())` → continue. Double-safeguard expires_at=9999 + attached_at=NOW(). PASS.
16. @PostConstruct warn: validateDefaultAvatars() iterate 2 path qua storageService.resolveAbsolute + Files.exists. Log WARN missing, INFO OK. Try/catch toàn method. PASS.
17. MemberRole.canAddMembers(): `return this != MEMBER` → ADMIN add được. **KHÔNG có bug**. PASS.
18. Regression 282/282: trust agent report (gradlew không available workspace agent).
19. ConversationDto.avatarUrl /public suffix: `FileConstants.publicUrl(conv.getAvatarFileId())`. PASS.
20. CONVERSATION_UPDATED /public payload: updateGroupInfo `changes.put("avatarUrl", FileConstants.DEFAULT_GROUP_AVATAR_URL)` remove, `publicUrl(newAvatarId)` set. ConversationBroadcaster.onMemberAdded dùng publicUrl. PASS.

### Non-blocking warnings

- N-1: ADR-021 conflict — 2 ADR cùng số (Content XOR Attachments + hybrid visibility). Consolidation nên rename ADR mới.
- N-2: UserAvatar + ConversationListItem chưa refactor native img. Intent contract "cacheable cross-session" không full đạt.
- N-3: Physical default avatars copy tay post-deploy — thêm DEPLOYMENT.md section.
- N-4: Option B auto-flip → thêm log INFO audit.
- N-5: Contract drift API_CONTRACT.md v1.3.0-w7 — Option A cần GROUP_AVATAR_NOT_PUBLIC error code, Option B không cần.

### Patterns mới (3)

1. Hybrid file visibility — per-file `is_public` flag (ADR-021 hybrid).
2. Seed default records + fixed UUID guard (migration + constant + cleanup skip triple-safeguard).
3. SecurityConfig permitAll ORDER matters.

---

## [W7-D4] SYSTEM messages (service + render + immutability guard) — APPROVED

Verdict: APPROVED (0 BLOCKING). 270/270 tests (+13 SystemMessageTest SM-01 → SM-13). Contract v1.2.0-w7-system + SOCKET v1.7-w7 finalized.

Checklist PASS:
- BE hook đủ 8 event: GROUP_CREATED, MEMBER_ADDED per added user (skipped excluded, SM-03), MEMBER_REMOVED (insert BEFORE hard-delete), MEMBER_LEFT, OWNER_TRANSFERRED (autoTransferred true/false), ROLE_PROMOTED/DEMOTED, GROUP_RENAMED (CHỈ khi changes.containsKey("name")).
- Event order OWNER leave: OWNER_TRANSFERRED TRƯỚC MEMBER_LEFT (SM-06 createdAt ASC).
- Avatar-only PATCH NO system message (SM-11 verify count=0).
- Immutability guard: editViaStomp + deleteViaStomp check `type == SYSTEM` TRƯỚC anti-enum, throw SYSTEM_MESSAGE_NOT_EDITABLE/NOT_DELETABLE 403 (SM-12/13).
- V10 migration: sender_id DROP NOT NULL; CHECK chk_message_system_consistency; JSONB column systemMetadata qua JsonMapConverter (portable H2/PG).
- MessageDto extend systemEventType + systemMetadata (2 optional, null cho TEXT/IMAGE/FILE).
- FE SystemMessage: centered italic pill, role="status", 8 event i18n + fallback "(sự kiện hệ thống)" + "Bạn"/"bạn" actor/target.
- FE dispatcher: MessagesList branch type; MessageItem memo wrapper cũng dispatch (defense-in-depth). Inner tách tránh hooks order violation.
- FE null-safety audit sau sender: MessageDto | null: useConvSubscription.appendToCache, MessagesList.shouldShowAvatar + isOwn, ReplyPreviewBox (sender?.fullName ?? 'hệ thống'), MessageItem avatar + sender name.
- FE ACK handler: SYSTEM_MESSAGE_NOT_EDITABLE toast + clear edit marker; NOT_DELETABLE toast without revert.
- SystemMessageService reuse MessageCreatedEvent + AFTER_COMMIT — KHÔNG tạo STOMP event mới.
- @Transactional REQUIRED (default) → join caller TX → atomic.

Key decisions:
- Anti-enum exception documented cho SYSTEM — visible mọi member, distinguish không leak.
- JsonMapConverter thay @JdbcTypeCode(SqlTypes.JSON) — H2 test fail (String→Map parse error).
- content="" (empty) thay null cho SYSTEM — FE render từ metadata.
- sender=null thay "system user" UUID — tránh maintain row đặc biệt.

Patterns confirmed (5 mới):
1. Server-generated subtype service (SystemMessageService.createAndPublish).
2. Immutable subtype guard trước anti-enum.
3. JPA JsonMapConverter JSONB H2/PG portable (PITFALL+FIX).
4. Dispatcher pattern polymorphism (list+component defense).
5. SYSTEM i18n "Bạn"/"bạn" substitution + fallback unknown enum.

Pitfalls:
1. Mockito Boolean.FALSE default + Jackson bool unbox (wrapped assert).
2. Optional field TS union (audit `.sender.` usage khi DTO lỏng).

Non-blocking warnings:
- SystemMessageService reload sau save redundant DB roundtrip. Micro-opt V2.
- @Transactional class + method redundant. Clean up khi refactor.
- Flyway V10 comment rationale rõ.

Contract: API v1.2.0-w7-system + SOCKET v1.7-w7 finalized.

---

## [W6-D5] Security Audit Tuần 6 — APPROVED

Verdict: APPROVED. 18/18 PASS, 0 BLOCKING.

### Audit checklist

**Path traversal (W6-D1)**
1. LocalStorageService.store: `assertWithinBase()` sau normalize + toAbsolutePath + startsWith(basePath). Reject fileId `/`, `\`, `..`; ext `/`, `\`, `.`. `basePath toRealPath()` resolve symlinks.
2. Internal filename UUID + ext từ `MIME_TO_EXT` map, KHÔNG originalFilename.
3. sanitizeFilename: strip `[\x00-\x1F\x7F]` + `[\\/]` + truncate 255. Output CHỈ cho Content-Disposition, KHÔNG storage path.

**MIME validation (W6-D1 + W6-D4-extend)**
4. Tika magic bytes: `tika.detect(InputStream)` peek ~8KB. Không trust `file.getContentType()`. Charset strip `split(";")[0].trim()`.
5. ZIP→Office override: CHỈ trigger khi `"application/zip".equals(detectedMime)` + ext docx/xlsx/pptx. Sau override vẫn ALLOWED_MIMES check.
6. Blacklist via whitelist: ALLOWED_MIMES 14 safe types. .exe → detect `application/x-msdownload` → FileTypeNotAllowedException 415.

**Authorization (W6-D2)**
7. FileAuthService 2 rules: uploader OR conv-member qua JPQL JOIN. Không bypass.
8. Anti-enum 404: not-found/expired/cleanup-deleted/not-uploader+not-member → Optional.empty() → 404. Không 403/410.
9. JPQL COUNT only, index cover query.

**Rate limit + Attachment (W6-D1/D2)**
10. Upload rate limit: Redis INCR `rate:file-upload:{userId}` + EX 60s first. Max 20. Catch DataAccessException fail-open.
11. validateAndAttachFiles 6 rules rẻ→đắt: count → existence → ownership → expiry → unique → group type.
12. Content XOR Attachments: `hasContent || hasAttachments` → MSG_NO_CONTENT 400 (ADR-021).

**Cleanup (W6-D3)**
13. stillAttached: deletePhysical TRƯỚC → existsByIdFileId → attached: setExpired(true)+save (DB giữ) + log WARN. GET subsequent → StorageException → 404 FILE_PHYSICALLY_DELETED.
14. Batch pagination: PageRequest.of(0, 100) luôn page 0. Terminate isEmpty() || getNumberOfElements() < BATCH_SIZE.
15. @ConditionalOnProperty: test profile enabled=true + cron="-" = bean load, scheduler không fire.

**FE security (W6-D4)**
16. useProtectedObjectUrl: api.get blob + signal, URL.createObjectURL, cleanup abort + revoke. Catch CanceledError silent.
17. No raw `<img src=/api/files`: AttachmentGallery + FileCard dùng hook → blob URL. SAFE.

**Contract (W6-D4-extend)**
18. iconType 8 values: IMAGE/PDF/WORD/EXCEL/POWERPOINT/TEXT/ARCHIVE/GENERIC. Cover 14 MIME. GENERIC fallback.

### Verdict APPROVED — production-ready V1 launch

- 0 BLOCKING, 0 non-blocking warnings.
- Move WARNINGS RESOLVED: W6-1 path traversal, W6-2 MIME spoofing, W6-4 orphan cleanup.
- V2 bucket expanded: signed URLs (FE perf), Office macro scanning (POI), per-user storage quota.

Tests: BE 210/210. FE build clean.

---

## [W6-D4-extend] Expand file types — APPROVE

Verdict: APPROVE. 210/210 pass (197+13). FE build zero TS errors.

Checklist PASS:
1. ALLOWED_MIMES = 14 (4 image + 10 non-image). 2. Charset strip. 3. ZIP→Office override gated. 4. resolveIconType 8 values. 5. singleNonImage rule. 6. FileDto field order. 7. validateFiles 3-arg. 8. AttachmentDto.iconType non-optional. 9. FileCard dùng `attachment.iconType ?? 'GENERIC'`. 10. PdfCard không còn import. 11. Security F22 .exe (MZ magic) → 415. 12. MIME mismatch defense V07 EXE rename reject.

Patterns confirmed: Group A/B mixing semantics, MSG_ATTACHMENTS_TOO_MANY cho >5 ảnh, Map.ofEntries > 10 entries.

Non-blocking: MessageInput accept 14 MIME verbose (có thể trích const), MSG_ATTACHMENTS_MIXED message "Không thể gửi lẫn PDF và ảnh" chưa update Group A/B (cosmetic), clientIconEmoji trùng logic resolveIconType (acceptable V1).

Contract: API_CONTRACT.md v0.9.5-files-extended chốt.

---

## [W6-D4] FE File Upload UI + Attachment Display — APPROVE

Blocking: 0. Build clean. 5 file FE + 1 folder mới `features/files/`.

BLOCKING checklist 1-9 PASS:
1. AbortController native (deprecated CancelToken).
2. revokeObjectURL 4 điểm (cancel/remove/clear/unmount via pendingRef).
3. Content-Type undefined FormData + comment lý do.
4. `attachments: []` optimistic.
5. RetryButton `attachmentIds: []`.
6. Guard uploading trước check disabled/connected.
7. Send disable đúng (errors-only KHÔNG enable).
8. DragLeave currentTarget.contains check.
9. StompSendPayload.attachmentIds wired.

ACK error handler +7 attachment codes khớp 100% SOCKET_EVENTS.md.

validateFiles đúng business rule. MessageItem Messenger-style (no bubble image + text caption riêng). AttachmentGallery lightbox + keyboard nav + download. Thumbnail fallback `att.thumbUrl ?? att.url` fail-open ADR-020.

Patterns confirmed:
- Blob URL cleanup ref-based (pendingRef tránh stale closure).
- FormData Content-Type undefined (axios + multipart).
- AbortController native (axios v1+).

Contract: SOCKET v1.3-w5d2 unchanged. 7 attachment error codes đã có từ W6-D1 draft.

---

## [W6-D3] File Cleanup Jobs (@Scheduled expired + orphan) — APPROVE

Blocking: 0. 197/197 tests pass.

Key findings:
- @EnableScheduling thêm ChatAppApplication.
- Cron Spring 6 6-fields: `0 0 3 * * *` (3 AM daily expired), `0 0 * * * *` (đầu giờ orphan). Externalize qua ${FILE_CLEANUP_*_CRON}.
- @ConditionalOnProperty(enabled=true, matchIfMissing=true). Test profile `enabled=true` + `cron="-"` disable trigger.
- Batch pagination `PageRequest.of(0, 100)` page 0 (sau xử lý records rời predicate). Terminate isEmpty() || getNumberOfElements() < BATCH_SIZE.
- Per-record try-catch: 1 IOException không kill job. Sau exception expired-job vẫn setExpired(true) defensive.
- stillAttached: physical delete TRƯỚC → check attached → setExpired(true)+save (DB giữ) hoặc delete DB. GET /files/{id} → StorageException → 404 FILE_PHYSICALLY_DELETED.
- LocalStorageService.delete() dùng deleteIfExists() idempotent race-safe.
- deletePhysical xóa cả storagePath + thumbnailInternalPath — không leak orphan thumb.

Test coverage CJ01-06: expired no-attach delete + storage.delete, not-expired untouched, expired+attached physical delete DB kept, orphan >1h delete, orphan <1h untouched, attached file orphan-job không touch.

Patterns: @Scheduled cleanup (6-field cron, ConditionalOnProperty, batch page-0, per-record try-catch), stillAttached graceful, Multi-instance V2 Redis SETNX lock.

---

## [W6-D2] Thumbnail + File Auth + Attachments — APPROVE WITH COMMENTS

Blocking: 0. 191/191 tests pass.

### Thumbnail
- ThumbnailService Thumbnailator 200×200 quality 0.85, giữ ext gốc. Path `{base}/yyyy/MM/{uuid}_thumb.{ext}`.
- StorageService.resolveAbsolute → SecurityException khi traversal (tách IllegalArgumentException — attack signal vs args invalid).
- FileService.upload step 6 fail-open: try-catch WRAP generate → thumb fail WARN + `thumbnail_internal_path=null` → FileDto.thumbUrl=null. Upload 201.
- FileController.downloadThumb: auth qua FileAuthService + filter thumbnailInternalPath != null → anti-enum 404. Cache-Control 7d cachePrivate + ETag + nosniff.

### Authorization
- FileAuthService tách khỏi FileService. Optional return, caller quyết error code. JPQL existsByFileIdAndConvMemberUserId COUNT > 0.
- Anti-enum 404 nhất quán: not-found, non-access, expired, cleanup-deleted.
- sanitizeForHeader: strip CRLF + `"` + non-ASCII. V1 Unicode mất dấu; V2 RFC 5987.

### Wire attachments
- SendMessagePayload +attachmentIds nullable. MessageDto +attachments LUÔN non-null (empty list thay null).
- validateStompPayload XOR: hasContent || hasAttachments → MSG_NO_CONTENT. Content length check CHỈ khi hasContent.
- validateAndAttachFiles order rẻ→đắt: count → findAllById mismatch → ownership → expiry → unique → group type. Fail-fast.
- Validation chạy SAU save trong cùng @Transactional → throw rollback cả message.
- MessageMapper.toDto strip `emptyList()` khi deletedAt != null. Applied REST + ACK + broadcast.
- Edit KHÔNG process attachmentIds — contract §3c V1.
- deriveMessageType từ attachmentIds[0]: image → IMAGE, else FILE.
- N+1 risk documented javadoc + AD-19 WARNINGS.md V2 plan.

Patterns: FileAuthService (ADR-019), Fail-open thumbnail (ADR-020), Content XOR (ADR-021), Soft-delete strip attachments (ADR-022), Validation rẻ→đắt.

Warnings non-blocking (8):
1. Contract "thumb luôn JPEG" vs code giữ ext → AD-23.
2. Thumb endpoint không Content-Disposition → AD-24 UX minor.
3. Content-Type theo record.mime → nếu AD-23 fix JPEG cần update → AD-25.
4. MessageMapper silent-skip FileRecord not-found → AD-26. ON DELETE CASCADE → không xảy ra V1.
5. upload step 6 save redundant với dirty tracking → AD-27.
6. sanitizeForHeader strip non-ASCII. V1 acceptable. V2 RFC 5987.
7. GIF animated thumb chỉ frame đầu (Thumbnailator default) V1 acceptable.
8. existsByFileIdAndConvMemberUserId JPQL index OK.

---

## [W6-D1] File upload foundation — contract draft

Verdict: contract v0.9.0-files (extended W6-D4 → v0.9.5). Pre-implement audit: 4 pre-prod WARNINGS (W6-1 path traversal, W6-2 MIME spoofing, W6-3 disk quota, W6-4 orphan cleanup). 4 AD items (N+1, soft-delete strip both, thumb lazy latency, SimpleBroker frame size).

Key contract: POST /api/files/upload multipart → 201 FileDto. GET /{id} download — FileAuth uploader OR conv-member, 404 anti-enum. GET /{id}/thumb — same auth image-only. attachmentIds trong /app/conv.{id}.message nullable. 7 error codes: MSG_ATTACHMENT_NOT_FOUND/NOT_OWNED/EXPIRED/ALREADY_USED/TOO_MANY/MIXED/MSG_NO_CONTENT.

---

## [W5] Tuần 5 summary

- **W5-D1 Typing Indicator** — APPROVED sau Fix A. BLOCKING: AuthChannelInterceptor throw FORBIDDEN cho mọi `/app/conv.*` non-member → conflict silent-drop typing → reconnect loop. Fix: DestinationPolicy enum STRICT_MEMBER vs SILENT_DROP. Patterns: ephemeral event, silent drop, 3-timer FE hook.
- **W5-D2 Edit + Unified ACK (ADR-017)** — APPROVED sau fix FE BLOCKING. BLOCKING: useEditMessage optimistic ghi đè content+editedAt không revert ERROR → cache lệch DB. Fix Option A: bỏ optimistic content, chỉ mark saving. ADR-016 STOMP-send Path B, ADR-017 unified ACK (1 queue + operation discriminator).
- **W5-D3 Delete + Facebook UI** — APPROVE W/COMMENTS. ADR-018 delete unlimited window. Anti-enum 4-case merge MSG_NOT_FOUND. Edit-after-delete regression guard. ACK minimal raw Map. Patterns: minimal ACK + defensive re-ACK.
- **W5-D4 Reconnect + Reply** — APPROVE W/COMMENTS. Forward pagination `after` param. wasDisconnectedRef trigger only reconnect. Reply state per-conv scoping. Contract drift fixed (replyToMessageId payload). 145/145 BE tests.
- **W5-D5 polish** — fix 3 warnings (status tick deleted, sonner toast, log WARN duplicate). WARNINGS.md restructure.

ADRs W5: ADR-016, ADR-017, ADR-018.
Contract: API v0.6.1-messages-stomp-shift + v0.6.2-after-param. SOCKET v1.3-w5d2.

---

## [W4] Tuần 4 summary

- **W4-D1 Messages REST** — APPROVE W/COMMENTS. Schema V5 + index `(conv_id, created_at DESC)`. Cursor `limit+1` ASC. Anti-enum 404 CONV_NOT_FOUND. W4-BE-1 (sender_id NOT NULL + ON DELETE SET NULL conflict, V1 không trigger).
- **W4-D2 Contract SOCKET v1.0-draft-w4 + UI Phase A** — APPROVE. ADR-014 REST-gửi + STOMP-broadcast. ADR-015 SimpleBroker V1 → RabbitMQ V2.
- **W4-D3 WebSocket foundation** — APPROVE. SockJS + SimpleBroker, setAllowedOriginPatterns config, size limit 64KB. AuthChannelInterceptor JWT CONNECT + member SUBSCRIBE. FE singleton + manual reconnect.
- **W4-D4 Realtime broadcast wire** — APPROVE. @TransactionalEventListener(AFTER_COMMIT) + try-catch. FE useConvSubscription re-sub CONNECTED. Dedupe cross-pages bằng id.
- **Post-W4 Path B STOMP-send** — APPROVE W/COMMENTS. ADR-016. Dedup SET NX EX 60s atomic. ACK afterCommit. FE timer 3 branches.

ADRs W4: ADR-014, ADR-015, ADR-016. Patterns: Transactional broadcast, STOMP sub hook, sockjs global shim.

---

## [W3] Tuần 3 summary

- **W3-D1 V3 schema + entities + FE layout** — APPROVE W/COMMENTS. ADR-012 UPPERCASE enum. W3-BE-1 UUID @GeneratedValue + insertable=false conflict → migrate @PrePersist Option B (resolved W3-D2).
- **W3-D2 BE 4 endpoints + FE scaffold** — REQUEST CHANGES → APPROVE. 2 BLOCKING FE: `.code` vs `.error` field; ConversationDto vs SummaryDto shape. Fix applied. ADR-013 ONE_ON_ONE race no-lock V1 (P<0.01%).
- **W3-D3 List UI + create dialog** — APPROVE W/COMMENTS. 409 idempotency navigate existing. Rate limit Redis INCR `rate:conv_create` 10/min. Contract drift fix 30/giờ → 10/min.
- **W3-D4 ConversationDetailPage + GET /users/{id} + last_seen_at** — APPROVE W/COMMENTS. UserSearchDto reuse (không expose email). 404 merge anti-enum. V4 last_seen_at KHÔNG expose V1 (AD-9).
- **W3-D5 Consolidation WARNINGS.md** — restructure 4 sections.

ADRs W3: ADR-012, ADR-013.

---

## [W2] Tuần 2 summary

- **W2-D1 W-BE-3 + W-FE-2 RESOLVED** — ADR-010 AuthMethod enum. tokenStorage.ts phá circular dep.
- **W2-D2 FE init + BE register/login** — APPROVE W/COMMENTS. ADR-005 Rate limit INCR + TTL first. Login tách checkLoginRateLimit khỏi incrementFailCounter. SHA-256 refresh token hash. User enumeration protection.
- **W2-D3 FE Login+Register wire** — APPROVE W/COMMENTS. W-FE-1 RESOLVED (username regex). Payload strip sensitive (explicit object).
- **W2-D3.5 POST /refresh** — APPROVE W/COMMENTS. ADR-006 rotation + reuse detection. Security standards formalized.
- **W2-D4 OAuth + Logout** — APPROVE W/COMMENTS. ADR-007 OAuth auto-link email. ADR-011 fail-open Redis blacklist.
- **W2 Final Audit** — Formalize ADR-008/009/010/011. WARNINGS.md 5 pre-prod + 8 acceptable + 6 cleanup + 7 tech-debt.

ADRs W2: ADR-005→ADR-011.

---

## [W1] Phase 3A+3B+Fix — Spring Security + JWT + FE auth scaffold — APPROVE

ADRs W1: ADR-001 JWT, ADR-002 BCrypt 12, ADR-003 FE persist refresh+user, ADR-004 Error format. JWT validateTokenDetailed enum VALID/EXPIRED/INVALID. Axios interceptor isRefreshing + failedQueue. Contract: v0.2-auth → v0.2.1-auth.

---

## [Contract W1] Initial Auth contract

5 endpoints register/login/oauth/refresh/logout. Token shape. Rate limits. Refresh rotation. OAuth auto-link email (Firebase verified). Login rate limit chỉ tính fail. Logout body refreshToken.
