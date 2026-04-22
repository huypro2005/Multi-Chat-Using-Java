# Frontend Knowledge — Tri thức chắt lọc cho frontend-dev

> Chỉ ghi những gì có giá trị tái sử dụng. KHÔNG ghi nhật ký (cái đó ở `frontend-log.md`).
> Giới hạn: ~400 dòng. Ai được sửa: chỉ `frontend-dev`, hoặc `code-reviewer` khi chốt quyết định.

---

## Quyết định kiến trúc đã chốt

### Stack
- **Zustand** client state (auth token, UI, dark mode); **TanStack Query v5** server state (fetching, cache, pagination). KHÔNG Redux.
- **React Router v6** (không v7) — routes: `/`, `/login`, `/register`, `/conversations` (protected), wildcard → `/`.
  - **ProtectedRoute**: layout route `<Outlet />`, KHÔNG `children` prop.
  - **Redirect**: `<Navigate to="/login" state={{ from: location }} replace />` — login đọc `location.state.from?.pathname`.
  - **Nested**: `<Route element={<ProtectedRoute />}><Route path="/conversations"><Route index/><Route path=":id"/></Route></Route>`.
- **Axios** singleton `src/lib/api.ts`; baseURL fallback `/api`; Vite proxy `/api` → `localhost:8080`. Prefix `/api` trong path (vd `api.post('/api/auth/login')`), KHÔNG trong baseURL.
- **TailwindCSS v4** via `@tailwindcss/vite` (KHÔNG PostCSS config). Import `@import "tailwindcss";`.
- **Vite** + `@vitejs/plugin-react`; path alias `@/` → `src/` (cả `vite.config.ts` + `tsconfig.app.json`).

### WebSocket / STOMP
- Singleton `src/lib/stompClient.ts` — `reconnectDelay: 0` (tự manage backoff 1s→30s cap, MAX_RECONNECT=10).
- SockJS URL = `http://` (KHÔNG `ws://`) — SockJS tự upgrade.
- Auth: `tokenStorage.getAccessToken()` → `connectHeaders: { Authorization: Bearer }`.
- `AUTH_TOKEN_EXPIRED` → `authService.refresh()` → reconnect. `AUTH_REQUIRED` → redirect `/login`.
- `webSocketFactory` là factory fn (không instance) — Client gọi lại mỗi reconnect.
- Dynamic import `authService` để tránh circular dep; debug log chỉ `import.meta.env.DEV`.
- Wire lifecycle trong App.tsx: `useEffect` watch `!!authStore.accessToken` → connect/disconnect.
- sockjs-client global shim: `main.tsx` runtime `window.global = window` + `vite.config.ts` `define: { global: 'globalThis' }` (belt+suspenders).

---

## Design tokens

- Primary: `indigo-600` (hover `indigo-700`, disabled `indigo-400`).
- Input: `border-gray-300`, focus `ring-2 ring-indigo-500 border-transparent`, error `border-red-500`.
- Border radius: `rounded-lg` inputs/buttons, `rounded-xl` card/modal.
- Button: `py-2.5 px-4 font-medium text-sm`.
- Card: `bg-white rounded-xl shadow-sm border border-gray-200 p-8`. Page bg `bg-gray-50`.
- Error text: `text-red-500 text-sm`.

---

## Cấu trúc `src/`

`pages/`, `components/` (shared), `features/{auth,conversations,messages,files}/`, `hooks/`, `stores/`, `lib/` (api, queryClient, tokenStorage, stompClient, firebase), `services/authService.ts`, `types/`, `utils/`.

---

## Auth patterns

### Axios Refresh Queue
- Flag `isRefreshing` + `failedQueue[]` — tránh race nhiều 401 cùng lúc.
- `processQueue(error, token)` → resolve/reject toàn bộ queue sau refresh done.
- Dùng `axios.create()` riêng cho `/refresh` (KHÔNG `api.post` — interceptor loop).
- `AUTH_TOKEN_EXPIRED` → refresh+retry; `AUTH_REQUIRED` → clear+redirect.

### Auth Store + tokenStorage
- Persist: `refreshToken` + `user` (KHÔNG `accessToken` — TTL 15 phút, ADR-003).
- `isHydrated` flag: tránh flash redirect trước khi store load.
- `onRehydrateStorage`: gọi `tokenStorage.setRefreshToken()` để interceptor có token ngay.
- Sync 2 chiều BẮT BUỘC: `tokenStorage.setTokens()` cùng lúc Zustand `set()`.
- `tokenStorage.ts` module in-memory KHÔNG import api.ts → phá circular dep api ↔ authStore.

### authService.init()
- Chạy khi app mount, trước routes. Dùng `rawAxios` (axios.create() riêng, không interceptors).
- Refresh fail → `clearAuth()`, trả `{ isAuthenticated: false }` (không throw).
- `isInitialized` gate trong App.tsx tránh flash redirect. `void authService.init().finally(...)` tránh lint floating-promise.

### Error handling — handleAuthError
- `setFormError(field, msg)` cho lỗi user fix (INVALID_CREDENTIALS, EMAIL_TAKEN, USERNAME_TAKEN).
- `showToast(msg, type)` cho lỗi hệ thống (RATE_LIMITED, ACCOUNT_DISABLED, unknown).

### Firebase OAuth
- Lazy init: `if (getApps().length === 0) initializeApp(...)`.
- Flow: `signInWithPopup` → `getIdToken()` → `oauthApi({ firebaseIdToken })` → `setAuth()` → navigate.
- Catch `auth/popup-closed-by-user` / `auth/cancelled-popup-request` → return silently.

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
- `initialPageParam: undefined as string | undefined`.
- `getNextPageParam`: `lastPage.hasMore ? lastPage.nextCursor ?? undefined : undefined`.
- `nextCursor` là ISO8601 (createdAt của oldest message trong page). items sorted ASC, scroll-up load cũ hơn.

### Optimistic update — useSendMessage
1. `cancelQueries` → snapshot → `tempId = crypto.randomUUID()` → append optimisticMsg.
2. `onError`: restore snapshot; `onSuccess`: replace item `id === tempId` bằng real.
3. `onSettled`: `invalidateQueries(['conversations'])` sidebar refresh.

### Pagination format
BE trả `{ content, page, size, totalElements, totalPages }` — KHÔNG `{ items, total }`.

### 409 CONV_ONE_ON_ONE_EXISTS
Return `{ existingConversationId }` thay vì throw — caller navigate.

---

## Layout patterns

### MessagesList scroll
- `bottomRef` + `isAtBottom` (threshold 80px) scroll-to-bottom chỉ khi ở cuối. IntersectionObserver `topSentinelRef` preserve scroll (lưu `prevScrollHeight`, cộng delta sau `fetchNextPage()`). `void fetchNextPage().then(...)` tránh lint.

### MessageItem grouping
- `shouldShowAvatar(messages, index)`: index=0, sender khác, gap >60s.
- Own bubble `bg-indigo-600 text-white rounded-2xl rounded-br-sm` justify-end; other `bg-white border rounded-2xl rounded-bl-sm` justify-start.
- Timestamp hover `opacity-0 group-hover:opacity-100` (parent `group`). `React.memo` bắt buộc.

### MessageInput
- Props `conversationId, disabled?, onStartTyping, onStopTyping, replyToMessageId?, onSent?`. Auto-resize textarea (cap 5 rows). Char counter >4500 yellow, >5000 red, block send.
- `onDragLeave`: chỉ clear `isDragging` khi `!e.currentTarget.contains(e.relatedTarget as Node)` — tránh flicker.

### MessageActions hover
- Parent có `group`; actions `opacity-0 group-hover:opacity-100`. Click-outside qua `useEffect` + `menuRef` + `document.addEventListener('mousedown')`.
- Menu position `style={{ [isOwn ? 'right' : 'left']: 0, bottom: '100%' }}`. Props stateless (`message, isOwn, canEdit, onEdit, onDelete, onReply, onCopy`).

---

## WebSocket / Realtime patterns

### useConvSubscription (per-conversation)
- Subscribe ngay nếu `client?.connected`, else chờ `onConnectionStateChange` → CONNECTED.
- Local ref `cleanup: (()=>void) | null` lưu `sub.unsubscribe`. Cleanup sub cũ trước re-sub (tránh duplicate handler).
- Dedupe: `old.pages.some(p => p.items.some(m => m.id === newMsg.id))` trước append.
- Append vào `pages[lastIdx].items`; invalidate `['conversations']`.
- **Reconnect catch-up**: `wasDisconnectedRef` (useRef) track — chỉ gọi `catchUpMissedMessages` khi `wasDisconnectedRef.current === true` (không gọi connect đầu).
- `catchUpMissedMessages` (features/messages/catchUp.ts): newestTs từ cache → `GET /messages?after=<ts>&limit=100` → merge; fallback `invalidateQueries` nếu REST fail.

### useTypingIndicator
- Subscribe `/topic/conv.{id}` (cùng với MESSAGE_CREATED). Filter TYPING_STARTED/STOPPED; skip self (`user.userId === currentUserId`).
- 3 timers riêng: **debounceTimerRef** (publish START 1/2s), **autoStopTimerRef** (publish STOP sau 3s im lặng), **autoRemoveTimersRef** Map<userId,timerId> (5s safety net).
- Payload: `userId, username, conversationId` — KHÔNG `fullName`.
- Cleanup tất cả 3 ref trong useEffect return. Clear typingUsers khi DISCONNECTED/ERROR.
- Capture `autoRemoveTimersRef.current` vào local var trước return (tránh exhaustive-deps lint).

### useAckErrorSubscription — Unified ACK/ERROR (ADR-017)
- `switch(envelope.operation)` route: SEND | EDIT | DELETE | READ.
- Shape: `{operation, clientId: UUID, message|error, code}`.
- Tab-awareness: `{send,edit,delete}TimerRegistry.get(clientId)` undefined → ignore ACK/ERROR từ tab khác.
- EDIT: `editTimerRegistry.get(clientId)` pattern. DELETE: `deleteTimerRegistry` same pattern.
- `MSG_NO_CHANGE` → silent revert, không toast.

### timerRegistry pattern (module-level singleton)
- 3 registry: `timerRegistry` (send), `editTimerRegistry`, `deleteTimerRegistry`.
- Structure: `Map<clientId, {timerId, messageId, convId}>`. `clearAll()` bắt buộc trong `authStore.clearAuth()`.
- Tab-awareness: ignore nếu registry undefined.

### MESSAGE_UPDATED broadcast
Dedup: `if (existing.editedAt && existing.editedAt >= updated.editedAt) return page` (ISO8601 lexicographic).

### Inline edit
- `canEdit` = `isOwn && !clientTempId && !failureCode && messageAgeMs < 290_000`.
- `messageAgeMs` dùng `useMemo([message.createdAt])` — KHÔNG `Date.now()` trong render.
- KHÔNG patch `content`/`editedAt` optimistic (Option A đơn giản, chờ ACK) — tránh cache drift khi ERROR.
- `patchMessageById` dùng real id; `patchMessageByTempId` dùng clientTempId.

### Soft delete
- `MessageDto`: `deletedAt: string | null`, `deletedBy: string | null`, `content: string | null`.
- `deleteStatus?: 'deleting'` — client-only, set khi chờ ACK (disable actions, KHÔNG ẩn bubble).
- `DeleteAckMessage`: `{id, conversationId, deletedAt, deletedBy}` — không có content.
- Optimistic message khởi tạo `deletedAt: null, deletedBy: null`.

### ReadTicks (W7-D5 V1 approximation)
- `member.lastReadMessageId !== null` → coi là đã đọc conv. Exact check cần compare createdAt — defer V2.
- `lastReadMessageId` không trong cache → "unknown, assume read" (§3.13 step 3).
- ReadTicks render CHỈ cho `message.sender?.id === currentUserId` AND `status !== 'sending|failed'`.

### handleReadUpdated
- `queryClient.setQueryData(conversationKeys.detail(id), old => patch member)`.
- Race-safe: member không tồn tại → return old silently.
- Idempotent: `if (existing.lastReadMessageId === payload.lastReadMessageId) return old`.
- KHÔNG cần `invalidateQueries` — setQueryData trigger re-render ReadTicks đủ.

### useAutoMarkRead (debounce 500ms)
- File `features/messages/hooks/useAutoMarkRead.ts`.
- `getStompClient().publish({ destination: /app/conv.{id}.read, body: {messageId} })`.
- `lastSentRef` dedupe — không gửi lại cùng messageId. Reset khi convId đổi.
- Silent fail khi STOMP not connected. Gọi từ `MessagesList` với `lastMessageId` = last non-system, non-optimistic.

---

## Pitfalls đã gặp

- **TS 5.8+ `baseUrl` deprecated**: `"ignoreDeprecations": "6.0"` vào `tsconfig.app.json`.
- **TailwindCSS v4**: KHÔNG cần `tailwind.config.js` / `postcss.config.js`.
- **accessToken không persist**: `authService.init()` restore session mỗi reload.
- **rawAxios trong init()**: KHÔNG `api.ts` instance (interceptor loop).
- **confirmPassword**: dùng explicit object build, không spread `...data`.
- **Zustand `set` signature**: chỉ khai báo param dùng (`_get` vẫn bị ESLint).
- **`react-hooks/set-state-in-effect`**: reset trong event handler, KHÔNG useEffect. Hoặc `{value, scopeId}` rồi derive.
- **Username regex**: `/^[a-zA-Z_][a-zA-Z0-9_]{2,49}$/` khớp BE (3-50, không bắt đầu số).
- **Optimistic discriminator**: KHÔNG `id.startsWith('temp-')` — id là UUID thuần. Dùng `clientTempId` field.
- **Optimistic edit không patch content/editedAt**: không snapshot revert khi lỗi. Chỉ update khi ACK.
- **TS optional chaining narrowing**: `replyState !== null && replyState.convId === id ? ...` thay vì `replyState?.convId === id ? ...`.

---

## File Upload (W6-D4)

### useUploadFile — AbortController + onUploadProgress
- `AbortController` thay `CancelToken` (axios v1+ deprecated). `signal: controller.signal`.
- `onUploadProgress: (e) => pct = Math.round(e.loaded / e.total * 100)`.
- Cancel = `controller.abort()` → `axios.isCancel(err)` → filter silent.
- **FormData Content-Type undefined** BẮT BUỘC: `headers: { 'Content-Type': undefined }`. KHÔNG omit (interceptor inject `application/json`); KHÔNG hardcode `multipart/form-data` (thiếu boundary → BE fail).
- Revoke blob URL 4 điểm: `cancel`, `remove`, `clear` loop, unmount useEffect cleanup. Pattern ref-based: `pendingRef.current = pending` mỗi render → cleanup đọc `pendingRef.current` (tránh stale closure).

### AttachmentGallery — Messenger-style
- Ảnh KHÔNG có bubble bg — chỉ `<AttachmentGallery>` (grid thumbnail).
- Text caption (nếu có) PHÍA DƯỚI ảnh, bubble riêng.
- Chỉ text: bubble như cũ. Chỉ ảnh: không bubble.
- Non-image docs/archives qua `<FileCard>` generic. `PdfCard` đã xóa.

### validateFiles (v0.9.5)
- Group A: all-images (tối đa 5, chọn nhiều lần).
- Group B: exactly 1 non-image alone (PDF, Word, Excel, PPT, TXT, ZIP, 7z).
- Mixing: images + non-image = rejected; 2+ non-image = rejected.
- Signature: `validateFiles(newFiles, count, pendingMimes?)`. Caller filter `status !== 'error'` trước count + extract mimes.

### iconType-based rendering
- FE đọc `attachment.iconType` (server-computed) chọn icon — KHÔNG hard-code MIME→icon ở render.
- Fallback undefined → check `mime.startsWith('image/')`.
- `clientIconEmoji(mime)` trong `PendingAttachmentItem`: detect client-side TRƯỚC upload xong. Tách biệt server iconType.
- `FileCard.tsx` generic nhận `AttachmentDto`. `AttachmentDto.iconType` non-optional (required).

### Drag-drop pattern trong MessageInput
- `onDragLeave`: check `!e.currentTarget.contains(e.relatedTarget as Node)`.
- `isDragging` ring: `ring-2 ring-indigo-400 bg-indigo-50/30`.

---

## Group Chat UI (W7-D3)

### CreateGroupDialog — async avatar upload + user search
Upload avatar ngay khi chọn file (không chờ submit) qua `POST /api/files/upload?public=true`. Preview qua `URL.createObjectURL` (blob tức thì). `avatarFileId` state null cho đến upload xong → submit không block (`avatarUploading` flag). Revoke blob trong `handleRemoveAvatar`, `handleClose`, `useEffect` cleanup. `blobUrlRef = useRef<string|null>(null)` capture trong cleanup return.

### GroupInfoPanel — role-based context menu
- OWNER × MEMBER: promote→ADMIN, kick.
- OWNER × ADMIN: demote→MEMBER, kick, transfer-owner.
- ADMIN × MEMBER: kick only.
- Self, ADMIN×ADMIN/OWNER, MEMBER: no menu.
- Render: `showMenuBtn = !isSelf && (OWNER over non-OWNER || ADMIN over MEMBER)`.

### useConvMembershipSubscription — global mount
Mount 1 lần tại `GlobalSubscriptions` trong `App.tsx`. Subscribe `/user/queue/conv-added` + `/user/queue/conv-removed`. `conv-added`: add cache (dedupe), toast. `conv-removed`: remove cache (idempotent), navigate '/' nếu đang view, toast nếu KICKED. Re-subscribe on STOMP reconnect.

### Tristate PATCH (EditGroupInfoDialog)
Build `body: Record<string, unknown>` chỉ khi state='changed'/'removed'; 'unchanged' → KHÔNG include key. Nếu `Object.keys(body).length === 0` → close dialog, không call API.

### Idempotent broadcast — MEMBER_REMOVED + conv-removed
- `/topic/conv.{id}` MEMBER_REMOVED với `isSelf === true` → **bỏ qua**.
- `/user/queue/conv-removed` xử lý tất cả (bao gồm KICKED).
- Tránh duplicate navigate/toast. Idempotent `filter(c.id !== convId)`.

### navigateRef pattern (hooks useNavigate)
```ts
const navigate = useNavigate()
const navigateRef = useRef(navigate)
useEffect(() => { navigateRef.current = navigate }) // update in effect, NOT during render
```
KHÔNG set `navigateRef.current = navigate` ngoài effect (lint error).

---

## SystemMessage (W7-D4)

### SystemMessage component — centered inline (no pill bg)
- Container: `flex justify-center my-1 px-4` — khớp §3e.1.
- Span: `text-xs italic text-gray-500 dark:text-gray-400 select-none` — KHÔNG `rounded-full bg-gray-100`.
- `role="status" aria-label={text}`. `React.memo` wrap.

### i18n — Bạn/bạn substitution
```ts
const isActor = !!meta.actorId && meta.actorId === currentUserId
const isTarget = !!meta.targetId && meta.targetId === currentUserId
const actorName = isActor ? 'Bạn' : (meta.actorName ?? 'Ai đó')
const targetName = isTarget ? 'bạn' : (meta.targetName ?? '')
```
- Guard `!!meta.actorId &&` tránh false positive khi undefined.
- targetName falsy → warning + fallback string.

### Conditional render TEXT vs SYSTEM
- `MessageItem` memo wrapper: dispatch SYSTEM TRƯỚC vào `MessageItemInner` (tránh hook order issue).
- `MessagesList` cũng dispatch SYSTEM trước `MessageItem` để skip shouldShowAvatar.
- Double dispatch intentional defense-in-depth.

### systemEventType copy mapping (vi-VN V1)
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

## Avatar native img public URL (ADR-021, W7-D4-fix)

**Quyết định**: Avatars là public files. `<img src>` native load trực tiếp — KHÔNG `useProtectedObjectUrl`.

### Constants (hardcode FE, khớp migration V11 seed)
```ts
const DEFAULT_USER_AVATAR  = '/api/files/00000000-0000-0000-0000-000000000001/public'
const DEFAULT_GROUP_AVATAR = '/api/files/00000000-0000-0000-0000-000000000002/public'
```

### Approach: div fallback + img overlay
```tsx
<div className="relative" style={{ width: size, height: size }}>
  <div className="absolute inset-0 rounded-full bg-indigo-100 ...">{initial}</div>
  <img src={src} className="absolute inset-0 rounded-full object-cover"
    onError={(e) => { e.currentTarget.style.display = 'none' }}
  />
</div>
```
- `onError` hide img → div lộ ra (graceful fallback).

### Avatar upload — luôn `?public=true`
```ts
api.post('/api/files/upload?public=true', formData, ...)
```
Apply CreateGroupDialog, EditGroupInfoDialog. Message attachment: KHÔNG pass param (mặc định private).

### Khi nào dùng gì
| Context | Approach |
|---------|----------|
| Avatar user/group | Native `<img src=publicUrl>` (ADR-021) |
| Message attachment image | `useProtectedObjectUrl` (private, auth) |
| Generic file download | `useProtectedObjectUrl` trong FileCard |

### EditGroupInfoDialog prop đổi (ADR-021)
- Old: `currentAvatarFileId: string | null` → BE fetch via protected URL.
- New: `currentAvatarUrl: string | null` → public URL, native img.
- GroupInfoPanel pass: `currentAvatarUrl={conv.avatarUrl}`.

---

## Auth-protected image loading — useProtectedObjectUrl (private attachments)

File: `src/features/files/hooks/useProtectedObjectUrl.ts`. Fetch via `api.get({ responseType: 'blob', signal: controller.signal })` → `URL.createObjectURL` → blob URL.

**Usage**: `AttachmentGallery.tsx` (thumbnail+lightbox), `FileCard.tsx` (private file download). **KHÔNG dùng cho avatar** (ADR-021).

**Critical rules**:
1. AbortController bắt buộc — cancel in-flight khi unmount/path đổi.
2. `URL.revokeObjectURL` trong cleanup return — tránh memory leak.
3. Dependency `[path]` — re-fetch khi id đổi.
4. Ignore abort error (`err.name === 'CanceledError'`).
5. Reset `setObjectUrl(null)` khi path falsy.

**Anti-patterns**: `<img src=/api/files/{id}>` không qua hook → 401. Token query `?token=xxx` → leak logs. `CancelToken` deprecated → `AbortController`.

**V2**: signed URL (HMAC+15min) hoặc Service Worker inject auth header.

---

## Misc patterns

### TypeScript enum → const object (erasableSyntaxOnly)
```ts
export const FooType = { A: 'A', B: 'B' } as const
export type FooType = (typeof FooType)[keyof typeof FooType]
```

### Toast (sonner)
- Từ W5-D5: migrate sang `sonner` package (singleton, 1 dep).
- `<Toaster position="bottom-right" richColors />` trong App.tsx.
- `toast.error('...')` cho timeout callback SEND/EDIT/DELETE.

### Form — RHF + Zod
`resolver: zodResolver(schema), mode: 'onTouched'` — validate blur, UX tốt. Error inline với `role="alert"`.

### Dialog pattern
Controlled `open` prop, Esc via `useEffect`, `autoFocus` input, `handleClose()` gộp reset + `onClose()`.

### ConversationListItem
`displayName` / `displayAvatarUrl` server-computed trong `ConversationSummaryDto`. `React.memo`.

---

---

## Message Reactions (W8-D1)

### EmojiPicker lazy load pattern
```tsx
const Picker = lazy(() => import('@emoji-mart/react'))
// Wrap trong <Suspense fallback={...}> — split bundle ~76KB gzip
```
- Phải cài thêm peer dep `emoji-mart` (không chỉ `@emoji-mart/react + @emoji-mart/data`).
- React 19 + legacy-peer-deps: `@emoji-mart/react` khai báo peer React ^16.8||^17||^18 nhưng chạy OK với 19.

### ReactionBar hover pattern
- `onMouseEnter/Leave` trên bubble container (không dùng CSS group-hover để tránh conflict).
- ReactionBar render `position: relative` → picker trong `absolute bottom-full mb-1`.
- 6 quick emojis (hardcoded) + "+" mở full picker.

### ReactionAggregate display
- `aria-pressed={r.currentUserReacted}` cho a11y.
- Highlight: `bg-indigo-100 border-indigo-400 text-indigo-700` khi react; gray khi chưa.
- `onToggle(emoji)` → gọi useReact — fire-and-forget (BE toggle: ADDED→REMOVED nếu đã react).

### updateMessageReactionsInCache pattern (applyReactionChange)
```ts
function applyReactionChange(reactions, payload, currentUserId): ReactionAggregateDto[]
// ADDED: addReaction (idempotent check userIds.includes)
// REMOVED: removeReaction (xoá aggregate khi count==0)
// CHANGED: removeReaction(previousEmoji) + addReaction(emoji)
// Sort: count DESC, emoji ASC
// currentUserReacted: recalculate sau mỗi branch
```

### ERROR handler tolerate clientId: null (REACT operation)
```ts
// CRITICAL: REACT ERROR frame có clientId: null — KHÔNG dùng registry.get(null)
case 'REACT': {
  handleReactError(code) // chỉ toast, không tra registry
  break
}
// Destructure clientId bên trong case cụ thể, không ở top level switch:
// const { operation, error, code } = envelope (không destructure clientId chung)
// case 'SEND': { const clientId = envelope.clientId as string; ... }
```

### ErrorEnvelope discriminated union
```ts
type ErrorEnvelope =
  | { operation: 'SEND'|'EDIT'|'DELETE'; clientId: string; error: string; code: string }
  | { operation: 'REACT'; clientId: null; error: string; code: string }
```

---

## Pin + Block UI (W8-D2)

### Pin message integration
- `PinnedMessagesBanner` đặt ở đầu `MessagesList`: mặc định show 1 item, có toggle expand/collapse cho phần còn lại.
- Scroll-to-message dùng `messageRefs` (`Record<messageId, HTMLDivElement | null>`) + `scrollIntoView({ block: 'center' })` + highlight ring 2s.
- Pin action reuse `MessageActions` dropdown (không tạo menu riêng): props `canPin`, `onTogglePin`, hiển thị `📌 Ghim/Bỏ ghim`.
- `MessageItem` hiển thị pinned indicator nhỏ trên bubble khi `message.pinnedAt` có giá trị.

### Pin permissions + conversation type compatibility
- `canPin` tính ở `MessagesList` từ `conversation.type` + role hiện tại:
  - `GROUP` -> chỉ `OWNER` hoặc `ADMIN`
  - direct (`ONE_ON_ONE`/`DIRECT`) -> mọi thành viên
- FE giữ tương thích cả `ONE_ON_ONE` (legacy) và `DIRECT` (contract mới) để tránh break khi BE chuyển enum.

### Realtime + error handling
- `useConvSubscription` thêm handlers `MESSAGE_PINNED`/`MESSAGE_UNPINNED`: patch message cache tại chỗ, rồi invalidate `conversationKeys.detail(id)` để refresh `pinnedMessages`.
- `useAckErrorSubscription` map thêm codes mới cho pin/block (`PIN_LIMIT_EXCEEDED`, `MESSAGE_NOT_PINNED`, `MSG_USER_BLOCKED`, `CANNOT_BLOCK_SELF`, `BLOCK_NOT_FOUND`, `MSG_DELETED`, `FORBIDDEN`).

### Block user UI
- Block/unblock ở direct chat header (không wire settings route ở D2): dùng confirm trước khi block.
- Hooks block dùng React Query mutation/query: `useBlockUser`, `useUnblockUser`, `useBlockedUsers`.
- Chỉ dùng `isBlockedByMe` ở FE (không có `hasBlockedMe`) để giữ privacy boundary.

---

## Thư viện đã chọn

| Library | Ghi chú |
|---------|---------|
| react 19+ | Latest stable |
| react-router-dom v6 | KHÔNG v7 |
| zustand | Client state |
| @tanstack/react-query v5 | Server state, cache, infinite |
| axios | HTTP + interceptors |
| react-hook-form + @hookform/resolvers + zod | Form |
| lucide-react | Icons tree-shakeable |
| date-fns | Thay moment |
| @stomp/stompjs + sockjs-client | STOMP |
| firebase | Google OAuth |
| tailwindcss v4 + @tailwindcss/vite | CSS |
| sonner | Toast singleton (W5-D5+) |
| @emoji-mart/react + @emoji-mart/data + emoji-mart | Emoji picker (W8-D1). Legacy-peer-deps cần. React 19 compat OK. |
