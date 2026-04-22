# Frontend Log — Nhật ký chi tiết frontend-dev

> Append-only, mới nhất ở đầu file.
> Mỗi ngày 1 entry, không gộp.

---

[2026-04-22 W8-D2] feat: pin message banner + bilateral block UI/actions
- TẠO: `src/features/messages/hooks/usePin.ts`
  - publish STOMP `/app/msg.{messageId}.pin` với action `PIN` / `UNPIN`
- TẠO: `src/features/messages/components/PinnedMessagesBanner.tsx`
  - banner ghim ở đầu danh sách tin nhắn, collapsed/expanded, click để scroll tới message
- TẠO: `src/features/users/components/BlockedUsersList.tsx` (chưa wire route theo scope D2)
- SỬA: `src/features/users/api.ts`, `src/features/users/hooks.ts`
  - thêm block/unblock/list blocked APIs + React Query hooks
- SỬA: `src/features/messages/components/MessageActions.tsx`
  - thêm menu action `📌 Ghim/Bỏ ghim` qua props `canPin/onTogglePin`
- SỬA: `src/features/messages/components/MessageItem.tsx`
  - nối `usePin`, thêm pinned indicator trên bubble, truyền pin action vào `MessageActions`
- SỬA: `src/features/messages/components/MessagesList.tsx`
  - render `PinnedMessagesBanner`, thêm `messageRefs` + highlight ring 2s để scroll-to-message
  - compute `canPin` theo role + type
- SỬA: `src/features/messages/useConvSubscription.ts`
  - handle `MESSAGE_PINNED` / `MESSAGE_UNPINNED`, patch cache + invalidate conversation detail
- SỬA: `src/features/messages/useAckErrorSubscription.ts`
  - map error codes mới cho pin/block/msg_deleted
- SỬA: `src/features/conversations/components/ConversationHeader.tsx`
  - thêm nút Chặn/Bỏ chặn ở direct header, có confirm trước khi block
- SỬA: `src/features/conversations/utils.ts`, `src/types/conversation.ts`, `src/types/message.ts`, `src/types/auth.ts`, `stores/authStore.ts`
  - mở rộng type cho pin/block và tương thích `DIRECT`
- Commit: N/A (không commit theo yêu cầu)
- Trạng thái: DONE (đã chạy build/lint ở cuối session)

[2026-04-22 W8-D1] feat: message action bar reaction picker cạnh reply (popover click-to-open)
- SỬA: `src/features/messages/components/MessageActions.tsx`
  - thêm nút reaction (`😊`) cạnh nút reply trong action bar của từng message
  - click mở popover quick reactions: 👍 ❤️ 😂 😮 😢 😡
  - popover đóng khi chọn emoji hoặc click ra ngoài (outside click listener)
  - disable reaction button cho message đã xóa, optimistic (`clientTempId`) hoặc `SYSTEM`
  - giữ style đồng nhất với action buttons hiện tại (rounded, hover gray, compact)
- SỬA: `src/features/messages/components/MessageItem.tsx`
  - nối `useReact(message.id)` vào `MessageActions` qua prop `onReact`
  - bỏ `ReactionBar` hover cũ để tránh duplicate UX, giữ `ReactionAggregate` như cũ
- TÍCH HỢP API/socket: **có dùng luồng thật** qua STOMP `/app/msg.{messageId}.react` (hook `useReact`), không phải UI-only
- Verify:
  - `npm run lint`: pass
  - `npm run build`: pass (bao gồm `tsc -b`)
  - `npm run typecheck`: script không tồn tại trong `package.json`
- Commit: N/A (chưa commit trong session này)
- Trạng thái: DONE, không blocker

[2026-04-22 W8-D1] feat: message reactions — @emoji-mart picker + ReactionBar hover + ReactionAggregate display + REACTION_CHANGED broadcast handler
- types/message.ts: thêm ReactionAggregateDto interface; thêm reactions?: ReactionAggregateDto[] vào MessageDto; đổi ErrorEnvelope thành discriminated union (REACT có clientId: null)
- features/reactions/hooks/useReact.ts: fire-and-forget STOMP publish /app/msg.{messageId}.react; toast khi not connected
- features/reactions/components/EmojiPicker.tsx: lazy import @emoji-mart/react qua React.lazy + Suspense; dynamic split bundle
- features/reactions/components/ReactionBar.tsx: 6 quick emojis + "+" mở picker; absolute positioned; hover trigger
- features/reactions/components/ReactionAggregate.tsx: count badges, currentUserReacted highlight indigo; aria-pressed accessibility
- MessageItem.tsx: thêm showReactBar state + useReact hook; isOwn bubble: onMouseEnter/Leave + ReactionAggregate (justify-end) + ReactionBar (conditional); other bubble: wrap flex-col, indent pl-9, ReactionAggregate + ReactionBar
- useConvSubscription.ts: thêm ReactionChangedPayload interface + handleReactionChanged (setQueryData in-place); helper applyReactionChange + addReaction + removeReaction (pure functions, idempotent); sort count DESC, emoji ASC; import ReactionAggregateDto
- useAckErrorSubscription.ts: thêm case 'REACT' trong ERROR switch (clientId: null safe — KHÔNG dùng registry); hàm handleReactError với 6 error codes; destructure clientId inside case (type-safe)
- npm: cài @emoji-mart/react + @emoji-mart/data + emoji-mart (--legacy-peer-deps, React 19 compat)
- npx tsc --noEmit: 0 errors; eslint: 0 warnings; vite build: success

[2026-04-22 W7-D5] feat: read receipt — ReadTicks ✓/✓✓ + handleReadUpdated + useAutoMarkRead
- types/conversation.ts: thêm lastReadMessageId: string | null vào MemberDto
- ReadTicks.tsx: ✓ (gray) khi 0 readers, ✓✓ (blue) khi có reader; V1 approximation lastReadMessageId non-null = đã đọc; chỉ hiện cho own messages; ẩn khi sending/failed
- MessageItem.tsx: thêm members?: MemberDto[] + currentUserId?: string props; render ReadTicks thay thế static ✓ tick khi members available; fallback về static ✓ khi members empty
- MessagesList.tsx: thêm useConversation (cached) để lấy members; pass members + currentUserId xuống MessageItem; derive lastMessageId (non-system, non-optimistic); gọi useAutoMarkRead
- hooks/useAutoMarkRead.ts: debounce 500ms; dedupe lastSentRef; reset khi convId đổi; getStompClient().publish đến /app/conv.{id}.read; silent fail khi not connected
- useConvSubscription.ts: thêm ReadUpdatedPayload interface + handleReadUpdated (setQueryData in-place, idempotent, race-safe)
- tsc: 0 errors; eslint: 0 warnings

[2026-04-22 W7-D4-fix] refactor: avatar native img public URL (ADR-021) — xóa useProtectedObjectUrl cho avatars
- UserAvatar.tsx: bỏ useProtectedObjectUrl; dùng native <img src=avatarUrl> + div fallback overlay pattern; DEFAULT_USER_AVATAR constant
- ConversationListItem.tsx: bỏ useProtectedObjectUrl; native img + DEFAULT_GROUP_AVATAR fallback cho GROUP
- GroupInfoPanel.tsx: refactor GroupAvatarDisplay → native img; bỏ extractFileId helper; pass currentAvatarUrl prop thay vì currentAvatarFileId
- EditGroupInfoDialog.tsx: prop currentAvatarFileId → currentAvatarUrl; bỏ useProtectedObjectUrl; avatar upload thêm ?public=true
- CreateGroupDialog.tsx: avatar upload thêm ?public=true
- types/message.ts: thêm isPublic + publicUrl vào AttachmentDto (ADR-021 contract)
- useProtectedObjectUrl.ts: fix pre-existing TS18047 (state null narrowing)
- tsc: 0 errors; vite build: ✓

[2026-04-22 W7-D4] feat: system messages rendering with i18n (8 event types)
- SỬA: SystemMessage.tsx — fix copy templates khớp chính xác contract §3e.1:
  - ROLE_PROMOTED: "đặt {target} làm quản trị viên"
  - ROLE_DEMOTED: "gỡ quyền quản trị của {target}"
  - OWNER_TRANSFERRED autoTransferred=true: "{actor} đã rời nhóm và chuyển quyền trưởng nhóm cho {target}"
  - OWNER_TRANSFERRED autoTransferred=false: "chuyển quyền trưởng nhóm cho {target}"
  - GROUP_RENAMED: thêm phần oldValue "từ X thành Y" khi oldValue có
  - default fallback: "(sự kiện hệ thống)"
- SỬA: Container class my-2 → my-1 px-4 (khớp §3e.1)
- SỬA: Span class → text-xs italic text-gray-500 dark:text-gray-400 (bỏ pill bg theo DOM contract)
- XÁC NHẬN: message.ts đã có systemEventType/systemMetadata/sender=null (W7-D4 types)
- XÁC NHẬN: MessageItem.tsx SYSTEM branch đã có (dispatch trước hooks)
- XÁC NHẬN: MessagesList.tsx render SystemMessage inline với TEXT messages
- XÁC NHẬN: useAckErrorSubscription.ts đã có SYSTEM_MESSAGE_NOT_EDITABLE + NOT_DELETABLE
- tsc: 0 errors, lint: 0 errors 0 warnings

[2026-04-22 W7-D3] feat: group UI + member management + 6 broadcast handlers
- TẠO MỚI: CreateGroupDialog (avatar async upload, user search debounce, min 2 members)
- TẠO MỚI: GroupInfoPanel (role-based context menu, AddMembersDialog, confirm dialogs x5)
- TẠO MỚI: EditGroupInfoDialog (tristate PATCH, avatar change/remove)
- TẠO MỚI: useGroupActions.ts (5 mutation hooks: addMembers, removeMember, leaveGroup, changeRole, transferOwner)
- TẠO MỚI: useConvMembershipSubscription.ts (global, conv-added/conv-removed queues)
- SỬA: useConvSubscription.ts — thêm 6 case W7: MEMBER_ADDED, MEMBER_REMOVED, ROLE_CHANGED, OWNER_TRANSFERRED, CONVERSATION_UPDATED, GROUP_DELETED + navigate support
- SỬA: App.tsx — mount useConvMembershipSubscription trong GlobalSubscriptions
- SỬA: ConversationDetailPage — GroupInfoPanel cho GROUP, ConversationInfoPanel cho ONE_ON_ONE
- SỬA: ConversationsLayout — wire CreateGroupDialog + onCreateGroup callback
- SỬA: ConversationListSidebar — thêm nút Users (tạo nhóm)
- SỬA: ConversationListItem — group badge (purple) ở corner avatar
- SỬA: CreateConversationDialog — enable tab Tạo nhóm với onCreateGroup callback
- SỬA: types/conversation.ts — thêm OwnerDto, request types W7, UpdateGroupRequest
- SỬA: conversations/api.ts — thêm 6 mutation functions W7
- FIX (pre-existing): useProtectedObjectUrl + useUploadFile lint errors
- tsc: 0 errors, lint: 0 errors 0 warnings

---

[2026-04-21 W6-D4-extend] feat: expand file types (Office/text/archive), FileCard, group validation update. Accept attribute 14 MIME types + extensions. PdfCard deleted. Build clean (0 TS errors).

[W6-D4] fix: useProtectedObjectUrl add AbortController to cancel in-flight requests on unmount. docs: add auth-protected image pattern to knowledge. [2026-04-21]

[W6-D4] feat: file upload UI + attachment gallery + PDF card. Wire attachmentIds vào useSendMessage. [2026-04-21]
- Tạo: src/features/files/validateFiles.ts — validate MIME, size, max 5 ảnh / 1 PDF alone
- Tạo: src/features/files/useUploadFile.ts — AbortController per file, onUploadProgress, cancel/remove/clear, URL.revokeObjectURL cleanup on unmount
- Tạo: src/features/files/components/PendingAttachmentItem.tsx — preview thumbnail với progress bar + error overlay
- Tạo: src/features/files/components/AttachmentGallery.tsx — grid 1-2 cols, lightbox với keyboard nav (arrow/esc) + download
- Tạo: src/features/files/components/PdfCard.tsx — link card với tên file + size
- Sửa: src/types/message.ts — thêm AttachmentDto + attachments: AttachmentDto[] vào MessageDto
- Sửa: src/lib/stompClient.ts — StompSendPayload thêm attachmentIds?: string[]
- Sửa: src/features/messages/hooks.ts — useSendMessage nhận attachmentIds?: string[], optimistic attachments: []
- Sửa: src/features/messages/components/MessageInput.tsx — enable Paperclip, hidden file input, drag-drop, pending bar, guard uploading/no-content
- Sửa: src/features/messages/components/MessageItem.tsx — render AttachmentGallery/PdfCard trước text caption, Messenger-style, RetryButton pass attachmentIds: []
- Sửa: src/features/messages/useAckErrorSubscription.ts — add toast import + attachment error codes (MSG_ATTACHMENT_*)
- Build: npm run build pass, zero TS errors

[FE][W5-D4][2026-04-20] feat: reconnect catch-up + reply UI
- Tạo: src/features/messages/catchUp.ts — REST catch-up sau reconnect, GET /messages?after=<ts>&limit=100, merge+dedup, fallback invalidate
- Sửa: src/features/messages/useConvSubscription.ts — wasDisconnectedRef track disconnect, gọi catchUpMissedMessages khi reconnect (không phải connect đầu)
- Sửa: src/types/message.ts — ReplyPreviewDto thêm deletedAt: string|null, contentPreview: string|null
- Tạo: ReplyQuote.tsx — quote bubble hiện trong message
- Tạo: ReplyPreviewBox.tsx — preview box trên MessageInput, Escape cancel
- Sửa: MessageItem.tsx — thêm onReply prop, import+render ReplyQuote, handleReply gọi callback thật (block khi deletedAt/clientTempId)
- Sửa: MessageActions.tsx — Reply button disable khi deletedAt || clientTempId
- Sửa: MessagesList.tsx — nhận+forward onReply prop
- Sửa: MessageInput.tsx — thêm replyToMessageId, onSent props; truyền replyToMessageId vào sendMessage()
- Sửa: hooks.ts — useSendMessage nhận replyToMessageId?, truyền vào publishConversationMessage
- Sửa: stompClient.ts — StompSendPayload thêm replyToMessageId?: string|null
- Sửa: ConversationDetailPage.tsx — replyState scoped pattern (không useEffect setState), wire ReplyPreviewBox+MessagesList.onReply+MessageInput.replyToMessageId+onSent
- tsc -b --noEmit: 0 error | lint: 0 error

[FE][W5-D5] fix: hide tick for deleted + toast timeouts
- Cài: sonner (toast singleton)
- Sửa: App.tsx — render `<Toaster position="bottom-right" richColors />` trong BrowserRouter
- Sửa: MessageItem.tsx — wrap status icon block trong `{!isDeleted && (...)}`
- Sửa: hooks.ts, useEditMessage.ts, useDeleteMessage.ts — import toast from sonner; `toast.error('...')` trong timeout callbacks
- tsc + lint: 0 error

[FE][W5-D3][2026-04-20] feat: delete message + Facebook-style hover actions
- Sửa: types/message.ts — deletedAt/deletedBy/deleteStatus vào MessageDto; content string|null; DeleteAckMessage interface
- Tạo: deleteTimerRegistry.ts — singleton Map<clientDeleteId, {timerId, messageId, convId}>, pattern giống editTimerRegistry
- Tạo: useDeleteMessage.ts — window.confirm → publish /app/conv.{id}.delete → mark deleteStatus='deleting' → 10s timeout → revert + log
- Sửa: useAckErrorSubscription.ts — DELETE ACK case (patch deletedAt+deletedBy+content=null) + DELETE ERROR case (revert deleteStatus + log error code)
- Sửa: useConvSubscription.ts — MESSAGE_DELETED handler (soft patch: content=null, deletedAt, deletedBy, idempotent)
- Tạo: MessageActions.tsx — Facebook Messenger style hover bar; Reply stub, More menu (Copy/Edit/Delete); click-outside + Escape close; isOwn gate
- Tạo: DeletedMessagePlaceholder.tsx — gray italic bubble "Tin nhắn đã bị xóa"
- Sửa: MessageItem.tsx — integrate MessageActions + DeletedMessagePlaceholder; isDeleted gate; isDeleting opacity; remove old "Sửa" button; wire callbacks
- Sửa: authStore.ts — clearAuth() gọi deleteTimerRegistry.clearAll()
- Sửa: hooks.ts — thêm deletedAt:null/deletedBy:null vào optimisticMsg
- tsc + lint: 0 error

[FE][W5-D2][2026-04-20] feat: edit message inline + wire STOMP (ADR-017)
- Sửa: types/message.ts — thêm AckEnvelope, ErrorEnvelope (unified ADR-017), EditStatus
- Tạo: editTimerRegistry.ts — singleton Map<clientEditId, {timerId, messageId, convId}>
- Sửa: hooks.ts — thêm patchMessageById (real id, khác patchMessageByTempId)
- Tạo: useEditMessage.ts — optimistic update + publish /app/conv.{id}.edit + 10s timeout
- Sửa: useAckErrorSubscription.ts — unified ACK/ERROR với switch(operation): SEND cũ, EDIT route sang editTimerRegistry + patchMessageById, MSG_NO_CHANGE silent revert
- Sửa: useConvSubscription.ts — MESSAGE_UPDATED handler dedup theo editedAt (lexicographic ISO8601)
- Sửa: MessageItem.tsx — inline edit UI: canEdit (isOwn + no clientTempId + messageAgeMs < 290s), InlineEditArea (textarea autoFocus, Enter save, Escape cancel, error display), "(đã chỉnh sửa)" badge
- Sửa: authStore.ts — clearAuth() gọi editTimerRegistry.clearAll()
- tsc + lint: 0 error

[FE] fix(w5-d1-race): stopTyping trước sendMessage trong MessageInput — đổi thứ tự handleSend tránh race ~200ms.

[FE][W5-D1][2026-04-20] feat: typing indicator — publish khi gõ, hiển thị khi người khác gõ
- Tạo: useTypingIndicator.ts — subscribe /topic/conv.{id} filter TYPING_STARTED/STOPPED, skip self, auto-remove 5s, startTyping() debounce 2s + autoStop 3s, clear typingUsers khi DISCONNECTED/ERROR
- Tạo: TypingIndicator.tsx — render null khi rỗng, "X đang gõ..." / "X, Y đang gõ..." / "N người đang gõ...", aria-live polite
- Sửa: MessageInput.tsx — thêm onTypingStart/onTypingStop props, gọi startTyping trong onChange, stopTyping sau send + onBlur
- Sửa: ConversationDetailPage.tsx — wire useTypingIndicator + TypingIndicator
- tsc + lint: 0 error (2 warnings fixed — exhaustive-deps)

[FE][W4-D4-hotfix][2026-04-20] fix: useConvSubscription heuristic match never triggered (ADR-016)
- Sửa: useConvSubscription.ts:30 — isLikelyMatchOptimistic: thay `!tempMsg.id.startsWith('temp-')` bằng `!tempMsg.clientTempId`
- Root cause: hooks.ts (Path B ADR-016) tạo optimistic msg với id=crypto.randomUUID() (UUID thuần) không có prefix "temp-". Check cũ không pass → broadcast về trước ACK append thêm mới thay vì replace → 2 bản real message.
- Fix: dùng clientTempId (optional field) discriminator — đúng semantic hơn.

[FE][W4-D4][2026-04-20] feat: useConvSubscription hook + wire ConversationDetailPage
- Tạo: useConvSubscription.ts — subscribe /topic/conv.{id}, appendToCache dedupe (pages.some m.id), invalidate ['conversations'], re-sub onConnectionStateChange
- Sửa: ConversationDetailPage.tsx — gọi useConvSubscription(id)
- Fix: frontend/tsconfig.json — thêm `"ignoreDeprecations": "6.0"` (baseUrl deprecated TS6.0)
- build: 0 TS error | lint: 0 ESLint error

[FE][W4-D3][2026-04-20] feat: STOMP client singleton, lifecycle, ConnectionStatus
- Tạo: stompClient.ts — Client singleton, ConnectionState, connectStomp/disconnectStomp/getStompClient, onConnectionStateChange, exponential backoff MAX_RECONNECT=10, AUTH_TOKEN_EXPIRED→refresh, AUTH_REQUIRED→logout
- Sửa: authService.ts — thêm refresh() method (dùng bởi stompClient)
- Sửa: App.tsx — wire STOMP lifecycle qua useEffect watch isAuthenticated, import ConnectionStatus
- Tạo: ConnectionStatus.tsx — fixed bottom-right indicator, ẩn CONNECTED prod, luôn hiện DEV
- Tạo: frontend/.env — VITE_WS_URL=http://localhost:8080/ws

[FE][W4-D2][2026-04-20] feat: MessagesList, MessageItem, MessageInput enabled, wire ConversationDetailPage
- Tạo: MessageItem.tsx — React.memo, isOwn/other bubble, hover timestamp, status icon, reply preview, shouldShowAvatar grouping
- Tạo: MessagesList.tsx — useInfiniteQuery flatten, bottomRef auto-scroll, IntersectionObserver infinite scroll + preserve scrollTop, skeleton/error/empty states
- Sửa: MessageInput.tsx — conversationId prop, useSendMessage nội bộ, auto-resize textarea, char counter 4500/5000, Enter send, remove disabled=true
- Sửa: hooks.ts — optimistic sender từ useAuthStore thay vì hardcode 'Bạn'
- Sửa: ConversationDetailPage.tsx — replace MessagesAreaPlaceholder → MessagesList

[FE][W4-D1][2026-04-19] feat: message types, API functions, useMessages (infinite query), useSendMessage (optimistic)
- Tạo: types/message.ts — MessageType (const obj), MessageSenderDto, ReplyPreviewDto, MessageDto, MessageListResponse, SendMessageRequest, OptimisticMessage
- Tạo: messages/api.ts — sendMessage(), getMessages() (cursor-based)
- Sửa: conversations/queryKeys.ts — thêm messageKeys factory
- Tạo: messages/hooks.ts — useMessages (useInfiniteQuery cursor), useSendMessage (optimistic: cancel → snapshot → append → onError rollback → onSuccess replace → onSettled invalidate conversations)
- Consolidate frontend-knowledge.md 294→265 lines

---

## W3 summary

[FE][W3-D4][2026-04-19] feat: ConversationDetailPage, ConversationHeader, MessagesAreaPlaceholder, MessageInput (disabled), ConversationInfoPanel
- Tạo: ConversationHeader (Back mobile, Avatar, DisplayName, sub-text, Info+More), MessagesAreaPlaceholder, MessageInput (disabled=true W3, Paperclip+Send icons), ConversationInfoPanel (slide-in, member list+role badges), ConversationDetailPage (3-section vertical, skeleton, 404 error state, info toggle)
- Sửa: App.tsx — thay placeholder div bằng ConversationDetailPage tại route :id

[FE][W3-D3-fix] fix: 429 RATE_LIMITED error state in CreateConversationDialog — thêm error state, try/catch trong handleSelectUser, reset error trong handleClose. 429 → "Bạn đang tạo quá nhiều cuộc trò chuyện."

[FE][W3-D3] feat: ConversationListItem, ConversationListSidebar, CreateConversationDialog, UserAvatar, utils, wire ConversationsLayout
- Tạo: utils.ts (getOtherMember, formatLastMessageTime), UserAvatar.tsx, ConversationListItem.tsx (React.memo), ConversationListSidebar.tsx, CreateConversationDialog.tsx (modal, Esc, autoFocus, 409 redirect UX)
- Pitfall: react-hooks/set-state-in-effect — không useEffect reset; reset trong handleClose()

[FE][W3-D2-fix] fix: 409 error field name (code→error), ConversationDto remove server-only fields, add getConversationDisplayName helper
- Sửa: api.ts — 409 catch dùng `err.response.data.error` (KHÔNG `.code`) khớp BE ErrorResponse
- Sửa: types/conversation.ts — ConversationDto xóa 4 fields server-only (displayName, displayAvatarUrl, unreadCount, mutedUntil); thêm createdBy: CreatedByDto | null
- Tạo: types/api.ts — ApiErrorBody interface common error shape
- Thêm: getConversationDisplayName(conv, currentUserId) helper

[FE][W3-D2] feat: conversation types, api, React Query hooks scaffold, useDebounce, queryKeys
- Tạo: types/conversation.ts, conversations/api.ts, queryKeys.ts, hooks.ts (useConversations, useConversation, useCreateConversation), users/api.ts, users/hooks.ts (useUserSearch với debounce), useDebounce.ts, lib/queryClient.ts
- Pitfall: TypeScript erasableSyntaxOnly không cho `enum` — dùng const object + type pattern

## 2026-04-19 (W3D1) — ProtectedRoute wired + ConversationsLayout skeleton
- Refactor ProtectedRoute dùng `<Outlet />` (layout route), `isHydrated` spinner, lưu `location.state.from` redirect /login. W-C-4 RESOLVED.
- Tạo ConversationsLayout (2 cột sidebar w-80 + flex-1 main, mobile-responsive), ConversationsIndexPage (empty state).
- App.tsx ProtectedRoute wrapper + nested routes.
- LoginPage redirect `location.state.from?.pathname ?? '/conversations'`.
- HomePage "Vào Chat →" link khi authenticated.

---

## W2 summary

## 2026-04-19 (W2D4 Phase B) — Firebase OAuth + Logout wire
- `.env.local`: 3 VITE_FIREBASE_* env vars (placeholder).
- `firebase.ts`: lazy init `getApps().length === 0`, export auth/googleProvider/signInWithPopup.
- `types/auth.ts`: thêm `OAuthResponse extends AuthResponse { isNewUser: boolean }`.
- `auth/api.ts`: thêm oauthApi/logoutApi.
- `GoogleLoginButton.tsx`: popup flow, silent cancel, onError, Google SVG logo.
- LoginPage + RegisterPage tích hợp GoogleLoginButton, divider "hoặc".
- HomePage: wire handleLogout thật — logoutApi best-effort, clearAuth + navigate('/login') finally. W-C-3 RESOLVED.
- Firebase không crash khi env placeholder — chỉ fail runtime khi click Google.

## 2026-04-19 (W2D3 Phase C) — Wire Login + Register với API thật
- Tạo: types/auth.ts (RegisterRequest, LoginRequest, UserDto, AuthResponse, ApiError từ contract).
- Tạo: auth/api.ts (loginApi, registerApi).
- Tạo: handleAuthError.ts — switch/case error codes: INVALID_CREDENTIALS → inline; EMAIL_TAKEN/USERNAME_TAKEN → inline; RATE_LIMITED/ACCOUNT_LOCKED → toast; VALIDATION_FAILED → fields loop; default → toast.
- Fix W-FE-1: username regex `/^[a-zA-Z_][a-zA-Z0-9_]{2,49}$/`.
- Wire Login/Register pages. Bỏ confirmPassword trước API bằng explicit object build (không spread).
- ProtectedRoute đọc accessToken redirect /login.
- Ghi chú: `AuthResponse` types/auth.ts vs authStore.ts structural typing → TypeScript accept không cần cast.

## 2026-04-19 (W2D2 Phase A) — W2-NEW: authService.init() + AppLoadingScreen
- `authService.init()`: rawAxios riêng (không interceptors). 3 case: no refreshToken → false; có accessToken → true; refreshToken no accessToken → call /refresh.
- `AppLoadingScreen.tsx`: spinner Tailwind indigo-600, aria-label + role="img".
- `App.tsx`: useEffect + isInitialized gate. Routes render sau init() xong. `void authService.init().finally(...)` tránh lint floating-promise.
- BE chưa implement /api/auth/refresh → init() rơi catch, clearAuth, trả false. App chạy đúng.

## 2026-04-19 (W2D1) — W-FE-2: tokenStorage migration
- Audit: tokenStorage.ts, api.ts, authStore.ts đã implement đúng (migration W1D4 chưa verify chính thức).
- Verify `globalThis` hoàn toàn absent trong src/ (grep rỗng).
- Cập nhật frontend-knowledge: TODO Tuần 2 → implemented, Auth Store pattern bỏ globalThis.
- tokenStorage.ts không import module nào trong project — dependency graph sạch.
- authStore.ts onRehydrateStorage gọi tokenStorage.setRefreshToken() sau hydrate → interceptor có token ngay.

---

## W1 summary

## 2026-04-19 (W1D5) — HomePage health check UI
- HomePage: backend health check 3 state (loading/ok/error). Discriminated union `HealthState`. `api.get('/api/health')` qua Vite proxy. Spinner loading, service/status ok, error message + hướng dẫn debug fail.
- Lưu ý: `baseURL` trong api.ts = `''` khi không set VITE_API_BASE_URL. Path `/api/health` đầy đủ prefix.

## 2026-04-19 (W1D3) — Register page, Axios client, Zustand auth store
- Tạo: registerSchema.ts (email, username regex, fullName, password chữ hoa+số, confirmPassword .refine).
- Tạo: RegisterPage.tsx (form 5 field, toggle show/hide password, RHF + zodResolver, loading spinner, design tokens).
- Tạo: lib/api.ts (axios singleton, refresh queue pattern isRefreshing + failedQueue, AUTH_TOKEN_EXPIRED vs AUTH_REQUIRED).
- Tạo: stores/authStore.ts (Zustand persist — refreshToken + user persist, accessToken KHÔNG, isHydrated, wire globalThis.__authStoreGetState để api.ts đọc tránh circular dep).
- Tạo: useAuth.ts hook (user, isAuthenticated, isHydrated, logout stub).
- Sửa: main.tsx — bọc QueryClientProvider, import authStore sớm để wire globalThis trước api.ts.
- Xóa: axios.ts placeholder.
- Circular dep api.ts ↔ authStore.ts: globalThis.__authStoreGetState — authStore wire khi module load, api.ts đọc qua global.
- Dùng axios.post (không api.post) cho /api/auth/refresh tránh interceptor loop.
- ESLint no-unused-vars không cho prefix `_`. Bỏ `get` trong Zustand `(set) => ({...})`.

## 2026-04-19 (W1D2) — Login page UI tĩnh
- loginSchema.ts (Zod validate không để trống — contract login không validate format tránh enumeration).
- Tạo: Toast.tsx + ToastContainer (custom, fixed bottom-right, auto 3s, slide). useToast.ts hook.
- LoginPage.tsx: RHF + zodResolver, toggle show/hide, loading spinner, design tokens indigo-600, error inline, link /register.
- Không có sonner/react-hot-toast → custom Toast. `mode: 'onTouched'` validate blur.

## 2026-04-19 (W1D1) — Khởi tạo Vite + React + TypeScript
- Scaffold `npm create vite@latest frontend -- --template react-ts`.
- Deps: react-router-dom@6, zustand, @tanstack/react-query, axios, react-hook-form, @hookform/resolvers, zod, lucide-react, date-fns, @stomp/stompjs, sockjs-client, firebase, tailwindcss v4, @tailwindcss/vite, @types/sockjs-client, @types/node.
- TailwindCSS v4: `@tailwindcss/vite` plugin, `@import "tailwindcss"` — không config file.
- Vite config: path alias `@/` → `src/`, proxy `/api` → `localhost:8080`, port 3000.
- TS alias: `baseUrl` + `paths` tsconfig.app.json, `ignoreDeprecations: "6.0"` (TS5101).
- Routing: App.tsx BrowserRouter + Routes `/`, `/login`, `/register`, wildcard.
- Placeholder pages: LoginPage, RegisterPage, HomePage.
- lib/axios.ts + .env.local (VITE_API_URL=/api).
- Cấu trúc: pages/, components/, hooks/, stores/, services/, types/, lib/.
- TS 5.8+ `baseUrl` deprecated → `"ignoreDeprecations": "6.0"` (không "5.0").
- Dev server `http://localhost:3000` ~295ms.
