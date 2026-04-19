# Memory Update — Thêm bộ nhớ bền vững cho agent team

## Trong bộ này có gì

```
memory-update/
├── README.md                              ← file bạn đang đọc
├── PATCH.md                               ← hướng dẫn sửa 3 agent + CLAUDE.md
└── .claude/
    └── memory/
        ├── backend-knowledge.md           ← BE: tri thức (đọc mỗi lần)
        ├── backend-log.md                 ← BE: nhật ký (đọc khi cần)
        ├── frontend-knowledge.md          ← FE: tri thức
        ├── frontend-log.md                ← FE: nhật ký
        ├── reviewer-knowledge.md          ← Reviewer: ADR + standard
        └── reviewer-log.md                ← Reviewer: lịch sử review
```

## Các bước áp dụng vào project hiện có

### Bước 1: Copy thư mục memory vào project

```bash
# Giả sử project ở D:\CodeMonHoc\SE330\chat-app\agent-team\
# Copy thư mục .claude/memory/ từ bộ này vào project (cùng cấp với .claude/agents/)
```

Sau khi copy, structure project phải như sau:

```
chat-app/
└── agent-team/                ← (tên project của bạn)
    ├── .claude/
    │   ├── agents/            ← đã có từ trước
    │   │   ├── backend-dev.md
    │   │   ├── frontend-dev.md
    │   │   └── code-reviewer.md
    │   ├── memory/            ← MỚI: 6 file memory
    │   │   ├── backend-knowledge.md
    │   │   ├── backend-log.md
    │   │   ├── frontend-knowledge.md
    │   │   ├── frontend-log.md
    │   │   ├── reviewer-knowledge.md
    │   │   └── reviewer-log.md
    │   └── settings.json
    ├── CLAUDE.md
    └── docs/
```

### Bước 2: Apply PATCH.md

Mở file `PATCH.md`, làm theo 1 trong 2 cách:

**Cách 1 (thủ công)**: copy từng section vào cuối file tương ứng:
- Section 1 → cuối `.claude/agents/backend-dev.md`
- Section 2 → cuối `.claude/agents/frontend-dev.md`
- Section 3 → cuối `.claude/agents/code-reviewer.md`
- Section 4 → cuối `CLAUDE.md`

**Cách 2 (nhờ Claude)**: mở Claude Code trong project, paste toàn bộ PATCH.md vào, yêu cầu nó apply.

### Bước 3: Verify

Trong Claude Code, gõ:

```
Check memory system:
1. Liệt kê file trong .claude/memory/
2. Đọc 20 dòng đầu backend-knowledge.md xác nhận format đúng
3. Gọi backend-dev, yêu cầu nó đọc backend-knowledge.md và trả lời: file hiện có quyết định kiến trúc nào chưa?
```

Nếu backend-dev đọc được file và báo đúng "chưa có quyết định nào được chốt" → memory system hoạt động.

## Nguyên tắc vàng khi dùng memory

### Knowledge ≠ Log

- **Knowledge**: ngắn, tri thức chắt lọc, đọc mỗi lần, giới hạn 300-400 dòng.
- **Log**: dài, lịch sử, chỉ đọc 20 dòng đầu, không giới hạn độ dài.

Đừng lẫn. Nếu ghi mọi task hằng ngày vào knowledge → file sẽ phình ra → agent đọc mỗi lần → cháy token.

### "Chỉ ghi thứ có giá trị tái sử dụng"

Hỏi bản thân trước khi thêm entry vào knowledge:
- "Thông tin này có ích cho task tuần sau không?"
- "Không có thông tin này, agent sẽ lặp lại lỗi gì?"
- "Nếu đây là team người thật, họ có ghi vào wiki không?"

Nếu cả 3 câu trả lời là "không rõ" → đừng ghi vào knowledge, ghi vào log là đủ.

### Update định kỳ, không phải liên tục

- **Cuối mỗi task**: update log (append 1 entry).
- **Cuối mỗi tuần**: orchestrator tổng kết, hỏi từng agent có gì đáng nâng từ log lên knowledge không.
- **Cuối mỗi phase (tuần 2, 4, 6, 8)**: reviewer rà soát knowledge, rút gọn entries cũ.

### Commit memory vào git

`.claude/memory/` nên được commit vào git, KHÔNG ignore. Lý do:
- Đây là "trí nhớ chung của dự án" — các lập trình viên thật sau này đọc cũng có giá trị.
- Nếu có sự cố (agent làm hỏng), có thể `git restore`.
- Team thật join sau có thể đọc để hiểu lịch sử quyết định.

Nhưng cẩn thận: đừng commit API key, password, hay link nội bộ vào memory. Knowledge nói về pattern, không nói về secret.

## Tiết kiệm token — kỳ vọng thực tế

Bạn sẽ thấy tiết kiệm rõ nhất khi:
- Đến tuần 3-4 (memory đã có nội dung).
- Agent tránh lặp lại debug cùng 1 bug Spring/React (mỗi lần tiết kiệm 5-15k token).
- Agent không tạo pattern lặp (vd không viết 2 cách handle error khác nhau).

Không tiết kiệm ở tuần 1:
- Memory rỗng, agent không có gì để "nhớ".
- Đọc file rỗng cũng tốn ít token (~100 token/lần), không đáng kể.

## Khi nào KHÔNG nên dùng memory

- Dự án rất ngắn (<1 tuần): overhead setup không bõ.
- Team chỉ 1 agent: context window của 1 session đã đủ.
- Chưa quen sub-agent: tập trung vào workflow cơ bản trước, memory thêm sau.

Với dự án 8 tuần, 3 agent của bạn → memory rất đáng giá.
