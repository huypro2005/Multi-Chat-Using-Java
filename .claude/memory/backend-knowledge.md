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
- JWT filter: KHÔNG implement `UserDetailsService` — `JwtAuthFilter` load `User` entity trực tiếp từ `UserRepository` rồi wrap bằng `UsernamePasswordAuthenticationToken(user, null, emptyList())`. Lý do: tránh double-lookup, không cần AuthenticationManager ở filter layer. `UserDetailsService` sẽ implement sau nếu cần `AuthenticationManager` cho auth endpoint. Ngày 3.
- jjwt 0.12.x API khác 0.11.x: parser dùng `Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)` (không còn `parseClaimsJws`). Sign dùng `.signWith(secretKey)` không cần truyền algorithm riêng. Ngày 3.
- Secret key: dùng `secret.getBytes(UTF_8)` trực tiếp thay vì Base64 decode. Keys.hmacShaKeyFor yêu cầu >= 32 bytes (256-bit) — secret trong config phải >= 32 ký tự. Ngày 3.
- CORS pitfall: KHÔNG set `allowedOrigins("*")` khi `allowCredentials(true)` — Spring Security throw `IllegalArgumentException` vì vi phạm CORS spec. Phải dùng list origins cụ thể từ property. Ngày 3.
- `authenticationEntryPoint` trong SecurityConfig phải trả JSON (không phải redirect HTML). Nếu không cấu hình, Spring Security mặc định trả HTML 401 redirect — FE không parse được. Ngày 3.
- BCryptPasswordEncoder strength 12 — `new BCryptPasswordEncoder(12)`. Khai báo làm `@Bean PasswordEncoder` trong SecurityConfig. Ngày 3.

### Database
- ddl-auto: validate — Hibernate KHÔNG tự alter schema. Mọi thay đổi qua Flyway migration. Tuần 1.
- JAVA_HOME phải trỏ đúng vào jdk-21.0.10 mới chạy được mvn: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"`.
- UUID primary key pattern: `@GeneratedValue(strategy = GenerationType.UUID)` + `@Column(insertable=false, updatable=false)`. Database generate bằng `gen_random_uuid()` (pgcrypto). Tuần 1.
- Timestamp: dùng `OffsetDateTime` (java.time) cho cột TIMESTAMPTZ. Không dùng Date/Calendar. Tuần 1.
- Soft delete users: dùng cột `status VARCHAR(20)` ('active'/'suspended'/'deleted') thay cho `deleted_at`. Lưu `deleted_name` khi xóa. Tuần 1.
- Repository method naming với ManyToOne: dùng `findByUser_Id(UUID)` không phải `findByUserId(UUID)` — Spring Data cần dấu `_` để phân biệt nested property. Tuần 1.

### WebSocket / STOMP
- (chưa chốt)

### JWT Token Validation Pattern (W1 Fix)
- Dùng `validateTokenDetailed()` trả enum `TokenValidationResult` (VALID/EXPIRED/INVALID) thay vì boolean `validateToken()`.
- `JwtAuthFilter` set `request.setAttribute("jwt_expired", true)` khi result == EXPIRED, rồi tiếp tục filter chain (không authenticate).
- `authenticationEntryPoint` trong SecurityConfig check attribute để trả `AUTH_TOKEN_EXPIRED` vs `AUTH_REQUIRED`.
- FE dùng `AUTH_TOKEN_EXPIRED` để trigger refresh queue, `AUTH_REQUIRED` để redirect login lại.
- `ExpiredJwtException` phải catch TRƯỚC `JwtException` vì nó là subclass — dùng separate catch blocks để rõ ràng.
- Package-private helper `generateTokenWithExpiration(User, long)` trong JwtTokenProvider để test generate expired token mà không hardcode string.

---

## Pattern đã dùng trong codebase

*(Điền khi đã có code mẫu cho pattern đó, để các feature sau tuân theo.)*

### Controller → Service → Repository
- (chưa có)

### Entity pattern
- KHÔNG dùng `@Data` cho entity — gây vấn đề equals/hashCode với JPA lazy-loading.
- Dùng `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`.
- Entity có thể có domain method (vd `user.markAsDeleted()`) — không phải POJO chay.
- `@PrePersist` / `@PreUpdate` để tự set createdAt/updatedAt thay vì Spring Auditing (giữ đơn giản).
- `@ManyToOne(fetch = FetchType.LAZY)` — luôn LAZY, không bao giờ EAGER.

### Package structure (đã dùng)
- `com.chatapp.user.entity` — User, UserAuthProvider, UserBlock
- `com.chatapp.user.repository` — UserRepository, UserAuthProviderRepository, UserBlockRepository
- `com.chatapp.config` — SecurityConfig
- `com.chatapp.controller` — HealthController
- Pattern: `com.chatapp.<domain>.entity`, `com.chatapp.<domain>.repository`, `com.chatapp.<domain>.service`, `com.chatapp.<domain>.controller`

### DTO vs Entity
- (chưa có — sẽ có từ Ngày 4 khi implement auth endpoints)

### Error handling
- `ErrorResponse` record: `{ error, message, timestamp, details }` với `@JsonInclude(NON_NULL)` để không serialize `details` khi null. Package: `com.chatapp.exception`. Ngày 3.
- `AppException(HttpStatus, String errorCode, String message)` — business exception dùng chung, `GlobalExceptionHandler` bắt và convert. Ngày 3.
- Test: `@SpringBootTest` KHÔNG có attribute `excludeAutoConfiguration` (đó là attribute của `@DataJpaTest`). Để exclude autoconfigure trong `@SpringBootTest`, dùng `properties = "spring.autoconfigure.exclude=..."`. Ngày 3.

---

## Pitfall đã gặp (đừng lặp lại)

*(Format: triệu chứng → giải pháp → gặp lần đầu ở đâu. Quan trọng: chỉ thêm bug đã tốn >1h debug, không thêm bug vặt.)*

- Spring Data Redis WARN log khi có JPA + Redis cùng project: "Could not safely identify store assignment for JPA repositories" — đây là INFO log bình thường, Spring Data đang xác nhận repository nào thuộc module nào. Không phải lỗi, không cần sửa. Gặp lần đầu Ngày 2.

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

### AuthMethod enum (Tuần 2, W-BE-3)
- Enum tại `com.chatapp.user.enums.AuthMethod`: PASSWORD("password"), OAUTH2_GOOGLE("oauth2_google").
- `generateAccessToken(User, AuthMethod)` — luôn truyền enum, không truyền string thô.
- `getAuthMethodFromToken(String)` — extract từ JWT claim "auth_method", fallback về PASSWORD nếu unknown.
- JWT claim key: "auth_method", value là string lowercase từ `enum.getValue()`.

---

## Changelog file này

- 2026-04-19 W2D1: Thêm AuthMethod enum pattern (W-BE-3).
- 2026-04-19 W1 Fix: Thêm JWT Token Validation Pattern (validateTokenDetailed, AUTH_TOKEN_EXPIRED vs AUTH_REQUIRED).
- 2026-04-19 Ngày 3: Thêm Security/JWT patterns, ErrorResponse shape, pitfall @SpringBootTest excludeAutoConfiguration, CORS gotcha, jjwt 0.12.x API notes.
- 2026-04-19: Thêm UUID/timestamp pattern, entity pattern, package structure, pitfall Spring Data Redis WARN.
