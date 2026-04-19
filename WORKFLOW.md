# WORKFLOW.md — Làm việc hằng ngày với agent team

## Mô hình tương tác

```
                      Bạn
                       │
                       ▼
         ┌──────────────────────────┐
         │  Main Claude Code        │
         │  (orchestrator)          │
         └───────────┬──────────────┘
                     │ gọi qua Task tool
         ┌───────────┼───────────┐
         ▼           ▼           ▼
    ┌────────┐  ┌────────┐  ┌──────────┐
    │   BE   │  │   FE   │  │ Reviewer │
    └────────┘  └────────┘  └──────────┘
```

Bạn **không gọi sub-agent trực tiếp**. Bạn nói với main session (orchestrator), nó quyết định gọi ai.

## Ngày 1: Bắt đầu tuần 1

```bash
cd ~/projects/chat-app
claude
```

Trong chat, gõ:

> Xin chào. Đây là dự án chat app đã có ARCHITECTURE.md trong docs/. Tôi muốn bắt đầu tuần 1 của roadmap. Trước khi code, hãy:
> 1. Đọc docs/ARCHITECTURE.md mục roadmap tuần 1.
> 2. Gọi code-reviewer viết contract v1 cho Auth (register + login) vào docs/API_CONTRACT.md.
> 3. Tóm tắt kế hoạch tuần 1 cho tôi duyệt trước khi bắt đầu.

Orchestrator sẽ làm theo thứ tự:
- Đọc ARCHITECTURE.md.
- Gọi `code-reviewer` (delegate qua Task tool) → reviewer tạo `docs/API_CONTRACT.md`.
- Báo cáo lại bạn.

Bạn review contract, OK thì nói:
> Contract OK. Tiếp tục:
> - Gọi backend-dev setup Spring Boot project + PostgreSQL + migration user table.
> - Gọi frontend-dev setup Vite + React + TailwindCSS + routing cơ bản + trang Login/Register static.
> - Làm song song.

## Mẫu lệnh cho từng tình huống

### Bắt đầu 1 feature

> Làm phần [auth / conversation CRUD / messaging / file upload] theo roadmap tuần N.
> Quy trình: reviewer viết contract → BE + FE code song song → reviewer review diff.

### Fix bug

> Có bug: [mô tả bug]. Xác định là BE hay FE, gọi agent tương ứng debug và fix. Sau khi fix, gọi reviewer check lại diff.

### Đã viết xong, muốn review

> Tôi vừa merge branch feat/w2-auth. Gọi code-reviewer review diff so với main, verdict trước khi tôi tag release.

### Cần quyết định kiến trúc

> Tôi đang phân vân giữa [option A] và [option B] cho [feature]. Gọi code-reviewer phân tích ưu nhược và đề xuất, tôi sẽ quyết cuối cùng.

### Daily standup (15 phút đầu ngày)

> Standup ngày hôm nay. Check:
> - Hôm qua team đã làm gì (đọc git log và commit message).
> - Có task nào còn dở không?
> - Có blocker gì không (contract lệch, test fail, dependency chờ)?
> - Gợi ý 3 task ưu tiên cho hôm nay.

Orchestrator có thể cần gọi reviewer để đánh giá tình trạng contract, hoặc tự chạy `git log`, `git status`.

## Quy tắc khi delegate

### Nguyên tắc "right tool for the job"

| Task | Gọi ai |
|------|--------|
| Viết contract trước khi code | `code-reviewer` |
| Viết Spring Boot controller / service / entity | `backend-dev` |
| Viết React component / hook | `frontend-dev` |
| Review diff đã xong | `code-reviewer` |
| Thiết kế socket flow | `code-reviewer` (architect hat) |
| Viết migration SQL | `backend-dev` |
| Viết TypeScript type | `frontend-dev` |
| Viết Dockerfile, docker-compose | Tự orchestrator (infra chung) |
| Setup CI/CD | Tự orchestrator |
| Sửa `.md` trong docs | `code-reviewer` (owner contract) |

### Khi nào KHÔNG delegate

- Task nhỏ (sửa 1-2 dòng) → orchestrator tự làm cho nhanh.
- Lệnh bash rõ ràng (`git log`, `npm test`) → orchestrator tự chạy.
- Câu hỏi kiến thức chung (ví dụ: "JWT là gì?") → orchestrator tự trả lời.

## Git workflow

### Branch naming

- `main` — production-ready, chỉ merge từ `dev`.
- `dev` — integration, merge từ feature branch hằng ngày.
- `feat/w<tuần>-<tên>` — feature branch. Vd: `feat/w2-auth`, `feat/w4-messaging`.
- `fix/<mô-tả>` — bugfix.

### Quy trình cho một feature

```
1. Người dùng: "Làm tuần 2 auth."
2. Orchestrator:
   - git checkout dev
   - git pull
   - git checkout -b feat/w2-auth
3. Gọi reviewer viết contract, commit [REVIEW] docs: auth contract v1.
4. Gọi BE + FE song song. Mỗi agent commit riêng:
   - [BE] feat: add POST /auth/login endpoint
   - [FE] feat: add Login page + auth store
5. Gọi reviewer review diff:
   - git diff dev...feat/w2-auth
6. Nếu reviewer REQUEST CHANGES: gọi BE/FE fix, review lại.
7. Nếu reviewer APPROVE: orchestrator báo cáo người dùng, chờ lệnh merge.
8. Người dùng confirm → orchestrator: git checkout dev && git merge feat/w2-auth.
```

### Agent không tự push

Lý do: push là hành động không thể undo trên remote. Người dùng push thủ công sau khi review toàn bộ.

```bash
# Bạn chạy tay, không agent nào tự chạy lệnh này:
git push origin dev
```

## Xử lý xung đột giữa agent

**Trường hợp thường gặp**: BE trả về `{ "id": 1 }` nhưng FE đọc `{ "userId": 1 }` vì contract chưa rõ.

Giải pháp:
1. Orchestrator phát hiện (thường là khi integration test fail).
2. Gọi `code-reviewer` xác định contract đúng là gì.
3. Reviewer cập nhật `docs/API_CONTRACT.md`.
4. Gọi BE hoặc FE (bên nào đang sai) sửa theo contract.
5. Reviewer review lại.

**Quy tắc vàng**: contract thắng. Nếu contract sai, sửa contract TRƯỚC, rồi sửa code.

## Tips để tiết kiệm token

1. **Context rõ ràng ngay từ đầu**: khi gọi sub-agent, cho nó biết file nào cần đọc (vd: "đọc `docs/API_CONTRACT.md` mục Auth") thay vì để nó tự tìm.
2. **Đừng dài dòng**: sub-agent sẽ copy lại yêu cầu vào context của nó. Yêu cầu 1 câu > yêu cầu 10 câu.
3. **Dùng `/compact` định kỳ** trên main session khi conversation dài. Sub-agent có context riêng, không bị ảnh hưởng.
4. **Gộp task nhỏ**: thay vì gọi BE 5 lần cho 5 endpoint, gọi 1 lần với list 5 endpoint.
5. **Commit thường xuyên**: reviewer có `git diff` để đối chiếu, không cần đọc lại toàn bộ file.

## Khi agent "đi chệch hướng"

Triệu chứng:
- Sub-agent backend bắt đầu suggest thay đổi frontend.
- Reviewer đột nhiên viết code thay vì review.
- Agent quên mất contract và tự ý đổi response format.

Xử lý:
1. **Dừng session hiện tại**: Ctrl+C.
2. **Restart**: `claude` lại. Sub-agent sẽ reload từ file `.md`, không giữ "bad habits" từ session trước.
3. **Nhắc lại boundary** ở đầu session: "Làm ơn tuân thủ phạm vi trong file agent definition. Nếu task ngoài phạm vi, báo tôi."

Nếu lặp lại → có thể prompt trong `.md` chưa đủ rõ, edit file và thử lại.

## Khi tất cả agent đều stuck

Triệu chứng: 3 agent đều nói "tôi chờ agent khác".

Xử lý: đây là deadlock do thiếu contract. Quy tắc: **luôn luôn gọi reviewer viết contract TRƯỚC** mỗi khi bắt đầu feature. Nếu stuck, dừng → gọi reviewer viết/cập nhật contract → resume.

## Khi nào nâng cấp lên cách B (git worktrees + 3 terminal)

Dấu hiệu bạn đã sẵn sàng:
- Đã làm xong tuần 1-3 với sub-agents.
- Tuần 4-5 cần BE và FE song song thật sự (messaging layer phức tạp).
- Bạn đã quen với việc review output của sub-agent.
- Ngân sách token đủ cho 3 session cùng lúc.

Lúc đó, tham khảo Anthropic engineering blog "Managing multi-agent coding with git worktrees" (search trên engineering.anthropic.com) để setup cách B. Các file agent definition trong `.claude/agents/` vẫn dùng được nguyên vẹn.

## Checklist "đã sẵn sàng làm việc"

Trước khi bắt đầu phase code, confirm:

- [ ] `docs/ARCHITECTURE.md` có trong project.
- [ ] `CLAUDE.md` ở root, orchestrator đã đọc.
- [ ] 3 file agent trong `.claude/agents/` đã load (gõ `/agents` để check).
- [ ] `docs/API_CONTRACT.md` có (dù rỗng hoặc mới bắt đầu).
- [ ] `docs/SOCKET_EVENTS.md` có.
- [ ] Git repo đã init, có branch `dev`.
- [ ] Docker Compose file đã test chạy được (Postgres + Redis).
- [ ] Đã quen lệnh `/agents`, `/compact`, `/clear` trong Claude Code.
