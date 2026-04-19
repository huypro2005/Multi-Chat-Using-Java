# SETUP.md — Hướng dẫn cài đặt từng bước

## Bước 1. Cài Claude Code

Claude Code là CLI chính thức của Anthropic để làm agentic coding. Yêu cầu Node.js 18+.

```bash
npm install -g @anthropic-ai/claude-code
```

Xác thực (chọn 1 trong 2):
- **Claude Pro/Max**: chạy `claude`, nó sẽ mở browser để login — đây là cách tiết kiệm nhất nếu bạn đã có plan.
- **API key**: đặt biến môi trường `ANTHROPIC_API_KEY=sk-ant-...` — tính theo usage, phù hợp nếu bạn chạy nhiều.

Test:
```bash
cd ~/Desktop
mkdir test-claude && cd test-claude
claude
```
Gõ "Hello, list files in current directory" — nếu thấy nó gọi tool và trả lời, setup xong.

## Bước 2. Tạo project folder

```bash
mkdir -p ~/projects/chat-app
cd ~/projects/chat-app

# Tạo cấu trúc
mkdir -p .claude/agents docs backend frontend

# Khởi tạo git (quan trọng — reviewer sẽ dùng git diff)
git init
git branch -M main
git checkout -b dev
```

## Bước 3. Copy `ARCHITECTURE.md` vào `docs/`

```bash
cp /đường/dẫn/ARCHITECTURE.md docs/ARCHITECTURE.md
```

Đây là **nguồn chân lý** (source of truth). Tất cả 3 agent và orchestrator đều sẽ đọc file này.

## Bước 4. Copy các file agent

Copy 3 file sau từ bộ hướng dẫn vào project:

```bash
cp /đường/dẫn/agent-team/.claude/agents/backend-dev.md   .claude/agents/
cp /đường/dẫn/agent-team/.claude/agents/frontend-dev.md  .claude/agents/
cp /đường/dẫn/agent-team/.claude/agents/code-reviewer.md .claude/agents/
```

Copy `CLAUDE.md` vào root:
```bash
cp /đường/dẫn/agent-team/CLAUDE.md CLAUDE.md
```

Copy templates:
```bash
cp /đường/dẫn/agent-team/templates/API_CONTRACT.md  docs/
cp /đường/dẫn/agent-team/templates/SOCKET_EVENTS.md docs/
```

## Bước 5. (Tuỳ chọn) Cấu hình permissions

Claude Code sẽ hỏi xin phép mỗi lần chạy bash, edit file. Để đỡ phải duyệt tay quá nhiều lần, tạo file `.claude/settings.json`:

```json
{
  "permissions": {
    "allow": [
      "Bash(git:*)",
      "Bash(npm:*)",
      "Bash(npx:*)",
      "Bash(mvn:*)",
      "Bash(./gradlew:*)",
      "Bash(docker:*)",
      "Bash(docker-compose:*)",
      "Bash(ls:*)",
      "Bash(cat:*)",
      "Bash(grep:*)",
      "Bash(rg:*)",
      "Bash(find:*)",
      "Edit",
      "Write",
      "Read"
    ],
    "deny": [
      "Bash(rm -rf:*)",
      "Bash(git push:*)",
      "Bash(curl:*)",
      "Bash(wget:*)"
    ]
  }
}
```

**Giải thích lựa chọn**:
- `allow`: các lệnh lặp đi lặp lại, an toàn (git, npm, docker, file ops).
- `deny`: lệnh nguy hiểm hoặc có side effect ra ngoài máy (push code, download).
- `git push` nằm trong `deny` vì bạn nên review trước khi push thật.

## Bước 6. Test sub-agents

Trong folder project, chạy:
```bash
claude
```

Test lần lượt từng agent bằng cách type trong chat:

```
Gọi sub-agent backend-dev và yêu cầu nó đọc docs/ARCHITECTURE.md, sau đó tóm tắt trong 3 câu phần Auth API cần xây.
```

Nếu nó gọi được `backend-dev`, đọc file, và tóm tắt đúng — agent này hoạt động. Lặp lại với `frontend-dev` và `code-reviewer`.

Lệnh `/agents` trong Claude Code cho bạn xem danh sách sub-agent đã load, verify file `.md` có được nhận diện không.

## Bước 7. Sẵn sàng bắt đầu tuần 1

Giờ đọc `WORKFLOW.md` để biết cách tương tác hằng ngày với team.

## Troubleshooting

**Sub-agent không được nhận diện**
- Check file có YAML frontmatter đúng format không (xem đầu mỗi file `.md` trong `.claude/agents/`).
- Thư mục đúng phải là `.claude/agents/` (dấu chấm đầu), không phải `claude/agents/`.
- Gõ `/agents` trong Claude Code để xem list. Nếu không có, restart `claude`.

**Agent bỏ qua CLAUDE.md**
- File phải ở **root project** (cùng cấp với `.claude/`), không phải trong `docs/`.
- Nội dung phải ngắn gọn — nếu quá dài (>500 dòng), agent có xu hướng skim.

**Sub-agent gọi sub-agent khác (infinite loop)**
- Trong Claude Code, sub-agent KHÔNG gọi được sub-agent khác theo mặc định. Nếu thấy loop, check lại prompt có yêu cầu nó "delegate" gì không — bỏ từ khoá đó đi.

**Token quá nhanh hết**
- Mỗi sub-agent có context riêng → gọi nhiều = tốn token hơn 1 agent. Chấp nhận trade-off hoặc cân nhắc gộp BE+FE thành 1 agent nếu ngân sách eo hẹp.
- Dùng `/compact` trong main session khi context dài để nén lại.
