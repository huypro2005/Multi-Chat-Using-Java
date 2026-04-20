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
