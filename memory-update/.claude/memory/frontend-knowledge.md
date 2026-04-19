# Frontend Knowledge — Tri thức chắt lọc cho frontend-dev

> File này là **bộ nhớ bền vững** của frontend-dev.
> Quy tắc: chỉ ghi những gì có giá trị tái sử dụng. KHÔNG ghi nhật ký (cái đó ở `frontend-log.md`).
> Giới hạn: file này không được dài quá ~300 dòng.
> Ai được sửa: chỉ `frontend-dev`, hoặc `code-reviewer` khi chốt quyết định.

---

## Quyết định kiến trúc đã chốt

### State management
- (chưa chốt)

### Routing
- (chưa chốt)

### API client
- (chưa chốt)

### WebSocket client
- (chưa chốt)

---

## Pattern đã dùng trong codebase

### Component organization
- Feature-first: mọi thứ liên quan feature chat đi trong `src/features/chat/`
- Shared UI trong `src/components/`

### Custom hooks
- (chưa có)

### Form handling
- (chưa có)

---

## Pitfall đã gặp (đừng lặp lại)

*(Format: triệu chứng → giải pháp. Chỉ thêm bug đã tốn >1h debug.)*

- (chưa có)

---

## Thư viện đã chọn

| Library | Version | Lý do chọn |
|---------|---------|------------|
| (chưa có) | | |

---

## Convention đặt tên

- File component: PascalCase (`MessageBubble.tsx`)
- File hook: camelCase với prefix `use` (`useSocket.ts`)
- File type: camelCase (`api.ts`, `socket.ts`) trong `src/types/`
- CSS class: Tailwind utilities, KHÔNG viết CSS thuần trừ khi buộc phải
- Event handler: `handle<Event>` (`handleSubmit`, `handleClick`)

---

## Changelog file này

- (chưa có entry)
