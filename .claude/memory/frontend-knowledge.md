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
- **Axios** instance singleton tại `src/lib/axios.ts`
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

## Pattern đã dùng trong codebase

### Component organization
- Feature-first: mọi thứ liên quan feature chat đi trong `src/features/chat/`
- Shared UI trong `src/components/`
- Pages (route-level) trong `src/pages/`

### Custom hooks
- (chưa có — sẽ thêm khi implement auth/socket)

### Form handling
- **React Hook Form + Zod** — chưa implement, sẽ dùng ở Ngày 2+

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
│   └── axios.ts    # Axios singleton instance
├── types/          # Shared TypeScript types (api.ts, socket.ts)
└── utils/
```

---

## Pitfall đã gặp (đừng lặp lại)

- **TypeScript 5.8+ với `baseUrl`**: bị deprecated warning, cần thêm `"ignoreDeprecations": "6.0"` vào `tsconfig.app.json`. Không dùng `"5.0"` sẽ vẫn báo lỗi.
- **`@tailwindcss/vite` (v4)**: KHÔNG cần `tailwind.config.js`, KHÔNG cần `postcss.config.js`. Chỉ cần plugin trong `vite.config.ts` và `@import "tailwindcss"` trong CSS.

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

- 2026-04-19: Khởi tạo project Tuần 1 Ngày 1. Ghi pitfall TypeScript deprecation và Tailwind v4 setup.
