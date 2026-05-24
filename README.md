# Chat App — SE330 Project

Real-time chat application với group chat, file sharing, reactions, read receipts, pin message và block user.

## Features

### Messaging
- Direct chat (1-1) và group chat
- Send/Edit/Delete/Reply message
- Any-emoji reactions
- Pin message (max 3 mỗi conversation)
- Read receipts + unread count
- Typing indicator + reconnect catch-up

### File Sharing
- Upload ảnh và tài liệu (14 MIME types)
- Public/default avatars + private attachments
- Thumbnail cho ảnh

### User
- Auth: register/login + Google OAuth
- Profile page: cập nhật thông tin, đổi avatar, đổi mật khẩu
- Settings page: blocked users + notification stub
- Bilateral block/unblock

### Platform
- Spring Boot + PostgreSQL + Redis + React + STOMP
- Docker-ready với `docker-compose.yml`

## Tech Stack

### Backend
- Java 21, Spring Boot 3.4.4
- PostgreSQL 18, Redis 7
- Flyway migrations
- Apache Tika + Thumbnailator

### Frontend
- React 19 + TypeScript + Vite
- TanStack Query + Zustand
- Tailwind CSS
- SockJS + STOMP

## Quick Start (Docker)

### 1) Setup env

```bash
cp .env.example .env
```

Mở `.env` và điền các biến bắt buộc:

| Biến | Mô tả | Mặc định |
|------|-------|----------|
| `DB_HOST` | Postgres trên máy host | `host.docker.internal` |
| `DB_PORT` | Port Postgres local | `5432` |
| `DB_NAME` | Tên database | `chatapp` |
| `DB_USER` | User Postgres local | `postgres` |
| `DB_PASSWORD` | Mật khẩu Postgres local | — |
| `JWT_SECRET` | Secret ký JWT (≥256 bit) | — |

> **Mặc định Docker dùng Postgres local** trên máy bạn (không chạy container postgres). Backend container kết nối qua `host.docker.internal`.

Đảm bảo Postgres local đã:
1. Đang chạy trên port `5432`
2. Có database `chatapp` (hoặc tên khớp `DB_NAME`)
3. Cho phép kết nối từ Docker (Windows Docker Desktop thường OK mặc định)

Tạo database nếu chưa có:

```sql
CREATE DATABASE chatapp;
```

Tạo `JWT_SECRET` nhanh (PowerShell):

```powershell
-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 64 | ForEach-Object {[char]$_})
```

**Google OAuth** (nếu cần đăng nhập Google trong Docker):

1. **Frontend** — thêm vào `.env` (Firebase Console → Project Settings → Web app):

```
VITE_FIREBASE_API_KEY=your-api-key
VITE_FIREBASE_AUTH_DOMAIN=your-project.firebaseapp.com
VITE_FIREBASE_PROJECT_ID=your-project-id
```

2. **Backend** — mount Firebase Admin SDK JSON vào container:

```
FIREBASE_CREDENTIALS_HOST_PATH=D:\path\to\firebase-adminsdk.json
```

> `VITE_*` bake vào frontend lúc build — đổi sau phải `docker compose build frontend`.  
> `FIREBASE_CREDENTIALS_HOST_PATH` là file trên máy host; container đọc tại `/app/config/firebase-credentials.json`.

### 2) Build and run

```bash
docker compose build
docker compose up -d
```

Kiểm tra containers:

```bash
docker compose ps
```

Services:
- **Frontend (dùng URL này):** http://localhost
- Backend API (debug trực tiếp): http://localhost:8080
- PostgreSQL: **Postgres local** trên máy host (port 5432)
- Redis: container Docker (port 6379)

> Mở app qua http://localhost (port 80). Frontend gọi `/api` và `/ws` qua nginx reverse proxy — không gọi thẳng `:8080` từ browser.

**Dùng Postgres trong Docker** (nếu không có Postgres local):

```bash
docker compose --profile docker-db up -d
```

Khi đó set trong `.env`: `DB_HOST=postgres`, `DB_USER=chatapp` (hoặc user bạn cấu hình trong profile).

### 3) Setup default avatars (optional)

Repo không chứa file avatar mặc định. Nếu cần avatar fallback cho user/group mới:

```bash
docker cp avatar_default.jpg chat-app-backend-1:/app/uploads/default/
docker cp group_default.jpg chat-app-backend-1:/app/uploads/default/
```

Hoặc mount local volume trong `docker-compose.yml`:

```yaml
backend:
  volumes:
    - ./backend/uploads:/app/uploads
    - ./default-avatars:/app/uploads/default:ro
```

Đặt `avatar_default.jpg` và `group_default.jpg` trong folder `./default-avatars/`.

## Local Dev (không Docker)

Docker và local dev dùng **env khác nhau**:

| | Docker | Local dev |
|---|--------|-----------|
| Env file | `.env` (root) | `backend/.env` + `frontend/.env` |
| Frontend URL | http://localhost | http://localhost:3000 |
| API routing | nginx proxy `/api` | Vite proxy hoặc `VITE_API_BASE_URL` |

**Backend:**

```bash
cp backend/.env.example backend/.env
# Điền DB, Redis, JWT, Firebase credentials path
cd backend && mvn spring-boot:run
```

**Frontend:**

```bash
cp frontend/.env.example frontend/.env
cp frontend/.env.local.example frontend/.env.local  # Firebase OAuth
cd frontend && npm install && npm run dev
```

Chi tiết từng biến: xem comment trong `backend/.env.example` và `frontend/.env.local.example`.

## Troubleshooting Docker

| Triệu chứng | Nguyên nhân | Cách fix |
|-------------|-------------|----------|
| Backend crash, không kết nối DB | Postgres local chưa chạy hoặc sai `DB_*` | Kiểm tra Postgres + `.env` |
| `Connection refused` tới DB | `DB_HOST` sai | Dùng `host.docker.internal` (Windows/Mac Docker Desktop) |
| File/avatar 404 trong Docker | DB local có record nhưng file nằm ở `backend/uploads/` host | Compose mount `./backend/uploads:/app/uploads` (mặc định) — restart backend |
| Login/register fail, console báo CORS | Frontend build với `localhost:8080` | Rebuild: `docker compose build frontend --no-cache` |
| Port 80 đã bị chiếm | IIS/Skype/container khác | Đổi port trong `docker-compose.yml`: `"8081:80"` |
| Google OAuth 503/502 | Backend không đọc được Firebase credentials | Set `FIREBASE_CREDENTIALS_HOST_PATH` trỏ file JSON trên host, rồi `docker compose up -d` |
| Avatar mặc định 404 | Chưa copy file vào `/app/uploads/default/` | Xem bước 3 ở trên |

Xem logs:

```bash
docker compose logs -f backend
docker compose logs -f frontend
```

## Docs

- `docs/ARCHITECTURE.md`
- `docs/API_CONTRACT.md`
- `docs/SOCKET_EVENTS.md`
- `docs/WARNINGS.md`
- `docs/RETROSPECTIVE.md`
