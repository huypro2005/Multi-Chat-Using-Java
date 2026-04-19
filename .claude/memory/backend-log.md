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
