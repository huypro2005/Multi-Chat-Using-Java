# Backend Log — Nhật ký chi tiết backend-dev

> File này là **lịch sử** công việc của backend-dev.
> Quy tắc: append-only, mới nhất ở ĐẦU file (để dễ đọc 20 dòng đầu).
> Mỗi ngày làm việc tạo 1 entry, không gộp nhiều ngày.
> Mục đích: (1) daily standup, (2) agent session sau biết đã làm đến đâu, (3) tra cứu "bug này đã gặp chưa".
> Giới hạn: không giới hạn độ dài — nhưng agent chỉ ĐỌC entry mới nhất trừ khi cần tra cứu cụ thể.

---

## Template cho mỗi entry

```
## YYYY-MM-DD (Tuần N, Ngày X) — <chủ đề ngắn>

### Xong
- <task>: <tóm tắt 1 câu> (commit <hash>)

### Đang dở
- <task>: <tình trạng>

### Blocker
- <vấn đề>: <đã xử lý gì, chờ gì>

### Ghi chú kỹ thuật
- <phát hiện đáng nhớ, nếu cần học được gì đã update vào backend-knowledge.md>
```

---

## Entries

*(Entries sẽ append ở đây, MỚI NHẤT trên cùng)*

## 2026-04-19 (Tuần 1, Ngày 2) — V2 migration + JPA entities + repositories

### Xong
- V2__create_users_and_auth_providers.sql: tạo 3 bảng users, user_auth_providers, user_blocks với đầy đủ constraint và index khớp ARCHITECTURE.md 3.1.
- User.java entity: UUID PK (DB generate), OffsetDateTime timestamps, @PrePersist/@PreUpdate, domain methods markAsDeleted()/isActive()/isDeleted().
- UserAuthProvider.java entity: ManyToOne(LAZY) -> User.
- UserBlock.java entity: 2 ManyToOne(LAZY) -> User (blocker, blocked).
- UserRepository, UserAuthProviderRepository, UserBlockRepository: Spring Data JPA interfaces.
- Flyway V2 applied thành công: "Successfully applied 1 migration to schema public, now at version v2".
- Hibernate validate PASS: không có schema mismatch.
- psql verify: cả 3 bảng tồn tại đúng structure, index đúng, FK đúng.

### Đang dở
- Auth service/controller (RegisterRequest, LoginRequest, JWT issuance) — để Ngày 3.

### Blocker
- Không có.

### Ghi chú kỹ thuật
- Port 8080 đã có process cũ chiếm (app Ngày 1 vẫn chạy). Khi boot test dùng port khác hoặc kill trước. Hibernate validate vẫn pass trước khi lỗi port.
- Spring Data Redis log WARN "Could not safely identify store assignment" cho JPA repositories là bình thường khi có cả JPA + Redis module — không phải lỗi.

## 2026-04-19 (Tuần 1, Ngày 1) — Khởi tạo Spring Boot project

### Xong
- Tạo pom.xml: Spring Boot 3.4.4, Java 21, Maven. Dependencies: Web, Security, JPA, Redis, WebSocket, Validation, Flyway, PostgreSQL, Lombok, jjwt 0.12.6, firebase-admin 9.4.1, test scope.
- Tạo application.yml: cấu hình datasource, jpa (ddl-auto: validate), flyway, redis, jwt properties. Tạm thời exclude FlywayAutoConfiguration, DataSourceAutoConfiguration, JPA, Redis autoconfigure để app start không cần DB thật.
- Tạo ChatAppApplication.java (main class).
- Tạo HealthController: GET /api/health trả {"status":"ok","service":"chat-app-backend"}.
- Tạo SecurityConfig tạm thời: csrf disabled, permitAll (sẽ lock down Ngay 3).
- Tạo V1__placeholder.sql cho Flyway.
- Verify: mvn compile OK, mvn spring-boot:run start thành công trong 1.548s trên port 8080.

### Đang dở
- Flyway migration thật (schema users, conversations, messages) — để Ngay 2.
- JWT filter chain, auth endpoints — để Ngay 2-3.

### Blocker
- JAVA_HOME trỏ vào jdk-25 không tồn tại. Fix: export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" trước khi chạy mvn. Cần set JAVA_HOME đúng trong môi trường hệ thống hoặc thêm vào script.

### Ghi chú kỹ thuật
- jjwt 0.12.6 là latest stable của 0.12.x series — dùng version này.
- firebase-admin 9.4.1 resolve OK với Spring Boot 3.4.4.
- Khi chưa có DB/Redis, exclude 5 autoconfigure classes trong application.yml để app start sạch.
