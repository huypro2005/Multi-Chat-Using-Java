# Chat App — SE330 Project

Real-time chat: direct/group chat, file sharing, reactions, read receipts, pin message, block user.

**Chạy nhanh (Docker):** Postgres local + Docker Desktop → copy `.env` → `docker compose up -d` → mở http://localhost

---

## Yêu cầu (Prerequisites)

### Chạy bằng Docker (khuyến nghị)

| Cần có | Ghi chú |
|--------|---------|
| [Docker Desktop](https://www.docker.com/products/docker-desktop/) | Windows / Mac / Linux |
| PostgreSQL cài trên máy | Port `5432`, tạo database `chatapp` |
| Git | Clone repo |

> Docker **không** chạy container Postgres mặc định — backend container kết nối Postgres **trên máy host** qua `host.docker.internal`. Redis chạy trong Docker.

### Chạy local dev (không Docker)

| Cần có | Phiên bản |
|--------|-----------|
| Java + Maven | Java 21 |
| Node.js + npm | Node 20+ |
| PostgreSQL | 18+ |
| Redis | 7+ (hoặc `docker compose up -d redis`) |

---

## Cách 1 — Docker (khuyến nghị)

### Bước 1: Clone & chuẩn bị Postgres

```bash
git clone <repo-url>
cd chat-app
```

Tạo database (pgAdmin hoặc `psql`):

```sql
CREATE DATABASE chatapp;
```

Đảm bảo PostgreSQL đang chạy trên port `5432`.

### Bước 2: Tạo file `.env`

```bash
cp .env.example .env
```

Mở `.env`, điền **ít nhất** các biến sau:

```env
DB_HOST=host.docker.internal
DB_PORT=5432
DB_NAME=chatapp
DB_USER=postgres
DB_PASSWORD=<mật-khẩu-postgres-của-bạn>

JWT_SECRET=<chuỗi-ngẫu-nhiên-64-ký-tự>
```

Tạo `JWT_SECRET` (PowerShell):

```powershell
-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 64 | ForEach-Object {[char]$_})
```

### Bước 3: Build & chạy

```bash
docker compose build
docker compose up -d
```

### Bước 4: Kiểm tra

```bash
docker compose ps          # backend + frontend + redis = Up (healthy)
curl http://localhost/api/health   # {"status":"ok",...}
```

Mở trình duyệt: **http://localhost** (port 80, không phải `:8080`).

| Service | URL |
|---------|-----|
| **App (dùng URL này)** | http://localhost |
| Backend API (debug) | http://localhost:8080 |
| Postgres | localhost:5432 (trên máy host) |
| Redis | localhost:6379 (container) |

**Upload file:** lưu tại `backend/uploads/` trên ổ cứng (mount tự động từ Docker).

---

## Google OAuth (tùy chọn)

Đăng ký/đăng nhập bằng **username + password** hoạt động **không cần** Firebase.

Nếu cần nút **Đăng nhập Google**, thêm vào `.env`:

```env
# Frontend (Firebase Console → Project Settings → Web app)
VITE_FIREBASE_API_KEY=...
VITE_FIREBASE_AUTH_DOMAIN=....firebaseapp.com
VITE_FIREBASE_PROJECT_ID=...

# Backend — đường dẫn file Admin SDK JSON trên máy host
FIREBASE_CREDENTIALS_HOST_PATH=D:\path\to\firebase-adminsdk.json
```

Sau đó **bắt buộc rebuild frontend** (config bake lúc build):

```bash
docker compose build frontend
docker compose up -d
```

---

## Cách 2 — Local dev (không Docker)

### Bước 1: Redis

Chọn một trong hai:

```bash
# Option A — chỉ chạy Redis bằng Docker
docker compose up -d redis
```

Hoặc cài Redis local trên port `6379`.

### Bước 2: Backend

```bash
cp backend/.env.example backend/.env
```

Ví dụ `backend/.env`:

```env
DB_HOST=localhost
DB_PORT=5432
DB_NAME=chatapp
DB_USER=postgres
DB_PASSWORD=<your-password>

REDIS_HOST=localhost
REDIS_PORT=6379

JWT_SECRET=<same-as-docker-or-new>
JWT_ACCESS_EXPIRY_MINUTES=15
JWT_REFRESH_EXPIRY_DAYS=7

CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:5173

# Chỉ cần nếu dùng Google OAuth
FIREBASE_CREDENTIALS_PATH=D:\path\to\firebase-adminsdk.json
```

```bash
cd backend
mvn spring-boot:run
```

Backend: http://localhost:8080

### Bước 3: Frontend

```bash
cp frontend/.env.example frontend/.env
cp frontend/.env.local.example frontend/.env.local   # nếu dùng Google OAuth
```

```bash
cd frontend
npm install
npm run dev
```

Frontend: http://localhost:3000

---

## Postgres trong Docker (thay vì Postgres local)

Nếu **không** có Postgres cài sẵn trên máy:

1. Sửa `.env`:

```env
DB_HOST=postgres
DB_USER=chatapp
DB_PASSWORD=chatapp_dev_password
POSTGRES_PASSWORD=chatapp_dev_password
```

2. Chạy:

```bash
docker compose --profile docker-db up -d
```

---

## Avatar mặc định (tùy chọn)

Repo không chứa sẵn file avatar fallback. Copy vào folder upload:

```bash
mkdir -p backend/uploads/default
copy avatar_default.jpg backend\uploads\default\
copy group_default.jpg backend\uploads\default\
```

(Docker đã mount `backend/uploads` — không cần `docker cp`.)

---

## Xử lý lỗi thường gặp

| Triệu chứng | Cách fix |
|-------------|----------|
| Backend không start, lỗi DB | Postgres local chưa chạy / sai `DB_*` trong `.env` |
| `Connection refused` DB | Windows/Mac: `DB_HOST=host.docker.internal` |
| Login fail, CORS trên console | `docker compose build frontend --no-cache` rồi `up -d` |
| Google OAuth 503 | Set `FIREBASE_CREDENTIALS_HOST_PATH`, restart backend |
| Google OAuth 502 sau restart | `docker compose restart frontend` |
| Ảnh/avatar 404 | File phải nằm trong `backend/uploads/` (đã mount mặc định) |
| Port 80 bị chiếm | Đổi `"80:80"` → `"8081:80"` trong `docker-compose.yml` |
| `docker compose up` lỗi mount Firebase | Set `FIREBASE_CREDENTIALS_HOST_PATH` trỏ file JSON (compose bắt buộc mount; password login vẫn chạy nếu BE warn Firebase) |

Xem log:

```bash
docker compose logs -f backend
docker compose logs -f frontend
```

---

## Tech stack

**Backend:** Java 21, Spring Boot 3.4, PostgreSQL, Redis, Flyway, STOMP WebSocket  
**Frontend:** React 19, TypeScript, Vite, TanStack Query, Zustand, Tailwind CSS

## Docs

- [ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [API_CONTRACT.md](docs/API_CONTRACT.md)
- [SOCKET_EVENTS.md](docs/SOCKET_EVENTS.md)
- [WARNINGS.md](docs/WARNINGS.md)
- [RETROSPECTIVE.md](docs/RETROSPECTIVE.md)
