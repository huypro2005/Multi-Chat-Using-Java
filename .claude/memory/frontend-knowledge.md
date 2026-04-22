# Frontend Knowledge — Tri thức chắt lọc cho frontend-dev

> Chỉ ghi những gì có giá trị tái sử dụng. KHÔNG ghi nhật ký (cái đó ở `frontend-log.md`).
> Giới hạn: ~300 dòng. Ai được sửa: chỉ `frontend-dev`, hoặc `code-reviewer` khi chốt quyết định.

---

## Quyết định kiến trúc đã chốt

### State management
- **Zustand** cho client state (auth token, UI state, dark mode)
- **TanStack Query v5** cho server state (fetching, cache, pagination)
- KHÔNG dùng Redux

### Routing
- **React Router v6** — routes: `/`, `/login`, `/register`, `/conversations` (protected), wildcard → `/`
- **ProtectedRoute**: layout route dùng `<Outlet />`, KHÔNG dùng `children` prop
- **Redirect**: `<Navigate to="/login" state={{ from: location }} replace />` — login đọc `location.state.from?.pathname`
- **Nested structure**: `<Route element={<ProtectedRoute />}> <Route path="/conversations"> <Route index .../> <Route path=":id" .../> </Route> </Route>`

### API client
- **Axios** singleton tại `src/lib/api.ts`; baseURL fallback `/api`; Vite proxy `/api` → `http://localhost:8080`
- Prefix `/api` ghi trong path (vd: `api.post('/api/auth/login')`), KHÔNG trong baseURL

### WebSocket / STOMP
- Singleton tại `src/lib/stompClient.ts` — `reconnectDelay: 0` (tự manage backoff)
- SockJS URL = `http://` (KHÔNG `ws://`) — SockJS tự upgrade
- Backoff: 1s → 2s → 4s → 8s → 16s → 30s cap, MAX_RECONNECT = 10
- Auth: token từ `tokenStorage.getAccessToken()` → `connectHeaders: { Authorization: Bearer }`
- `AUTH_TOKEN_EXPIRED` → `authService.refresh()` → reconnect. `AUTH_REQUIRED` → redirect `/login`
- `webSocketFactory` phải là factory fn (không phải instance) — Client gọi lại mỗi reconnect
- Dynamic import `authService` để tránh circular dep; debug log chỉ khi `import.meta.env.DEV`
- Wire lifecycle trong App.tsx: `useEffect` watch `!!authStore.accessToken` → connect/disconnect

### Styling & Bundler
- **TailwindCSS v4** via `@tailwindcss/vite` (KHÔNG PostCSS config). Import: `@import "tailwindcss";`
- **Vite v8** + `@vitejs/plugin-react`; path alias `@/` → `src/` (cả `vite.config.ts` + `tsconfig.app.json`)

---

## Design tokens (chốt toàn project)

- Primary: `indigo-600` (hover: `indigo-700`, disabled: `indigo-400`)
- Input border: `border-gray-300`, focus: `ring-2 ring-indigo-500 border-transparent`, error: `border-red-500`
- Border radius: `rounded-lg` inputs/buttons, `rounded-xl` card/modal
- Button padding: `py-2.5 px-4 font-medium text-sm`
- Card: `bg-white rounded-xl shadow-sm border border-gray-200 p-8`; Page bg: `bg-gray-50`
- Error text: `text-red-500 text-sm`

---

## Cấu trúc `src/`

```
src/
├── pages/          # Route-level components
├── components/     # Shared reusable UI (Toast, etc.)
├── features/
│   ├── auth/       # schemas/, components/
│   ├── conversations/ # components/, hooks.ts, api.ts, queryKeys.ts
│   └── messages/   # components/, hooks.ts, api.ts, useConvSubscription.ts
├── hooks/          # Shared hooks (useDebounce, useToast)
├── stores/         # Zustand stores
├── lib/            # api.ts, queryClient.ts, tokenStorage.ts, stompClient.ts, firebase.ts
├── types/          # auth.ts, conversation.ts, message.ts
└── utils/
```

---

## Auth patterns

### Axios Refresh Queue
- Flag `isRefreshing` + `failedQueue[]` — tránh race condition khi nhiều 401 cùng lúc
- `processQueue(error, token)` → resolve/reject toàn bộ queue sau refresh done
- Dùng `axios.create()` riêng cho `/refresh` (KHÔNG `api.post`) — tránh interceptor loop
- `AUTH_TOKEN_EXPIRED` → refresh + retry; `AUTH_REQUIRED` → clear + redirect

### Auth Store + tokenStorage
- Persist: `refreshToken` + `user` (KHÔNG persist `accessToken` — TTL 15 phút)
- `isHydrated` flag: tránh flash redirect trước khi store load từ localStorage
- `onRehydrateStorage`: gọi `tokenStorage.setRefreshToken()` để interceptor có token ngay
- Sync 2 chiều bắt buộc: `tokenStorage.setTokens()` cùng lúc với Zustand `set()`

### authService.init()
- Chạy khi app mount, trước khi routes render; dùng `rawAxios` (axios.create() riêng)
- Refresh fail → `clearAuth()`, trả `{ isAuthenticated: false }` (không throw)
- `isInitialized` gate trong App.tsx tránh flash redirect

### Error handling — auth API
`src/features/auth/utils/handleAuthError.ts`:
- `setFormError(field, msg)` cho lỗi user fix (INVALID_CREDENTIALS, EMAIL_TAKEN, USERNAME_TAKEN)
- `showToast(msg, type)` cho lỗi hệ thống (RATE_LIMITED, ACCOUNT_DISABLED, unknown)

### Firebase OAuth
- Lazy init: check `getApps().length === 0` trước `initializeApp`
- Flow: `signInWithPopup` → `getIdToken()` → `oauthApi({ firebaseIdToken })` → `setAuth()` → navigate
- Catch `auth/popup-closed-by-user` / `auth/cancelled-popup-request` → return silently

### Logout
Best-effort logout API, luôn `clearAuth()` + `navigate('/login')` trong `finally`.

---

## React Query patterns

### Query key factories
```ts
export const messageKeys = { all: (convId: string) => ['messages', convId] as const }
export const conversationKeys = {
  all: ['conversations'] as const,
  lists: () => [...conversationKeys.all, 'list'] as const,
  detail: (id) => [...conversationKeys.all, 'detail', id] as const,
}
```

### useInfiniteQuery — cursor-based messages
- `initialPageParam: undefined as string | undefined`
- `getNextPageParam`: `lastPage.hasMore ? lastPage.nextCursor ?? undefined : undefined`
- `nextCursor` là ISO8601 string (createdAt của message cũ nhất trong page)
- items sorted ASC, scroll-up để load trang cũ hơn

### Optimistic update — useSendMessage
1. `cancelQueries` → snapshot → `tempId = temp-${Date.now()}-${random}` → append optimisticMsg
2. `onError`: restore snapshot; `onSuccess`: replace item `id === tempId` bằng `realMsg`
3. `onSettled`: `invalidateQueries(['conversations'])` để sidebar refresh `lastMessageAt`

### Pagination format
BE trả `{ content, page, size, totalElements, totalPages }` — KHÔNG phải `{ items, total }` (conversations list).

### 409 CONV_ONE_ON_ONE_EXISTS
Return `{ existingConversationId }` thay vì throw — caller navigate sang conversation đó.

---

## Layout patterns

### MessagesList scroll
- `bottomRef` + `isAtBottom` (threshold 80px): scroll to bottom chỉ khi user đang ở cuối
- IntersectionObserver `topSentinelRef`: preserve scroll — lưu `prevScrollHeight`, cộng delta sau `fetchNextPage()`
- `void fetchNextPage().then(...)` để tránh lint floating-promise

### MessageItem grouping
- `shouldShowAvatar(messages, index)`: hiện khi index=0, sender khác, hoặc gap >60_000ms
- Own bubble: `bg-indigo-600 text-white rounded-2xl rounded-br-sm`, justify-end
- Other bubble: `bg-white border border-gray-200 rounded-2xl rounded-bl-sm`, justify-start
- Timestamp hover: `opacity-0 group-hover:opacity-100 transition-opacity` (parent cần `group` class)
- `React.memo` bắt buộc cho MessageItem (list 100+ items)

### MessageInput
- Props: `conversationId: string`, `disabled?: boolean`, `onStartTyping`, `onStopTyping`
- Auto-resize: `el.style.height = 'auto'; el.style.height = Math.min(el.scrollHeight, 5*24) + 'px'`
- Character counter >4500 (yellow), >5000 (red). Block send khi over limit

### MessageActions hover
- Parent bubble cần `group` class; MessageActions: `opacity-0 group-hover:opacity-100`
- Click-outside: `useEffect` + `menuRef` + `document.addEventListener('mousedown', handler)`
- Menu position: `style={{ [isOwn ? 'right' : 'left']: 0, bottom: '100%' }}`
- Props: `message, isOwn, canEdit, onEdit, onDelete, onReply, onCopy` — stateless

---

## WebSocket / Realtime patterns

### useConvSubscription (per-conversation)
- Subscribe ngay nếu `client?.connected`, else chờ `onConnectionStateChange` về CONNECTED
- Cleanup: `sub.unsubscribe()` + unsub state listener trong useEffect cleanup
- Dedupe: `old.pages.some(p => p.items.some(m => m.id === newMsg.id))` trước khi append
- Append vào `pages[lastIdx].items`; invalidate `['conversations']` khi nhận message mới
- `appendToCache` nhận `QueryClient` (import type) làm param
- **Reconnect catch-up**: `wasDisconnectedRef` (useRef, không state) track disconnect — chỉ call `catchUpMissedMessages` khi `wasDisconnectedRef.current === true` (không gọi lần connect đầu)
- `catchUpMissedMessages` (features/messages/catchUp.ts): lấy newestTs từ cache → `GET /messages?after=<ts>&limit=100` → merge; fallback `invalidateQueries` nếu REST fail

### useTypingIndicator
- Subscribe riêng `/topic/conv.{id}` — không conflict với useConvSubscription
- Filter TYPING_STARTED / TYPING_STOPPED, ignore MESSAGE_CREATED; skip self
- `startTyping()`: debounce 2s + autoStop timer 3s
- Auto-remove user sau 5s phòng STOPPED miss — `autoRemoveTimersRef` Map<userId, timerId>
- Capture `autoRemoveTimersRef.current` vào local var trước return (tránh exhaustive-deps lint)
- Payload chỉ có `userId`, `username`, `conversationId` — KHÔNG có `fullName`

### useAckErrorSubscription — Unified ACK/ERROR handler (ADR-017)
- `switch(envelope.operation)` để route: SEND | EDIT | DELETE
- Shape: `{operation, clientId: UUID, message|error, code}`
- EDIT: `editTimerRegistry.get(clientId)` undefined → tab-awareness → ignore nếu tab này không phát
- DELETE: `deleteTimerRegistry.get(clientId)` — same tab-awareness pattern
- `MSG_NO_CHANGE` → silent revert, không toast

### timerRegistry pattern (module-level singleton)
Ba registry: `timerRegistry` (send), `editTimerRegistry` (edit), `deleteTimerRegistry` (delete).
Cùng structure: `Map<clientId, {timerId, messageId, convId}>`. `clearAll()` bắt buộc gọi trong `authStore.clearAuth()`.
Tab-awareness: registry undefined nếu tab này không phát operation → ignore ACK/ERROR từ tab khác.

### MESSAGE_UPDATED broadcast handler
Trong `useConvSubscription`, case `MESSAGE_UPDATED`:
Dedup: `if (existing.editedAt && existing.editedAt >= updated.editedAt) return page` (ISO8601 lexicographic compare).

### Inline edit pattern
- `canEdit` = `isOwn && !clientTempId && !failureCode && messageAgeMs < 290_000`
- `messageAgeMs` dùng `useMemo([message.createdAt])` — không gọi `Date.now()` trong render
- KHÔNG patch `content`/`editedAt` lúc optimistic — chỉ patch khi ACK về từ server
- `patchMessageById` dùng real `id`; `patchMessageByTempId` dùng `clientTempId`

### Soft delete — MessageDto fields
- `deletedAt: string | null`, `deletedBy: string | null`, `content: string | null`
- `deleteStatus?: 'deleting'` — client-only, set khi chờ ACK (disable actions, không ẩn bubble)
- `DeleteAckMessage`: chỉ `{id, conversationId, deletedAt, deletedBy}` — không có `content`
- Optimistic message trong `useSendMessage` cần khởi tạo `deletedAt: null, deletedBy: null`

---

## Pitfalls đã gặp (đừng lặp lại)

- **TypeScript 5.8+ `baseUrl` deprecated**: thêm `"ignoreDeprecations": "6.0"` vào `tsconfig.app.json`
- **TailwindCSS v4**: KHÔNG cần `tailwind.config.js` / `postcss.config.js`
- **accessToken không persist**: mỗi reload = null. `authService.init()` restore session
- **rawAxios trong init()**: KHÔNG dùng `api.ts` instance (interceptor loop)
- **confirmPassword không gửi BE**: dùng explicit object, không spread `...data`
- **Zustand `set` signature**: chỉ khai báo param thực sự dùng (`_get` vẫn bị ESLint no-unused-vars)
- **`react-hooks/set-state-in-effect`**: reset state trong event handler, KHÔNG trong useEffect
- **Username regex**: `/^[a-zA-Z_][a-zA-Z0-9_]{2,49}$/` — khớp BE (3-50 ký tự, không bắt đầu số)
- **Optimistic message discriminator**: KHÔNG dùng `id.startsWith('temp-')` — optimistic id là UUID thuần (crypto.randomUUID()). Dùng `clientTempId` field (present ⟺ message là optimistic)
- **Optimistic edit không patch content/editedAt**: không có snapshot để revert khi lỗi. Chỉ update cache khi ACK về
- **`react-hooks/set-state-in-effect`**: để reset state khi prop thay đổi, lưu state kèm `{ value, scopeId }` rồi derive: `const effective = state?.scopeId === currentId ? state.value : null` — không cần useEffect
- **TypeScript optional chaining + narrowing**: `replyState?.convId === id ? replyState.msg : null` bị TS lỗi vì không narrow. Dùng `replyState !== null && replyState.convId === id ? replyState.msg : null`

---

## W7-D4 SystemMessage Patterns

### SystemMessage component — centered inline (no pill bg)
- Container: `flex justify-center my-1 px-4` — khớp §3e.1 DOM contract.
- Span: `text-xs italic text-gray-500 dark:text-gray-400 select-none` — KHÔNG có `rounded-full bg-gray-100` (pill).
- `role="status" aria-label={text}` cho accessibility.
- `React.memo` wrap bắt buộc (MessagesList render nhiều item).

### i18n pattern — Bạn/bạn substitution
```ts
const isActor = !!meta.actorId && meta.actorId === currentUserId
const isTarget = !!meta.targetId && meta.targetId === currentUserId
const actorName = isActor ? 'Bạn' : (meta.actorName ?? 'Ai đó')
const targetName = isTarget ? 'bạn' : (meta.targetName ?? '')
```
- Guard `!!meta.actorId &&` trước so sánh tránh false positive khi actorId undefined.
- targetName falsy → warning + fallback string (không crash).

### Conditional render TEXT vs SYSTEM trong MessageItem
- `MessageItem` là memo wrapper: dispatch SYSTEM trước khi vào `MessageItemInner` (tránh hook order issue — inner component có nhiều hooks).
- `MessagesList` cũng dispatch SYSTEM trước `MessageItem` để skip shouldShowAvatar logic.
- Double dispatch là intentional defense-in-depth.

### systemEventType copy mapping (§3e.1 vi-VN, V1)
| type | template |
|------|----------|
| GROUP_CREATED | `{actor} đã tạo nhóm` |
| MEMBER_ADDED | `{actor} đã thêm {target} vào nhóm` |
| MEMBER_REMOVED | `{actor} đã xóa {target} khỏi nhóm` |
| MEMBER_LEFT | `{actor} đã rời nhóm` |
| ROLE_PROMOTED | `{actor} đã đặt {target} làm quản trị viên` |
| ROLE_DEMOTED | `{actor} đã gỡ quyền quản trị của {target}` |
| OWNER_TRANSFERRED (auto) | `{actor} đã rời nhóm và chuyển quyền trưởng nhóm cho {target}` |
| OWNER_TRANSFERRED (explicit) | `{actor} đã chuyển quyền trưởng nhóm cho {target}` |
| GROUP_RENAMED | `{actor} đã đổi tên nhóm từ "{old}" thành "{new}"` |
| default/unknown | `(sự kiện hệ thống)` |

---

## W7-D3 Group UI Patterns

### CreateGroupDialog — async avatar upload + user search
- Upload avatar ngay khi chọn file (không chờ submit) qua `POST /api/files/upload` multipart.
- Preview qua `URL.createObjectURL` (blob URL tức thì, trước khi upload xong).
- `avatarFileId` state: null cho đến khi upload hoàn tất → submit button không block vì đã có `avatarUploading` flag.
- Revoke blob URL: trong `handleRemoveAvatar`, `handleClose`, và `useEffect` cleanup (unmount).
- `blobUrlRef = useRef<string | null>(null)` để capture current blob URL trong cleanup return.

### GroupInfoPanel — role-based context menu authorization matrix
```
Current = OWNER, target = MEMBER: promote → ADMIN, kick
Current = OWNER, target = ADMIN: demote → MEMBER, kick, transfer-owner
Current = OWNER, target = OWNER (self): no menu
Current = ADMIN, target = MEMBER: kick only
Current = ADMIN, target = ADMIN/OWNER: no menu
Current = MEMBER: no menu button rendered
```
Render `showMenuBtn` condition: `!isSelf && (OWNER over non-OWNER || ADMIN over MEMBER)`.

### useConvMembershipSubscription — global mount pattern
- Mount 1 lần tại `GlobalSubscriptions` component trong `App.tsx`.
- Subscribe `/user/queue/conv-added` và `/user/queue/conv-removed`.
- `conv-added`: add to conversations cache (dedupe), toast.
- `conv-removed`: remove from cache (idempotent), navigate '/' if currently viewing, toast nếu KICKED.
- Re-subscribe on STOMP reconnect (same pattern as useConvSubscription).

### Tristate PATCH pattern (EditGroupInfoDialog)
```ts
// Only send fields the user actually changed:
const body: Record<string, unknown> = {}
if (name !== currentName) body.name = name
if (avatarMode === 'changed') body.avatarFileId = newFileId
if (avatarMode === 'removed') body.avatarFileId = null
// avatarMode === 'unchanged': không include field → BE không đổi
if (Object.keys(body).length === 0) { handleClose(); return } // no-op
```

### Idempotent broadcast handling — MEMBER_REMOVED + conv-removed
- `/topic/conv.{id}` MEMBER_REMOVED với `isSelf === true` → **bỏ qua**, không xử lý.
- `/user/queue/conv-removed` xử lý tất cả trường hợp user bị remove (bao gồm cả KICKED).
- Lý do: tránh duplicate navigate/toast khi user nhận cả 2 frames.
- Idempotent remove: `filter(c.id !== convId)` — nếu đã remove → no-op.

### navigateRef pattern (hooks dùng useNavigate)
```ts
const navigate = useNavigate()
const navigateRef = useRef(navigate)
useEffect(() => { navigateRef.current = navigate }) // update in effect, NOT during render
// Dùng navigateRef.current trong callback để tránh stale closure
```
KHÔNG viết `navigateRef.current = navigate` trực tiếp ngoài effect (react-hooks/refs lint error).

---

## Misc patterns

### TypeScript enum → const object (erasableSyntaxOnly)
```ts
export const FooType = { A: 'A', B: 'B' } as const
export type FooType = (typeof FooType)[keyof typeof FooType]
```

### Toast
`src/components/Toast.tsx` + `src/hooks/useToast.ts` — custom, không dùng thư viện ngoài.
Fixed bottom-right, auto-dismiss 3s, slide animation.

### Form — RHF + Zod
`resolver: zodResolver(schema), mode: 'onTouched'`; error inline với `role="alert"`.

### Dialog pattern
Controlled `open` prop, Esc via `useEffect`, `autoFocus` input, `handleClose()` gộp reset + `onClose()`.

### ConversationListItem
`displayName` / `displayAvatarUrl` server-computed trong `ConversationSummaryDto`. `React.memo`.

---

## File upload patterns (W6-D4)

### useUploadFile — AbortController + onUploadProgress
- `AbortController` thay `CancelToken` (axios v1+ deprecated CancelToken). `signal: controller.signal`.
- `onUploadProgress: (e) => pct = Math.round(e.loaded / e.total * 100)` — track per file.
- Cancel = `controller.abort()` → axios throws with `axios.isCancel(err)` → filter from list silently.
- Revoke blob URLs pattern: `URL.revokeObjectURL(item.previewUrl)` trong `cancel`, `remove`, `clear` + useEffect cleanup khi unmount (dùng `useRef` để capture current state trong cleanup).

### AttachmentGallery — Messenger-style (no bubble background for images)
- Ảnh hiển thị KHÔNG có bubble background — chỉ render `<AttachmentGallery>` (grid thumbnail).
- Text caption (nếu có) hiển thị PHÍA DƯỚI ảnh, trong bubble riêng.
- Nếu chỉ có text: bubble như cũ. Nếu chỉ có ảnh: không có bubble.
- Non-image docs/archives render qua `<FileCard>` (generic card). `PdfCard` đã xóa.

### validateFiles (v0.9.5)
- Group A: all-images (tối đa 5 total, có thể chọn nhiều lần).
- Group B: exactly 1 non-image alone (PDF, Word, Excel, PPT, TXT, ZIP, 7z).
- Check mixing: images + non-image = rejected; 2+ non-image = rejected.
- Signature: `validateFiles(newFiles, count, pendingMimes?)` — pass `pendingMimes` để check mixing.
- Caller phải filter `status !== 'error'` trước khi tính count và extract mimes.

### iconType-based rendering (W6-D4-extend)
- FE đọc `attachment.iconType` (server-computed) để chọn icon — KHÔNG hard-code MIME → icon map ở render layer.
- Fallback: nếu `iconType` undefined (optimistic msg chưa có server confirm) → check `mime.startsWith('image/')`.
- `clientIconEmoji(mime)` trong `PendingAttachmentItem`: detect icon client-side TRƯỚC khi upload xong (pending state). Tách biệt với server iconType.
- `FileCard.tsx` generic: nhận `AttachmentDto`, hiển thị icon + size + download. Replace `PdfCard.tsx`.
- `AttachmentDto.iconType` field non-optional (required) trong type — mọi mock/test phải có.

### Drag-drop pattern trong MessageInput
- `onDragLeave`: chỉ clear `isDragging` khi `!e.currentTarget.contains(e.relatedTarget as Node)` — tránh flicker khi rời child element.
- `isDragging` ring feedback: `ring-2 ring-indigo-400 bg-indigo-50/30` trên container.

---

## W7-D5 Read Receipt Patterns

### ReadTicks V1 approximation
- `member.lastReadMessageId !== null` → coi là đã đọc conv (V1 approx, đủ cho MVP).
- Exact check cần compare `createdAt` của `lastReadMessageId` vs message — phức tạp, để V2.
- Nếu `lastReadMessageId` không có trong messages cache (old message) → treat as "unknown, assume read" (§3.13 step 3).
- ReadTicks chỉ render cho `message.sender?.id === currentUserId` AND `status !== 'sending|failed'`.

### handleReadUpdated — setQueryData in-place (không invalidate)
- Pattern: `queryClient.setQueryData(conversationKeys.detail(id), old => patch member)`.
- Race-safe: nếu member không tồn tại trong cache (MEMBER_REMOVED trước) → return old silently.
- Idempotent: `if (existing.lastReadMessageId === payload.lastReadMessageId) return old` (self-echo dedup).
- KHÔNG cần `invalidateQueries` — setQueryData trigger re-render ReadTicks đủ rồi.

### useAutoMarkRead debounce 500ms
- File: `features/messages/hooks/useAutoMarkRead.ts`.
- Dùng `getStompClient().publish({ destination: /app/conv.{id}.read, body: {messageId} })`.
- `lastSentRef` dedupe — không gửi lại cùng messageId.
- Reset `lastSentRef` khi `convId` đổi (useEffect deps [convId]).
- Silent fail khi STOMP not connected (không throw, không toast — read receipt không cần ACK).
- Gọi từ `MessagesList` dùng `lastMessageId` = last non-system, non-optimistic message id.

## Thư viện đã chọn

| Library | Ghi chú |
|---------|---------|
| react 19+ | Latest stable |
| react-router-dom v6 | Không phải v7 |
| zustand | Client state nhẹ |
| @tanstack/react-query v5 | Server state, cache, infinite query |
| axios | HTTP + interceptors |
| react-hook-form + @hookform/resolvers + zod | Form + validation |
| lucide-react | Icons tree-shakeable |
| date-fns | Thay moment |
| @stomp/stompjs + sockjs-client | STOMP over WebSocket |
| firebase | Google OAuth |
| tailwindcss v4 + @tailwindcss/vite | CSS utility-first |

---

## Pattern: Auth-protected image loading (W6-D4 fix)

### Bug context
`<img src='/api/files/{id}/thumb'>` không gửi `Authorization` header vì browser native fetch không qua axios interceptor. BE yêu cầu JWT → trả 401 → ảnh broken.

### Solution: useProtectedObjectUrl

File: `src/features/files/hooks/useProtectedObjectUrl.ts`

Fetch file qua `api.get({ responseType: 'blob', signal: controller.signal })` (có Bearer token) → `URL.createObjectURL` → return local blob URL cho `<img src>`.

### Usage (scope thu hẹp sau ADR-021)
- `AttachmentGallery.tsx`: thumbnail grid + lightbox full-size (private message images)
- `FileCard.tsx`: generic private file download
- **KHÔNG dùng cho avatar** (xem "Pattern: Avatar native img public URL" bên dưới)

### Critical implementation rules

1. **AbortController bắt buộc** — cancel in-flight request khi unmount hoặc path đổi:
   ```ts
   const controller = new AbortController()
   api.get(path, { responseType: 'blob', signal: controller.signal })
   return () => { controller.abort(); URL.revokeObjectURL(currentUrl) }
   ```

2. **URL.revokeObjectURL trong cleanup return** — tránh memory leak:
   ```ts
   return () => {
     controller.abort()
     if (currentUrl) URL.revokeObjectURL(currentUrl)
   }
   ```

3. **Dependency `[path]`** — re-fetch khi attachment id thay đổi.

4. **Ignore abort error** — không setObjectUrl(null) khi err.name === 'CanceledError' (abort là expected, không phải lỗi thật).

5. **Reset khi path = null** — `setObjectUrl(null)` khi path falsy để clear state.

### Anti-patterns cấm
- `<img src>` trỏ thẳng `/api/files/{id}` không qua hook → 401
- Token trong query param `?token=xxx` → leak trong server logs + Referer header
- `CancelToken` (deprecated axios v0.x) → dùng `AbortController` thay

### V2 alternatives
- Short-lived signed URL (HMAC + 15min expiry): BE generate, FE dùng trực tiếp, browser cache native. Tốt hơn về performance khi scale.
- Service Worker: intercept fetch image, inject auth header. Phức tạp hơn.

V1 blob URL đơn giản, secure, đủ cho demo/MVP.

### Regression test
- Upload ảnh → thumbnail hiện trong bubble (không broken icon)
- Mở lightbox → full-size load
- Refresh page → ảnh vẫn load (token restore qua authService.init)
- Scroll nhanh qua 20+ ảnh → không memory leak (DevTools Memory tab flat)

---

## Pattern: Avatar native img public URL (ADR-021, W7-D4-fix)

**Quyết định**: Avatars là public files (không cần auth). `<img src>` native load trực tiếp — không cần `useProtectedObjectUrl`.

### Constants (hardcode FE, khớp migration V11 seed)
```ts
const DEFAULT_USER_AVATAR  = '/api/files/00000000-0000-0000-0000-000000000001/public'
const DEFAULT_GROUP_AVATAR = '/api/files/00000000-0000-0000-0000-000000000002/public'
```

### Approach: div fallback + img overlay
```tsx
<div className="relative" style={{ width: size, height: size }}>
  {/* Initial letter (always visible underneath — fallback khi img lỗi) */}
  <div className="absolute inset-0 rounded-full bg-indigo-100 ...">{initial}</div>
  {/* Native img — public URL, no auth */}
  <img src={src} className="absolute inset-0 rounded-full object-cover"
    onError={(e) => { e.currentTarget.style.display = 'none' }}
  />
</div>
```

- `onError` hide img → div lộ ra phía dưới (graceful fallback)
- KHÔNG dùng `useProtectedObjectUrl` cho avatar

### Avatar upload — luôn dùng `?public=true`
```ts
api.post('/api/files/upload?public=true', formData, ...)
```
Avatar flows: CreateGroupDialog, EditGroupInfoDialog.
Message attachment flows: KHÔNG pass param (mặc định private).

### Khi nào dùng gì
| Context | Hook/Approach |
|---------|---------------|
| Avatar user/group | Native `<img src=publicUrl>` (ADR-021) |
| Message attachment image | `useProtectedObjectUrl` (private, auth required) |
| Generic file download | `useProtectedObjectUrl` trong FileCard |

### EditGroupInfoDialog prop thay đổi (ADR-021)
- Old: `currentAvatarFileId: string | null` → BE fetch via protected URL
- New: `currentAvatarUrl: string | null` → public URL, native img trực tiếp
- GroupInfoPanel pass: `currentAvatarUrl={conv.avatarUrl}` (không cần extractFileId)
