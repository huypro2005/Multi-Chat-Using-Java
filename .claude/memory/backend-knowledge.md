# Backend Knowledge — Tri thức chắt lọc cho backend-dev

> File này là **bộ nhớ bền vững** của backend-dev.
> Quy tắc: chỉ ghi những gì có giá trị tái sử dụng. KHÔNG ghi nhật ký (cái đó ở `backend-log.md`).
> Giới hạn: file này không được dài quá ~300 dòng. Nếu vượt, phải rút gọn/gộp entries cũ.
> Ai được sửa: chỉ `backend-dev` (agent tự update khi học được điều mới), hoặc `code-reviewer` khi chốt quyết định kiến trúc.

---

## Quyết định kiến trúc đã chốt

*(Điền khi team chốt một lựa chọn quan trọng. Ghi ngắn gọn: quyết định là gì, lý do chính, tuần đã chốt.)*

### Security
- SecurityConfig dùng lambda DSL (Spring Security 6), KHÔNG dùng WebSecurityConfigurerAdapter. Tuần 1.
- Tạm thời permitAll cho đến Ngày 3 khi implement JWT filter.

### Database
- ddl-auto: validate — Hibernate KHÔNG tự alter schema. Mọi thay đổi qua Flyway migration. Tuần 1.
- JAVA_HOME phải trỏ đúng vào jdk-21.0.10 mới chạy được mvn.

### WebSocket / STOMP
- (chưa chốt)

---

## Pattern đã dùng trong codebase

*(Điền khi đã có code mẫu cho pattern đó, để các feature sau tuân theo.)*

### Controller → Service → Repository
- (chưa có)

### DTO vs Entity
- (chưa có)

### Error handling
- (chưa có)

---

## Pitfall đã gặp (đừng lặp lại)

*(Format: triệu chứng → giải pháp → gặp lần đầu ở đâu. Quan trọng: chỉ thêm bug đã tốn >1h debug, không thêm bug vặt.)*

- (chưa có)

---

## Thư viện đã chọn

*(Chốt một lần, không tự ý đổi. Nếu cần đổi, phải có lý do và báo reviewer.)*

| Library | Version | Lý do chọn |
|---------|---------|------------|
| spring-boot-starter-parent | 3.4.4 | Latest stable Spring Boot 3.x tại thời điểm khởi tạo (Tuần 1) |
| jjwt-api/impl/jackson | 0.12.6 | Latest stable jjwt 0.12.x, API mới dùng builder pattern rõ ràng |
| firebase-admin | 9.4.1 | Latest stable 9.x, verify Google OAuth token |
| flyway-core + flyway-database-postgresql | BOM managed | Cần cả 2 artifact cho PostgreSQL support trong Flyway 10+ |

---

## Convention đặt tên

*(Ghi khi phát hiện team hay nhầm lẫn.)*

- Package: `com.chatapp.<domain>` (tất cả thường dùng `com.chatapp.auth`, `com.chatapp.message`, ...)
- DTO: `{Action}Request`, `{Action}Response` — ví dụ `LoginRequest`, `LoginResponse`
- Service method: verb + noun — `createUser()`, `sendMessage()`
- Migration: `V{n}__{snake_case_description}.sql`

---

## Changelog file này

- (chưa có entry)
