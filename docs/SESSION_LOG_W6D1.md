# Session Log — W6-D1 Start (2026-04-20)

## Trạng thái khi dừng

Token gần hết, dừng giữa chừng W6-D1. Reviewer agent đang chạy nền để update contract.

---

## Những gì đã hoàn thành trong session này

### Tuần 5 — ĐÃ HOÀN CHỈNH (tag v0.5.0-w5)

| Commit | Feature |
|--------|---------|
| `1670e09` | W5-D1: Typing indicator + destination-aware auth policy |
| `15a7c4e` | W5-D2: Edit message + unified ACK shape (ADR-017) |
| `e586f14` | W5-D3: Delete message + Facebook-style hover actions |
| `4f0738d` | W5-D5: Polish (3 warnings fix + memory consolidate + WARNINGS.md) |
| `b2d97bf` | W5-D4: Reconnect catch-up + Reply UI |

**Tests:** 145 BE pass, FE tsc + lint 0 errors.

---

## W6-D1 — ĐÃ BẮT ĐẦU, CHƯA HOÀN THÀNH

### Decisions đã chốt (tuần 6)
- Storage: local disk + StorageService interface (swap S3 V2)
- Model: `files` table + `message_attachments` table (normalize)
- Multi-file: 1 PDF OR 1-5 images/message (không trộn)
- Types: JPG, PNG, WebP, GIF, PDF
- Caption: content + attachments (ít nhất 1 trong 2 phải có)
- Expiry: 30 ngày từ upload
- Orphan cleanup: upload không attach trong 1h → cleanup xóa

### Phase A — Contract update (ĐANG CHẠY NỀN)

Reviewer agent `a83960c6dce72977d` đang update:
- `docs/API_CONTRACT.md` → v0.9.0-files (POST /upload, GET /{id}, GET /{id}/thumb, MessageDto + attachments)
- `docs/SOCKET_EVENTS.md` → v1.4-w6 (thêm attachmentIds vào inbound payload)
- `docs/ARCHITECTURE.md` → thêm ADR-019
- `docs/WARNINGS.md` → thêm W6-1..W6-4
- Flyway SQL (V7) cho `files` + `message_attachments` tables

### Phase B — BE implementation (CHƯA BẮT ĐẦU)

Cần gọi backend-dev (Opus) sau khi contract xong để implement:
1. Flyway V7 migration (files + message_attachments tables)
2. Entity: `FileRecord`, `MessageAttachment`
3. `StorageService` interface + `LocalStorageService` impl
4. `FileValidationService` (size, extension, magic bytes via Apache Tika)
5. `FileService.upload()` với rate limit 20/phút
6. `FileController` (POST /upload, GET /{id} stub)
7. Exception mapping (MaxUploadSizeExceededException → FILE_TOO_LARGE)
8. `application.yml` config (multipart max 20MB, storage path)
9. Tests ≥ 12 (validation, storage, controller integration)
10. pom.xml: add `tika-core:2.9.1`

### Phase C — FE (CHƯA BẮT ĐẦU)

Cần implement:
- File picker component (drag & drop + click)
- Upload progress UI
- Attachment preview (image thumbnails, PDF icon)
- Wire vào MessageInput
- AttachmentGrid component trong MessageItem

---

## Cách làm tiếp ngày mai

### Bước 1: Đọc file này
```
Đọc docs/SESSION_LOG_W6D1.md để nắm context.
```

### Bước 2: Check reviewer agent có xong không
```
Đọc docs/API_CONTRACT.md xem đã có section Files Management chưa (v0.9.0-files).
```
Nếu chưa → gọi lại reviewer agent với prompt như Phase A ở trên.
Nếu rồi → tiếp tục Phase B.

### Bước 3: Gọi backend-dev (Opus) làm Phase B

Prompt đầy đủ đã có trong session cũ (xem git history hoặc nhắc lại):
> "Implement file upload foundation: Flyway V7, FileRecord entity, StorageService + LocalStorageService, FileValidationService (Tika), FileService, FileController stub, tests ≥ 12"

### Bước 4: Reviewer review BE

Sau khi BE xong → gọi reviewer verify:
- Path traversal protection (canonical path check)
- MIME spoofing protection (Tika magic bytes)
- Rate limit upload 20/phút
- Tests ≥ 12 pass

### Bước 5: Commit
```
git commit -m "[W6-D1] feat: file upload foundation + storage abstraction + validation"
```

---

## Roadmap còn lại

| Tuần | Ngày | Feature |
|------|------|---------|
| W6 | D1 | Upload endpoint + storage + validation ← ĐANG LÀM |
| W6 | D2 | Thumbnail + download auth + link attachment vào message |
| W6 | D3 | Cleanup job (@Scheduled) + expiry + orphan cleanup |
| W6 | D4 | FE: file picker + upload progress + attachment display |
| W6 | D5 | Polish + tag v0.6.0-w6 |
| W7 | — | Reactions, pin, block, read receipt, badge |
| W8 | — | Polish, deploy, CI/CD |

---

## State quan trọng

- **Branch**: main (5 commits ahead of origin)
- **BE tests**: 145 pass
- **Tag hiện tại**: v0.5.0-w5 (local, chưa push)
- **Pending push**: user cần `git push origin main && git push origin v0.5.0-w5`
- **Reviewer agent còn chạy**: a83960c6dce72977d (contract W6-D1)

---

*Log tạo lúc dừng session 2026-04-20. Đọc file này khi bắt đầu lại.*
