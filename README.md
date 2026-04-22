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

### 2) Build and run

```bash
docker compose build
docker compose up -d
```

Services:
- Frontend: http://localhost
- Backend: http://localhost:8080
- PostgreSQL: localhost:5432
- Redis: localhost:6379

### 3) Setup default avatars

```bash
docker cp avatar_default.jpg chatapp-backend-1:/app/uploads/default/
docker cp group_default.jpg chatapp-backend-1:/app/uploads/default/
```

Hoặc mount local volume:

```yaml
volumes:
  - ./default-avatars:/app/uploads/default:ro
```

## Docs

- `docs/ARCHITECTURE.md`
- `docs/API_CONTRACT.md`
- `docs/SOCKET_EVENTS.md`
- `docs/WARNINGS.md`
- `docs/RETROSPECTIVE.md`
