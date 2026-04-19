# Frontend Log — Nhật ký chi tiết frontend-dev

> Append-only, mới nhất ở đầu file.
> Mỗi ngày 1 entry, không gộp.

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
