# Frontend Log — Nhật ký chi tiết frontend-dev

> Append-only, mới nhất ở đầu file.
> Mỗi ngày 1 entry, không gộp.

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
