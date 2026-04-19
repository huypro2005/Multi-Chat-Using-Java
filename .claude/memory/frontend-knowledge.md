# Frontend Knowledge — Tri thức chắt lọc cho frontend-dev

> File này là **bộ nhớ bền vững** của frontend-dev.
> Quy tắc: chỉ ghi những gì có giá trị tái sử dụng. KHÔNG ghi nhật ký (cái đó ở `frontend-log.md`).
> Giới hạn: file này không được dài quá ~300 dòng.
> Ai được sửa: chỉ `frontend-dev`, hoặc `code-reviewer` khi chốt quyết định.

---

## Quyết định kiến trúc đã chốt

### State management
- **Zustand** cho client state (auth token, UI state, dark mode)
- **TanStack Query v5** cho server state (fetching, cache, pagination)
- KHÔNG dùng Redux — over-engineer cho quy mô này

### Routing
- **React Router v6** (không phải v7)
- Routes: `/` (Home), `/login`, `/register`, `/conversations` (protected), wildcard `*` redirect về `/`
- **ProtectedRoute pattern**: layout route dùng `<Outlet />` (KHÔNG dùng `children` prop).
- **Redirect pattern**: `<Navigate to="/login" state={{ from: location }} replace />` — login đọc `location.state.from?.pathname`.
- **Nested route structure**: `<Route element={<ProtectedRoute />}> <Route path="/conversations" element={<ConversationsLayout />}> <Route index .../> <Route path=":id" .../> </Route> </Route>`

### API client
- **Axios** instance singleton tại `src/lib/api.ts`, export default `api`
- baseURL từ `VITE_API_URL` env var, fallback `/api`; Vite proxy: `/api` → `http://localhost:8080`
- API functions trong features gọi `api.post/get('/api/...')` — prefix `/api` được ghi trong path, KHÔNG trong baseURL

### WebSocket client
- **@stomp/stompjs + sockjs-client** — STOMP singleton tại `src/lib/stompClient.ts`
- SockJS URL = HTTP (`http://`), KHÔNG ws:// — SockJS tự upgrade.
- `reconnectDelay: 0` — tắt built-in reconnect, tự manage exponential backoff.
- Backoff: 1s → 2s → 4s → 8s → 16s → 30s (cap), MAX_RECONNECT = 10.
- Auth flow: token từ `tokenStorage.getAccessToken()` → `connectHeaders: { Authorization: Bearer }`.
- `AUTH_TOKEN_EXPIRED` STOMP error → `authService.refresh()` → reconnect (không logout).
- `AUTH_REQUIRED` STOMP error → `window.location.href = '/login'` (logout).
- Wire lifecycle trong App.tsx: `useEffect` watch `!!authStore.accessToken` → connect khi login, disconnect khi logout.
- `webSocketFactory` phải là factory fn (không phải instance) — Client gọi lại mỗi reconnect.
- Dynamic import `authService` trong `stompClient.ts` để tránh circular dep (App.tsx import cả 2 static, Vite warning cosmetic — runtime OK).
- Debug log: `import.meta.env.DEV` only.

### Styling
- **TailwindCSS v4** via `@tailwindcss/vite` plugin (KHÔNG dùng PostCSS config)
- Import duy nhất trong `index.css`: `@import "tailwindcss";`

### Bundler
- **Vite v8** với `@vitejs/plugin-react`
- Path alias `@/` → `src/` (cấu hình cả trong `vite.config.ts` và `tsconfig.app.json`)

---

## Design tokens (chốt toàn project)

- **Primary color**: `indigo-600` (hover: `indigo-700`, disabled: `indigo-400`)
- **Input border**: `border-gray-300`, focus: `ring-2 ring-indigo-500 border-transparent`, error: `border-red-500`
- **Border radius**: `rounded-lg` cho inputs/buttons, `rounded-xl` cho card/modal
- **Button padding**: `py-2.5 px-4 font-medium text-sm`
- **Card**: `bg-white rounded-xl shadow-sm border border-gray-200 p-8`
- **Page background**: `bg-gray-50`
- **Error text**: `text-red-500 text-sm`

---

## Pattern đã dùng trong codebase

### Component organization
- Feature-first: `src/features/{feature}/` chứa cả `components/`, `hooks.ts`, `api.ts`, `types.ts` (nếu có)
- Shared UI trong `src/components/`; Pages trong `src/pages/`

### Form handling — RHF + Zod
```tsx
const { register, handleSubmit, formState: { errors } } = useForm<FormData>({
  resolver: zodResolver(schema),
  mode: 'onTouched',  // validate khi blur
})
```
- Error inline: `{errors.field && <p className="text-red-500 text-sm" role="alert">{errors.field.message}</p>}`
- **Login schema**: chỉ validate không trống; **Register schema**: validate đầy đủ format

### Toast — Custom component (không dùng thư viện ngoài)
- `src/components/Toast.tsx` + `src/hooks/useToast.ts`
- `const { toasts, addToast, removeToast } = useToast()` → render `<ToastContainer>`
- Fixed bottom-right, auto-dismiss 3s, slide animation

### TypeScript enum → const object (erasableSyntaxOnly mode)
```ts
export const FooType = { A: 'A', B: 'B' } as const
export type FooType = (typeof FooType)[keyof typeof FooType]
```

---

## Cấu trúc thư mục `src/`

```
src/
├── pages/          # Route-level components
├── components/     # Shared reusable UI
├── features/
│   ├── auth/       # schemas/, components/
│   ├── conversations/ # components/, hooks.ts, api.ts, queryKeys.ts
│   ├── messages/   # components/, hooks.ts, api.ts
│   └── users/
├── hooks/          # Shared hooks (useDebounce, useToast)
├── stores/         # Zustand stores
├── lib/            # api.ts (Axios), queryClient.ts, tokenStorage.ts, firebase.ts
├── types/          # Shared TS types: auth.ts, conversation.ts, message.ts
└── utils/
```

---

## Axios Refresh Queue Pattern

Flag `isRefreshing` + `failedQueue[]` để tránh race condition khi nhiều request 401 cùng lúc.
`processQueue(error, token)` → resolve/reject toàn bộ queue sau khi refresh done.
Dùng `axios.post` (KHÔNG phải `api.post`) cho `/refresh` để tránh interceptor loop.
Phân biệt 2 error code: `AUTH_TOKEN_EXPIRED` → refresh + retry; `AUTH_REQUIRED` → clear + redirect.

## Auth Store + tokenStorage Pattern

- Persist: `refreshToken` + `user` (KHÔNG persist `accessToken` vì 15 phút TTL)
- `isHydrated` flag để UI biết khi nào store đã load từ localStorage (tránh flash redirect)
- `src/lib/tokenStorage.ts` — module trung gian, KHÔNG import api.ts → phá circular dep
- Sync 2 chiều bắt buộc: gọi `tokenStorage.setTokens()` cùng lúc với set() Zustand
- `onRehydrateStorage`: sau hydrate, gọi `tokenStorage.setRefreshToken()` để interceptor có refreshToken ngay

## authService.init() pattern

`src/services/authService.ts` — chạy khi app mount, trước khi routes render.
- Dùng `rawAxios` (axios.create() riêng) để tránh interceptor loop
- Refresh fail → `clearAuth()`, trả `{ isAuthenticated: false }` (không throw)
- `isInitialized` gate trong App.tsx tránh flash redirect /login
- `void authService.init().finally(...)` — `void` để tránh lint floating promise

## Error handling — auth API errors

`src/features/auth/utils/handleAuthError.ts`:
- `setFormError(field, msg)` cho lỗi user fix được (INVALID_CREDENTIALS, EMAIL_TAKEN, USERNAME_TAKEN)
- `showToast(msg, type)` cho lỗi hệ thống (RATE_LIMITED, ACCOUNT_DISABLED, unknown)

---

## React Query patterns

### Query key factories
```ts
// src/features/conversations/queryKeys.ts
export const messageKeys = {
  all: (convId: string) => ['messages', convId] as const,
}
export const conversationKeys = {
  all: ['conversations'] as const,
  lists: () => [...conversationKeys.all, 'list'] as const,
  list: (page, size) => [...conversationKeys.lists(), { page, size }] as const,
  detail: (id) => [...conversationKeys.all, 'detail', id] as const,
}
```

### useInfiniteQuery — cursor-based messages
```ts
useInfiniteQuery({
  queryKey: messageKeys.all(convId),
  queryFn: ({ pageParam }) => getMessages(convId, pageParam as string | undefined),
  initialPageParam: undefined as string | undefined,
  getNextPageParam: (lastPage) => lastPage.hasMore ? lastPage.nextCursor ?? undefined : undefined,
  enabled: !!convId,
  staleTime: 10_000,
})
```
- `items` sorted ASC (oldest first), UI scroll-up để load trang cũ hơn
- `nextCursor` là ISO8601 string (createdAt của message cũ nhất trong page)

### Optimistic update — useSendMessage
1. `cancelQueries` → snapshot → tạo `tempId = temp-${Date.now()}-${random}` → append optimisticMsg (id = tempId)
2. `onError`: restore snapshot
3. `onSuccess`: replace item có `id === tempId` bằng `realMsg`
4. `onSettled`: `invalidateQueries(['conversations'])` để sidebar refresh `lastMessageAt`
- Type cho setQueryData: `(old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) => ...`

### Pagination — Spring Page format
BE trả `{ content, page, size, totalElements, totalPages }` — KHÔNG phải `{ items, total, pageSize }`
Áp dụng cho conversations list; messages dùng cursor-based (khác).

### 409 CONV_ONE_ON_ONE_EXISTS
Return `{ existingConversationId }` thay vì throw — caller navigate sang conversation đó.

### useDebounce hook
`src/hooks/useDebounce.ts` — dùng cho search input (300ms delay, `enabled` khi >= 2 chars)

---

## Layout patterns

### Layout 2 cột (ConversationsLayout)
- Sidebar: `w-80 flex-shrink-0 bg-white border-r border-gray-200`
- Main: `flex-1 min-w-0`
- Mobile: ẩn/hiện sidebar/main dựa vào `useParams().id`

### ConversationDetailPage — 3-section vertical
`flex flex-col h-full` → header (`flex-shrink-0`) + body (`flex flex-1 overflow-hidden`) + input (`flex-shrink-0` inside body column)
- `disabled` prop cho MessageInput: `true` tuần 3 (placeholder), `false` tuần 4 khi wire STOMP

### Dialog pattern
Controlled `open` prop, Esc handler qua `useEffect`, `autoFocus` input, `handleClose()` gộp reset + `onClose()`.

### ConversationListItem
`displayName` / `displayAvatarUrl` server-computed trong `ConversationSummaryDto` — không cần client compute.
`React.memo` để tránh re-render.

---

## Firebase JS SDK

`src/lib/firebase.ts` — lazy init (check `getApps().length === 0` trước `initializeApp`).
OAuth: `signInWithPopup` → `getIdToken()` → `oauthApi({ firebaseIdToken })` → `setAuth(response)` → navigate.
Edge case popup closed: catch `auth/popup-closed-by-user` hoặc `auth/cancelled-popup-request` → return silently.

## Logout pattern
Best-effort logout API, luôn `clearAuth()` + `navigate('/login')` trong `finally`.

---

## Pitfall đã gặp (đừng lặp lại)

- **TypeScript 5.8+ `baseUrl` deprecated**: thêm `"ignoreDeprecations": "6.0"` vào `tsconfig.app.json`.
- **TailwindCSS v4**: KHÔNG cần `tailwind.config.js` / `postcss.config.js`. Chỉ plugin trong vite.config.ts.
- **authStore ↔ tokenStorage sync**: gọi tokenStorage.setTokens() trong cùng action với set() Zustand.
- **accessToken không persist → phải init()**: mỗi reload, accessToken = null. authService.init() restore session.
- **rawAxios trong init()**: KHÔNG dùng api.ts instance (loop). Dùng axios.create() riêng.
- **confirmPassword không gửi BE**: dùng explicit object `{ email, username, password, fullName }`, không spread `...data`.
- **Zustand (set) signature**: chỉ khai báo tham số thực sự dùng. `_get` vẫn bị ESLint no-unused-vars.
- **`react-hooks/set-state-in-effect`**: KHÔNG dùng `useEffect` để reset state khi prop thay đổi. Reset trong event handler.
- **Username regex**: `/^[a-zA-Z_][a-zA-Z0-9_]{2,49}$/` — khớp BE (3-50 ký tự, không bắt đầu bằng số).

---

## Thư viện đã chọn

| Library | Lý do |
|---------|-------|
| react 19+ | Latest stable |
| react-router-dom v6 | Standard routing |
| zustand | Lightweight client state |
| @tanstack/react-query v5 | Server state, cache, infinite query |
| axios | HTTP client với interceptor |
| react-hook-form + @hookform/resolvers + zod | Form + validation |
| lucide-react | Icons nhẹ, tree-shakeable |
| date-fns | Date formatting (thay moment) |
| @stomp/stompjs + sockjs-client | STOMP over WebSocket |
| firebase | Google OAuth |
| tailwindcss v4 + @tailwindcss/vite | CSS utility-first |

---

## Convention đặt tên

- File component: PascalCase (`MessageBubble.tsx`)
- File hook: camelCase với prefix `use` (`useSocket.ts`)
- File type: camelCase trong `src/types/` (`message.ts`, `auth.ts`)
- Event handler: `handle<Event>` (`handleSubmit`, `handleClose`)

---

## MessagesList scroll pattern

- `bottomRef` + `isAtBottom` state: scroll to bottom chỉ khi user đang ở cuối (threshold 80px).
- `isAtBottom` track qua `onScroll` → `scrollHeight - scrollTop - clientHeight < 80`.
- useEffect deps: `[messages.length, isAtBottom]` — trigger khi count thay đổi.
- Không dùng eslint-disable khi rule không thực sự cần disable (lint sẽ warn "unused directive").

## IntersectionObserver infinite scroll

- `topSentinelRef` ở đầu list, observe khi `hasNextPage && !isFetchingNextPage`.
- Preserve scroll position sau fetch: lưu `prevScrollHeight` trước `fetchNextPage()`, cộng delta vào `scrollTop` trong `.then()`.
- Dùng `void fetchNextPage().then(...)` để tránh lint floating-promise.

## MessageItem grouping

- `shouldShowAvatar(messages, index)`: hiện avatar khi index=0, hoặc sender khác, hoặc gap > 60_000ms.
- isOwn bubble: `bg-indigo-600 text-white rounded-2xl rounded-br-sm`, justify-end.
- Other bubble: `bg-white border border-gray-200 rounded-2xl rounded-bl-sm`, justify-start.
- Timestamp hover: `opacity-0 group-hover:opacity-100 transition-opacity` (parent cần `group` class).
- Status icon: `id.startsWith('temp-')` → spinner; else → ✓.
- `React.memo` bắt buộc cho MessageItem (list 100+ items).

## Optimistic sender — lấy từ authStore

`useAuthStore((s) => s.user)` trong `useSendMessage` hook → dùng `user?.id/username/fullName/avatarUrl` thay vì hardcode `'Bạn'`.

## MessageInput (W4-D2)

- Props: `conversationId: string`, `disabled?: boolean` (default false).
- `useSendMessage(conversationId)` nội bộ — không cần `onSend` prop từ parent.
- Auto-resize: `el.style.height = 'auto'; el.style.height = Math.min(el.scrollHeight, 5*24) + 'px'`.
- Character counter > 4500 (yellow), > 5000 (red). Block send khi over limit.

---

## Changelog file này

- 2026-04-20 (W4-D3): STOMP client singleton, connect/disconnect lifecycle, ConnectionStatus debug UI. authService.refresh() added.
- 2026-04-20 (W4-D2): MessageItem (memo, grouping, status icon, hover timestamp), MessagesList (infinite scroll, auto-scroll, skeleton/error/empty states), MessageInput (enabled, Enter send, auto-resize, char counter), wire ConversationDetailPage. Optimistic sender từ authStore.
- 2026-04-19 (W4-D1): CONSOLIDATE (xóa outdated notes, gộp pattern). Thêm: message types, useInfiniteQuery pattern, optimistic update useSendMessage, messageKeys factory.
- 2026-04-19 (W3D4): ConversationDetailPage 3-section, MessageInput disabled pattern, features/messages/ folder.
- 2026-04-19 (W3D3): ConversationListItem, dialog/memo/409 patterns.
- 2026-04-19 (W3D2): Conversation types, React Query hooks, queryKeys, useDebounce, enum→const pattern.
- 2026-04-19 (W3D1): ProtectedRoute Outlet pattern, ConversationsLayout 2-col, route nesting.
- 2026-04-19 (W2D4): Firebase SDK, OAuth popup, logout pattern.
- 2026-04-19 (W2D3): handleAuthError utility, wire Login/Register API.
- 2026-04-19 (W2D2): authService.init(), isInitialized gate, rawAxios pattern.
- 2026-04-19 (W2D1): tokenStorage, Axios refresh queue, Auth Store pattern.
