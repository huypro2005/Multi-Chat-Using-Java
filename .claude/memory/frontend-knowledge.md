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
- Routes: `/` (Home), `/login`, `/register`, wildcard `*` redirect về `/`

### API client
- **Axios** instance singleton tại `src/lib/api.ts`
- baseURL từ `VITE_API_URL` env var, fallback `/api`
- Vite proxy: `/api` → `http://localhost:8080`

### WebSocket client
- **@stomp/stompjs + sockjs-client** (chưa implement, sẽ làm Tuần 2+)

### Styling
- **TailwindCSS v4** via `@tailwindcss/vite` plugin (KHÔNG dùng PostCSS config)
- Import duy nhất trong `index.css`: `@import "tailwindcss";`
- Không viết CSS thuần trừ khi Tailwind không làm được

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
- Feature-first: mọi thứ liên quan auth đi trong `src/features/auth/`
  - `src/features/auth/schemas/` — Zod schemas
  - `src/features/auth/components/` — feature-specific components
- Shared UI trong `src/components/`
- Pages (route-level) trong `src/pages/`

### Form handling — RHF + Zod pattern
```tsx
const { register, handleSubmit, formState: { errors } } = useForm<FormData>({
  resolver: zodResolver(schema),
  mode: 'onTouched',  // validate khi blur, không phải mỗi keystroke
})
```
- Error hiển thị inline dưới field: `{errors.field && <p className="text-red-500 text-sm">{errors.field.message}</p>}`
- Input có error state: thêm `border-red-500` vào className khi `errors.field` truthy
- Dùng `role="alert"` trên error `<p>` để accessible

### Validation schema — Login vs Register khác nhau
- **Login schema**: chỉ validate không để trống (contract note: server KHÔNG validate format để tránh lộ thông tin)
- **Register schema**: validate đầy đủ format (alphanumeric, chữ hoa, số, v.v.) theo contract `/api/auth/register`

### Toast — Custom component (không dùng thư viện ngoài)
- Vì không có sonner/react-hot-toast trong dependencies
- Tạo `src/components/Toast.tsx` + `src/hooks/useToast.ts`
- Pattern: `const { toasts, addToast, removeToast } = useToast()` rồi render `<ToastContainer>` ở cuối component
- Fixed bottom-right, auto-dismiss 3s, slide animation

### Custom hooks đã có
- `useToast` — manage toast queue (add/remove), dùng `useState` + `useCallback`

---

## Cấu trúc thư mục `src/`

```
src/
├── pages/          # Route-level components (LoginPage, RegisterPage, HomePage)
├── components/     # Shared reusable UI components
├── features/       # Feature modules (auth, chat, ...) — feature-first
├── hooks/          # Shared custom hooks (useAuth, useSocket)
├── stores/         # Zustand stores
├── services/       # Alias của api/ — REST client functions
├── lib/
│   └── api.ts    # Axios singleton instance với refresh queue
├── types/          # Shared TypeScript types (api.ts, socket.ts)
└── utils/
```

---

## Axios Refresh Queue Pattern

Flag `isRefreshing` + `failedQueue[]` để tránh race condition khi nhiều request 401 cùng lúc.
`processQueue(error, token)` → resolve/reject toàn bộ queue sau khi refresh done.
Lưu ý: dùng `axios.post` (KHÔNG phải `api.post`) cho `/refresh` để tránh interceptor loop.
Phân biệt 2 error code: `AUTH_TOKEN_EXPIRED` → refresh + retry; `AUTH_REQUIRED` → clear + redirect.

## Auth Store Pattern

Persist: `refreshToken` + `user` (KHÔNG persist `accessToken` vì 15 phút TTL).
`isHydrated` flag để UI biết khi nào store đã load từ localStorage (tránh flash redirect).
Circular dep `api.ts ↔ authStore.ts` đã giải quyết bằng `tokenStorage.ts` (module trung gian, không import api.ts).
`main.tsx` KHÔNG cần import authStore chỉ để wire global — dependency chain sạch.

## tokenStorage pattern (đã implement Tuần 2)

`src/lib/tokenStorage.ts` — module trung gian, KHÔNG import api.ts, phá circular dep.
- In-memory: `accessToken` (không persist) + `refreshToken` (mirror từ Zustand)
- API: `getAccessToken()`, `setAccessToken()`, `getRefreshToken()`, `setRefreshToken()`, `setTokens()`, `clear()`
- `api.ts` import tokenStorage để đọc/ghi trong interceptors
- `authStore.ts` gọi `tokenStorage.setTokens()` trong `setAuth`, `tokenStorage.clear()` trong `clearAuth`
- Sync 2 chiều bắt buộc: gọi tokenStorage cùng lúc với set() Zustand — không tách ra 2 call
- onRehydrateStorage: sau khi Zustand hydrate từ localStorage, gọi `tokenStorage.setRefreshToken()` để interceptor có refreshToken ngay

## Zustand (set) signature

Nếu không dùng `get` tham số thứ 2, bỏ hẳn: `(set) => ({...})`. Đừng dùng `_get` — ESLint vẫn báo `no-unused-vars` với underscore prefix trừ khi có rule riêng. Để tránh lỗi lint, chỉ khai báo tham số nào thực sự dùng.

---

## Pitfall đã gặp (đừng lặp lại)

- **TypeScript 5.8+ với `baseUrl`**: bị deprecated warning, cần thêm `"ignoreDeprecations": "6.0"` vào `tsconfig.app.json`. Không dùng `"5.0"` sẽ vẫn báo lỗi.
- **`@tailwindcss/vite` (v4)**: KHÔNG cần `tailwind.config.js`, KHÔNG cần `postcss.config.js`. Chỉ cần plugin trong `vite.config.ts` và `@import "tailwindcss"` trong CSS.
- **Register username regex**: `/^[a-zA-Z0-9_]+$/` cho phép `123abc`. Phải đổi thành `/^[a-zA-Z_][a-zA-Z0-9_]*$/` theo contract. Reviewer flag Ngày 4 — chưa fix (server validate lại nên không blocking). Fix khi implement API call thật ở Tuần 2.
- **authStore ↔ tokenStorage sync**: gọi tokenStorage.setTokens() trong cùng action với set() Zustand. Không tách thành 2 chỗ — race condition khi axios interceptor đọc tokenStorage ngay lập tức.

---

## Thư viện đã chọn

| Library | Version | Lý do chọn |
|---------|---------|------------|
| react | 19+ | Latest stable |
| react-router-dom | 6 | Standard routing, v6 API |
| zustand | latest | Lightweight client state |
| @tanstack/react-query | v5 | Server state, cache, infinite query |
| axios | latest | HTTP client với interceptor support |
| react-hook-form | latest | Form state management |
| @hookform/resolvers | latest | Zod integration cho RHF |
| zod | latest | Schema validation |
| lucide-react | latest | Icon set nhẹ, tree-shakeable |
| date-fns | latest | Date formatting (thay moment) |
| @stomp/stompjs | latest | STOMP over WebSocket |
| sockjs-client | latest | WebSocket fallback |
| firebase | latest | Google OAuth via Firebase JS SDK |
| tailwindcss | v4 | Utility-first CSS |
| @tailwindcss/vite | v4 | Vite plugin cho TailwindCSS v4 |

---

## Convention đặt tên

- File component: PascalCase (`MessageBubble.tsx`)
- File hook: camelCase với prefix `use` (`useSocket.ts`)
- File type: camelCase (`api.ts`, `socket.ts`) trong `src/types/`
- CSS class: Tailwind utilities, KHÔNG viết CSS thuần trừ khi buộc phải
- Event handler: `handle<Event>` (`handleSubmit`, `handleClick`)

---

## Changelog file này

- 2026-04-19 (Tuần 2, Ngày 1): W-FE-2 done. Migrate TODO → implemented section. Cập nhật Auth Store pattern (xóa globalThis, dùng tokenStorage). Thêm pitfall authStore ↔ tokenStorage sync.
- 2026-04-19 (Ngày 4): Fix stale axios.ts reference → api.ts. Thêm tokenStorage migration TODO. Thêm username regex pitfall.
- 2026-04-19 (Ngày 3): Thêm Axios refresh queue pattern, Auth Store pattern, Zustand (set) signature pitfall.
- 2026-04-19 (Ngày 2): Chốt design tokens (indigo-600, rounded-lg, v.v.). Ghi pattern form RHF+Zod, toast custom, feature structure auth. Thêm useToast hook.
- 2026-04-19 (Ngày 1): Khởi tạo project Tuần 1. Ghi pitfall TypeScript deprecation và Tailwind v4 setup.
