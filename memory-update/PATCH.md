# PATCH — Cập nhật 3 file agent hiện có để dùng memory

Sau khi copy thư mục `.claude/memory/` vào project, bạn cần thêm một section mới vào **cuối** mỗi file agent. Nội dung cụ thể như sau.

---

## 1. Thêm vào cuối `.claude/agents/backend-dev.md`

```markdown
---

## Memory system — bộ nhớ bền vững

Bạn có 2 file memory trong `.claude/memory/`:

### `backend-knowledge.md` — TRI THỨC (đọc mỗi lần)

**Luôn đọc file này ở đầu mỗi session.** Nó chứa quyết định kiến trúc đã chốt, pattern đã dùng, pitfall đã gặp, thư viện đã chọn. Nếu không đọc, bạn sẽ dễ lặp lại lỗi cũ hoặc tạo pattern không nhất quán.

**Khi nào bạn UPDATE file này:**
- Khi học được một bug tốn >1h debug → thêm vào mục "Pitfall đã gặp".
- Khi chốt một thư viện/pattern mới qua discussion với reviewer → thêm vào mục tương ứng.
- Khi orchestrator yêu cầu rõ "ghi lại quyết định X".

**Khi nào KHÔNG update:**
- Với chi tiết task lặt vặt → đó là việc của log, không phải knowledge.
- Với ý kiến cá nhân hoặc giả định chưa được xác nhận.

**Nguyên tắc**: nếu file vượt 300 dòng, bạn phải RÚT GỌN trước khi thêm mới — gộp entries cũ thành 1 câu súc tích hơn.

### `backend-log.md` — NHẬT KÝ (đọc khi cần)

**Chỉ đọc 20 dòng đầu file (entries mới nhất)** khi:
- Orchestrator hỏi "tiếp tục task hôm qua".
- Bạn cần biết task trước đang dở ở đâu.
- Trước daily standup.

**Không đọc toàn bộ file** — sẽ tốn token vô ích.

**Cuối mỗi session làm việc** (trước khi báo orchestrator "xong"), append 1 entry mới ở ĐẦU file theo template có sẵn trong file. Entry ngắn gọn, tập trung:
- Việc đã xong (kèm commit hash nếu có)
- Việc còn dở
- Blocker (nếu có)

**Quy tắc vàng**: mới nhất ở đầu, không xoá entry cũ (append-only).
```

---

## 2. Thêm vào cuối `.claude/agents/frontend-dev.md`

```markdown
---

## Memory system — bộ nhớ bền vững

Bạn có 2 file memory trong `.claude/memory/`:

### `frontend-knowledge.md` — TRI THỨC (đọc mỗi lần)

**Luôn đọc file này ở đầu mỗi session.** Nó chứa quyết định kiến trúc FE, pattern đã dùng (component organization, hook pattern, state management), thư viện đã chọn, pitfall đã gặp.

**Khi nào bạn UPDATE:**
- Gặp bug React/TypeScript tốn >1h debug → thêm vào "Pitfall".
- Chốt một lib mới (chart, virtualization, ...) → thêm vào "Thư viện".
- Phát triển pattern dùng chung cho nhiều feature → thêm vào "Pattern".

**Khi nào KHÔNG update:**
- Chi tiết task hằng ngày → ghi vào log.
- Preference cá nhân → không phải knowledge.

**Giới hạn 300 dòng** — vượt thì phải rút gọn.

### `frontend-log.md` — NHẬT KÝ (đọc khi cần)

**Chỉ đọc 20 dòng đầu** khi cần context task trước đó. Không đọc toàn bộ.

**Cuối session**: append entry mới ở đầu file (mới nhất trên cùng), kèm commit hash, task xong/dở/blocker.
```

---

## 3. Thêm vào cuối `.claude/agents/code-reviewer.md`

```markdown
---

## Memory system — bộ nhớ bền vững

Bạn có 2 file memory trong `.claude/memory/`, nhưng với vai trò đặc biệt:

### `reviewer-knowledge.md` — ARCHITECTURAL MEMORY

**Luôn đọc ở đầu session.** Bạn là người OWN file này — BE và FE cũng đọc nhưng không sửa. Nội dung gồm:
- **ADR** (Architectural Decision Records): mỗi quyết định lớn (chọn JWT vs session, chọn Redis vs in-memory, ...) là 1 ADR ngắn.
- **Contract version**: track version hiện tại của `API_CONTRACT.md` và `SOCKET_EVENTS.md`.
- **Review standard**: pattern thường lặp trong review → nâng lên thành quy tắc.
- **Approved/Rejected patterns**: pattern đã OK / đã từ chối, kèm lý do.

**Khi nào bạn UPDATE:**
- Sau mỗi quyết định kiến trúc → thêm ADR mới.
- Sau khi cập nhật contract → tăng version, ghi changelog.
- Khi phát hiện pattern đã review 3+ lần với cùng issue → nâng lên standard.

**Giới hạn 400 dòng** (cao hơn BE/FE vì bao quát cross-cutting).

### `reviewer-log.md` — REVIEW HISTORY

**Chỉ đọc 20 dòng đầu** khi cần biết "đã review gì gần đây", "branch này đã review lần nào chưa".

**Mỗi session review**: append 1 entry ở đầu file với verdict, file đã review, issue tìm thấy.

**Mỗi session viết contract**: cũng append entry ghi lại thêm gì, quyết định đặc biệt nào.

### Vai trò "single source of truth"

Bạn là người duy nhất được phép sửa các file contract:
- `docs/API_CONTRACT.md`
- `docs/SOCKET_EVENTS.md`
- `.claude/memory/reviewer-knowledge.md` (ADR + standard)

BE và FE có thể đề xuất thay đổi, nhưng bạn là người chốt và ghi vào các file trên.
```

---

## 4. Thêm vào cuối `CLAUDE.md` (orchestrator)

```markdown
---

## Memory system của team

Trong `.claude/memory/` có 6 file (3 knowledge + 3 log). Bạn KHÔNG trực tiếp đọc các file này — chúng thuộc về từng sub-agent. Nhưng bạn cần biết sự tồn tại của chúng để:

1. **Khi gọi sub-agent**, nhắc nó đọc knowledge file tương ứng ở đầu. Ví dụ: "backend-dev, đọc `.claude/memory/backend-knowledge.md` trước khi bắt đầu."
2. **Cuối mỗi phase (tuần)**, yêu cầu mỗi agent update log của nó, và update knowledge nếu có điều mới học được.
3. **Trước daily standup**, yêu cầu mỗi agent tóm tắt 20 dòng đầu log của mình — đây là cách biết tiến độ mà không tốn token đọc toàn bộ lịch sử.

### Lệnh chuẩn khi gọi agent

Thay vì: "backend-dev, làm POST /auth/register"

Dùng: "backend-dev, đọc `.claude/memory/backend-knowledge.md` trước, sau đó làm POST /auth/register theo contract trong `docs/API_CONTRACT.md`. Cuối session update log."

Cách viết dài hơn 1 chút nhưng giảm 50% số lần agent lặp lại bug cũ hoặc tạo pattern không nhất quán.

### Khi nào YÊU CẦU agent update knowledge

- Khi bạn thấy agent vừa debug một bug khó → "Ghi lại bug này vào `backend-knowledge.md` mục Pitfall."
- Khi team chốt một quyết định kiến trúc → "reviewer, thêm ADR vào `reviewer-knowledge.md`."
- Khi BE/FE chọn thư viện mới → "Ghi library này vào knowledge file."

Đừng ghi tất cả mọi thứ — chỉ ghi thứ có khả năng TÁI SỬ DỤNG.
```

---

## Cách apply patch

### Cách 1: Tự copy-paste (khuyến nghị)

Mở từng file trong `.claude/agents/` và `CLAUDE.md`, paste section tương ứng vào **cuối file**.

### Cách 2: Nhờ Claude Code tự làm

Trong Claude Code, gõ:

```
Tôi đã copy thư mục .claude/memory/ vào project. Giờ hãy:
1. Đọc file PATCH.md trong root (hoặc tôi sẽ paste vào đây).
2. Thêm các section memory system vào cuối 3 file agent và CLAUDE.md.
3. Verify bằng cách gọi /agents và check 3 agent vẫn load được.
```

Paste nội dung PATCH.md vào, Claude Code sẽ tự edit 4 file.
