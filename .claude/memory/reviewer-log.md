# Reviewer Log — Nhật ký review (consolidated)

> Consolidated 2026-04-20. Mỗi entry chỉ giữ verdict + blocking + key decisions + patterns.
> Mới nhất ở đầu file. Chi tiết file:line được bỏ, tên file giữ khi cần.

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
- JdbcTemplate override timestamp pattern (vì `@PrePersist` set createdAt=now) — CAST(? AS UUID) cho H2 + java.sql.Timestamp.from(Instant) cho TIMESTAMPTZ.
- @MockBean StorageService + StringRedisTemplate (do FileService inject Redis cần mock để context load).

Minor (non-blocking, đã document):
- `MessageAttachmentRepository.existsByIdFileId(UUID)` đã exists trước W6-D3 (W6-D2 wiring) — reuse OK, không tạo method mới redundant.
- Multi-instance race risk khi scale V2 (2 BE instance cùng chạy `@Scheduled` → potential double-delete + StaleObjectStateException) — đã document trong `backend-knowledge.md` mục "FileCleanupJob V2" với Redis SETNX pattern. NOTE: chưa thêm vào `WARNINGS.md` V2 Enhancement bucket — recommend orchestrator gọi BE add 1 dòng.

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
- F11-F15 tests: upload JPEG → thumbnail_internal_path DB non-null; PDF → null; GET /thumb returns 200 + image/jpeg header; PDF GET /thumb → 404; no-JWT → 401.

Key findings (VIỆC 2 — Authorization):
- `FileAuthService` tách riêng khỏi `FileService` — Optional return thay vì throw, caller quyết định error code. JPQL `existsByFileIdAndConvMemberUserId` dùng COUNT > 0 (không load entity thừa). JOIN path `message_attachments → Message → ConversationMember`.
- Anti-enum 404 nhất quán: not-found, non-access, expired (expires_at < now), cleanup-deleted (expired=true) — ALL return Optional.empty() → 404 NOT_FOUND (không 403, không 410 Gone).
- Controller `sanitizeForHeader`: strip CRLF + `"` + non-ASCII cho Content-Disposition filename (chống header injection). Unicode filename mất dấu — V1 trade-off đơn giản, V2 cân nhắc RFC 5987 `filename*=UTF-8''encoded`.
- F16-F18 tests: conv-member download → 200; non-member non-uploader → 404 NOT_FOUND; expired file → 404 (không 410).

Key findings (VIỆC 3 — Wire attachments):
- `SendMessagePayload` thêm `attachmentIds: List<UUID>` (nullable/empty OK). `MessageDto` thêm `attachments: List<FileDto>` (LUÔN non-null, empty list thay null).
- `validateStompPayload` XOR check: `hasContent || hasAttachments` — cả 2 rỗng → `MSG_NO_CONTENT`. Content length check CHỈ khi hasContent (không apply max với attachment-only).
- `validateAndAttachFiles` flow order (RẺ → ĐẮT): (1) Count > MAX_IMAGE_ATTACHMENTS (pre-check pre-DB), (2) findAllById → size mismatch → `MSG_ATTACHMENT_NOT_FOUND`, (3) per-file ownership `uploaderId == userId` → `MSG_ATTACHMENT_NOT_OWNED` (403), (4) per-file expiry → `MSG_ATTACHMENT_EXPIRED` (410 GONE), (5) unique `existsByIdFileId` → `MSG_ATTACHMENT_ALREADY_USED` (409), (6) group type check (all images OR 1 PDF) → `MSG_ATTACHMENTS_MIXED`. Sau pass: INSERT message_attachments rows + `file.markAttached()`.
- `validateAndAttachFiles` chạy SAU `messageRepository.save(message)` trong cùng `@Transactional` → throw sẽ rollback CẢ message save (confirmed: service method `@Transactional` at line 160). Atomic guarantee: nếu attachment fail → message không được save.
- `MessageMapper.toDto` strip attachments khi deletedAt != null: `isDeleted ? Collections.emptyList() : loadAttachmentDtos(message)`. Applied TRUNG TÂM cho REST + ACK + broadcast. Privacy consistent với content strip (W5-D3).
- Edit (`editViaStomp`) KHÔNG process attachmentIds — contract §3c đã ghi "EDIT constraint V1: KHÔNG cho sửa attachments". Confirmed bằng đọc source — `editViaStomp` chỉ touch content + editedAt, không load attachments.
- `deriveMessageType` từ `attachmentIds.get(0)`: image → IMAGE, else FILE (PDF). TEXT khi attachments null/empty. Validation sau catch mixed case.
- N+1 risk trong `MessageMapper.loadAttachmentDtos` (JOIN + N findById per file) → documented trên class javadoc (lines 38-42) + AD-19 trong WARNINGS.md với V2 plan `@EntityGraph`.

Regression:
- 191/191 tests pass (confirmed via mvn test output: AuthControllerTest 33, ChatAppApplicationTests 1, AuthChannelInterceptorTest 10, ConversationControllerTest 20, FileControllerTest 18, FileValidationServiceTest 10, LocalStorageServiceTest 7, MessageBroadcasterTest 2, MessageControllerTest 21, MessageServiceStompTest 21, JwtTokenProviderTest 10, SecurityConfigTest 6, ChatDeleteMessageHandlerTest 10, ChatEditMessageHandlerTest 12, ChatTypingHandlerTest 4, WebSocketIntegrationTest 6).
- `MessageDto.attachments` field mới — FE BREAKING cho mọi path consume MessageDto (list, send ACK, edit ACK, delete ACK minimal không ảnh hưởng, broadcast). FE sẽ break tạm cho đến W6-D4 khi FE component render attachments.

Contract compliance:
- API §Files Management POST /upload, GET /{id}, GET /{id}/thumb khớp.
- SOCKET §3.1 SEND payload `attachmentIds` khớp.
- Error codes tuân theo mapping contract: `MSG_NO_CONTENT` 400, `MSG_ATTACHMENTS_TOO_MANY` 400, `MSG_ATTACHMENTS_MIXED` 400, `MSG_ATTACHMENT_NOT_FOUND` 404, `MSG_ATTACHMENT_EXPIRED` 410, `MSG_ATTACHMENT_NOT_OWNED` 403, `MSG_ATTACHMENT_ALREADY_USED` 409.
- **Contract drift (minor, documented AD-23)**: §GET /thumb dòng 947 nói "Content-Type: image/jpeg (luôn JPEG)" — code giữ extension gốc (png/webp/gif/jpg) theo `record.getMime()`. ThumbnailService javadoc ghi rõ lý do (alpha preserve, avoid re-encode loss). Fix đề xuất: update contract thay vì đổi code.

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

Kết luận: APPROVE WITH COMMENTS. 0 BLOCKING. 8 non-blocking warnings (đa số là contract sync + V2 items). Core logic + auth + validation + mapper strip đều đúng pattern. Tests coverage tốt (F01-F18 cover happy + edge). ADR-019/020/021/022 thêm vào knowledge.

---

## [W5-D4] Reconnect catch-up + Reply UI — APPROVE WITH COMMENTS

Blocking: 0. Tests 145/145 BE pass, FE tsc + eslint 0 errors.

Key findings (BE):
- `MessageRepository.findByConversation_IdAndCreatedAtAfterOrderByCreatedAtAsc` — NO `deletedAt` filter (đúng spec: catch-up phải thấy placeholder của deleted message). Query derived naming đúng Spring Data JPA.
- `MessageController.getMessages` thêm `after` param; cursor + after mutex check 400 VALIDATION_FAILED (trước khi vào service).
- `MessageService.getMessages(convId, userId, cursor, after, limit)` — nhánh `after` riêng, dùng ASC direct không reverse, nextCursor = item mới nhất (last).
- `MessageMapper.toReplyPreview(source)` public method — strip content=null + set deletedAt khi source soft-deleted. `toDto` delegate vào method này thay vì inline logic → DRY + nhất quán.
- `ReplyPreviewDto` thêm field `deletedAt` (ISO8601 string | null). `contentPreview` spec rõ "null khi source deleted".
- `sendViaStomp` Step 2b validate `replyToMessageId`: `existsByIdAndConversation_Id` check cross-conv (throw VALIDATION_FAILED với field+error details) + `existsById` fallback để phân biệt "cross-conv" vs "not-exist" (UX message khác nhau). Source soft-deleted vẫn OK (no filter).
- `SendMessagePayload` thêm field `replyToMessageId` nullable UUID. `Message.setReplyToMessage(repository.getReferenceById(...))` — dùng proxy reference, không full load → 1 query thay vì 2.
- T14-T21 mới (8 test case): broadcast destination/envelope, broadcast NOT called khi validation fail, broker throws → REST 201, after-param ASC + include deleted, cursor+after mutex 400, reply-to-deleted, cross-conv reply, non-existent reply.

Key findings (FE):
- `catchUpMissedMessages`: Set<id> O(1) dedup, `new Date(...).getTime()` lấy newest ts từ cache, `after=iso` param. Fail → `invalidateQueries` fallback refetch toàn bộ. Append vào `pages[0]` (newest window) đúng convention infinite cache.
- `wasDisconnectedRef`: `useRef(false)`, set true khi DISCONNECTED/ERROR, trigger catch-up chỉ khi CONNECTED + ref=true → reset false. Lần đầu connect KHÔNG catch-up (đúng, vì React Query đã fetch initial data).
- Reply state clear khi đổi conv: pattern đặc biệt — `replyState` lưu kèm `{msg, convId}`, derive `replyingTo = replyState.convId === id ? replyState.msg : null`. Không cần useEffect reset. Memory trade-off: stale entry đến khi set reply mới (1 obj, non-blocking).
- `ReplyPreviewBox`: Escape key handler cleanup đúng, `onCancel` + `✕` button + deleted state render "Tin nhắn đã bị xóa".
- `ReplyQuote`: 2 state render (normal content / deleted placeholder) dựa vào `!!replyTo.deletedAt`.
- `MessageActions` reply button `disabled={!!message.deletedAt || !!message.clientTempId}` — không reply tin đã xóa hoặc optimistic. `MessageItem.handleReply` guard lần 2 (defense-in-depth).
- `useSendMessage(content, replyToMessageId?)` truyền `replyToMessageId ?? null` vào STOMP payload. Optimistic vẫn set `replyToMessage: null` — ACK server sẽ patch full preview.
- `onSent()` callback clear reply state sau khi publish thành công. `MessageInput` dep array đúng (`replyToMessageId, onSent`).
- `MessagesList` thêm `didInitialScrollRef` reset khi đổi conversationId + initial auto-scroll bottom. Flatten pages đảo ngược: `[...pages].reverse().flatMap(...)` — vì cache giữ pages[0]=newest, fetchNextPage append pages cũ hơn phía sau → phải đảo để render ASC end-to-end.
- `publishConversationMessage` (new export) trong `stompClient.ts` + type `StompSendPayload` với `replyToMessageId?: string | null`.
- `MessagesList` luôn reverse khi flatten — cần verify `[...pages].reverse()` chỉ tạo shallow copy → OK, không mutate cache.

Contract:
- API v0.6.2-messages-after-param: GET messages `after` param documented, `ReplyPreviewDto.deletedAt` documented, mutex `cursor`/`after` 400 documented. OK khớp implementation.
- **Contract drift detected + fixed**: SOCKET_EVENTS.md §3b.1 ban đầu nói "Reply sẽ add ở Tuần 5" nhưng BE + FE đã implement `replyToMessageId` trong payload STOMP. Reviewer patch §3b.1 payload shape + thêm validation rule cho `replyToMessageId` (cùng conv, cho phép quoting deleted). Không có drift khác.

Warnings (non-blocking):
1. `catchUpMissedMessages` limit=100 fixed — nếu user offline >100 msg sẽ miss phần còn lại. `hasMore=true` trong response KHÔNG trigger fetch page tiếp (hiện chỉ xử lý 1 batch). V1 acceptable (<1000 concurrent, traffic thấp); V2 loop với nextCursor forward cho đến khi hasMore=false. Log vào WARNINGS.md AD-17.
2. `existsById` fallback trong BE Step 2b — tốn 1 query thêm chỉ để cho UX message khác nhau giữa "cross-conv" vs "not-exist". Có thể merge thành 1 error code chung (tương tự anti-enum) để cắt 1 DB call — tuy nhiên validation drift chứ không security drift nên non-blocking. Xem xét nếu hot path.
3. `MessageService.sendViaStomp` Step 2b chạy TRƯỚC rate limit check → attacker spam request với replyToMessageId invalid sẽ không bị throttle bởi edit dedup. V1 acceptable, low risk.
4. `replyState` stale khi user đổi conv mà không trigger lại setReplyState → obj message thuê trong state đến khi gắn reply mới. Memory 1 obj, non-blocking.
5. `MessagesList.reverse()` mỗi render chỉ là shallow copy (useMemo dep `[data]`) — rerun chỉ khi data ref đổi. OK. Gợi ý comment rõ invariant "pages[0] = newest" tại nơi reverse để future-self không confuse.

Patterns confirmed:
- **Forward pagination pattern** (W5-D4 new): cursor (backward) + after (forward) mutex params, ASC direct trong forward nhánh, nextCursor = last item (mới nhất). Include deleted messages cho catch-up (placeholder state). Rule: "nếu 2 cursor modes → 400 VALIDATION_FAILED, không được ngầm override".
- **Reconnect-only catch-up** (W5-D4 new): `useRef(false)` flag, set true khi state rời CONNECTED, chỉ trigger catch-up khi state trở lại CONNECTED + flag=true. Lần đầu connect SKIP (React Query đã initial fetch). Reset flag sau khi catch-up.
- **Reply state per-conversation scoping** (W5-D4 new): state lưu kèm `{value, convId}` + derive visible value qua so sánh với URL param thay vì reset via useEffect(..., [id]). Pattern này scale cho draft/edit state lifecycle khi navigating giữa conversations.

Kết luận: APPROVE WITH COMMENTS. Contract đã patch để khớp implementation. 5 warning non-blocking có thể track V2.

---

## [W5-D3] Delete Message + Facebook-style UI — APPROVE WITH COMMENTS

Blocking: 0.
Key findings:
- V6 migration `deleted_by` UUID NULL (SET NULL). `MessageMapper` strip `content=null` khi `deletedAt != null` áp dụng TRUNG TÂM (REST + ACK + broadcast).
- `deleteViaStomp` flow 8 bước: validate → rate limit → SETNX dedup `msg:delete-dedup:{userId}:{clientDeleteId}` → load + anti-enum → mark deleted → save → update dedup → ACK+broadcast AFTER_COMMIT.
- ACK shape minimal raw Map `{operation:"DELETE", clientId, message:{id, conversationId, deletedAt, deletedBy}}` — KHÁC EDIT (full MessageDto) do §3d.3 yêu cầu không leak content/sender/type.
- Anti-enum: 4 case (null / wrong-conv / not-owner / already-deleted) merge `MSG_NOT_FOUND`. Edit-after-delete check guard regression.
- `deleteTimerRegistry` FE singleton Option A (không optimistic `deletedAt`, chỉ `deleteStatus='deleting'` + opacity-50). ACK patch từ server, ERROR clear marker → cache không lệch DB.
- `AuthChannelInterceptor.resolveSendPolicy` thêm `.delete → STRICT_MEMBER`.

Patterns confirmed:
- **Minimal ACK raw Map** cho operation không cần full DTO (DELETE). Trade-off type-safety đổi lấy shape chính xác spec.
- **Defensive re-ACK check** cho dedup: check `deletedAt != null` trước khi re-send ACK (guard false ACK khi dedup value là realId nhưng DB state khác).

Warnings (non-blocking):
- Status tick `✓` vẫn render cạnh DeletedMessagePlaceholder (UX minor).
- `useDeleteMessage` timeout path không toast (V1 acceptable, V2 toast singleton).
- `handleDuplicateDeleteFrame` silent drop không log WARN khi check fail.

Contract: SOCKET §3.3 + §3d + §3e + §7.1 khớp. API unchanged. Tests 140/140 BE pass.

ADR: ADR-018 (Delete không giới hạn thời gian, khác Edit 5 phút) — document inline contract, không formalize trong knowledge.

---

## [W5-D2] Edit Message STOMP + Unified ACK — APPROVED (sau fix FE BLOCKING)

Blocking (initial): FE `useEditMessage` optimistic ghi đè `content + editedAt` không revert khi ERROR → cache lệch DB (MSG_NOT_FOUND, EDIT_WINDOW_EXPIRED, NO_CHANGE, TIMEOUT). Contract §3c.6 nói "KHÔNG update UI content ngay (chờ ACK)".

Fix applied (Option A): không optimistic content/editedAt, chỉ clear failureCode. ACK patch từ server. ERROR chỉ set failureCode. MSG_NO_CHANGE silent revert. Tab-awareness qua `editTimerRegistry.get(clientId)`.

Key decisions:
- ADR-016 STOMP-send Path B confirmed (đã apply W4 post + W5-D2).
- ADR-017 Unified ACK: 1 queue `/user/queue/acks` + `/user/queue/errors` cho tất cả operation, discriminator `operation` field. `clientId` generic thay `tempId`/`clientEditId`. BE + FE deploy đồng bộ (breaking).
- Dedup `msg:edit-dedup:{userId}:{clientEditId}` SET NX EX 60s TRƯỚC save.
- Edit window 300s server-side, FE disable sớm 290s (clock skew buffer).
- Anti-enum: null / wrong-conv / not-owner / soft-deleted merge `MSG_NOT_FOUND`.
- `MessageMapper` reuse REST + ACK + broadcast.

Patterns confirmed:
- **Anti-enumeration merge NOT_FOUND** — formalized review standard.
- **Optimistic edit rollback** Option A (chờ ACK patch, không optimistic) ưu tiên Option B (lưu originalContent). Ghi vào knowledge.

Contract: SOCKET v1.3-w5d2 implemented. API v0.6.1-messages-stomp-shift (REST POST deprecated for FE, giữ cho batch/bot/test).

Tests: BE 130/130 pass. FE tsc+eslint clean.

---

## [W5-D1] Typing Indicator — APPROVED (sau Fix A applied)

Blocking (initial): `AuthChannelInterceptor.handleSend` throw FORBIDDEN cho mọi `/app/conv.*` non-member, conflict với spec silent-drop cho typing → non-member typing → ERROR frame → FE reconnect loop.

Fix A applied: `DestinationPolicy` enum (`STRICT_MEMBER`, `SILENT_DROP`). `resolveSendPolicy(destination)` package-private + 4 unit tests cover 4 branch. `.message / .edit / .delete → STRICT_MEMBER`, `.typing / .read → SILENT_DROP`, unknown → STRICT_MEMBER (safe default). FE `stopTyping` gọi TRƯỚC `sendMessage` (race fix).

Key decisions:
- Ephemeral event (typing/presence/read receipt): NO DB, NO entity, NO event publisher. Handler broadcast trực tiếp. Rate limit per-user-per-conv (`rate:typing:{userId}:{convId}` 1/2s INCR+EX), fail-open Redis down.
- Silent drop non-critical: log DEBUG/WARN, không throw ERROR. Tránh noise `onStompError` + reconnect loop.
- Typing payload `{userId, username, conversationId}` — KHÔNG có `fullName` (FE lookup từ cache nếu cần).
- FE 3 timers: debounce 2s (START), autoStop 3s (tự STOP), autoRemove 5s safety Map<userId, timer>. Skip self. Clear khi DISCONNECTED/ERROR.

Patterns confirmed:
- **Ephemeral event pattern** (BE): handler fire-and-forget, rate limit Redis fail-open, shape `{userId, username, conversationId}`.
- **Silent drop pattern** (BE): non-critical destination không throw ERROR → bypass interceptor check bằng destination policy split.
- **Destination-aware auth policy**: interceptor không uniform, có resolver `destination → policy`. Safe default STRICT_MEMBER khi unknown.
- **Typing indicator hook pattern** (FE): 3 timers độc lập, Map per-user safety net, cleanup all refs trong useEffect return.

Contract: SOCKET §3.4 unchanged, §7.1 Destination Policy Table thêm mới. Tests 118/118 BE pass.

---

## [Post-W4] Path B STOMP-send full wire — APPROVE WITH COMMENTS

Blocking: 0.

Key decisions:
- ADR-016 Path B applied: REST POST thành deprecated cho FE hot path. `/app/conv.{id}.message` payload `{tempId, content, type}`, ACK `/user/queue/acks`, ERROR `/user/queue/errors`.
- Dedup `msg:dedup:{userId}:{tempId}` SET NX EX 60s atomic (dùng `Duration` trong `setIfAbsent`, không tách SETNX+EXPIRE).
- ACK qua `TransactionSynchronizationManager.registerSynchronization(afterCommit())`.
- `@MessageExceptionHandler` echo tempId qua `AppException.details.tempId` (không ThreadLocal).
- FE `timerRegistry` module-level singleton. Timer clear 3 branch (ACK, ERROR, timeout 10s). Retry = tempId MỚI mỗi lần.
- FE `useAckErrorSubscription` mount 1 lần ở App root (GlobalSubscriptions khi authenticated).
- Re-subscribe via `onConnectionStateChange` → CONNECTED → unsubscribe cũ + subscribe mới.
- Auth interceptor thêm SEND case member check khi `/app/conv.{id}.*` + STRICT_MEMBER.

Warnings non-blocking:
- Dedup value update không atomic với save (Redis fail giữa SET PENDING và SET realId → AD-14).
- TTL reset gap <100ms khi set value thứ 2 (AD-15).
- setQueryData trên queryKey đã cancel (React Query edge case, mitigated bằng staleTime).
- MessageItem RetryButton call useSendMessage mỗi row (V1 list <100 OK).

Patterns confirmed:
- **Message exception handler echo tempId qua AppException.details** — pattern đẹp hơn ThreadLocal.
- **timerRegistry {tempId → {timerId, convId}}** — fast path lookup convId trong ERROR handler.
- **markFailedByTempId fast path + slow path** scan all queries — robust cho edge case timer expired trước ERROR.

Contract: SOCKET v1.1-w4 implemented fully.

---

## [W4-D4] Realtime broadcast wire — APPROVE

Blocking: 0.

Key decisions:
- `@TransactionalEventListener(phase=AFTER_COMMIT)` cho `MessageBroadcaster.onMessageCreated` — không race broadcast trước rollback.
- `MessageMapper` tách @Component singleton, stateless — reuse REST response + broadcast payload → shape IDENTICAL (Rule Vàng SOCKET_EVENTS.md).
- `MessageCreatedEvent` record immutable, dùng Spring `ApplicationEventPublisher`.
- Broadcaster try-catch toàn bộ (broker down không propagate → REST 201 vẫn OK).
- FE `useConvSubscription(convId)` dep `[convId, queryClient]`, inner `subscribe()` check `client?.connected`, re-sub khi CONNECTED, cleanup DISCONNECTED/ERROR.
- FE dedupe cross all pages bằng `some(m => m.id === newMsg.id)` (sender nhận lại broadcast).
- sockjs-client global shim belt+suspenders: runtime `window.global = window` + vite define build-time.

Patterns confirmed:
- **Transactional broadcast pattern** (BE): Mapper + Event + `@TransactionalEventListener(AFTER_COMMIT)` + try-catch + mapper reuse giữa REST và broadcast.
- **STOMP subscription hook pattern** (FE): ref lưu cleanup, re-subscribe on CONNECTED, cleanup on DISCONNECTED/ERROR + effect teardown.

Tests: T14 destination+envelope, T15 never() when validation fail, T16 broker throws REST 201 OK + 2 unit isolated.

Contract: SOCKET v1.0-draft-w4 → v1.0-w4 (bỏ suffix draft) suggested.

---

## [W4-D3] WebSocket foundation — APPROVE

Blocking: 0. Warnings 4.

Key decisions:
- `WebSocketConfig`: SockJS endpoint `/ws`, SimpleBroker `/topic` `/queue`, `setAllowedOriginPatterns` config-driven (không `"*"`), `setMessageSizeLimit` 64KB.
- `ContextRefreshedEvent` wire `StompErrorHandler` (không `@PostConstruct`) — tránh circular dep bean `subProtocolWebSocketHandler`.
- `AuthChannelInterceptor`: CONNECT verify JWT qua `validateTokenDetailed` (reuse REST), SUBSCRIBE check member cho `/topic/conv.{uuid}`, UUID parse fail → FORBIDDEN. EXPIRED → `AUTH_TOKEN_EXPIRED`, invalid/missing → `AUTH_REQUIRED`.
- `StompErrorHandler extends StompSubProtocolErrorHandler` — unwrap cause chain, set ERROR header `message` = error code (không leak stack trace).
- FE `stompClient.ts` module-level singleton `_client`. Manual reconnect (reconnectDelay:0), MAX_RECONNECT=10, exponential backoff 1s→30s. AUTH_TOKEN_EXPIRED → dynamic import `authService.refresh()` → reconnect. Dynamic import phá circular dep với tokenStorage.
- FE lifecycle `isAuthenticated` + `prevAuthRef` pattern.
- Debug log DEV-only, error/warn giữ production.

Warnings:
- SimpleBroker chưa setHeartbeatValue(10000,10000) → zombie detection không enforce 20s rule. Pre-production, V1 acceptable vì `@stomp/stompjs` negotiate qua CONNECTED frame.
- `/ws` endpoint register 2 lần (SockJS + native) — không collision nhưng misleading, gợi ý 1 với `.withSockJS()`.
- `onWebSocketClose` potential double-schedule reconnect khi ERROR trước rồi close (guard bằng `_state !== 'DISCONNECTED' && _state !== 'ERROR'`).
- `connectStomp` race khi deactivate cũ → state flicker CONNECTING→DISCONNECTED→CONNECTED (listeners thấy flicker 1 lần).

Patterns confirmed:
- **ContextRefreshedEvent wire deep-nested bean** — solution đúng cho Spring 6 + Spring Messaging circular dep.
- **Module-level singleton TypeScript + manual reconnect** (FE).
- **Raw WebSocket test pattern** cho ERROR frame header inspection (DefaultStompSession ẩn header `message`).

Tests: 6 WebSocketIntegrationTest pass.

---

## [W4-D2] Contract SOCKET_EVENTS.md v1.0-draft-w4 + Messages UI Phase A

### Contract draft (SOCKET v1.0-draft-w4)

Key decisions:
- ADR-014: REST-gửi + STOMP-broadcast (publish-only), không tempId inbound. Trade-off REST +30-50ms vs unified transport, V1 acceptable.
- ADR-015: SimpleBroker V1 → RabbitMQ V2 trigger khi scale >1 BE instance hoặc cần persistent queue.
- Destination `/topic/conv.{convId}` MESSAGE_CREATED envelope `{type, payload}`. Payload = MessageDto IDENTICAL REST response (BẮT BUỘC reuse MessageMapper).
- BE broadcast PHẢI ở `@TransactionalEventListener(AFTER_COMMIT)`.
- FE dedupe bắt buộc bằng message id.
- Member authorization check ở SUBSCRIBE interceptor (SimpleBroker không có ACL).

5 điểm dễ sai BE: (1) broadcast trước commit, (2) `"*"` hardcode origin, (3) quên size limit, (4) member check chỉ REST, (5) hardcode validate thay vì reuse JWT provider.
5 điểm dễ sai FE: (1) quên dedupe message id, (2) quên unsubscribe cleanup, (3) thiếu `client.connected` dep, (4) nhầm `AUTH_TOKEN_EXPIRED` vs `AUTH_REQUIRED`, (5) không REST catch-up sau reconnect.

### Messages UI Phase A — APPROVE

Blocking: 0. Warnings 6 (non-blocking).

Key decisions:
- Optimistic tempId prefix `temp-${Date.now()}-${random}` (không UUID, tránh conflict). onMutate snapshot, onError rollback, onSuccess replace tempId.
- Sender lấy từ authStore (không hardcode "Bạn").
- Auto-scroll threshold 80px, không ép scroll khi user đang scroll lên (load older pages).
- Infinite scroll `observer.disconnect()` cleanup, preserve scroll position bằng `prevScrollHeight` delta.
- MessageItem `memo(function ...)`, props primitives.
- MessageInput: Enter send, Shift+Enter newline, MAX_CHARS=5000 guard, auto-resize textarea cap 5*24px.
- shouldShowAvatar: index===0 || sender khác || gap >60s.

Warnings: (1) sender-triggered scroll không force khi isAtBottom=false (UX), (2) `messageKeys` ở wrong feature folder, (3) không toast khi sendMessage fail (defer W5), (4) infinite query không `maxPages`, (5) `isOverLimit` counter vs `trimmed.length` inconsistent, (6) `fetchNextPage()` Promise không catch.

---

## [W4-D1] Messages REST endpoints + FE hooks scaffold — APPROVE WITH COMMENTS

Blocking: 0. Warnings 4.

Key decisions:
- Schema V5: index `(conversation_id, created_at DESC)` match query pattern. Defer FK `last_read_message_id → messages` hợp lệ.
- `sender_id NOT NULL + ON DELETE SET NULL` conflict — V1 không có DELETE user endpoint nên KHÔNG trigger. Documented W4-BE-1 pre-production.
- Cursor pagination: `limit+1` query → `subList` + reverse → ASC cho FE, `nextCursor` = createdAt cũ nhất.
- Anti-enumeration 404 CONV_NOT_FOUND cho non-member + non-existent.
- Reply validation `existsByIdAndConversation_Id` (không filter `deletedAt IS NULL` — AD-12 defer W6).
- Rate limit 30/min/user INCR + set TTL on first, fail-open Redis down.
- FE `tempId = "temp-${...}"` prefix, onMutate cancel queries, onSettled invalidate chỉ `['conversations']` (không messages — tránh mất tempId).

Warnings:
- AD-11 `lastMessageAt` race convergent (touchLastMessage chỉ set khi messageTime.isAfter).
- AD-13 N+1 LAZY `sender` + `replyToMessage.sender` (V1 acceptable, V2 JOIN FETCH).
- AD-12 reply soft-deleted chưa block (defer W6 Edit/Delete policy chốt).
- `toMessageDto` reload sau save 1 query thừa (LAZY proxy fire).

Contract: v0.5.2 → v0.6.0-messages-rest.

---

## [W3-D5] Consolidation WARNINGS.md

Housekeeping, không review code.

- Restructure format: 🔴 Pre-production / 🟡 Documented acceptable / 🔵 Cleanup / ✅ Resolved.
- Bảng có Effort (XS/S/M/L) + "Fix khi nào".
- Resolved W3: W3-BE-1 (@PrePersist UUID), W3-BE-3 (pgcrypto extension), W3-BE-6 (rate limit POST /conversations), TD-8 (MethodArgumentTypeMismatch handler), W-C-4 (ProtectedRoute wire).
- Memory file sizes: backend-knowledge 215, frontend-knowledge 294 (watch W4), reviewer-knowledge 248. All under limit.

---

## [W3-D4] ConversationDetailPage + GET /api/users/{id} + last_seen_at — APPROVE WITH COMMENTS

Blocking: 0.

Key decisions:
- FE `useConversation(id ?? '')` + `enabled: !!id` pattern cho routing. React Query key đổi = auto refetch.
- Error 404 vs generic tách branch. `MessageInput disabled + onSend?` optional ready cho W4.
- BE `GET /api/users/{id}` dùng `UserSearchDto` (không expose email/status/lastSeenAt). 404 merge not-exist + inactive (anti-enumeration).
- V4 migration `last_seen_at TIMESTAMPTZ NULL` + index DESC NULLS LAST.
- JwtAuthFilter update last_seen debounce 30s, try/catch fail-open.

Warnings logged to WARNINGS.md:
- AD-9 `last_seen_at` không expose V1 (privacy policy chưa chốt).
- AD-10 JwtAuthFilter `userRepository.save(user)` full entity → lost-update window + auto-commit tx.
- TD-8 MethodArgumentTypeMismatchException chưa map 400.
- TD-9 Error state không có Retry button (React Query retry 3 lần mitigated).
- TD-10 `getUserById` unused `currentUser` param (giữ cho V2 block-list).

Contract: v0.5.1 → v0.5.2-conversations.

---

## [W3-D3] Conversation list UI + create dialog + BE rate limit — APPROVE WITH COMMENTS

Blocking: 0. Warnings 3+.

Key decisions:
- 409 idempotency: `createConversation` result `{conversation | existingConversationId}` → FE navigate existing, không toast error.
- Dialog Esc handler `addEventListener` + cleanup. Backdrop click close. `autoFocus` input.
- `React.memo(ConversationListItem)` + props primitives. Caveat: inline arrow `onClick={() => navigate(...)}` trong Sidebar phá memo (W3-FE-4 cleanup).
- BE rate limit Redis INCR `rate:conv_create:{userId}` TTL 60s, max 10/min.

Warnings:
- BE fail-CLOSED khi Redis down (khác ADR-011 fail-OPEN cho JWT blacklist). Gợi ý wrap try/catch fail-open → rate limit pattern ADR-005 nhất quán.
- Contract drift: API_CONTRACT "30/giờ" vs code "10/phút" → sync contract = code.
- FE 429 không toast (V1 acceptable, demo bắt buộc fix).
- Rate limit response thiếu `details.retryAfterSeconds` (contract nói có).

Contract: v0.5.1-conversations update rate limit value 10/min.

---

## [W3-D2] BE 4 Conversations endpoints + FE scaffold — REQUEST CHANGES → APPROVE

Initial blocking (2, FE side):
1. `api.ts` đọc `.code` thay vì `.error` field của `ErrorResponse` → 409 CONV_ONE_ON_ONE_EXISTS sẽ LUÔN throw.
2. FE `ConversationDto` có 4 field `displayName/displayAvatarUrl/unreadCount/mutedUntil` không có trong BE response (chỉ ở `ConversationSummaryDto`) → runtime undefined.

Fix applied: `.code` → `.error` + import `ApiErrorBody` từ `types/api.ts` chung. Xóa 4 field khỏi `ConversationDto`, helper `getConversationDisplayName(conv, currentUserId)` derive runtime. BE `createGroup` đủ 3 validation: caller-in-memberIds, dedupe `distinct()`, max 49.

Key decisions:
- ADR-013 ONE_ON_ONE race no-lock V1 formalized: P(collision) < 0.01%, SERIALIZABLE overhead không đáng, V2 partial UNIQUE index.
- W3-BE-1 RESOLVED: `@GeneratedValue UUID + insertable=false` conflict → migrate sang `@PrePersist` Option B.
- `findExistingOneOnOne` native SQL JOIN double + filter `type='ONE_ON_ONE'` — verified không false-positive với GROUP.
- Anti-enumeration GET `/api/conversations/{id}` merge not-exist + not-member → CONV_NOT_FOUND.
- `entityManager.flush() + clear()` pattern để force save members trước reload via `findByIdWithMembers` (OK nhưng fragile nếu chain logic).

Patterns confirmed (review standards mới):
- **Error response field name drift BE↔FE**: BE dùng `.error`, không phải `.code`. FE define `ApiErrorBody` 1 chỗ, import khắp nơi.
- **DTO shape drift Summary vs Detail**: không copy toàn bộ field của Summary vào Detail — phải bám contract per-endpoint.

Warnings BE non-blocking: N+1 list conversations (W3-BE-4), loop `findById` memberIds (không batch), rate limit 4 endpoints TODO, UserController cross-package `ConversationService` (W3-BE-5 code), `jsonPath.doesNotExist()` fragile assertion cho null field.

---

## [W3-D1] V3 schema + Conversation domain + FE layout skeleton — APPROVE WITH COMMENTS

Blocking: 0.

Key decisions:
- ADR-012: UPPERCASE enum `ONE_ON_ONE/GROUP`, role `OWNER/ADMIN/MEMBER` (khác ARCHITECTURE.md lowercase). Java enum convention + Jackson default + DB CHECK constraint UPPERCASE. ARCHITECTURE.md KHÔNG sửa (tài liệu thiết kế gốc).
- V3 đơn giản hoá: bỏ `left_at`, `leave_reason`, `is_hidden`, `cleared_at` (soft-leave/soft-hide out-of-scope W3). `muted_until` (grammar past participle).
- `ON DELETE SET NULL` cho `created_by` là dead code V1 (soft-delete pattern). Future-proof.
- FE ProtectedRoute refactor children-prop → Outlet pattern, `isHydrated` check + `location.state.from`.

Warnings (ghi WARNINGS.md):
- W3-BE-1 `@GeneratedValue UUID + insertable=false` conflict Hibernate 6 (RESOLVED W3-D2).
- W3-BE-3 migration V2 thiếu `CREATE EXTENSION IF NOT EXISTS pgcrypto` (RESOLVED W3-D2).
- W3-BE-5 (schema) conversation_members không có updated_at (V1 đủ).
- W3-FE-1 ProtectedRoute `isHydrated` dead code vì App.tsx đã gate (defensive, cleanup option).

Contract: v0.5.0-conversations mới.

---

## [W2 Final Audit] Pre-tag v0.2.0-w2 — NEEDS_CLEANUP → READY_FOR_TAG

Blocking: 0.

Formalized 4 ADR implicit: ADR-008 (HS256 + jjwt 0.12.x), ADR-009 (Redis key schema `rate:/refresh:/jwt:blacklist:`), ADR-010 (AuthMethod enum), ADR-011 (Fail-open blacklist trade-off).

Tạo `docs/WARNINGS.md` tổng hợp: 5 pre-production (W-BE-4 race existsBy→save, W-BE-5 null passwordHash guard, W-BE-6 X-Forwarded-For sanitize, W-BE-7 fail-open monitoring, W-BE-8 generateUniqueUsername race), 8 documented-acceptable, 6 cleanup-tuần-8, 7 tech-debt-nhỏ.

Orphan TODO: `useAuth.ts:29` "TODO Tuần 2 call logout API" — logout đã implement `HomePage.tsx:25-39` dùng `logoutApi` trực tiếp. Map vào CL-1. Dead code detected: `handleAuthError.ts AUTH_ACCOUNT_DISABLED` (CL-3), `LoginPage+RegisterPage PROVIDER_ALREADY_LINKED` (CL-2).

Contract audit: 5 auth endpoints + 1 health, 0 drift so với v0.4.0-auth-complete. 

---

## [W2-D4] OAuth + Logout — APPROVE WITH COMMENTS

Blocking: 0.

Key decisions:
- ADR-007 OAuth auto-link by email: thứ tự (1) `user_auth_providers` by providerUid → (2) `users` by email AUTO-LINK → (3) new. Google luôn email_verified → an toàn. BẮT BUỘC check `email_verified` khi thêm Facebook/Apple.
- Firebase Admin SDK `firebaseAuth.verifyIdToken()` — BẮT BUỘC, không tự parse JWT (bỏ qua sig check với Google public keys).
- FirebaseAuth null check trước gọi → `AUTH_FIREBASE_UNAVAILABLE` 503 nếu chưa init.
- Blacklist TTL = `accessTokenRemainingMs/1000` (không dài hơn lãng phí, không ngắn hơn attacker dùng lại).
- Blacklist check TRƯỚC set SecurityContext (BLOCKING if order sai).
- ADR-011 Fail-open Redis blacklist check — intentional trade-off, comment rõ.
- Logout best-effort: try/catch refresh delete, vẫn blacklist access + trả 200.
- `OAuthResponse extends AuthResponse + isNewUser` (primitive boolean).
- `revokeAllUserSessions` chỉ gọi ở reuse detection, không ở normal oauth.

Warnings:
- FE `PROVIDER_ALREADY_LINKED` dead code (BE không emit) → CL-2.
- Contract `AUTH_FIREBASE_UNAVAILABLE` mô tả "timeout 5s" nhưng thực tế cũng cover "SDK chưa init" → CL-6.
- `HomePage.handleLogout` refreshToken null sau rehydrate race → skip API, access không blacklist (AD-6).
- `generateUniqueUsername` race → W-BE-8.

Contract: v0.3.0 → v0.4.0-auth-complete.

---

## [W2-D3.5] POST /api/auth/refresh — APPROVE WITH COMMENTS

Blocking: 0.

Key decisions:
- ADR-006 Refresh rotation + reuse detection: DELETE old token TRƯỚC SAVE new (cửa sổ 0 token). `MessageDigest.isEqual` constant-time compare hash. Hash mismatch → `revokeAllUserSessions(userId)` trước throw.
- `redisTemplate.keys("refresh:{userId}:*")` O(N) OK cho V1 user <10 sessions (V2 migrate SADD set members).
- Error code: INVALID (malformed/sig-sai/reused/user-not-found) merge 1 code để không leak. EXPIRED tách riêng cho FE biết re-login.
- Rate limit counter KHÔNG reset sau refresh thành công (AD-2).

Security standards formalized:
- Hash/token compare phải constant-time (MessageDigest.isEqual).
- DELETE trước SAVE cho token rotation.
- Reuse detection revoke ALL sessions.
- Log security event WARN với userId+jti, không raw token.
- Error code phân biệt INVALID vs EXPIRED, KHÔNG leak.

Warnings:
- Unused import `java.util.Arrays`.
- Contract dòng 337 nhắc `REFRESH_TOKEN_REUSED` — code dùng `AUTH_REFRESH_TOKEN_INVALID` → sync contract.
- Test 17 expired path fallback sang INVALID (CL-4, `@SpyBean` stub).
- Rate limit 10/60s per-userId vs contract 30/15min/IP → update contract = code.

Contract: v0.2.1 → v0.3.0-auth.

---

## [W2-D3] Phase C Wire FE Login + Register — APPROVE WITH COMMENTS

Blocking: 0.

Key decisions:
- W-FE-1 RESOLVED: username regex `/^[a-zA-Z_][a-zA-Z0-9_]{2,49}$/` khớp exact BE, first char không digit, 3-50 ký tự.
- Payload strip sensitive/client-only fields: `RegisterPage` tạo payload explicit (không spread `...data`) để loại `confirmPassword`.
- 6 BE error codes đều map FE: AUTH_INVALID_CREDENTIALS→field, AUTH_ACCOUNT_LOCKED→toast, RATE_LIMITED→toast+retryAfter, AUTH_EMAIL_TAKEN/AUTH_USERNAME_TAKEN→field, VALIDATION_FAILED→per-field qua details.fields.

Warnings (cleanup):
- AUTH_ACCOUNT_DISABLED dead case → CL-3.
- registerSchema regex gộp length+format → UX message sai → AD-7.
- HomePage logout button stub `onClick={() => {}}` (done W2-D4).
- ProtectedRoute chưa wire App.tsx (done W3-D1).

---

## [W2-D2] Phase A (FE authService.init) + Phase B (BE register + login) — APPROVE WITH COMMENTS

Blocking: 0.

Key decisions:
- ADR-005 Rate limit pattern Redis INCR + TTL on first. Login: tách `checkLoginRateLimit` (GET) khỏi `incrementLoginFailCounter` (INCR+EX), success → `delete(key)` reset.
- Refresh token SHA-256 hash vào Redis (không raw token) — nếu Redis compromise, hash không forge được.
- User enumeration protection: `findByUsername...orElse(null)` + AUTH_INVALID_CREDENTIALS cho user-not-found + wrong-password. Check status SAU verify credentials.
- `extractClientIp()` X-Forwarded-For[0] split "," + trim, fallback getRemoteAddr (chưa sanitize IP format → W-BE-6).
- Register rate limit tính MỌI request (AD-8), login chỉ tính fail.

Warnings (WARNINGS.md):
- W-BE-4 race `existsByEmail → save` → 500 thay 409 (DB UNIQUE constraint catch chưa có).
- AD-1 Redis fail sau save user → user tồn tại DB nhưng không refresh token (FE retry login).
- W-BE-6 X-Forwarded-For chưa sanitize IP format.
- `init()` catch empty silence error (TD-3).
- AppLoadingScreen không dấu (TD-1).

Contract unchanged v0.2.1-auth.

---

## [W2-D1] W-BE-3 + W-FE-2 RESOLVED — APPROVE WITH COMMENTS

Key decisions:
- ADR-010 AuthMethod enum (PASSWORD | GOOGLE) lowercase value trong JWT claim `auth_method`. `generateAccessToken(User, AuthMethod)` — không hardcode. Reader fallback PASSWORD khi claim unknown (backward compat).
- **tokenStorage.ts pattern**: module in-memory không import api.ts → phá circular dep `api.ts <-> authStore.ts`. Bỏ `globalThis` workaround. `authStore.setAuth/clearAuth` sync 2 chiều. `onRehydrateStorage` chỉ restore refreshToken (accessToken không persist theo ADR-003).

Warning: post-rehydrate access=null → request đầu trả AUTH_REQUIRED → interceptor redirect thay vì call /refresh. Pre-existing. Cần `authService.init()` call /refresh khi load có refreshToken.

---

## [W1 Phase 3A+3B+Fix] Spring Security + JWT + FE auth scaffold — APPROVE

Key decisions:
- ADR-001 JWT strategy: Access 1h JWT, Refresh 7d JWT, rotation.
- ADR-002 BCrypt strength 12 (~250ms hash time).
- ADR-003 FE persist refresh+user, không persist access.
- ADR-004 Error format `{error, message, timestamp, details}`.
- JWT `validateTokenDetailed()` trả enum VALID/EXPIRED/INVALID. Request attribute `jwt_expired` set đúng. authenticationEntryPoint trả AUTH_TOKEN_EXPIRED vs AUTH_REQUIRED.
- Axios interceptor: isRefreshing flag + failedQueue pattern.

Warnings:
- `auth_method` hardcode "password" (RESOLVED W2-D1 ADR-010).
- JwtAuthFilter query DB mỗi request (TD-5 cache Redis V2).
- AppException log DEBUG → không thấy brute force (TD-7 đổi WARN 4xx).
- registerSchema thiếu validate không bắt đầu bằng số (RESOLVED W2-D3).

Contract: v0.2-auth → v0.2.1-auth (AUTH_TOKEN_EXPIRED code).

---

## [Contract W1] Initial Auth contract

Viết API_CONTRACT.md v0.2-auth — 5 endpoints register/login/oauth/refresh/logout với token shape chuẩn, rate limits, error codes.

Ghi chú thiết kế:
- Refresh rotation mỗi lần.
- OAuth auto-link by email (Firebase verified).
- Login rate limit chỉ tính fail.
- Logout yêu cầu refreshToken trong body.
- `isNewUser` field trong `/oauth` response (exception intentional, không phải drift).
