# Retrospective — Chat App SE330 (8 weeks)

## Metrics

| Metric | Value |
|---|---|
| Duration | 8 weeks |
| ADRs | 24 |
| Migrations | 15 |
| Test suite | 355+ |

## What shipped

- Auth (password + OAuth), refresh, logout
- Conversation core (direct/group) + role matrix
- Realtime messaging via STOMP with ACK/ERROR flow
- Message features: edit/delete/reply/reaction/pin/read receipts
- File module: upload, thumbnails, visibility model
- Bilateral block user
- Profile + Settings pages
- UX polish: 404, skeleton, empty states, error boundary
- Docker build setup for backend/frontend/full stack

## Wins

- Contract-first workflow giữ BE/FE sync tốt.
- Pattern consistency: AFTER_COMMIT broadcast, AppException, anti-enumeration 404.
- Feature growth theo tuần nhưng vẫn giữ test suite lớn ổn định.
- Hybrid file visibility (public avatars + private attachments) giảm độ phức tạp FE.

## Lessons learned

- Rate-limit và dedup cần được thiết kế sớm cho mọi realtime action.
- Realtime event ordering phải xử lý idempotent phía FE.
- Một số race conditions đã được chấp nhận cho V1 single-instance, cần distributed lock ở V2.

## V2 priorities

1. Signed URLs cho private files.
2. Distributed lock cho pin/count race.
3. Presence và notifications hoàn chỉnh.
4. Full-text search cho messages.
5. Voice/video features.
