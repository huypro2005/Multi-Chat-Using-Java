# WARNINGS — Chat App V1

_Last updated: 2026-04-22 (W8-D2 Pin + Block: 3 warnings thêm — pin-deleted race, block-existing-conv acceptable, pin-limit-race single-instance)_

> Tổng hợp mọi warning / TODO / tech debt đã biết trong codebase. Mỗi TODO trong code phải map 1 ID hoặc có plan cụ thể.
>
> Triết lý: chia theo mức độ action cần — **phải fix trước launch** vs **đã accept V1** vs **dọn cuối** vs **enhancement V2**.

---

## Pre-production (phải fix trước launch V1)

Phải resolve trước deploy V1 public. Effort: XS <30p · S 0.5d · M 1d · L >1d.

| ID | Mô tả | File | Effort | Fix khi nào |
|----|-------|------|--------|-------------|
| W-BE-4 | Race `existsByEmail` → `save` → 500 thay vì 409 AUTH_EMAIL_TAKEN/AUTH_USERNAME_TAKEN. Cần catch `DataIntegrityViolationException` + map constraint name. | `AuthService.java` (register path) | S | Tuần 6 hardening |
| W-BE-5 | `passwordHash = null` cho OAuth-only user. `/login` gọi `BCryptPasswordEncoder.matches()` throw `IllegalArgumentException` → 500. Guard null trước matches(). | `AuthService.java` (login path) | XS | Tuần 6 |
| W-BE-6 | `extractClientIp()` X-Forwarded-For[0] không sanitize → IP spoofing ghi Redis key rác bypass rate limit. Validate IP format trước khi dùng làm key suffix. | `AuthController.java` | S | Tuần 6 |
| W-BE-7 | Fail-open JWT blacklist khi Redis down → logged-out access token vẫn valid đến natural expiry (≤1h). Cần monitoring alert + runbook (rotate JWT_SECRET nếu nghi compromised). V2 circuit breaker. | `JwtAuthFilter.java` | M | Tuần 6 |
| W-BE-8 | `generateUniqueUsername` race: 2 OAuth concurrent cùng displayName → DB UNIQUE violate → 500. Retry loop bắt `DataIntegrityViolationException` regenerate. Gộp fix với W-BE-4. | `AuthService.java` (oauth create) | S | Tuần 6 |
| W4-BE-1 | V5 schema conflict: `sender_id NOT NULL + ON DELETE SET NULL` → PG throw constraint violation khi cascade. V1 không có DELETE user endpoint nên không trigger. Fix: migration đổi `sender_id` thành NULL hoặc `NO ACTION`. Chốt policy giữ message khi user deleted. | `V5__create_messages.sql` | XS | Khi mở DELETE user endpoint |
| W6-3 | **Disk quota per-user** — V1 KHÔNG có quota. Attacker có thể upload liên tục → disk full. Mitigation hiện có: rate limit 20 uploads/phút/user + max 20MB/file + 30 ngày expiry. Max consumption lý thuyết: 20 × 20MB × 1440 phút = ~575GB/user/ngày (attacker có thể trigger disk full trong vài giờ nếu disk nhỏ). V2 enforce quota qua `SUM(size) WHERE uploader_id = ? AND expires_at > NOW()` trước mỗi upload. V1 acceptable vì <1000 users, cần monitoring disk usage. | `FileService.java` | M | V2 |
| W6-5 | **File upload rate limit fail-open khi Redis down** — `FileService.checkUploadRateLimit()` catch `DataAccessException` → log WARN + return (allow upload). Acceptable vì Redis down là exceptional, block user sẽ worse UX. Nhưng cần monitoring/alert: nếu Redis down kéo dài, attacker có thể brute upload → disk full (W6-3 không quota). Mitigation hiện có: Spring multipart max 20MB + MIME whitelist. V2: circuit breaker + fallback local in-memory LRU counter. | `FileService.java` | S | V2 (gộp với W-BE-7 Redis fail-open pattern) |
| W6-6 | **Multipart size 21MB request vs 20MB file** — `spring.servlet.multipart.max-request-size: 21MB` chỉ dư 1MB cho form boundary + other parts. Acceptable V1 (upload endpoint chỉ nhận 1 field `file`). Nếu sau này thêm `convId` / `messageId` vào cùng request (không khuyến khích theo current contract) cần bump request size. | `application.yml` | XS | Khi contract multi-field upload |
| W7-1 | **Auto-transfer race khi OWNER leave** — RESOLVED W7-D2 (contract): `POST /api/conversations/{id}/leave` spec chốt dùng `SELECT ... FOR UPDATE` trên actor row (hoặc toàn bộ rows conv) trong cùng `@Transactional` trước khi SELECT candidate promote. Contract finalize tại API_CONTRACT.md v1.1.0-w7 mục POST /leave → Auto-transfer logic bước 1. BE implement W7-D2 enforce. | `ConversationService.leaveGroup()` (BE W7-D2 implement) | M | ~~W7-D2~~ RESOLVED contract (pending BE implementation) |
| W7-2 | **Empty group edge case** — Last member (OWNER alone) leave → set `conversations.owner_id = NULL`, preserve group row. V1 không auto-delete. Group tồn tại vĩnh viễn cho đến khi có endpoint cleanup V2 hoặc admin manual. Risk: table bloat với group bị abandon; orphan avatar files (linked qua `avatar_file_id` nhưng owner deleted). Mitigation V1: monitoring alert — query `SELECT COUNT(*) FROM conversations c WHERE c.type='GROUP' AND NOT EXISTS (SELECT 1 FROM conversation_members cm WHERE cm.conversation_id = c.id) AND c.created_at < NOW() - INTERVAL '7 days'`. Alert nếu > 100 rows. V2 cleanup job weekly hoặc UI-triggered. | `conversations` table | M (monitoring) / L (cleanup job) | V2 |
| W7-3 | **Max 50 enforcement race** — RESOLVED W7-D2 (contract): `POST /api/conversations/{id}/members` spec chốt dùng `SELECT COUNT(*) FROM conversation_members WHERE conversation_id = :id FOR UPDATE` lock trong `@Transactional` trước INSERT batch. All-or-nothing check: nếu `currentCount + validToAddCount > 50` → throw `MEMBER_LIMIT_EXCEEDED` (409, v1.1.0-w7 rename from `GROUP_FULL`), không partial insert. Contract API_CONTRACT.md v1.1.0-w7 POST /members → Validation rules + Notes bước 2. BE implement W7-D2 enforce. | `ConversationService.addMembers()` (BE W7-D2 implement) | S | ~~W7-D2~~ RESOLVED contract (pending BE implementation) |
| W7-4 | **Kick broadcast ordering (topic vs user queue)** — User bị kick có thể nhận TOPIC broadcast `MEMBER_REMOVED` (`/topic/conv.{id}`) TRƯỚC hoặc SAU USER-SPECIFIC `/queue/conv-removed`. Spring STOMP 2 channel riêng, ordering không guaranteed. Nếu FE xử lý topic trước → đã remove conv → khi user queue về → idempotent no-op. Nếu user queue trước → FE remove conv → khi topic MEMBER_REMOVED về với `payload.userId == currentUser.id` → PHẢI cũng handle (remove UI + navigate) — nghĩa là FE handler topic phải check `userId == self` và treat như conv-removed. Mitigation: FE handler cả 2 destination idempotent (dedupe bằng conversationId: nếu conv đã remove khỏi cache → skip toast lần 2, skip navigate lần 2). KHÔNG là bug critical — chỉ UX polish. Acceptable V1. | FE `useAckErrorSubscription` + `useConvSubscription` idempotent | XS | V1 acceptable (FE idempotent handling đủ) |
| W7-5 | **`/queue/conv-added` offline delivery** — Khi BE fire `convertAndSendToUser(newUserId, "/queue/conv-added", ...)` nhưng user đang offline → SimpleBroker (V1) KHÔNG persist, frame bị drop vĩnh viễn. User reconnect sẽ KHÔNG nhận lại. Mitigation V1: khi user reconnect hoặc next page load, FE gọi `GET /api/conversations` list → thấy conv mới trong response → thêm vào sidebar tự nhiên (không có toast "bạn vừa được add"). UX loss: user không biết CHÍNH XÁC conv nào mới (phải nhìn vào sidebar và tự nhận ra). Acceptable V1 vì: (1) traffic thấp <1000 users, (2) user thường không bị add khi offline dài, (3) FE có thể highlight conv mới (diff với cached list trước reconnect) nếu cần. V2 fix: (a) RabbitMQ persistent queue với offline buffer, hoặc (b) push notification (FCM) qua PWA, hoặc (c) DB table `pending_notifications` + fetch khi login. | BE `ConversationService.addMembers()` (notify user) + FE sidebar refresh logic | M | V2 (cùng ADR-015 RabbitMQ migration) |
| W8-pin-deleted-race | **Pin message và soft delete đồng thời có thể race** — User A pin message X (đang validate, chưa commit), user B soft-delete message X (commit trước A). A service check `deletedAt == null` pass → commit pin. Result: message vừa pinned vừa soft-deleted. V1 mitigation: query pinned list filter `WHERE deleted_at IS NULL` → ghost pinned row không hiện trong banner nhưng count thừa 1 (next pin still OK nếu count < 3 sau filter). Impact thấp: race window rất nhỏ (concurrent pin+delete cùng message cùng instant), single-instance V1. V2 fix: (a) DB trigger auto-unpin khi soft delete, hoặc (b) `SELECT ... FOR UPDATE` trên message row trong pin service. | `PinService.pin()` + `MessageService.deleteViaStomp()` | S | V2 |
| W8-pin-limit-race | **Pin limit race** (2 user cùng pin khác message trong conv đã 2 pinned → race có thể cho 4 pinned) — `countPinnedInConversation` không dùng FOR UPDATE V1 → 2 concurrent check cả 2 thấy count=2 < 3 → cả 2 pin → result 4 pinned. V1 single-instance acceptable (race window nhỏ, cần 2 request concurrent exact timing). Mitigation: query pinned list `findPinnedByConversation` trả tất cả → FE hiện 4 thì tối đa 1 thừa, UX OK. V2 multi-instance fix: (a) `SELECT COUNT(*) ... FOR UPDATE` lock conv rows, (b) Redis SETNX distributed lock `lock:pin-count:{convId}`, (c) DB trigger check count. | `PinService.pin()` count check | S | V2 multi-instance |

---

## Acceptable V1 (đã documented, có ADR/rationale)

Đã review và chấp nhận cho V1. Không bắt buộc fix. V2 enhancement nếu có signal.

- **W8-block-existing-conv** A và B có conv 1-1 từ trước, A block B → conv vẫn tồn tại, chỉ không gửi message mới. Messages cũ vẫn visible. Acceptable V1: cả 2 đã consent khi tham gia conv cũ, historical data không bị xóa (consistent với delete message soft-delete pattern). V2 enhancement: option "Xóa lịch sử khi block" (user-initiated, không automatic).
- **AD-1** Redis fail sau save user (register) → user tồn tại DB nhưng không refresh token. `@Transactional` không bao Redis. FE retry login mitigate. Rollback Redis phức tạp và không atomic thật.
- **AD-2** Rate limit counter KHÔNG reset sau refresh thành công. Window 60s ngắn, tự hồi phục. FE refresh queue pattern đảm bảo không gọi song song.
- **AD-3** Rate limit refresh lấy userId từ token chưa validate Redis hash trước → JWT reused vẫn consume counter → DoS nhắm user cụ thể. Threat thấp V1.
- **AD-4** `refresh()` DELETE trước SAVE: crash giữa 2 bước → user mất session phải login lại. ADR-006 cửa sổ 0 token > cửa sổ 2 token. V2 MULTI/EXEC.
- **AD-5** `email_verified` không check khi auto-link (Google luôn verified). BẮT BUỘC add check khi thêm Facebook/Apple (ADR-007).
- **AD-6** `HomePage.handleLogout` refreshToken null sau rehydrate race → skip /logout API → access không blacklist. Token tự expire ≤1h.
- **AD-7** `registerSchema` regex gộp length + format → error message khi >50 ký tự nói "bắt đầu bằng chữ cái". UX trade-off nhỏ, schema match contract BE.
- **AD-8** Register rate limit tính MỌI request (10/15min/IP). Anti-abuse intent, legit không tạo 10 account/15min.
- **AD-9** `users.last_seen_at` có column nhưng KHÔNG expose API V1. Privacy policy chưa chốt. Column sẵn để V2 không tốn migration.
- **AD-10** `JwtAuthFilter` update `last_seen_at` dùng `userRepository.save(user)` full entity → lost-update window + auto-commit tx. V1 traffic thấp, user hiếm concurrent self-update. V2 partial UPDATE hoặc Redis presence.
- **AD-11** `MessageService.sendMessage` cập nhật `conversation.lastMessageAt` không có `@Version`. `touchLastMessage()` chỉ set khi `messageTime.isAfter(current)` → convergent (max). V2 thêm `@Version` nếu cần audit.
- **AD-12** Reply tới message đã soft-delete (`deletedAt != null`) KHÔNG bị block. V1 chưa có endpoint Edit/Delete. Tuần 6 chốt policy: block reply hay cho phép với placeholder.
- **AD-13** `MessageService.toMessageDto` N+1 LAZY `sender` + `replyToMessage.sender` → page 50 msg có thể fire 50-100 SELECT. V1 traffic thấp acceptable. V2 JOIN FETCH.
- **AD-14** Clock skew client vs server → edit window FE có thể sai vài giây. FE buffer 290s thay 300s để mitigate. V2: BE trả `serverNow` trong contract để FE sync.
- **AD-15** Redis dedup TTL reset khi update PENDING → realId. Spring Data Redis không expose KEEPTTL flag → set lần 2 dùng full 60s TTL. Gap <100ms acceptable V1.
- **AD-16** Reply to deleted message → BE cho phép (AD-12 gộp), UX có thể rối (reply preview của placeholder). V2: xem xét block hoặc strip reply preview.
- **AD-17** Catch-up limit 100 messages (REST cursor). User offline dài có thể miss. V2 pagination catch-up nhiều trang.
- **AD-18** timerRegistry fail-open khi STOMP silent drop → message stuck `sending` 10s. Mitigated bởi timeout mark failed + retry.
- **AD-19** `MessageMapper.toDto` load attachments qua `JOIN message_attachments` — N+1 risk cho list messages (50 msg × 1 JOIN query = 50 queries). V1 acceptable (<1000 users, list messages không phải hot path như REST hot). V2 optimize qua `@EntityGraph(attributePaths="attachments")` hoặc JOIN FETCH ở `MessageRepository.findByConversationId`. Liên quan AD-13.
- **AD-20** `MessageMapper.toDto` khi `message.deletedAt != null` — strip cả `content = null` (W5-D3 pattern đã có) VÀ `attachments = []` (W6-D1 mở rộng). Lý do: attachment cũng là "content" đã xoá, không leak URL sau delete. Applied nhất quán ở REST list + WS broadcast + ACK.
- **AD-21** Thumbnail lazy-generate lần đầu GET `/api/files/{id}/thumb` → latency cao request đầu (~100-300ms cho Thumbnailator resize 20MB image). V1 acceptable (1 lần/file), lần sau cache disk. V2 pre-generate async khi upload (worker queue) để request đầu nhanh.
- **AD-22** SimpleBroker broadcast payload khi message có 5 attachments + content dài: mỗi FileDto ~200 bytes → 5 × 200 = 1KB + content 5KB + reply preview ~200 bytes = ~6.5KB. Vẫn xa giới hạn 64KB STOMP frame. Monitor nếu group lớn (50+ member) × nhiều attachment bắt đầu chậm.
- **AD-23** Thumbnail format giữ extension gốc (jpg/png/webp/gif) thay vì luôn JPEG như contract §GET /thumb note dòng 947 nói. Lý do chọn: đơn giản, giữ aspect ratio alpha (PNG/webp transparent), avoid re-encode loss. Trade-off: size thumbnail webp/png lớn hơn JPEG ~30% cho ảnh chụp. Contract drift → sync bằng cách update contract §947 nói "thumbnail giữ MIME gốc" thay vì đổi code. Documented trong `ThumbnailService.java` javadoc. V2 nếu cần tối ưu size → force JPEG + xử lý alpha.
- **AD-24** Thumbnail endpoint `GET /api/files/{id}/thumb` KHÔNG set `Content-Disposition` (download endpoint `{id}` có set). Acceptable vì thumb chỉ dùng cho `<img src>` render inline, không cần hint "download as". Nếu FE lỡ dùng thumb URL cho download button → browser có thể fallback auto-name "thumb". Minor UX, V1 acceptable.
- **AD-25** Thumbnail endpoint Content-Type trả theo `record.mime` (có thể là `image/png|webp|gif|jpeg`). Nếu thumbnail format thực tế mismatch record.mime (không xảy ra V1 vì code giữ ext gốc) → browser render lỗi. Nếu AD-23 fix sang JPEG thì PHẢI override Content-Type thành `image/jpeg`. Note để future-self.
- **AD-26** `MessageMapper.loadAttachmentDtos` silent-skip (filter `Objects::nonNull`) khi FileRecord không tìm thấy — scenario hiếm (file bị hard-delete nhưng message_attachments row vẫn còn). V1 OK vì ON DELETE CASCADE từ files → không xảy ra. Defensive code giữ.
- **AD-27** `FileService.upload` thumbnail step 6 gọi `fileRecordRepository.save(record)` lần 2 trong cùng `@Transactional` — JPA dirty tracking sẽ UPDATE khi commit, save() là no-op đắt (1 EntityManager merge). Non-blocking V1. V2 dùng `entityManager.flush()` hoặc remove save() redundant.
- **AD-28 (Office macro risk, W6-D4-extend)**: DOCX/XLSX/PPTX có thể chứa VBA macros. V1 accept và serve as-is. V2: scan macros (Apache POI `XSSFWorkbook.getMacros()`) hoặc warn user trước khi mở (đặc biệt với PPTX). Không phải XSS vector (server không execute), nhưng social engineering risk: attacker upload "Báo cáo lương.xlsx" có macro auto-run khi user enable. Mitigation hiện có: `Content-Disposition: attachment` cho non-image (browser không inline-render Office docs), user phải explicit download + open + enable macro = 3-step friction. **W6-D5 audit confirmed**: V1 acceptable, V2 candidate.
- **AD-29 (Blacklist maintenance, W6-D4-extend)**: Executable blacklist (`.exe`, `.dll`, `.bat`, `.sh`, `.cmd`, `.ps1`, `.msi`, `.vbs`, `.py`, standalone `.js`) + XSS vectors (`image/svg+xml`, `text/html`, `application/xhtml+xml`) phải review khi thêm types mới vào whitelist. **Lịch review: trước mỗi expand whitelist cycle**. Người chốt: code-reviewer agent (architect role). Documented trong `docs/API_CONTRACT.md` mục "Allowed MIME types (v0.9.5)". Lý do giữ explicit blacklist thay vì "whitelist-only ngầm hiểu": phòng future PR vô tình thêm `text/html` mà không nhớ XSS risk.
- **AD-30 (Blob URL lifecycle, W6-D5 audit)**: `useProtectedObjectUrl` hook (FE) tạo 1 blob URL mỗi `<img>` mount + revoke khi unmount. Cho conversation 50+ images → 50 round-trip qua axios với `responseType: 'blob'` → memory peak ~5-50MB tuỳ size. Browser native cache HTTP không kick in vì axios fetch qua Bearer token (request khác Cache-Control matching key của browser). V1 acceptable (per-component lifecycle, refetch sau re-render OK với scroll virtualization). V2: signed URL (HMAC + expiry) cho phép `<img src="https://api/files/{id}?sig=abc&exp=...">` browser tự cache + parallel decode + tận dụng HTTP/2 multiplex. Trade-off: signed URL leak qua Referer header + log → cần khoảng `exp` ngắn (5 phút).
- **W3-BE-2** `Conversation.createdBy` DB `ON DELETE SET NULL` là dead code V1 (soft-delete pattern). Future-proof V2.
- **W3-BE-4** N+1 query risk khi list conversations. Traffic V1 <1000 users + index có sẵn. Optimize tuần 4-5 nếu query plan nóng.
- **W3-BE-5 (schema)** `conversation_members` không có `updated_at` + trigger BEFORE UPDATE. `joined_at` immutable + `last_read_message_id` tự track. V1 đủ.
- **ADR-013** ONE_ON_ONE race no-lock V1: 2 concurrent cùng pair → có thể dup. P < 0.01%. SERIALIZABLE overhead không đáng. V2 partial UNIQUE index + clean-up script merge dup.

---

## Cleanup (Tuần 8 hoặc khi có bandwidth)

Effort XS-S, không ảnh hưởng chức năng. Dọn khi có thời gian hoặc ghép vào PR gần đó.

| ID | Mô tả | File | Effort |
|----|-------|------|--------|
| CL-1 / TD-3 | `useAuth.ts` orphan TODO + logic outdated. Logout đã implement ở `HomePage.tsx`. Quyết: dẹp hoặc centralize. | `useAuth.ts` | S |
| CL-2 | `LoginPage.tsx` + `RegisterPage.tsx` check `PROVIDER_ALREADY_LINKED` — BE không emit, dead branch. | `LoginPage.tsx`, `RegisterPage.tsx` | XS |
| CL-3 | `handleAuthError.ts` case `AUTH_ACCOUNT_DISABLED` dead (BE chỉ throw `AUTH_ACCOUNT_LOCKED`). | `handleAuthError.ts` | XS |
| CL-4 | Test `refreshWithExpiredToken_returnsExpiredError` fallback sang INVALID. EXPIRED chỉ có unit test. Dùng `@SpyBean` stub. | `AuthControllerTest.java` | S |
| CL-5 | Comment "Tuần 2 sẽ truyền dynamic" về `auth_method` — đã resolved W2-D1. Xóa comment. | `JwtTokenProvider.java` | XS |
| CL-6 | Contract `AUTH_FIREBASE_UNAVAILABLE` chỉ nói "timeout 5s" — thực tế cũng cover "SDK chưa init". Mở rộng câu. | `API_CONTRACT.md` | XS |
| W3-BE-5 (code) | `UserController` inject `ConversationService` cross-package. Nên có `UserService` riêng. | `UserController.java` | S |
| W3-FE-1 | `ProtectedRoute` có `isHydrated` check dù `App.tsx` đã gate `isInitialized`. Branch spinner dead code. Chọn xóa hoặc giữ với comment. | `ProtectedRoute.tsx` | XS |
| W3-FE-4 | Inline arrow `onClick={() => navigate(...)}` trong `ConversationListSidebar` phá `React.memo`. Fix `useCallback` + pattern `onSelect(id)`. | `ConversationListSidebar.tsx` | S |
| TD-1 | `AppLoadingScreen.tsx` text không dấu "Dang khoi dong...". | `AppLoadingScreen.tsx` | XS |
| TD-2 | `AuthService.register` thiếu structured log cho security events. | `AuthService.java` | S |
| TD-4 | `AuthController` javadoc outdated (list 2 endpoints thay vì 5). | `AuthController.java` | XS |
| TD-5 | `JwtAuthFilter` query DB mỗi request authenticated. Cache User vào Redis TTL ngắn khi load tăng. | `JwtAuthFilter.java` | M |
| TD-6 | `application-test.yml` JWT secret rõ trong file. OK cho test, guard profile split không leak prod. | `application-test.yml` | XS |
| TD-7 | `GlobalExceptionHandler.AppException` log DEBUG → spam `AUTH_INVALID_CREDENTIALS` không thấy trong INFO. Đổi WARN cho 4xx security (401/403/429). | `GlobalExceptionHandler.java` | XS |
| TD-9 | `ConversationDetailPage` error state chỉ có "Quay lại", không có "Thử lại" in-place. React Query tự retry 3 lần. | `ConversationDetailPage.tsx` | XS |
| TD-10 | `UserController.getUserById` unused `@AuthenticationPrincipal User currentUser` param. Giữ cho V2 block-list filter, suppress warning. | `UserController.java` | XS |
| W5-D3-1 | `MessageItem` status icon `✓` vẫn render cạnh DeletedMessagePlaceholder. Bọc `{!isDeleted && ...}`. Fixed W5-D5 planned. | `MessageItem.tsx` | XS |
| W5-D3-2 | `useDeleteMessage` timeout path không toast — chỉ `console.error`. User không thấy notification. V2 toast singleton pattern. Fixed W5-D5 planned. | `useDeleteMessage.ts` | S |
| W5-D3-3 | `ChatTypingHandler` load `userRepo` mỗi event. Rate limit 1/2s đã mitigate. V2 cache `(userId→username)` TTL 5min. | `ChatTypingHandler.java` | S |
| W5-D3-4 | `TypingRateLimiter` fail-open khi Redis down không emit metric. V2 `typing.ratelimiter.fail_open.count`. | `TypingRateLimiter.java` | XS |
| W5-D3-5 | `handleDuplicateDeleteFrame` silent drop không log WARN khi defensive check fail (scenario cực hiếm). | `MessageService.java` | XS |

---

## V2 Enhancement

Outside scope V1, ghi nhận để future consideration.

- **Delete không có undo grace period** (Gmail-style 5s). V1 dùng `window.confirm()` trước delete thay cho undo.
- **Presence / online status** chưa implement (Tuần 7).
- **File upload** chưa implement (Tuần 6). V1 local storage, V2 chuyển S3.
- **Full-text search message content** chưa wire FE.
- **Read receipts** chưa implement (Tuần 7).
- **Offline queueing client-side** (gửi khi offline, sync khi online). V1 không có → STOMP Path B ADR-016 acceptable. V2 có thể cần reconsider.
- **Destination ACL built-in broker** — V1 custom `AuthChannelInterceptor` SUBSCRIBE. V2 nếu migrate RabbitMQ → dùng broker ACL.
- **Revoke all sessions qua SET members** thay `KEYS refresh:{userId}:*` (O(N) scan). V2 SADD `user_sessions:{userId}` jti.
- **Circuit breaker cho Redis blacklist fail-open** — V1 ADR-011 accept trade-off, V2 nếu có tool monitoring.
- **`@Scheduled` multi-instance race — FileCleanupJob** — V1 single BE instance OK. V2 multi-instance + RabbitMQ: 2 instance cùng chạy `@Scheduled` → có thể cùng DELETE FileRecord → `StaleObjectStateException`. Fix: Redis SETNX distributed lock (`SETNX lock:file-cleanup:expired instanceId EX 1800`). Pattern đầy đủ xem `backend-knowledge.md` mục "FileCleanupJob V2".
- **Signed URLs thay blob URL** — V1 FE dùng `useProtectedObjectUrl` (axios + Bearer token + `URL.createObjectURL`). Memory peak với conversation 50+ images, không tận dụng browser native HTTP cache. V2: BE phát signed URL `/api/files/{id}?sig={HMAC}&exp={epoch}` (HMAC-SHA256 với secret + 5min TTL); FE dùng raw `<img src>` → browser cache, parallel decode, HTTP/2 multiplex. Liên quan AD-30. Trade-off: signed URL leak qua Referer + log → exp ngắn (5 phút).
- **Office macro scanning DOCX/XLSX** — AD-28. V1 serve as-is + Content-Disposition: attachment friction. V2 dùng Apache POI `XSSFWorkbook.getMacros()` reject hoặc warn user trước khi mở. Đặc biệt PPTX (auto-run macro on open).
- **Per-user storage quota (W6-3 V2 path)** — V1 không enforce, chỉ rate limit + expiry mitigate. V2 enforce qua `SUM(size_bytes) WHERE uploader_id=? AND expires_at > NOW()` trước mỗi upload, giới hạn ví dụ 1GB/user/30days. Cần thêm metric/UI hiển thị usage.

---

## Resolved (tham khảo lịch sử)

### W6

| ID | Mô tả | Resolved | Fix |
|----|-------|----------|-----|
| W6-1 | Path traversal via originalName — KHÔNG dùng originalName trong filesystem path. | 2026-04-21 (W6-D5 audit) | `LocalStorageService.store()` dùng `{base}/{yyyy}/{mm}/{UUID}.{ext}`. ext lấy từ MIME whitelist. `assertWithinBase()` canonical prefix check (resolve → normalize → startsWith). Reject fileId chứa `/`, `\`, `..` ở entry. `sanitizeFilename` strip path separator + control chars CHỈ dùng cho `Content-Disposition`. ADR-019 confirmed. |
| W6-2 | MIME spoofing via Content-Type header — verify qua Tika magic bytes. | 2026-04-21 (W6-D5 audit) | `FileValidationService.validate()` gọi `tika.detect(InputStream)` đọc magic bytes (~8KB peek). Charset strip `split(";")[0].trim()` cho text/plain. ZIP→Office override CHỈ KHI `application/zip` + extension docx/xlsx/pptx; sau override vẫn check whitelist. Declared MIME ≠ detected → throw `MimeMismatchException` 415. .exe (magic MZ) → Tika `application/x-msdownload` → reject (không trong whitelist). |
| W6-4 | Orphan file cleanup — file upload nhưng không attach trong 1h. | 2026-04-21 (W6-D3 implement, W6-D5 verified) | `FileCleanupJob.cleanupOrphanFiles` `@Scheduled` mỗi giờ (cron `0 0 * * * *`). `findByAttachedAtIsNullAndCreatedAtBefore(now-1h, PageRequest.of(0, 100))` batch loop. Per-record try-catch. `deletePhysical` + DB delete (orphan không có FK constraint). FileRecord.markAttached() set attachedAt khi attach succeed → orphan = attachedAt IS NULL. Test CJ04/CJ05/CJ06 cover happy/grace/non-orphan. |

### W5

| ID | Mô tả | Resolved | Fix |
|----|-------|----------|-----|
| W5-D1-1 | `AuthChannelInterceptor` destination policy drift (throw FORBIDDEN cho mọi `/app/conv.*` non-member) → typing race reconnect loop. | W5-D1 | Fix A: `DestinationPolicy` enum + `resolveSendPolicy(destination)` package-private. `.message/.edit/.delete → STRICT_MEMBER`, `.typing/.read → SILENT_DROP`. |
| W5-D1-2 | `stopTyping` race vs `sendMessage` — receiver thấy message trước rồi typing biến mất. | W5-D1 | MessageInput gọi `onTypingStop?.()` TRƯỚC `sendMessage(trimmed)`. |
| W5-D2-1 | Optimistic edit không revert `content` khi ERROR → cache lệch DB (MSG_NOT_FOUND/WINDOW_EXPIRED/NO_CHANGE/TIMEOUT). | W5-D2 | Option A: bỏ optimistic `content + editedAt`, chỉ clear `failureCode`. ACK patch từ server. ERROR chỉ set failureCode. MSG_NO_CHANGE silent. |
| W5-D3-1 | Status tick cạnh DeletedMessagePlaceholder UX lạ. | W5-D5 | Bọc `{!isDeleted && ...}` quanh status icon. |
| W5-D3-2 | Timeout silent không toast. | W5-D5 | Toast singleton thêm cho timeout path. |
| W5-D3-3 | Duplicate request no log. | W5-D5 | Log WARN added cho handleDuplicateDeleteFrame defensive fail. |

### W4

| ID | Mô tả | Resolved |
|----|-------|----------|
| (W4 không có item resolved riêng; W4-BE-1 vẫn pending đến khi mở DELETE user endpoint) | - | - |

### W3

| ID | Mô tả | Resolved | Fix |
|----|-------|----------|-----|
| W3-BE-1 | `@GeneratedValue UUID + insertable=false` conflict Hibernate 6. | W3-D2 | Migrate sang `@PrePersist` Option B: `if (id == null) id = UUID.randomUUID()`. |
| W3-BE-3 | `CREATE EXTENSION pgcrypto` thiếu V2 → fresh DB fail `gen_random_uuid()`. | W3-D2 | Thêm `CREATE EXTENSION IF NOT EXISTS pgcrypto;` V2. `repair-on-migrate: true`. |
| W3-BE-6 | `POST /api/conversations` không có rate limit. | W3-D3 | Redis INCR `rate:conv_create:{userId}` TTL 60s, max 10/min/user. |
| TD-8 | `MethodArgumentTypeMismatchException` cho `@PathVariable UUID` → 500 thay 400. | W3-D5 | `GlobalExceptionHandler.handleTypeMismatch()` map 400 `VALIDATION_FAILED`. |
| W-C-4 | `ProtectedRoute` chưa wire router tree. | W3-D1 | Wrap `/conversations/*` routes + redirect `/login` nếu không auth. |

### W2

| ID | Vấn đề | Fix | Resolved |
|----|--------|-----|----------|
| W-BE-3 | `generateAccessToken` hardcode `auth_method="password"` → OAuth user gắn claim sai. | Enum `AuthMethod` (PASSWORD/GOOGLE), reader fallback PASSWORD khi claim unknown. | W2-D1 (ADR-010) |
| W-FE-1 | Username regex FE lệch BE. | Sync `/^[a-zA-Z_][a-zA-Z0-9_]{2,49}$/`. | W2-D3 |
| W-FE-2 | Circular dep `api.ts <-> authStore.ts` dùng `globalThis`. | Migrate `tokenStorage.ts` module in-memory trung gian. | W2-D1 |

---

## Audit trail

- **W2 Final Audit**: Tạo file trước tag `v0.2.0-w2`. 5 pre-prod, 8 documented, 6 cleanup, 7 tech debt nhỏ.
- **W3-D4**: Thêm AD-9, AD-10, TD-8, TD-9, TD-10.
- **W3-D5 consolidation**: Restructure emoji priority + Effort + "Fix khi nào". Mark resolved W3-BE-1/3/6, TD-8.
- **W4-D1**: Thêm W4-BE-1 (V5 schema conflict), AD-11 (lastMessageAt no lock), AD-12 (reply soft-delete), AD-13 (N+1 list messages).
- **W5-D3 consolidation (2026-04-20)**: Restructure 4 section (Pre-production / Acceptable V1 / Cleanup / V2 Enhancement / Resolved). Thêm AD-14 clock skew, AD-15 dedup TTL reset, AD-16 reply deleted message, AD-17 catch-up limit, AD-18 timerRegistry fail-open. Thêm W5-D3 cleanup items (status tick, timeout toast, typing userRepo load, rate limit metric, defensive check log). Mark resolved W5-D1/D2/D3-post-W5D5 items.
- **W6-D1 (2026-04-20)**: Thêm 4 pre-production items cho File upload — W6-1 (path traversal: originalName không dùng làm path), W6-2 (MIME spoofing: verify qua Apache Tika magic bytes, không trust Content-Type header), W6-3 (disk quota V1 không có, V2 enforce), W6-4 (orphan file cleanup 1h). Thêm 4 AD items documented — AD-19 (N+1 load attachments trong list messages), AD-20 (soft-delete strip cả content lẫn attachments), AD-21 (thumbnail lazy-generate latency request đầu), AD-22 (SimpleBroker frame size với multiple attachments).
- **W6-D2 (2026-04-21)**: Thêm 5 AD items — AD-23 thumbnail format giữ ext gốc (contract drift vs §947, update contract), AD-24 thumb endpoint không set Content-Disposition, AD-25 Content-Type của thumb bám record.mime (sync với AD-23 nếu đổi JPEG), AD-26 MessageMapper silent-skip file not-found, AD-27 FileService save() lần 2 redundant. 0 BLOCKING. 191/191 tests pass.
- **W6-D4-extend (2026-04-21)**: Contract bump v0.9.0 → v0.9.5. Expand MIME whitelist 5 → 14 types: Group A (images jpeg/png/webp/gif, 1–5/msg) + Group B (docs/archives: pdf/docx/xlsx/pptx/doc/xls/ppt/txt/zip/7z, exactly 1/msg). Documented blacklist (executables, scripts, XSS vectors). Mixing rules formalized. Thêm field `iconType` enum vào FileDto (server-computed, FE chọn icon — không bind MIME để dễ extend). Thêm AD-28 (office macro risk, V2 scan), AD-29 (blacklist maintenance cycle).
- **W6-D5 (2026-04-21)**: Security audit 18/18 PASS. Move RESOLVED W6 (3 items): W6-1 path traversal (canonical prefix check + UUID filename), W6-2 MIME spoofing (Tika magic bytes + ZIP→Office gated), W6-4 orphan cleanup (FileCleanupJob hourly + grace period). Acceptable V1 confirmed (2 items): W6-3 disk quota (rate limit + expiry mitigate), W6-5 upload rate limit fail-open (consistent ADR-005 pattern). V2 Enhancement bucket expanded: signed URLs (replace blob URL), Office macro scanning (POI), per-user storage quota (W6-3 V2 path). Thêm AD-30 (blob URL lifecycle V1 acceptable, V2 signed URL).
- **W8-D2 contract (2026-04-22)**: Pin Message + Bilateral User Block contract-first. ADR-023 Pin Message (max 3/conv, GROUP OWNER/ADMIN, DIRECT any member, STOMP `/app/msg.{messageId}.pin`, broadcast MESSAGE_PINNED/UNPINNED), ADR-024 Bilateral Block (REST only, `existsBilateral` OR query, integrate sendViaStomp DIRECT + createDirect, privacy `isBlockedByMe` only). 3 WARNINGS: W8-pin-deleted-race (pre-production, V2 DB trigger), W8-pin-limit-race (pre-production, V2 FOR UPDATE/distributed lock), W8-block-existing-conv (acceptable V1, historical preserved). Migrations V14 + V15. Error codes: 4 pin (PIN_LIMIT_EXCEEDED, MESSAGE_NOT_PINNED, MSG_DELETED, INVALID_ACTION) + 3 block (CANNOT_BLOCK_SELF, BLOCK_NOT_FOUND, MSG_USER_BLOCKED). API_CONTRACT.md v1.6.0-w8-pin-block + SOCKET_EVENTS.md v1.11-w8.
- **W7-D2 contract (2026-04-21)**: 5 member-management endpoints finalize. W7-1 và W7-3 annotated "RESOLVED W7-D2" (contract-level): FOR UPDATE lock spec đã chốt trong API_CONTRACT.md v1.1.0-w7 (POST /leave bước 1, POST /members bước 2). Implementation W7-D2 BE pending. Thêm W7-4 (kick broadcast ordering topic vs user queue — FE idempotent handling, V1 acceptable) + W7-5 (`/queue/conv-added` offline delivery SimpleBroker drop — V1 mitigate qua GET /conversations refetch, V2 RabbitMQ persistent queue).
