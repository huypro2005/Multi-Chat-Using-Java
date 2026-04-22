# Frontend Log — Nhật ký chi tiết frontend-dev

> Append-only, mới nhất ở đầu file.
> Mỗi ngày 1 entry, không gộp.

---

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
  - ROLE_PROMOTED: "đặt {target} làm quản trị viên" (thay vì "thăng làm phó nhóm")
  - ROLE_DEMOTED: "gỡ quyền quản trị của {target}" (thay vì "giáng xuống thành viên")
  - OWNER_TRANSFERRED autoTransferred=true: "{actor} đã rời nhóm và chuyển quyền trưởng nhóm cho {target}"
  - OWNER_TRANSFERRED autoTransferred=false: "chuyển quyền trưởng nhóm cho {target}"
  - GROUP_RENAMED: thêm phần oldValue "từ X thành Y" khi oldValue có
  - default fallback: "(sự kiện hệ thống)" thay vì generic string
- SỬA: Container class my-2 → my-1 px-4 (khớp §3e.1)
- SỬA: Span class → text-xs italic text-gray-500 dark:text-gray-400 (bỏ pill bg theo DOM contract)
- XÁC NHẬN: message.ts đã có systemEventType/systemMetadata/sender=null (W7-D4 types)
- XÁC NHẬN: MessageItem.tsx SYSTEM branch đã có (dispatch trước hooks)
- XÁC NHẬN: MessagesList.tsx render SystemMessage inline với TEXT messages
- XÁC NHẬN: useAckErrorSubscription.ts đã có SYSTEM_MESSAGE_NOT_EDITABLE + NOT_DELETABLE
- tsc: 0 errors, lint: 0 errors 0 warnings

[2026-04-22 W7-D3] feat: group UI + member management + 6 broadcast handlers
- TẠOMỚI: CreateGroupDialog (avatar async upload, user search debounce, min 2 members)
- TẠOMỚI: GroupInfoPanel (role-based context menu, AddMembersDialog, confirm dialogs x5)
- TẠOMỚI: EditGroupInfoDialog (tristate PATCH, avatar change/remove)
- TẠOMỚI: useGroupActions.ts (5 mutation hooks: addMembers, removeMember, leaveGroup, changeRole, transferOwner)
- TẠOMỚI: useConvMembershipSubscription.ts (global, conv-added/conv-removed queues)
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

---

[W6-D4] fix: useProtectedObjectUrl add AbortController to cancel in-flight requests on unmount. docs: add auth-protected image pattern to knowledge. [2026-04-21]

---

[W6-D4] feat: file upload UI + attachment gallery + PDF card. Wire attachmentIds vào useSendMessage. [2026-04-21]
- Tạo: src/features/files/ (thư mục mới)
- Tạo: src/features/files/validateFiles.ts — validate MIME, size, max 5 ảnh / 1 PDF alone
- Tạo: src/features/files/useUploadFile.ts — AbortController per file, onUploadProgress, cancel/remove/clear, URL.revokeObjectURL cleanup on unmount
- Tạo: src/features/files/components/PendingAttachmentItem.tsx — preview thumbnail với progress bar + error overlay
- Tạo: src/features/files/components/AttachmentGallery.tsx — grid 1-2 cols, lightbox với keyboard nav (arrow/esc) + download
- Tạo: src/features/files/components/PdfCard.tsx — link card với tên file + size
- Sửa: src/types/message.ts — thêm AttachmentDto interface + attachments: AttachmentDto[] vào MessageDto
- Sửa: src/lib/stompClient.ts — StompSendPayload thêm attachmentIds?: string[]
- Sửa: src/features/messages/hooks.ts — useSendMessage nhận attachmentIds?: string[], optimistic msg init attachments: []
- Sửa: src/features/messages/components/MessageInput.tsx — enable Paperclip, hidden file input, drag-drop, pending attachments preview bar, guard uploading/no-content
- Sửa: src/features/messages/components/MessageItem.tsx — render AttachmentGallery/PdfCard trước text caption, Messenger-style (ảnh không có bubble bg), RetryButton pass attachmentIds: []
- Sửa: src/features/messages/useAckErrorSubscription.ts — add toast import + attachment error codes (MSG_ATTACHMENT_*)
- Build: npm run build pass, zero TS errors

[FE] feat(w5-d4): reconnect catch-up + reply UI [2026-04-20]
- Tạo: src/features/messages/catchUp.ts — REST catch-up sau reconnect, GET /messages?after=<ts>&limit=100, merge+dedup, fallback invalidate
- Sửa: src/features/messages/useConvSubscription.ts — wasDisconnectedRef track disconnect, gọi catchUpMissedMessages khi reconnect (không phải connect đầu)
- Sửa: src/types/message.ts — ReplyPreviewDto thêm deletedAt: string|null, contentPreview: string|null
- Tạo: src/features/messages/components/ReplyQuote.tsx — quote bubble hiện trong message
- Tạo: src/features/messages/components/ReplyPreviewBox.tsx — preview box trên MessageInput, Escape cancel
- Sửa: src/features/messages/components/MessageItem.tsx — thêm onReply prop, import+render ReplyQuote, handleReply gọi callback thật (block khi deletedAt/clientTempId)
- Sửa: src/features/messages/components/MessageActions.tsx — Reply button disable khi deletedAt || clientTempId
- Sửa: src/features/messages/components/MessagesList.tsx — nhận+forward onReply prop
- Sửa: src/features/messages/components/MessageInput.tsx — thêm replyToMessageId, onSent props; truyền replyToMessageId vào sendMessage()
- Sửa: src/features/messages/hooks.ts — useSendMessage nhận replyToMessageId?, truyền vào publishConversationMessage
- Sửa: src/lib/stompClient.ts — StompSendPayload thêm replyToMessageId?: string|null
- Sửa: src/pages/ConversationDetailPage.tsx — replyState scoped pattern (không useEffect setState), wire ReplyPreviewBox+MessagesList.onReply+MessageInput.replyToMessageId+onSent
- tsc -b --noEmit: 0 error | lint: 0 error

---

[FE] fix(w5-d5): hide tick for deleted + toast timeouts
- Cài: sonner (toast singleton, 1 package)
- Sửa: src/App.tsx — import + render <Toaster position="bottom-right" richColors /> trong BrowserRouter
- Sửa: src/features/messages/components/MessageItem.tsx — wrap status icon block trong {!isDeleted && (...)} — không render spinner/✓/AlertCircle khi message.deletedAt != null
- Sửa: src/features/messages/hooks.ts — import toast from sonner; gọi toast.error('Gửi thất bại, thử lại') trong SEND timeout callback
- Sửa: src/features/messages/useEditMessage.ts — import toast from sonner; gọi toast.error('Sửa thất bại, thử lại') trong EDIT timeout callback
- Sửa: src/features/messages/useDeleteMessage.ts — import toast from sonner; replace broken dynamic import workaround bằng toast.error('Xóa thất bại, thử lại') trong DELETE timeout callback; xóa console.error cũ
- tsc -b --noEmit: 0 error | lint: 0 error

[FE][W5-D3][2026-04-20] feat: delete message + Facebook-style hover actions
- Sửa: src/types/message.ts — thêm deletedAt/deletedBy/deleteStatus vào MessageDto; content đổi sang string|null; thêm DeleteAckMessage interface (minimal ACK payload cho DELETE)
- Tạo: src/features/messages/deleteTimerRegistry.ts — singleton Map<clientDeleteId, {timerId, messageId, convId}>, pattern giống editTimerRegistry
- Tạo: src/features/messages/useDeleteMessage.ts — window.confirm → publish /app/conv.{id}.delete → mark deleteStatus='deleting' → 10s timeout → revert + log; patchMessageById pattern
- Sửa: src/features/messages/useAckErrorSubscription.ts — thêm DELETE ACK case (patch deletedAt+deletedBy+content=null) + DELETE ERROR case (revert deleteStatus + log error code); import deleteTimerRegistry
- Sửa: src/features/messages/useConvSubscription.ts — thêm MESSAGE_DELETED handler (soft patch: content=null, deletedAt, deletedBy, idempotent)
- Tạo: src/features/messages/components/MessageActions.tsx — Facebook Messenger style hover bar; Reply stub, More menu (Copy/Edit/Delete); click-outside + Escape close; isOwn gate cho Edit/Delete
- Tạo: src/features/messages/components/DeletedMessagePlaceholder.tsx — gray italic bubble "Tin nhắn đã bị xóa", bg-gray-100/dark:bg-gray-800, opacity-70
- Sửa: src/features/messages/components/MessageItem.tsx — integrate MessageActions + DeletedMessagePlaceholder; isDeleted gate; isDeleting opacity; remove old standalone "Sửa" button; wire onDelete/onCopy/onEdit/onReply; "(đã chỉnh sửa)" badge guard deletedAt
- Sửa: src/stores/authStore.ts — clearAuth() gọi deleteTimerRegistry.clearAll()
- Sửa: src/features/messages/hooks.ts — thêm deletedAt:null/deletedBy:null vào optimisticMsg (required fields mới)
- tsc -b --noEmit: 0 error | lint: 0 error

[FE][W5-D2][2026-04-20] feat: edit message inline + wire STOMP (ADR-017)
- Sửa: src/types/message.ts — thêm AckEnvelope, ErrorEnvelope (unified ADR-017), EditStatus type. Giữ AckPayload/ErrorPayload deprecated.
- Tạo: src/features/messages/editTimerRegistry.ts — singleton Map<clientEditId, {timerId, messageId, convId}>, pattern giống timerRegistry
- Sửa: src/features/messages/hooks.ts — thêm patchMessageById (patch theo real id, khác patchMessageByTempId theo clientTempId)
- Tạo: src/features/messages/useEditMessage.ts — optimistic update + publish /app/conv.{id}.edit + 10s timeout timer
- Sửa: src/features/messages/useAckErrorSubscription.ts — unified ACK/ERROR handler với switch(operation): SEND giữ logic cũ, EDIT route sang editTimerRegistry + patchMessageById, MSG_NO_CHANGE silent revert
- Sửa: src/features/messages/useConvSubscription.ts — thêm MESSAGE_UPDATED handler với dedup theo editedAt (lexicographic ISO8601 compare)
- Sửa: src/features/messages/components/MessageItem.tsx — inline edit UI: canEdit (isOwn + no clientTempId + messageAgeMs < 290s), InlineEditArea (textarea autoFocus, Enter save, Escape cancel, error display), "(đã chỉnh sửa)" badge, hover Edit button
- Sửa: src/stores/authStore.ts — clearAuth() gọi editTimerRegistry.clearAll() thêm vào cạnh timerRegistry.clearAll()
- tsc -b --noEmit: 0 error | lint: 0 error

[FE] fix(w5-d1-race): stopTyping trước sendMessage trong MessageInput
- Sửa: src/features/messages/components/MessageInput.tsx — đổi thứ tự trong handleSend: onTypingStop?.() gọi trước sendMessage(trimmed) để tránh race condition ~200ms typing indicator còn hiển thị sau khi message đã gửi
- tsc -b --noEmit: 0 error | lint: 0 error

[FE][W5-D1][2026-04-20] feat: typing indicator — publish khi gõ, hiển thị khi người khác gõ
- Tạo: src/features/messages/useTypingIndicator.ts — subscribe /topic/conv.{id} filter TYPING_STARTED/STOPPED, skip self, auto-remove 5s, startTyping() debounce 2s + autoStop 3s, stopTyping() explicit, clear typingUsers khi DISCONNECTED/ERROR, cleanup timers khi unmount
- Tạo: src/features/messages/components/TypingIndicator.tsx — render null khi rỗng, "X đang gõ..." / "X, Y đang gõ..." / "N người đang gõ...", aria-live polite
- Sửa: src/features/messages/components/MessageInput.tsx — thêm onTypingStart/onTypingStop props, gọi startTyping trong onChange, stopTyping sau send + onBlur
- Sửa: src/pages/ConversationDetailPage.tsx — import useTypingIndicator + TypingIndicator, render <TypingIndicator> trên <MessageInput>, pass startTyping/stopTyping
- tsc -b --noEmit: 0 error | lint: 0 error (2 warnings đã fix — exhaustive-deps)

[FE][W4-D4-hotfix][2026-04-20] fix: useConvSubscription heuristic match never triggered (ADR-016)
- Sửa: src/features/messages/useConvSubscription.ts:30 — isLikelyMatchOptimistic: thay `!tempMsg.id.startsWith('temp-')` bằng `!tempMsg.clientTempId`
- Root cause: hooks.ts (Path B ADR-016) tạo optimistic msg với id=crypto.randomUUID() (UUID thuần), không có prefix "temp-". Check cũ không bao giờ pass → broadcast về trước ACK bị append thêm mới thay vì replace → ACK cũng replace → 2 bản real message trong cache.
- Fix: dùng clientTempId (optional field trong MessageDto) làm discriminator cho optimistic messages — đúng semantic hơn.
- tsc -b --noEmit: 0 error | lint: 0 error

[FE][W4-D4][2026-04-20] feat: useConvSubscription hook with dedupe + wire in ConversationDetailPage
- Tạo: src/features/messages/useConvSubscription.ts — subscribe /topic/conv.{id}, appendToCache với dedupe (pages.some m.id check), invalidate ['conversations'], re-subscribe qua onConnectionStateChange
- Sửa: src/pages/ConversationDetailPage.tsx — import + call useConvSubscription(id)
- Fix: frontend/tsconfig.json root — thêm "ignoreDeprecations": "6.0" (baseUrl deprecated TS6.0)
- build: 0 TS error | lint: 0 ESLint error

[FE][W4-D3][2026-04-20] feat: STOMP client singleton, connect/disconnect lifecycle, ConnectionStatus debug UI
- Tạo: src/lib/stompClient.ts — Client singleton, ConnectionState type, connectStomp/disconnectStomp/getStompClient, onConnectionStateChange listener set, exponential backoff MAX_RECONNECT=10, AUTH_TOKEN_EXPIRED→refresh, AUTH_REQUIRED→logout
- Sửa: src/services/authService.ts — thêm refresh() method (dùng bởi stompClient khi token expired)
- Sửa: src/App.tsx — wire STOMP lifecycle qua useEffect watch isAuthenticated (accessToken presence), import ConnectionStatus
- Tạo: src/components/ConnectionStatus.tsx — fixed bottom-right indicator, ẩn CONNECTED ở prod, luôn hiện ở DEV
- Tạo: frontend/.env — VITE_WS_URL=http://localhost:8080/ws
- build: 0 TS error | lint: 0 ESLint error

[FE][W4-D2][2026-04-20] feat: MessagesList (infinite scroll, auto-scroll), MessageItem (grouping, status), MessageInput (enabled, Enter send, auto-resize), wire ConversationDetailPage
- Tạo: src/features/messages/components/MessageItem.tsx — React.memo, isOwn/other bubble, hover timestamp, status icon (spinner/✓), reply preview, shouldShowAvatar grouping
- Tạo: src/features/messages/components/MessagesList.tsx — useInfiniteQuery flatten, bottomRef auto-scroll, IntersectionObserver infinite scroll + preserve scrollTop, MessagesSkeleton/Error/Empty states
- Sửa: src/features/messages/components/MessageInput.tsx — conversationId prop, useSendMessage nội bộ, auto-resize textarea, char counter 4500/5000, Enter send, remove disabled=true
- Sửa: src/features/messages/hooks.ts — optimistic sender lấy từ useAuthStore thay vì hardcode 'Bạn'
- Sửa: src/pages/ConversationDetailPage.tsx — replace MessagesAreaPlaceholder → MessagesList, update MessageInput props
- build: 0 TS error | lint: 0 ESLint error/warning

[FE][W4-D1][2026-04-19] feat: message types, API functions, useMessages (infinite query), useSendMessage (optimistic), frontend-knowledge.md consolidated
- Tạo: src/types/message.ts — MessageType (const obj), MessageSenderDto, ReplyPreviewDto, MessageDto, MessageListResponse, SendMessageRequest, OptimisticMessage
- Tạo: src/features/messages/api.ts — sendMessage(), getMessages() (cursor-based)
- Sửa: src/features/conversations/queryKeys.ts — thêm messageKeys factory { all: (convId) => ['messages', convId] }
- Tạo: src/features/messages/hooks.ts — useMessages (useInfiniteQuery, cursor-based), useSendMessage (optimistic: cancelQueries → snapshot → append → onError rollback → onSuccess replace-by-tempId → onSettled invalidate conversations)
- Consolidate: frontend-knowledge.md 294→265 lines (gộp pattern tương tự, xóa outdated notes)
- build: 0 TS error | lint: 0 ESLint error

[FE][W3-D4][2026-04-19] feat: ConversationDetailPage, ConversationHeader, MessagesAreaPlaceholder, MessageInput (disabled), ConversationInfoPanel
- Tạo: src/features/conversations/components/ConversationHeader.tsx — Back(mobile), Avatar, DisplayName, sub-text (@username | N thành viên), Info + More buttons
- Tạo: src/features/messages/components/MessagesAreaPlaceholder.tsx — placeholder cho tuần 4 MessagesList
- Tạo: src/features/messages/components/MessageInput.tsx — disabled=true tuần 3, onSend prop undefined, Paperclip + Send icons
- Tạo: src/features/conversations/components/ConversationInfoPanel.tsx — slide-in từ phải, member list với role badges
- Tạo: src/pages/ConversationDetailPage.tsx — 3-section vertical (header + messages + input), loading skeleton, 404 error state, info panel toggle
- Sửa: src/App.tsx — thay placeholder div bằng ConversationDetailPage tại route :id
- build: 0 TS error | lint: 0 ESLint error

[FE][W3-D3-fix][2026-04-19] fix: 429 RATE_LIMITED error state in CreateConversationDialog
- Sửa: src/features/conversations/components/CreateConversationDialog.tsx — thêm `error` state, try/catch trong handleSelectUser, reset error trong handleClose
- 429 RATE_LIMITED → inline error "Bạn đang tạo quá nhiều cuộc trò chuyện. Vui lòng thử lại sau."
- Các lỗi khác → "Không thể tạo cuộc trò chuyện. Vui lòng thử lại."
- Error hiển thị với role="alert" (accessible), text-red-600, reset khi dialog đóng
- build: 0 error | lint: 0 error

[FE][W3-D3][2026-04-19] feat: ConversationListItem, ConversationListSidebar, CreateConversationDialog, UserAvatar, utils helpers, wire into ConversationsLayout
- Tạo: src/features/conversations/utils.ts (getOtherMember, formatLastMessageTime)
- Tạo: src/components/UserAvatar.tsx — shared avatar component (img nếu có URL, else initial letter div)
- Tạo: src/features/conversations/components/ConversationListItem.tsx — React.memo, dùng ConversationSummaryDto
- Tạo: src/features/conversations/components/ConversationListSidebar.tsx — loading/empty/error/list states
- Tạo: src/features/conversations/components/CreateConversationDialog.tsx — modal, Esc handler, autoFocus, useUserSearch, useCreateConversation, 409 redirect UX
- Sửa: src/pages/ConversationsLayout.tsx — wire ConversationListSidebar + CreateConversationDialog, useState createDialogOpen
- Pitfall: react-hooks/set-state-in-effect — không dùng useEffect để reset query; reset trong handleClose() thay vào đó
- build: 0 TypeScript error | lint: 0 ESLint error

[FE][W3-D2-fix][2026-04-19] fix: 409 error field name (code→error), ConversationDto remove server-only fields, add getConversationDisplayName helper
- Sửa: src/features/conversations/api.ts — 409 catch dùng `err.response.data.error` (KHÔNG phải `.code`) khớp BE ErrorResponse record
- Sửa: src/types/conversation.ts — ConversationDto xóa 4 fields không có trong BE (displayName, displayAvatarUrl, unreadCount, mutedUntil); thêm createdBy: CreatedByDto | null; thêm CreatedByDto interface
- Tạo: src/types/api.ts — ApiErrorBody interface (error, message, timestamp, details?) làm common error shape
- Thêm: getConversationDisplayName(conv, currentUserId) helper trong conversation.ts — derive display name ở FE runtime
- build: 0 TypeScript error | lint: 0 ESLint error

[FE][W3-D2][2026-04-19] feat: conversation types, api functions, React Query hooks scaffold, useDebounce, queryKeys
- Tạo: src/types/conversation.ts (ConversationType, MemberRole, MemberDto, ConversationDto, ConversationSummaryDto, PageResponse, CreateConversationRequest, UserSearchDto)
- Tạo: src/features/conversations/api.ts (createConversation, listConversations, getConversation)
- Tạo: src/features/conversations/queryKeys.ts (conversationKeys factory, userKeys)
- Tạo: src/features/conversations/hooks.ts (useConversations, useConversation, useCreateConversation)
- Tạo: src/features/users/api.ts (searchUsers)
- Tạo: src/features/users/hooks.ts (useUserSearch với debounce)
- Tạo: src/hooks/useDebounce.ts
- Tạo: src/lib/queryClient.ts — extract từ main.tsx
- Sửa: src/main.tsx — import queryClient từ lib/queryClient
- Pitfall: TypeScript erasableSyntaxOnly không cho phép `enum` — dùng const object + type pattern
- build: 0 TypeScript error | lint: 0 ESLint error

---

## 2026-04-19 (W3D1) — ProtectedRoute wired + ConversationsLayout skeleton

### Xong
- `src/components/ProtectedRoute.tsx`: Refactor dùng `<Outlet />` (layout route pattern), thêm `isHydrated` spinner, lưu `location.state.from` khi redirect /login. W-C-4 RESOLVED.
- `src/pages/ConversationsLayout.tsx`: Tạo mới — layout 2 cột (sidebar w-80 + flex-1 main), mobile-responsive (ẩn/hiện sidebar/main theo :id param), avatar user initials.
- `src/pages/ConversationsIndexPage.tsx`: Tạo mới — empty state với icon + text "Chọn một cuộc trò chuyện".
- `src/App.tsx`: Thêm ProtectedRoute wrapper cho /conversations, nested routes (index + :id placeholder).
- `src/pages/LoginPage.tsx`: Redirect về `location.state.from?.pathname ?? '/conversations'` sau login. Khi đã auth → redirect /conversations thay vì /.
- `src/pages/HomePage.tsx`: Thêm Link "Vào Chat →" (bg-indigo-600) khi isAuthenticated.
- `npm run build`: 0 error — PASS.
- `npm run lint`: 0 error — PASS.

### Blocker / TODO Ngày tiếp
- Ngày 3: List conversations component (sidebar)
- Ngày 3: Create conversation dialog
- Ngày 4: Conversation detail (/conversations/:id)

---

## 2026-04-19 (W2D4 Phase B) — Firebase OAuth + Logout wire

### Xong
- `frontend/.env.local`: Thêm 3 VITE_FIREBASE_* env vars (placeholder, cần thay bằng config thật).
- `src/lib/firebase.ts`: Tạo mới — lazy init với `getApps().length === 0`, export `auth`, `googleProvider`, `signInWithPopup`.
- `src/types/auth.ts`: Thêm `OAuthResponse extends AuthResponse { isNewUser: boolean }`.
- `src/features/auth/api.ts`: Thêm `oauthApi({ firebaseIdToken })` và `logoutApi({ refreshToken })`.
- `src/features/auth/components/GoogleLoginButton.tsx`: Tạo mới — popup flow, silent cancel, onError callback, Google SVG logo hard-coded.
- `src/pages/LoginPage.tsx`: Tích hợp GoogleLoginButton sau form, divider "hoặc", onError handler.
- `src/pages/RegisterPage.tsx`: Tích hợp GoogleLoginButton sau form, divider "hoặc", onError handler.
- `src/pages/HomePage.tsx`: Wire handleLogout thật — logoutApi best-effort, clearAuth + navigate('/login') trong finally. Thêm ToastContainer.
- `npm run build`: 0 error — PASS.
- `npm run lint`: 0 error — PASS.

### Lưu ý
- Firebase KHÔNG crash khi env placeholder — chỉ fail lúc runtime khi user click Google button.
- `OAuthResponse` structural subtype của `authStore.AuthResponse` → TypeScript chấp nhận `setAuth(oauthResponse)` không cần cast.
- W-C-3 logout RESOLVED.

---

## 2026-04-19 (W2D3 Phase C) — Wire Login + Register với API thật

### Xong
- `src/types/auth.ts`: Tạo mới — `RegisterRequest`, `LoginRequest`, `UserDto`, `AuthResponse`, `ApiError` từ contract.
- `src/features/auth/api.ts`: Tạo mới — `loginApi()` và `registerApi()` gọi axios instance.
- `src/features/auth/utils/handleAuthError.ts`: Tạo mới — switch/case theo error codes: INVALID_CREDENTIALS → inline field error; EMAIL_TAKEN / USERNAME_TAKEN → inline; RATE_LIMITED / ACCOUNT_LOCKED → toast; VALIDATION_FAILED → fields loop; default → toast generic.
- `src/features/auth/schemas/registerSchema.ts`: Fix W-FE-1 — username regex từ `/^[a-zA-Z0-9_]+$/` → `/^[a-zA-Z_][a-zA-Z0-9_]{2,49}$/`. Bỏ `.min(3).max(50)` riêng.
- `src/pages/LoginPage.tsx`: Wire với `loginApi` + `setAuth` + `navigate('/')`. Bỏ setTimeout stub.
- `src/pages/RegisterPage.tsx`: Wire với `registerApi` + `setAuth` + `navigate('/')`. Bỏ confirmPassword trước khi gọi API bằng explicit object build.
- `src/pages/HomePage.tsx`: Thêm auth state display (fullName + username + logout stub).
- `src/components/ProtectedRoute.tsx`: Tạo mới — đọc `accessToken`, redirect /login nếu unauthenticated.
- `npm run build`: 0 error — PASS.
- `npm run lint`: 0 error — PASS.

### Dang do
- Logout button (stub, chờ Ngày 5).
- Google OAuth (chờ Ngày 4).

### Blocker
- Không có.

### Ghi chú kỹ thuật
- `AuthResponse` trong `types/auth.ts` vs `authStore.ts` có shape giống nhau (structural typing) — TypeScript accept không cần cast.
- Destructure `{ confirmPassword: _, ...rest }` bị ESLint lỗi no-unused-vars. Fix bằng explicit object build thay spread.

---

## 2026-04-19 (W2D2 Phase A) — W2-NEW: authService.init() + AppLoadingScreen

### Xong
- `src/services/authService.ts`: init() với rawAxios riêng (không interceptors). 3 case: no refreshToken → false; có accessToken → true; có refreshToken no accessToken → call /refresh.
- `src/components/AppLoadingScreen.tsx`: spinner Tailwind indigo-600, accessible (aria-label + role="img").
- `src/App.tsx`: thêm useEffect + isInitialized gate. Routes chỉ render sau khi init() hoàn tất. Dùng `void authService.init().finally(...)` để tránh lint floating promise warning.
- `frontend-knowledge.md`: thêm authService.init() pattern vào Pattern section, thêm 2 pitfall mới về rawAxios và accessToken reload.
- `npm run build`: 0 error, 0 warning — PASS.
- `npm run lint`: 0 error — PASS.

### Dang do
- BE chưa implement /api/auth/refresh → init() luôn rơi vào catch, clearAuth(), trả false. App vẫn chạy đúng.

### Blocker
- Không có. init() graceful khi BE chưa sẵn.

### Ghi chú kỹ thuật
- rawAxios dùng cùng baseURL với api.ts (VITE_API_BASE_URL || ''). Khi BE implement /refresh, init() sẽ tự hoạt động không cần sửa.
- `void` operator trước `authService.init().finally(...)` là đúng pattern để tránh ESLint `@typescript-eslint/no-floating-promises` (không viết `authService.init().finally(...).catch(() => {})` — verbose thừa).
- isInitialized = false chỉ là transient state (tồn tại < 1s nếu network bình thường). AppLoadingScreen blink nhanh, không ảnh hưởng UX.

---

## 2026-04-19 (Tuần 2, Ngày 1) — W-FE-2: tokenStorage migration

### Xong
- Audit codebase: `tokenStorage.ts`, `api.ts`, `authStore.ts` đã implement đúng pattern trước khi task bắt đầu (migration đã xảy ra ở Tuần 1, Ngày 4 nhưng chưa được verify chính thức).
- Verify `globalThis` hoàn toàn absent trong toàn bộ `src/` (grep kết quả rỗng).
- `npm run build`: 0 error, 0 warning — PASS.
- `npm run lint`: 0 error — PASS.
- Cập nhật `frontend-knowledge.md`: TODO Tuần 2 → implemented section, Auth Store pattern bỏ globalThis, thêm pitfall sync.

### Đang dở
- Login/Register chưa call API thật (task Tuần 2 tiếp theo)

### Blocker
- Không có

### Ghi chú kỹ thuật
- `tokenStorage.ts` không import bất kỳ module nào trong project — dependency graph sạch.
- `authStore.ts` onRehydrateStorage gọi `tokenStorage.setRefreshToken()` sau khi hydrate từ localStorage — đảm bảo interceptor có token ngay khi app load mà không cần user action.
- `api.ts` chỉ gọi `tokenStorage.setTokens()` trong refresh interceptor (không gọi authStore) — đúng pattern, tránh double-write race.

---

## Template

```
## YYYY-MM-DD (Tuần N, Ngày X) — <chủ đề>

### Xong
- <task>: <tóm tắt 1 câu> (commit <hash>)

### Đang dở
- <task>: <tình trạng>

### Blocker
- <vấn đề>: <chờ gì>

### Ghi chú kỹ thuật
- <phát hiện đáng nhớ>
```

---

## Entries

## 2026-04-19 (Tuần 1, Ngày 5) — HomePage health check UI

### Xong
- `src/pages/HomePage.tsx`: thêm backend health check với 3 state (loading/ok/error). Dùng discriminated union `HealthState` cho type safety. Gọi `api.get('/api/health')` qua Vite proxy `/api` → `localhost:8080`. Hiển thị spinner khi loading, service/status text khi ok, error message + hướng dẫn debug khi fail.
- `npm run build`: 0 TypeScript error — PASS

### Đang dở
- Login/Register chưa call API thật (Tuần 2)

### Blocker
- Không có

### Ghi chú kỹ thuật
- `baseURL` trong `api.ts` = `''` (empty string) khi không set `VITE_API_BASE_URL`. Path gọi phải là `/api/health` (đầy đủ prefix) để Vite proxy nhận và forward sang `localhost:8080`.
- Nếu `baseURL` là `/api` thì path chỉ cần `/health` — nhưng hiện tại không phải trường hợp này.

## 2026-04-19 (Tuần 1, Ngày 3) — Register page, Axios client, Zustand auth store

### Xong
- `src/features/auth/schemas/registerSchema.ts`: Zod schema đầy đủ (email, username regex, fullName, password chữ hoa+số, confirmPassword với .refine cross-field)
- `src/pages/RegisterPage.tsx`: form 5 field, toggle show/hide password + confirmPassword, RHF + zodResolver, loading spinner, link về /login, design tokens nhất quán với LoginPage
- `src/lib/api.ts`: axios singleton thay thế axios.ts cũ — refresh queue pattern (isRefreshing flag + failedQueue[]), phân biệt AUTH_TOKEN_EXPIRED (refresh + retry) vs AUTH_REQUIRED (clear + redirect)
- `src/stores/authStore.ts`: Zustand persist — refreshToken + user persist, accessToken KHÔNG persist (15 phút TTL), isHydrated flag, wire globalThis.__authStoreGetState để api.ts đọc không bị circular dep
- `src/hooks/useAuth.ts`: hook expose user, isAuthenticated, isHydrated, logout (stub — sẽ call API Tuần 2)
- `src/main.tsx`: bọc QueryClientProvider, import authStore sớm để wire globalThis trước api.ts
- Xóa `src/lib/axios.ts` (placeholder cũ)
- `npm run build`: 0 TypeScript error — PASS
- `npm run lint`: 0 error — PASS

### Đang dở
- login / register chưa call API thật (sẽ implement Tuần 2)
- authStore.setAuth chưa được gọi từ UI (chờ API sẵn sàng)

### Blocker
- Không có

### Ghi chú kỹ thuật
- Circular dep giữa api.ts ↔ authStore.ts: giải quyết bằng globalThis.__authStoreGetState — authStore tự wire khi module load, api.ts đọc qua global thay vì import trực tiếp
- Dùng axios.post (không phải api.post) cho /api/auth/refresh để tránh interceptor loop
- ESLint @typescript-eslint/no-unused-vars không chấp nhận prefix `_` nếu không có rule. Bỏ `get` hoàn toàn trong Zustand (set) => ({...}) thay vì dùng `_get`

## 2026-04-19 (Tuần 1, Ngày 2) — Login page UI tĩnh

### Xong
- `src/features/auth/schemas/loginSchema.ts`: Zod schema cho login (validate không để trống — theo contract login không validate format)
- `src/features/auth/components/`: thư mục rỗng tạo sẵn cho Ngày 3+
- `src/components/Toast.tsx`: custom Toast + ToastContainer component (fixed bottom-right, auto-dismiss 3s, slide animation)
- `src/hooks/useToast.ts`: hook manage toast queue
- `src/pages/LoginPage.tsx`: form đầy đủ với RHF + zodResolver, toggle show/hide password, loading spinner, design tokens indigo-600, error inline, link tới /register
- `npm run build`: 0 TypeScript error, 0 lint warning — PASS

### Đang dở
- Chưa call API thật (sẽ làm Ngày 3+)
- Chưa tạo RegisterPage UI

### Blocker
- Không có

### Ghi chú kỹ thuật
- Không có sonner/react-hot-toast trong package.json → tạo Toast custom component
- Login schema KHÔNG validate format (regex) để đồng nhất với contract: server không validate format login để tránh user enumeration
- `mode: 'onTouched'` cho RHF — validate sau khi blur, UX tốt hơn onChange

## 2026-04-19 (Tuần 1, Ngày 1) — Khởi tạo Vite + React + TypeScript project

### Xong
- Project scaffold: `npm create vite@latest frontend -- --template react-ts` trong `D:/CodeMonHoc/SE330/chat-app/`
- Dependencies: cài đủ react-router-dom@6, zustand, @tanstack/react-query, axios, react-hook-form, @hookform/resolvers, zod, lucide-react, date-fns, @stomp/stompjs, sockjs-client, firebase, tailwindcss v4, @tailwindcss/vite, @types/sockjs-client, @types/node
- TailwindCSS v4: cấu hình qua `@tailwindcss/vite` plugin, `@import "tailwindcss"` trong index.css — không cần config file
- Vite config: path alias `@/` → `src/`, proxy `/api` → `localhost:8080`, port 3000
- TypeScript alias: `baseUrl` + `paths` trong `tsconfig.app.json`, thêm `ignoreDeprecations: "6.0"` để tắt TS5101
- Routing: `App.tsx` với BrowserRouter + Routes cho `/`, `/login`, `/register`, wildcard `*`
- Placeholder pages: LoginPage, RegisterPage, HomePage đều render h1 + Tailwind classes
- `src/lib/axios.ts`: axios instance với baseURL từ VITE_API_URL
- `.env.local`: VITE_API_URL=/api
- Cấu trúc thư mục: pages/, components/, hooks/, stores/, services/, types/, lib/ đều tạo sẵn

### Đang dở
- Không có

### Blocker
- Không có

### Ghi chú kỹ thuật
- TypeScript 5.8+ coi `baseUrl` là deprecated, phải dùng `"ignoreDeprecations": "6.0"` (không phải `"5.0"`)
- TailwindCSS v4 không cần `tailwind.config.js` hay `postcss.config.js`
- `npm run build` và `npm run lint` đều pass sạch, 0 error
- Dev server khởi động tại `http://localhost:3000` trong ~295ms
