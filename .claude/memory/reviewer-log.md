# Reviewer Log — Nhật ký review

> Append-only, mới nhất ở đầu file.
> Mỗi session review tạo 1 entry.

---

## Template cho entry review

```
## YYYY-MM-DD — Review <branch / task name>

### Verdict
✅ APPROVE / ⚠️ APPROVE WITH COMMENTS / ❌ REQUEST CHANGES

### Files reviewed
- <path>: <1 câu tóm tắt thay đổi>

### Issues found
- [BLOCKING] <vấn đề> — đã yêu cầu fix
- [WARNING] <vấn đề> — gợi ý, không block

### Contract impact
- Có/Không cập nhật contract
```

---

## 2026-04-19 — W3D3 Review: conversation list UI + create dialog + BE rate limit

### Verdict
⚠️ APPROVE WITH COMMENTS

Không có BLOCKING. UI foundation clean; Esc cleanup đúng; 409 UX đúng pattern đã chốt; rate limit INCR đúng ADR-005. Có 3 warning non-blocking (fail-closed khi Redis down, contract drift giá trị rate limit 10/min vs 30/giờ, FE chưa handle 429 toast) + vài gợi ý micro. Ship được.

### Files reviewed
- `frontend/src/features/conversations/components/ConversationListItem.tsx` (mới, 86 dòng): row item, `React.memo`, dùng `ConversationSummaryDto`.
- `frontend/src/features/conversations/components/ConversationListSidebar.tsx` (mới, 116 dòng): 4 states loading/error/empty/list không overlap, Plus button có `aria-label`.
- `frontend/src/features/conversations/components/CreateConversationDialog.tsx` (mới, 177 dòng): Esc handler có cleanup, backdrop click close, autoFocus input, 409 redirect UX đúng.
- `frontend/src/components/UserAvatar.tsx` (mới, 37 dòng): shared avatar, fallback initial letter.
- `frontend/src/features/conversations/utils.ts` (mới, 26 dòng): `getOtherMember` + `formatLastMessageTime`.
- `frontend/src/pages/ConversationsLayout.tsx` (sửa): wire sidebar + dialog; thay placeholder.
- `backend/src/main/java/com/chatapp/conversation/service/ConversationService.java` (sửa, +14 dòng): inject `StringRedisTemplate`; rate limit INCR/TTL đầu `createConversation()`.
- `docs/WARNINGS.md` (sửa): thêm W3-BE-6 vào bảng Resolved.

### Checklist BLOCKING pass
1. **UX 409 handling** PASS — `CreateConversationDialog.handleSelectUser` (L43–56): await `createConversation` → nếu `result.existingConversationId` → `navigate(existingConversationId)`, KHÔNG show toast error. `handleClose()` sau cả 2 nhánh (success + idempotent) → dialog đóng sau navigate.
2. **Active state logic** PASS — `ConversationListSidebar` L105 dùng strict `===` so sánh `conv.id === activeId`. `activeId` có thể `undefined` khi ở `/conversations` (không có `:id`) — `conv.id === undefined` luôn `false` → không highlight bừa. OK.
3. **Loading/Empty/Error không race** PASS — pattern đúng: `isLoading` (L53) isolate skeleton; `isError && !isLoading` (L71); `!isLoading && !isError && length===0` (L85). Không có trạng thái nào overlap — empty không hiện khi đang loading, error không hiện khi đang loading.
4. **Dialog accessibility** PASS — Esc handler `addEventListener` + `removeEventListener` trong cleanup (L33–34). Backdrop click → `handleClose()` (L65). `autoFocus` ở input (L116). Plus button có `aria-label="Tạo cuộc trò chuyện mới"` (L31). Dialog có `role="dialog"` + `aria-modal="true"` + `aria-label`. X button có `aria-label="Đóng dialog"`.
5. **React.memo** PASS — `ConversationListItem` wrap `memo(function ...)`. Props: `conversation` (stable reference từ list), `isActive` boolean, `onClick` (inline trong Sidebar, xem warning 4), `currentUserId` string. Pass serialize check.
6. **BE rate limit** — xem warning 1 bên dưới.
7. **FE handle 429** — xem warning 3 bên dưới.

### Warnings (non-blocking, V1 acceptable)

1. **[BE] Fail-CLOSED khi Redis down** — `ConversationService.java:45` `redisTemplate.opsForValue().increment(rateKey)` không wrap try/catch. Nếu Redis down → `RedisConnectionFailureException` / `RedisSystemException` bubble ra `GlobalExceptionHandler` catch-all → 500 INTERNAL_ERROR → user không tạo được conversation. Đây là **fail-CLOSED khác với pattern ADR-011 (fail-OPEN cho JWT blacklist)**. Lý do trái pattern: rate limit mất Redis = toàn bộ user không tạo được conv, trong khi JWT blacklist fail-open = logged-out token vẫn work đến natural expiry — trade-off khác nhau. Rate limit nên cũng fail-open (log warn, skip counter) để availability tốt hơn, vì rủi ro abuse 10 conv/min < rủi ro downtime feature chính. Gợi ý fix V1 ngắn:

   ```java
   Long count;
   try {
       count = redisTemplate.opsForValue().increment(rateKey);
       if (count != null && count == 1) redisTemplate.expire(rateKey, 60, TimeUnit.SECONDS);
   } catch (DataAccessException e) {
       log.warn("[RATE_LIMIT] Redis unavailable for key={}, fail-open", rateKey, e);
       count = null;
   }
   if (count != null && count > 10) throw ...
   ```

   Có thể defer V2 nếu monitoring Redis availability. Ghi nhận trong WARNINGS.md.

2. **[Contract drift] Giá trị rate limit lệch contract** — `API_CONTRACT.md:482` ghi "Vượt 30 conversations/giờ/user"; code implement "10/phút/user". Semantic khác nhau (30/giờ = ~0.5/phút trung bình nhưng cho phép burst cao hơn 10/phút nếu trung bình). Contract nên được sync để match implementation (hoặc ngược lại). Đề xuất **sync contract = code** (10/phút đơn giản hơn 30/giờ với window cố định, phù hợp với ADR-005). Reviewer sẽ update contract dòng 482 + Rate limit header ở đầu endpoint POST /api/conversations trong commit docs tiếp theo.

3. **[FE] 429 không có toast user-facing** — `api.ts` interceptor chỉ catch 401. Khi BE trả 429 RATE_LIMITED, axios error bubble thẳng lên `useCreateConversation.mutateAsync` → `handleSelectUser` **await throw** → promise reject → **không có UI feedback**. Người dùng sẽ thấy dialog đóng (vì `handleClose()` sau try, nhưng ở đây không try/catch nên handleClose ở L55 **không chạy khi throw**) — thực tế dialog giữ nguyên, không có message. UX kém.

   Fix V1 ngắn: thêm try/catch trong `handleSelectUser`:
   ```ts
   try {
     const result = await createConversation({ ... })
     if (result.conversation) navigate(...)
     else if (result.existingConversationId) navigate(...)
     handleClose()
   } catch (err) {
     if (axios.isAxiosError(err) && err.response?.status === 429) {
       toast.error('Bạn tạo quá nhanh, thử lại sau 1 phút')
     } else {
       toast.error('Không tạo được cuộc trò chuyện, thử lại sau')
     }
   }
   ```

   Nếu project chưa wire toast library, ít nhất thêm local error state hiển thị trong dialog. Non-blocking cho V1 happy path nhưng **bắt buộc fix trước demo**.

4. **[FE] Inline arrow trong ConversationListSidebar L106 phá benefit React.memo** — `onClick={() => navigate(...)}` tạo function mới mỗi render → prop `onClick` thay đổi → `React.memo` shallow-compare fail → re-render hết list items mỗi khi sidebar re-render. Memo không giúp gì ở flow hiện tại. Non-blocking cho V1 (list ≤50 items). Fix khi scale: `useCallback((id) => navigate(...), [navigate])` + chuyển sang pattern `onSelect(id)` để child gọi `onClick={() => onSelect(conversation.id)}` (vẫn inline nhưng parent stable).

5. **[FE] `formatLastMessageTime` dùng `new Date().getTime()` mỗi render** — không sai, nhưng khi list 50 items mount thì 50 lần tính `diffMins`. Stale time (phút) không update theo realtime. V1 OK, ngày 4+ nếu cần "x phút trước" live-update cần `useInterval` re-render component parent.

6. **[FE] `unreadCount > 0` render badge nhưng server trả 0 V1** — L74 code check `> 0` trước khi render badge. BE V1 luôn 0 → badge không bao giờ hiện. Đúng ý đồ "chuẩn bị sẵn cho tuần 4+". OK.

7. **[BE] Rate limit key `rate:conv_create:{userId}` chưa có trong ADR-009 schema** — ADR-009 list 3 prefix (`rate:`, `refresh:`, `jwt:blacklist:`) và đề cập scope `register/login/refresh` nhưng không explicit `conv_create`. Nên update ADR-009 thêm `rate:conv_create:{userId}` vào danh sách scope đã dùng để tránh developer mới đặt trùng key sau này. Non-blocking — key an toàn (prefix `rate:` đã reserved).

### Contract check
- `API_CONTRACT.md` POST /api/conversations: error `RATE_LIMITED` (429) đã có trong contract. Code throw đúng code `"RATE_LIMITED"` khớp.
- **Lệch giá trị**: contract "30/giờ/user" vs code "10/phút/user" — sync cần thiết (xem warning 2).
- Contract nói error có `details.retryAfterSeconds` — code hiện KHÔNG set details. AppException constructor dùng 3-arg `(HttpStatus, code, message)` không có details Map. `GlobalExceptionHandler` response sẽ thiếu `details.retryAfterSeconds` → FE không có info để hiển thị "thử lại sau X giây". Gợi ý fix: sau `getExpire(rateKey, TimeUnit.SECONDS)` → throw với `details = Map.of("retryAfterSeconds", ttl)`. Non-blocking cho V1 (FE chưa dùng field này), nhưng contract nói có → hoặc sửa code hoặc sửa contract (khuyến nghị sửa code).

### Suggestions (hoàn toàn tuỳ chọn)
- `ConversationListItem` line "Bắt đầu trò chuyện" hardcode VN — OK cho V1, nhưng nếu sau muốn hiện snippet last message, cần thêm field `lastMessagePreview` vào `ConversationSummaryDto` (contract update).
- `CreateConversationDialog` backdrop và dialog có **2 layer z-50 riêng biệt** (L64 + L74). `pointer-events-none` trên dialog + `pointer-events-auto` trên inner box — pattern hợp lệ nhưng dễ confuse khi thêm focus trap sau này. Có thể gộp thành 1 wrapper z-50 với `stopPropagation` đơn giản hơn. Non-blocking.
- `UserAvatar` type `user: { fullName?: string; username?: string; avatarUrl?: string | null }` là structural subtyping — accept bất kỳ object nào có shape này. OK reusable, nhưng có thể tighten nếu cần: `Pick<UserSearchDto, 'fullName' | 'username' | 'avatarUrl'>` để tránh lạm dụng.

### Action items cần follow-up
- Update contract `API_CONTRACT.md:482` sync giá trị "10 conversations/phút/user" (hoặc ngược lại, tuỳ BE quyết).
- Update ADR-009 thêm `rate:conv_create:{userId}` vào schema list.
- Khi thêm toast library → fix 429 UX trong `handleSelectUser`.
- Cân nhắc wrap Redis call fail-open (warning 1).

### Contract impact
- Draft update v0.5.1-conversations: sync rate limit value. Reviewer sẽ viết commit contract riêng sau khi BE xác nhận giá trị cuối (10/min hay giữ 30/giờ).

---

## 2026-04-19 — W3D2 Re-check sau khi BE+FE fix 2 BLOCKING

### Verdict
✅ APPROVE

Re-check 3 items sau fix round: (1) FE `api.ts:32` đã đổi sang `.error === 'CONV_ONE_ON_ONE_EXISTS'` + import `ApiErrorBody` từ `@/types/api` (file mới `types/api.ts` có interface đúng shape BE ErrorResponse). Cast 2 lần `as ApiErrorBody | undefined` hơi dài nhưng acceptable — không drift nữa. (2) FE `types/conversation.ts` `ConversationDto` giờ chỉ còn 8 field (id/type/name/avatarUrl/createdBy/members/createdAt/lastMessageAt) — 4 field Summary-only đã bỏ. Helper `getConversationDisplayName(conv, currentUserId)` derive đúng (GROUP→`conv.name` fallback 'Nhóm không tên'; ONE_ON_ONE→fullName/username của other member). (3) BE `ConversationService.createGroup` có đủ 3 validation: caller-in-memberIds check (line 134), dedupe via `.distinct()` (line 141), max 49 (line 151). 2 BLOCKING từ W3D2 đã RESOLVED. Các warning non-blocking khác (N+1, rate limit TODO, UserController cross-package) giữ nguyên trong WARNINGS.md.

---

## 2026-04-19 — W3D2 Review: BE 4 endpoints (createConversation, list, detail, users.search) + FE API scaffold

### Verdict
❌ REQUEST CHANGES

Có 1 BLOCKING ở FE sẽ **phá hoàn toàn flow 409 idempotency** + 1 BLOCKING drift types giữa FE và BE response. BE về cơ bản đúng contract, chỉ có vài warning non-blocking. Sau khi FE fix 2 điểm này và chạy type-check thì có thể APPROVE.

### Files reviewed
- `backend/src/main/java/com/chatapp/conversation/service/ConversationService.java`: mới, 348 dòng. 4 method: createConversation (branch ONE_ON_ONE/GROUP), listConversations (offset pagination + 2-query pattern), getConversation (merge 404 anti-enumeration), searchUsers (min 2 chars, exclude self + non-active).
- `backend/src/main/java/com/chatapp/conversation/controller/ConversationController.java`: mới, 3 endpoints POST/GET list/GET {id}. `@AuthenticationPrincipal User`. 201 cho POST.
- `backend/src/main/java/com/chatapp/user/controller/UserController.java`: mới, 1 endpoint GET /api/users/search. Delegate vào ConversationService.searchUsers (gọi chéo package — xem warning).
- `backend/src/main/java/com/chatapp/conversation/dto/*.java`: 7 DTOs (ConversationDto, ConversationSummaryDto, ConversationListResponse, CreateConversationRequest, CreatedByDto, MemberDto, UserSearchDto).
- `backend/src/main/java/com/chatapp/conversation/repository/ConversationRepository.java`: thêm 3 native query (findExistingOneOnOne, findConversationsByUserPaginated, countConversationsByUser).
- `backend/src/main/java/com/chatapp/user/repository/UserRepository.java`: thêm searchUsers @Query LIKE (prefix username + substring fullName).
- `backend/src/main/java/com/chatapp/conversation/entity/Conversation.java` + `ConversationMember.java`: refactor UUID — **bỏ @GeneratedValue**, chuyển sang @PrePersist set `UUID.randomUUID()` (Option B từ warning W3-BE-1 đã RESOLVED).
- `backend/src/test/java/com/chatapp/conversation/ConversationControllerTest.java`: mới, 15 test cases (1 W3-BE-1 unit + 14 integration), MockBean Redis + Firebase.
- `frontend/src/types/conversation.ts`: mới, types cho ConversationDto/Summary/PageResponse/UserSearchDto. Dùng `const object + type` pattern thay vì enum.
- `frontend/src/features/conversations/api.ts`: mới — createConversation với try/catch 409, listConversations, getConversation.
- `frontend/src/features/conversations/hooks.ts`: mới — useConversations, useConversation, useCreateConversation.
- `frontend/src/features/users/api.ts` + `hooks.ts`: mới — searchUsers + useUserSearch với debounce 300ms.
- `frontend/src/hooks/useDebounce.ts`: mới, useDebounce generic 10 dòng.
- `frontend/src/lib/queryClient.ts`: mới, QueryClient retry 1 + staleTime 30s.

### Issues found

#### BLOCKING

1. **[FE][BLOCKING] `frontend/src/features/conversations/api.ts:31` — 409 handler đọc sai tên field, idempotency flow sẽ LUÔN throw thay vì return existingConversationId.**
   - Code hiện: `(err.response.data as { code?: string }).code === 'CONV_ONE_ON_ONE_EXISTS'`.
   - BE `ErrorResponse` (xem `backend/.../exception/ErrorResponse.java`) có shape `{ error, message, timestamp, details }` — field là **`error`** chứ không phải **`code`**. Đã chốt trong contract đầu file `API_CONTRACT.md` dòng 13-18.
   - Hệ quả: khi user tạo dup ONE_ON_ONE, BE trả 409 đúng contract với `{error: "CONV_ONE_ON_ONE_EXISTS", details: {conversationId: "..."}}`, nhưng FE check `.code` luôn false → không catch, throw ra UI → mất toàn bộ UX "redirect sang conv cũ".
   - **Fix**: đổi `.code` → `.error`. Ngoài ra nên dùng chung type cho error response (ví dụ `interface ApiErrorBody { error: string; message: string; details?: any }`) import từ 1 chỗ để không bao giờ drift lại.

2. **[FE][BLOCKING] `frontend/src/types/conversation.ts:29-41` — `ConversationDto` định nghĩa có `displayName`, `displayAvatarUrl`, `unreadCount`, `mutedUntil` nhưng BE response KHÔNG trả các field đó trong full DTO.**
   - BE `ConversationDto` (từ `POST /api/conversations` 201 và `GET /api/conversations/{id}` 200) chỉ có 8 field: `id, type, name, avatarUrl, createdBy, members, createdAt, lastMessageAt`. Xem `backend/.../dto/ConversationDto.java`.
   - Các field `displayName/displayAvatarUrl/unreadCount/mutedUntil` **chỉ có trong `ConversationSummaryDto`** (GET list). Contract API_CONTRACT.md dòng 436-471 (POST 201 shape) và dòng 510-533 (GET list shape) đã phân biệt rõ 2 shape.
   - Hệ quả: khi FE truy cập `conversation.displayName` (ở detail page) → `undefined` tại runtime, TypeScript không cảnh báo (vì types nói có). UI sẽ render rỗng.
   - **Fix**: xóa 4 field đó khỏi `ConversationDto`. Tạo helper ở FE để **derive displayName từ members + currentUserId** (tìm other member cho ONE_ON_ONE, dùng `conversation.name` cho GROUP). Hoặc nếu muốn `displayName` xuất hiện ở cả 2 endpoint → cần request BE update DTO; nhưng theo contract đã chốt thì detail KHÔNG có, FE derive.

#### Warnings (non-blocking)

3. **[BE][WARNING] `ConversationService.java:216-220` — N+1 query khi list conversations.**
   - Sau khi có `convIds` từ native query, loop từng `convId` và gọi `findByIdWithMembers(convId)` → mỗi conv 1 query riêng (LEFT JOIN FETCH members + users). Với size=20 → 21 query tổng (1 list + 20 detail-fetch).
   - Contract đã documented V1 accept nhưng đây là pattern dễ scale-blow. Khuyến nghị:
     - V1 (giữ như hiện tại): OK vì size ≤ 50 và dataset nhỏ. Comment đã ghi rõ `// acceptable for V1`.
     - V2: thay bằng 1 query duy nhất `SELECT c, m, u FROM Conversation c LEFT JOIN FETCH c.members m LEFT JOIN FETCH m.user u WHERE c.id IN :ids` (1 query cho N conversations) — tránh Hibernate Pagination+JOIN FETCH pitfall bằng cách chia 2 bước: native query lấy IDs (đã làm), rồi batch JPA fetch.
   - Không block V1.

4. **[BE][WARNING] `ConversationService.java:134-146` — loop `userRepository.findById` cho mỗi memberId.**
   - Với GROUP tối đa 49 memberIds → 49 query riêng lẻ cộng với query tạo 49 member. Nên batch: `userRepository.findAllById(memberIds)` → 1 query; so sánh kết quả với request để tìm `missingIds`.
   - Non-blocking cho V1 (traffic thấp, group size trung bình 2-10), nhưng là easy win.

5. **[BE][WARNING] `ConversationService.java` — KHÔNG validate `memberIds` cho duplicate và exclude caller.**
   - Contract dòng 432: "không chứa UUID của caller (caller tự add qua OWNER role). Không được có phần tử trùng".
   - Hiện `createGroup`:
     - Nếu request `memberIds: [B, B, C]` → service fetch 3 lần (B, B, C) → targetUsers.size=3, tạo 3 ConversationMember, nhưng 1 trong đó đụng UNIQUE `(conversation_id, user_id)` → DataIntegrityViolationException → 500 INTERNAL_ERROR (không phải 400 VALIDATION_FAILED).
     - Nếu request `memberIds: [callerId, B]` → caller sẽ bị add làm MEMBER + tiếp sau add OWNER → UNIQUE violate → 500.
   - `createOneOnOne` có check self (line 61) nhưng không check duplicate cho array (dù chỉ 1 phần tử nên không hit trực tiếp).
   - **Fix**: trước khi fetch, thêm:
     ```java
     Set<UUID> uniqueIds = new HashSet<>(req.memberIds());
     if (uniqueIds.size() != req.memberIds().size()) throw VALIDATION_FAILED;
     if (uniqueIds.contains(currentUser.getId())) throw VALIDATION_FAILED;
     ```
   - Non-blocking V1 vì FE tự guard, nhưng BE defense-in-depth cần có.

6. **[BE][WARNING] `ConversationService.createGroup` — chưa enforce `memberIds.size() <= 49`.**
   - Contract dòng 434: GROUP max 49 (cộng caller = 50). Service chỉ check min 2, không check max.
   - Nếu FE (hoặc client lạ) gửi 200 memberIds → tạo group 201 thành viên vượt ARCHITECTURE limit.
   - Fix: thêm `if (req.memberIds().size() > 49) throw VALIDATION_FAILED`.

7. **[BE][WARNING] Rate limit toàn bộ 4 endpoints Conversations chưa implement.**
   - Contract: POST 30/h/user, GET list 60/min/user, GET detail 120/min/user, search 30/min/user. Không thấy Redis INCR pattern nào trong service/controller.
   - Intent có thể là "để sau" — nhưng chưa có comment/TODO ghi rõ. Đề xuất: thêm `// TODO(W3-D5): rate limit — xem ADR-005` hoặc log vào WARNINGS.md để không quên.

8. **[BE][WARNING] `UserController.java:27` — gọi chéo package: user module depend conversation service.**
   - `UserController` (package `com.chatapp.user.controller`) inject `ConversationService` (package `com.chatapp.conversation.service`). Ngược lại pattern — search users là **user domain**, không phải conversation domain.
   - Khuyến nghị refactor: tách `UserSearchService` trong `com.chatapp.user.service` (gọi `userRepository.searchUsers`). `ConversationService.searchUsers` xóa đi. Đồng thời `UserSearchDto` di chuyển sang `com.chatapp.user.dto`.
   - Non-blocking (code chạy đúng) nhưng sẽ khó maintain khi user module phát triển. Đề xuất làm clean-up ở Ngày 3-4.

9. **[BE][WARNING] `ConversationService.java:104-105` — `entityManager.flush() + clear()` sau khi save group/ONE_ON_ONE members.**
   - Rationale: flush để force commit members trước khi reload via `findByIdWithMembers`. Clear để đuổi stale entity khỏi cache. OK về mặt chức năng.
   - Concern: `entityManager.clear()` detach MỌI entity trong persistence context của request hiện tại. Nếu sau này service chain thêm logic (ví dụ audit log, event emit) dùng entity `currentUser` cũ → NullPointerException hoặc LazyInitException. V1 hiện không có chain, OK.
   - Alternative: thay `clear()` bằng `refresh()` trên conversation cụ thể. Hoặc cấu trúc lại để không cần clear (ví dụ DTO.from dùng eagerly loaded data trực tiếp). Non-blocking.

10. **[BE][WARNING] `ConversationControllerTest.java:191` — `jsonPath("$.name").doesNotExist()` cho response ONE_ON_ONE có name=null.**
    - `ConversationDto` dùng `@JsonInclude(JsonInclude.Include.ALWAYS)` → `null` vẫn serialize ra JSON. Jackson output: `"name": null`. MockMvc `jsonPath(...).doesNotExist()` expect path **không tồn tại trong JSON**, nhưng ở đây path tồn tại với value null.
    - Nếu test thực sự PASS → có thể Spring parser của JsonPath coi null tương đương không tồn tại. Nhưng đây là hành vi phiên bản-specific, fragile. Đề xuất thay bằng `jsonPath("$.name").value(nullValue())` (import `org.hamcrest.Matchers.nullValue`) hoặc `jsonPath("$.name").isEmpty()`.
    - Non-blocking nếu CI pass, nhưng stronger assertion clearer intent.

11. **[BE][WARNING] `ConversationRepository.findExistingOneOnOne` native query với `CAST(:userId AS UUID)` và `c.type = 'ONE_ON_ONE'`.**
    - Query CORRECT cho PG. Với H2 test profile phải có compatibility mode. Kiểm tra `application-test.yml` để xác nhận H2 mode=PostgreSQL. Không có trong diff → giả định đã setup W3D1. Non-blocking.
    - Cast là cần vì tham số là `String` (xem comment dòng 26 lý do H2 vs PG UUID mapping). OK.
    - KHÔNG false-positive: `c.type = 'ONE_ON_ONE'` filter group ra khỏi kết quả. Nếu có 2 user-A + user-B cùng nằm trong 1 GROUP → query vẫn trả 0 rows vì type ≠ ONE_ON_ONE. ✅ Verified.

12. **[BE][WARNING] Race condition ONE_ON_ONE tạo duplicate.**
    - Transaction isolation mặc định PostgreSQL = READ_COMMITTED. Hai request concurrent:
      - T1: SELECT findExistingOneOnOne → empty.
      - T2: SELECT findExistingOneOnOne → empty.
      - T1: INSERT conversation + 2 members, commit.
      - T2: INSERT conversation + 2 members, commit.
      - Kết quả: 2 ONE_ON_ONE giữa cùng cặp user.
    - Contract đã documented acceptable V1. Fix V2 cần partial UNIQUE index hoặc upsert pattern.
    - **Cân nhắc nhanh**: có thể giảm risk ngay ở V1 bằng cách thêm `@Transactional(isolation = Isolation.SERIALIZABLE)` cho `createOneOnOne` — nhưng PG SERIALIZABLE retry overhead cao cho hot path. Hoặc dùng advisory lock `pg_advisory_xact_lock(hash(userA||userB))` — clean nhưng thêm complexity. **Reviewer đề xuất**: giữ nguyên V1 (documented), fix ở V2 cùng partial UNIQUE index. Không block W3D2.

13. **[BE][WARNING] Search exclude blocked users — chưa wire, OK documented.**
    - `UserRepository.searchUsers` chỉ exclude `id != currentUserId` + `status='active'`. Contract dòng 631 đã documented "V1 chấp nhận tạm chưa filter blocked — documented".

14. **[FE][WARNING] `hooks.ts:33-44` — `useCreateConversation` onSuccess check `result.conversation` nhưng khi 409 trả về `{existingConversationId}` → không invalidate list.**
    - Logic đúng (không invalidate khi 409 vì chưa có conv mới). Nhưng không notify caller một cách rõ ràng. Caller phải tự check `result.existingConversationId` sau khi mutation resolve.
    - Gợi ý (non-blocking): tách thành 2 callback `onConversationCreated` và `onConversationExisted` trong API layer hoặc dùng discriminated union `{ status: 'created', conversation } | { status: 'existed', id }` để TypeScript ép caller handle cả 2 case.

15. **[FE][WARNING] `api.ts:26` — `err.response?.status === 409` nhưng không check status code khác.**
    - Nếu BE trả 409 với code khác (tương lai có `CONV_MEMBER_BLOCKED` cũng 409), code sẽ throw đúng (vì code không khớp). OK.
    - Nhưng cast type `(err.response.data as { code?: string })` quá loose — không type-safe. Nên define `interface ApiErrorBody { error: string; message: string; details?: Record<string, unknown> }` shared.

16. **[FE][WARNING] `api.ts:9` — import axios thuần để dùng `isAxiosError` rồi `err.response.data`.**
    - OK. Axios 1.x `isAxiosError` là pattern chuẩn. Không dùng `instanceof AxiosError` (yếu hơn).

17. **[FE][WARNING] `useUserSearch` default `limit = 20` nhưng contract default=10.**
    - Contract dòng 603: `limit` default 10, max 20. FE gọi với limit=20 mặc định.
    - Không vi phạm contract (20 trong range 1..20), nhưng FE dùng max luôn thay vì default → tăng payload không cần thiết cho mỗi search call. Non-blocking.
    - Đề xuất: đổi default limit FE về 10 hoặc tăng contract default lên 20 nếu team muốn show nhiều hơn.

18. **[FE][WARNING] `types/conversation.ts:7-18` — `const object + type` pattern thay vì enum.**
    - Type-safety ở compile time: TypeScript suy ra `ConversationType` là union `'ONE_ON_ONE' | 'GROUP'` thông qua `(typeof ConversationType)[keyof typeof ConversationType]`. Không thể gán string tuỳ ý. ✅ Type-safe.
    - Lý do tránh enum: project dùng `erasableSyntaxOnly` mode (comment line 6) — enum không phải pure erasable. Pattern này là industry standard trong TS-only codebase. APPROVED.

### Answered checklist items

1. **BE findOrCreate ONE_ON_ONE**:
   - Native SQL `findExistingOneOnOne` có đúng? ✅ Đúng — JOIN double, filter `type='ONE_ON_ONE'`, không false-positive với GROUP.
   - Race condition 2 req cùng lúc: ✅ Có thể xảy ra dup (READ_COMMITTED default). Documented acceptable V1 (xem W3-BE-Warning-12).

2. **BE enumeration protection**:
   - ✅ `GET /api/conversations/{id}` trả `CONV_NOT_FOUND` cho cả not-exist và not-member (xem `ConversationService.getConversation` line 309-315).
   - Message "Conversation không tồn tại hoặc bạn không phải thành viên" — OK, không leak. Thậm chí có thể rút gọn thành "Conversation không tồn tại" để cực kỳ paranoid, nhưng hiện tại đủ an toàn vì cùng HTTP status + cùng error code.

3. **BE N+1**:
   - ⚠️ `GET /api/conversations` list dùng 2-query approach (IDs first native + batch load). ĐÚNG intent — NHƯNG batch load lại gọi `findByIdWithMembers` per convId → N+1. Xem W3-BE-Warning-3.
   - ✅ `GET /api/conversations/{id}` dùng JOIN FETCH — 1 query.

4. **W3-BE-1 verification**:
   - ✅ `@PrePersist` UUID generation thay thế đúng `@GeneratedValue`. Cả Conversation và ConversationMember đã migrate sang pattern `if (id == null) id = UUID.randomUUID()`. `@Column(id, updatable=false, nullable=false)` không còn `insertable=false`.
   - ✅ Test `savingConversation_shouldPersistWithNonNullId` tồn tại trong suite (line 121-127) và assert `id != null` sau save. W3-BE-1 **RESOLVED** — cập nhật knowledge.

5. **FE 409 handling**:
   - ❌ BLOCKING: đọc `.code` thay vì `.error`. Xem BLOCKING-1.

6. **FE types match BE**:
   - ✅ PageResponse format đã update cho Spring Page (`content/page/size/totalElements/totalPages`) — khớp BE.
   - ❌ BLOCKING: `ConversationDto` FE có `displayName/displayAvatarUrl/unreadCount/mutedUntil` nhưng BE không trả. Xem BLOCKING-2.
   - ✅ `ConversationSummaryDto` FE khớp BE (displayName là server-computed).
   - ✅ `const object + type` pattern type-safe (W3-FE-Warning-18).

7. **Contract drift**:
   - ✅ `GET /api/conversations` response shape BE trả `{content, page, size, totalElements, totalPages}` — khớp contract dòng 510-533. **KHÔNG cần update contract** (contract viết đúng từ đầu, BE implement đúng).
   - ✅ `GET /api/users/search` có `limit` param (default 10, max 20) — khớp contract dòng 603.
   - ℹ️ Contract đúng, drift nằm ở FE types (xem BLOCKING-2) → không phải drift contract, là drift implementation FE.

### Contract impact

- **KHÔNG cập nhật `docs/API_CONTRACT.md`**. Contract v0.5.0-conversations đã viết đúng từ W3D1. BE implement khớp contract. FE lệch với contract (bug ở FE, không phải contract sai).
- Giữ nguyên version `v0.5.0-conversations`. Khi FE fix 2 BLOCKING sẽ APPROVE mà không đụng contract.

### Orchestrator decisions cần lưu ý
- **Gọi frontend-dev fix 2 BLOCKING NGAY**: (1) `api.ts:31` đổi `.code` → `.error`; (2) `types/conversation.ts:29-41` xóa displayName/displayAvatarUrl/unreadCount/mutedUntil khỏi ConversationDto, derive ở FE runtime.
- **Sau khi FE fix**, reviewer check lại → APPROVE. Không cần BE re-touch.
- **BE warnings 3-9**: không fix trong W3D2. Tạo issue/WARNINGS.md entry cho 5-6-7 (dedupe memberIds, enforce max 49, rate limit TODO). Warning 8 (UserController cross-package) tạo ticket clean-up W3D3-D4. Các warning khác documented acceptable.
- **W3-BE-1 RESOLVED**: cập nhật knowledge — không cần còn track.

---

## 2026-04-19 — W3D1 Review: V3 schema + Conversation domain + FE layout skeleton; Draft Conversations contract

### Verdict
⚠️ APPROVE WITH COMMENTS

### Files reviewed
- `backend/src/main/resources/db/migration/V3__create_conversations.sql`: mới — 2 tables conversations + conversation_members, CHECK enum UPPERCASE, UNIQUE (conv_id, user_id), ON DELETE CASCADE members, ON DELETE SET NULL created_by, 4 indexes.
- `backend/src/main/java/com/chatapp/conversation/{enums,entity,repository}/*` (6 files): ConversationType, MemberRole, Conversation, ConversationMember, ConversationRepository (findByIdWithMembers JOIN FETCH), ConversationMemberRepository (findByUser_Id + existsByConv_User).
- `frontend/src/pages/ConversationsLayout.tsx`: mới — 2-col desktop, stack mobile theo :id param.
- `frontend/src/pages/ConversationsIndexPage.tsx`: mới — empty state.
- `frontend/src/components/ProtectedRoute.tsx`: refactor children-prop → Outlet pattern, add isHydrated spinner, add location.state.from.
- `frontend/src/App.tsx`: nest ProtectedRoute parent + ConversationsLayout + index/:id.
- `frontend/src/pages/LoginPage.tsx`: đọc location.state.from để redirect sau login, default /conversations.
- `frontend/src/pages/HomePage.tsx`: thêm CTA "Vào Chat →".

### Issues found

#### Blocking
- (không có)

#### Warnings (non-blocking, nên log lại để revisit nếu hit bug)

1. **[BE][W3-BE-1] `Conversation.java:33` + `ConversationMember.java:36` — `@GeneratedValue(strategy=GenerationType.UUID)` + `@Column(insertable=false, updatable=false)` có thể conflict.**
   - `@GeneratedValue(UUID)` yêu cầu Hibernate tự sinh UUID (provider side) — giống strategy IDENTITY. Nhưng `insertable=false` nói Hibernate "đừng include cột này trong INSERT". Kết quả phụ thuộc phiên bản Hibernate: Hibernate 6 có thể throw `MappingException` hoặc im lặng pass nhưng miss ID ở INSERT rồi hit DB default (gen_random_uuid). Nếu lucky (DB default fire) thì Hibernate vẫn cần refresh để đọc ID ra.
   - **Khuyến nghị**: hoặc (a) giữ `@GeneratedValue(UUID)` và bỏ `insertable=false` + `updatable=false` để Hibernate đóng role generator; hoặc (b) bỏ `@GeneratedValue` hoàn toàn, chỉ dùng `insertable=false` + dựa vào DB default `gen_random_uuid()` kèm `@Column(... columnDefinition="UUID")` — nhưng khi đó sau `save()` entity không có ID, cần `entityManager.refresh()`.
   - **Test cần có ở Ngày 2**: integration test `repository.save(Conversation.builder().type(GROUP).build())` rồi assert `id != null`. Hiện W3D1 chỉ có test schema + entity load, chưa test insert end-to-end — nên warning chưa bị trigger. Khi BE viết service Ngày 2, nếu hit MappingException hoặc NullPointerException khi access `saved.getId()` → fix theo (a) hoặc (b).

2. **[BE][W3-BE-2] `Conversation.java:61` — `@ManyToOne(LAZY) private User createdBy` nhưng DB column `created_by UUID REFERENCES users(id) ON DELETE SET NULL`.**
   - `createdBy` nullable đúng, nhưng khi user bị xóa (V1 chưa có hard-delete users; có `status='deleted'` soft-delete pattern) DB không fire `SET NULL` vì không có DELETE thật. Kết quả: pattern `ON DELETE SET NULL` hiện là **dead code** cho V1. OK để giữ (tương lai-proof), chỉ log vào knowledge.
   - Không blocking. Documented rationale OK.

3. **[BE][W3-BE-3] Schema V3 không có `CREATE EXTENSION IF NOT EXISTS pgcrypto`.**
   - V2 comment "pgcrypto extension đã có sẵn" — nghĩa là được tạo manual trước. Nếu team mới clone repo setup fresh DB → Flyway V2 dùng `gen_random_uuid()` fail, V3 cũng fail.
   - **Khuyến nghị**: thêm `CREATE EXTENSION IF NOT EXISTS pgcrypto;` vào đầu V2 (không phải V3, vì V2 là nơi đầu tiên dùng). An toàn idempotent. Non-blocking nếu team đã setup local DB rồi; blocking cho developer mới onboard.

4. **[BE][W3-BE-4] `ConversationMemberRepository.findByUser_IdOrderByJoinedAtDesc` trả về `Page<ConversationMember>` — kéo theo `ConversationMember` entity.**
   - Để serve `GET /api/conversations` response có `displayName` / `unreadCount` / `memberCount` cần JOIN thêm conversation + aggregate. Method hiện tại không đủ. Ngày 2 BE sẽ viết `@Query` custom (hoặc query trên `Conversation` entity filter theo members). Không cần sửa method này ngay.

5. **[BE][W3-BE-5] Không có `updated_at` và trigger `BEFORE UPDATE` cho `conversation_members`.**
   - Khi member thay đổi role hoặc `last_read_message_id`, không có cột để track "lần cập nhật gần nhất". Có thể OK vì chúng ta có `joined_at` immutable + `last_read_message_id` tự track thay đổi. Non-blocking V1.

6. **[BE][Architecture drift] V3 thiếu các field `left_at`, `leave_reason`, `is_hidden`, `cleared_at`, `mute_until` (V3 dùng `muted_until`).**
   - So với ARCHITECTURE.md mục 3.2: V3 **đơn giản hóa intentional** — soft-leave và soft-hide out-of-scope tuần 3. Contract v0.5.0 đã documented. Khi cần tính năng "rời nhóm" và "xóa chat" ở tuần 5-6 → migration V4.
   - `muted_until` (V3) vs `mute_until` (ARCHITECTURE): V3 chọn `muted_until` (past participle — grammatically đúng hơn). OK giữ V3.

7. **[FE][W3-FE-1] `ProtectedRoute.tsx:19` + `App.tsx:23` — hai tầng "gate hydration" chồng lấn.**
   - App.tsx có `isInitialized` gate (không render `BrowserRouter` cho đến khi `authService.init()` xong). ProtectedRoute có `isHydrated` check riêng.
   - Thực tế: Zustand persist hydrate **synchronously** khi storage = localStorage → tại thời điểm `App.tsx:useEffect` chạy, `isHydrated` đã `true`. Và `authService.init()` await xong → `setIsInitialized(true)` → routes render. Khi ProtectedRoute mount, `isHydrated` **luôn = true** → nhánh spinner trong ProtectedRoute là **dead code**.
   - Không blocking (defense-in-depth) nhưng khiến review sau dễ nhầm. Options: (a) xóa nhánh spinner trong ProtectedRoute, tin tưởng gate ở App.tsx; (b) giữ nhưng comment rõ "defensive — App.tsx đã gate, đây là safety net nếu sau này bỏ gate ở App.tsx". Reviewer không enforce, FE tự chọn.

8. **[FE][W3-FE-2] `ConversationsLayout.tsx:17-18` — mobile responsive dùng `useParams().id`.**
   - OK hoạt động. Nhưng khi routes nested có path phức tạp hơn (ví dụ `/conversations/:id/settings` ở tuần 5), `useParams().id` vẫn có giá trị khi user ở trang settings → sidebar vẫn bị ẩn mobile. Có thể đúng UX (đang deep trong conv). Non-blocking.

9. **[FE][W3-FE-3] `ConversationsLayout.tsx:38-42` — fallback `?` cho avatar khi `user?.fullName` rỗng.**
   - Edge case: user vừa tạo không có fullName → BE validation chặn rồi (`fullName` required 1..100). OK. Non-blocking.

### Contract check
- ✅ Schema V3 khớp domain model (entities + repositories map đúng tables).
- ✅ Enum UPPERCASE thống nhất cả SQL CHECK + Java enum + JSON (sắp bắt đầu trong contract mới).
- ℹ️ Contract Conversations chưa từng tồn tại trước W3D1 → không có "lệch contract" để check. Đã viết mới ở v0.5.0-conversations.
- ℹ️ ARCHITECTURE.md mục 3.2 lệch với V3 (lowercase → UPPERCASE + bỏ vài cột). Đã log ADR-012 + note trong contract header. KHÔNG sửa ARCHITECTURE (giữ tài liệu gốc, contract thắng).

### Answered checklist items
1. **Indexes đủ cho query list conversations của 1 user?** — Có `idx_members_user (user_id, joined_at DESC)` cho hit conversation_members. Join ngược lên conversations dùng PK. Cần thêm query plan test khi BE viết custom `@Query` Ngày 2. OK baseline.
2. **UNIQUE (conversation_id, user_id)?** — ✅ Có `uq_members_conv_user` ở cả DB và entity annotation.
3. **ON DELETE CASCADE members?** — ✅ Đúng (xóa conv → xóa members).
4. **ON DELETE SET NULL created_by?** — ✅ Đúng intent (giữ conv khi user deleted). Xem warning W3-BE-2.
5. **type enum lowercase vs UPPERCASE** — Đã chốt UPPERCASE (ADR-012).
6. **V3 không conflict V1/V2** — ✅ Tên table không trùng, FK references đúng `users(id)`, extension pgcrypto đã cần từ V2. Xem W3-BE-3 cho setup note.
7. **gen_random_uuid() extension** — pgcrypto. Đã log warning W3-BE-3 thêm CREATE EXTENSION vào V2.
8. **Lazy fetch** — ✅ Cả 3 relationship LAZY.
9. **@ToString include lazy fields** — ✅ Không dùng `@ToString` nên không lo. (Không dùng `@Data`.)
10. **@Builder.Default cho List members** — ✅ `members = new ArrayList<>()` có Default.
11. **isHydrated race với authService.init()** — Thực tế không có race vì persist localStorage sync. Gate App.tsx đã đủ. Xem W3-FE-1.
12. **location.state.from pattern React Router v6** — ✅ Đúng: `<Navigate state={{ from: location }} />` + reader `(location.state as { from?: { pathname?: string } } | null)?.from?.pathname`. LoginPage đọc đúng.
13. **W-C-4 resolved?** — ✅ Resolved. ProtectedRoute dùng Outlet pattern, có isHydrated spinner, có from redirect.
14. **Nested routes cú pháp v6** — ✅ Đúng: parent route không path (`<Route element={<ProtectedRoute />}>`), con path="/conversations" element layout, cháu index + path=":id".
15. **Outlet vs children consistent** — ✅ Thuần Outlet, không mix. Type signature `ProtectedRouteProps` đã xóa — OK.

### Contract impact
- ✅ Viết mới section "Conversations API (v0.5.0-conversations)" trong `docs/API_CONTRACT.md`. 4 endpoints với full error codes, rate limits, validation rules, response shape, notes. BE/FE Ngày 2 có thể implement song song.
- ✅ Thêm ADR-012 (UPPERCASE enum) vào reviewer-knowledge.md.
- ✅ Update contract version hiện tại → v0.5.0-conversations.
- ✅ Contract changelog row mới cho v0.5.0.

### Orchestrator decisions cần lưu ý
- BE Ngày 2: khi implement service layer, **bắt buộc integration test insert** để validate warning W3-BE-1 (UUID generation). Nếu hit MappingException hoặc NPE trên `saved.getId()` → fix theo option (a) bỏ `insertable=false,updatable=false` trên `id`.
- BE Ngày 2 cần confirm: **CREATE EXTENSION pgcrypto** có trong V2 hay đã được tạo manual. Nếu manual → log vào README "setup DB" để onboarding không lỡ.
- FE Ngày 2: response shape đã cố định. `displayName` / `displayAvatarUrl` là **server-computed**, FE không compute. `unreadCount` V1 **luôn bằng 0** — FE code sẵn sàng nhưng đừng test "badge hiển thị > 0" trong tuần 3.

---

## 2026-04-19 — W2D2 Review Phase A (FE authService.init) + Phase B (BE register + login)

### Verdict
⚠️ APPROVE WITH COMMENTS

### Files reviewed
- `backend/src/main/java/com/chatapp/auth/controller/AuthController.java`: 2 endpoints (register + login), extractClientIp helper.
- `backend/src/main/java/com/chatapp/auth/service/AuthService.java`: business logic, Redis rate limit, SHA-256 hash refresh token.
- `backend/src/main/java/com/chatapp/auth/dto/request/{RegisterRequest,LoginRequest}.java`: record + Jakarta Validation.
- `backend/src/main/java/com/chatapp/auth/dto/response/{AuthResponse,UserDto}.java`: token shape chuẩn.
- `backend/src/test/java/com/chatapp/auth/AuthControllerTest.java`: 13 integration tests bao phủ happy path + edge cases.
- `frontend/src/services/authService.ts`: init() dùng rawAxios, 3 case logic đúng.
- `frontend/src/components/AppLoadingScreen.tsx`: spinner + text, không có bug.
- `frontend/src/App.tsx`: isInitialized gate qua useEffect+finally.

### Issues found
- [WARNING][BE] `AuthService.register()` không catch `DataIntegrityViolationException` — race condition khi 2 request cùng email vượt qua existsByEmail rồi cả 2 save. Lần thứ 2 hiện throw 500 thay vì 409 AUTH_EMAIL_TAKEN. Non-blocking cho V1 (<1000 users, traffic thấp) nhưng cần fix trước production.
- [WARNING][BE] Redis fail SAU khi save user → user đã tồn tại DB nhưng không có refresh token. @Transactional chỉ bao DB, không rollback Redis side effect. Acceptable vì FE sẽ login lại, nhưng document lại hành vi này.
- [WARNING][BE] `extractClientIp()` lấy X-Forwarded-For[0] mà không sanitize ký tự. Redis key `rate:login:{ip}` về lý thuyết có thể bị inject nếu attacker forge header (ví dụ `"; FLUSHDB; #`). Redis command injection qua StringRedisTemplate hầu như không xảy ra (serialized key), nhưng nên validate IP format để phòng abuse counter space.
- [WARNING][BE] Rate limit register tính MỌI request thay vì chỉ thất bại. OK theo contract ("10 requests/15 phút/IP, mọi request đều tính"), nhưng user legitimate tạo 10 account hợp lệ cũng bị chặn. Phù hợp với intent anti-abuse.
- [WARNING][FE] `init()` catch empty — nuốt mọi error bao gồm cả network timeout. Acceptable vì mục đích là "gate luôn mở", nhưng nên `console.warn()` để dev debug.
- [WARNING][FE] `AppLoadingScreen` hiển thị text không dấu "Dang khoi dong...". Nếu cố tình vì tránh encoding issue thì OK, nhưng toàn bộ app khác dùng tiếng Việt có dấu, không nhất quán.

### Contract impact
- Không cập nhật contract. Implementation khớp 100% với `docs/API_CONTRACT.md` v0.2.1-auth:
  - HTTP status 200 cho register (khớp contract dòng 112).
  - Error codes đều có prefix `AUTH_`: AUTH_EMAIL_TAKEN, AUTH_USERNAME_TAKEN, AUTH_INVALID_CREDENTIALS, AUTH_ACCOUNT_LOCKED (khớp dòng 135-137, 193-194).
  - Response shape `{accessToken, refreshToken, tokenType, expiresIn, user:{id,username,email,fullName,avatarUrl}}` khớp.
  - user.id là UUID string (UserDto.from gọi user.getId().toString()).
  - Rate limit: register 10/15min/IP, login 5/15min/IP (chỉ tính fail) — khớp.
  - Refresh token lưu SHA-256 hash vào Redis key `refresh:{userId}:{jti}` TTL 7 ngày — khớp contract dòng 201.

---

## Template cho entry viết contract

```
## YYYY-MM-DD — Contract update: <feature>

### Thêm vào API_CONTRACT.md
- <endpoint 1>
- <endpoint 2>

### Thêm vào SOCKET_EVENTS.md
- <event>

### Ghi chú
- <quyết định đặc biệt khi thiết kế>
```

---

## Entries

[2026-04-19 - W2 Final Audit trước tag v0.2.0-w2] Verdict: **NEEDS_CLEANUP (minor — tất cả non-blocking, safe to tag với commit dọn dẹp nhỏ)**. Scope audit: (1) Contract consistency, (2) ADR completeness, (3) Warnings tracking file mới, (4) Orphan TODOs.

**1. Contract consistency — PASS**. `docs/API_CONTRACT.md` version v0.4.0-auth-complete. 5 auth endpoints đầy đủ (register, login, oauth, refresh, logout) với request body + response + error codes + notes. Grep controller source: `AuthController.java` có 5 `@PostMapping` match exact với contract (register/login/refresh/oauth/logout), `HealthController.java` có 1 `@GetMapping("/health")` — health không nằm trong API_CONTRACT.md (infrastructure endpoint, không phải business API — acceptable, không drift). Không phát hiện endpoint implement mà thiếu contract, ngược lại cũng không có endpoint contract mà BE chưa implement.

**2. ADR consistency — NEEDS_CLEANUP, đã fix trong audit này**. Checklist yêu cầu 7 quyết định lớn; trước audit chỉ có ADR-001 đến ADR-007 (thiếu 3). Đã bổ sung: ADR-008 (HS256 + jjwt 0.12.x — trước chỉ nêu lướt trong reviewer-log W1), ADR-009 (Redis key schema — trước rải rác trong ADR-005/006/security-standards, không có chỗ tổng hợp), ADR-010 (AuthMethod enum — trước chỉ note "W-BE-3 RESOLVED", không có ADR chính thức), ADR-011 (Fail-open blacklist trade-off — trước chỉ trong security-standards bullet, không formalized). BCrypt-12 (ADR-002), Refresh rotation + reuse detection (ADR-006), Auto-link by email (ADR-007) — đã có đầy đủ.

**3. Warnings tracking — tạo mới `docs/WARNINGS.md`**. Tổng hợp từ reviewer-log W2D1/W2D2/W2D3/W2D3.5/W2D4 + grep codebase. Kết quả:
- Pre-production: 5 items (W-BE-4 race existsByEmail→save, W-BE-5 null passwordHash guard, W-BE-6 X-Forwarded-For sanitize, W-BE-7 fail-open monitoring+runbook, W-BE-8 generateUniqueUsername race — W-BE-4/8 có thể gộp).
- Documented acceptable: 8 items (AD-1 đến AD-8, bao gồm Redis non-transactional, rate limit counter, email_verified chưa check cho Google, rehydrate race trong logout, registerSchema regex gộp, register rate limit anti-abuse).
- Cleanup tuần 8: 6 items (CL-1 dead TODO useAuth.ts, CL-2 PROVIDER_ALREADY_LINKED dead branch × 2, CL-3 AUTH_ACCOUNT_DISABLED dead case, CL-4 expired token test fallback, CL-5 comment "Tuần 2" trong JwtTokenProvider, CL-6 contract FIREBASE_UNAVAILABLE mở rộng description).
- Tech debt nhỏ: 7 items (TD-1 AppLoadingScreen không dấu, TD-2 structured log security events, TD-3 authService.init catch empty, TD-4 controller javadoc outdated, TD-5 cache User Redis, TD-6 JWT secret test config, TD-7 log level 4xx).

**4. Orphan TODO — 1 item**. Grep `TODO|FIXME|HACK|W-*-`: chỉ phát hiện 1 TODO trong code: `frontend/src/hooks/useAuth.ts:29` — "TODO Tuần 2: call /api/auth/logout". Logout đã implement trong `HomePage.tsx:25-39` dùng `logoutApi` trực tiếp, không qua `useAuth.logout()`. TODO orphan (công việc đã làm nơi khác) → map vào CL-1 trong WARNINGS.md, decision defer sang Tuần 3 khi refactor nav/header. Ngoài ra 3 reference outdated "Tuần 2" trong comments (useAuth.ts:16-17 → CL-1, JwtTokenProvider.java:189 → CL-5) — không phải TODO chính thức nhưng cần cleanup.

**Dead code phát hiện**:
- `handleAuthError.ts:28` case `AUTH_ACCOUNT_DISABLED` — BE không throw code này. → CL-3.
- `LoginPage.tsx:201` + `RegisterPage.tsx:277` check `PROVIDER_ALREADY_LINKED` — BE không emit + contract không define. → CL-2.
- Tất cả dead code đều có fallback hợp lệ (chung handler/message) → không blocking, không gây bug runtime.

**Verdict cuối**: ✅ **READY_FOR_TAG sau khi commit WARNINGS.md + knowledge update**. Không có blocking issue. Contract khớp implementation. 5 pre-production items đã tracked với solution rõ ràng — sẽ fix trong phase hardening trước V1 public launch. Auth foundation Tuần 2 solid, sẵn sàng tag `v0.2.0-w2`.

**Recommendation cho orchestrator**:
1. Review `docs/WARNINGS.md` vừa tạo.
2. Commit 2 file (WARNINGS.md + reviewer-knowledge.md + reviewer-log.md).
3. Tag `v0.2.0-w2` trên commit đó.
4. Bắt đầu Tuần 3 với awareness về 5 pre-production items — không block Tuần 3 nhưng tracked để quay lại fix trước deploy.

---

[2026-04-19 - W2D4 Review] OAuth + Logout. Verdict: APPROVE WITH COMMENTS. Auth foundation Tuần 2 complete. Firebase SDK verify (A1): PASS — dùng `firebaseAuth.verifyIdToken()` của Admin SDK, không tự parse JWT. FirebaseAuth null check (A5): PASS — `if (firebaseAuth == null) throw AUTH_FIREBASE_UNAVAILABLE` line 307 trước khi gọi. Auto-link by email (A2): PASS logic, `email_verified` chưa check (Google luôn verified nên OK V1, DOCUMENTED cần add khi thêm provider khác). generateUniqueUsername (A3): race theoretical (check→save không atomic, fallback UUID 8 ký tự) — acceptable V1. Password_hash=null cho OAuth user (A4): DOCUMENTED — login() chưa guard null passwordHash, BCrypt.matches sẽ throw IllegalArgumentException→500. Sửa khi touch login tiếp. Blacklist ordering (B6): PASS — check Redis TRƯỚC khi set SecurityContext (line 75-89 rồi mới line 91+). Logout không trong whitelist (B7): PASS — SecurityConfig list explicit register/login/oauth/refresh. Blacklist TTL (B8): PASS — `accessTokenRemainingMs/1000` giới hạn đúng. Best-effort logout (B9): PASS — try/catch quanh refresh delete, vẫn blacklist access + trả 200. Fail-open Redis blacklist check (B10): INTENTIONAL trade-off (có comment rõ trong filter), acceptable V1 scale <1000 users — risk: khi Redis down window, logged-out token còn valid đến natural expiry ≤1h. KHÔNG BLOCKING. Contract compliance: OAuthResponse có `boolean isNewUser` (primitive, khớp JSON `true/false` non-nullable); HTTP 200 cho OAuth+logout OK; error codes FIREBASE_TOKEN_INVALID+AUTH_FIREBASE_UNAVAILABLE match contract. revokeAllUserSessions (D14): KHÔNG gọi trong oauth() — chỉ ở reuse detection (line 261). @Transactional oauth (D15): bao đúng save user + save provider. FE items: popup-closed-by-user silent (F16) PASS; không log idToken (F17) PASS; logoutApi failure → finally clearAuth+navigate (F18) PASS; OAuthResponse extends AuthResponse + isNewUser (F19) PASS; signInWithPopup từ firebase/auth (F20) PASS (không phải compat). Non-blocking warnings: (1) FE check error code `PROVIDER_ALREADY_LINKED` trong Login/RegisterPage onError nhưng BE không emit code này và contract không define — dead code, fallback sang message chung OK; (2) contract dòng 256 nói AUTH_FIREBASE_UNAVAILABLE cho "timeout sau 5 giây" nhưng BE implement cho "SDK chưa init (null bean)" — semantics hơi khác, error code khớp, nên cập nhật contract cho rõ cả 2 case; (3) handleLogout HomePage: refreshToken có thể null sau rehydrate race — FE hiện skip API call → access token không được blacklist (chỉ clear local). Acceptable V1 (token tự expire); (4) generateUniqueUsername concurrent race — 2 OAuth user mới cùng email prefix có thể chọn trùng username → UNIQUE violate → 500. DB UNIQUE là guard cuối, V1 traffic thấp OK; (5) FirebaseConfig.initializeFirebase() fail → log.error nhưng app vẫn start → OAuth endpoint trả 503 AUTH_FIREBASE_UNAVAILABLE tường minh, tốt cho dev. Contract v0.3.0-auth → v0.4.0-auth-complete (OAuth + Logout implemented).

[2026-04-19 - W2D3.5 Review] POST /api/auth/refresh. Verdict: APPROVE WITH COMMENTS. Constant-time compare: PASS (MessageDigest.isEqual). Token rotation correctness: PASS (DELETE trước SAVE; buildAuthResponse sinh jti mới; rate limit counter không reset sau refresh thành công — acceptable vì window 60s ngắn, 10 calls đủ cho FE queue pattern). Reuse detection: PASS (revokeAllUserSessions trước throw; pattern `refresh:{userId}:*` đúng; log WARN có userId+jti, không log raw token). Error codes: PASS (INVALID cho malformed/signature/reused/user-not-found; EXPIRED cho quá TTL). Rate limit: PASS (key `rate:refresh:{userId}`, increment mỗi call). 23/23 tests pass. Non-blocking issues: (1) unused import `java.util.Arrays` — cleanup; (2) Contract drift nhẹ: dòng 337 API_CONTRACT.md nhắc tới error code `REFRESH_TOKEN_REUSED` (không có prefix AUTH_) nhưng implementation dùng `AUTH_REFRESH_TOKEN_INVALID` cho reuse case. Error table chỉ liệt kê `AUTH_REFRESH_TOKEN_INVALID` và `AUTH_REFRESH_TOKEN_EXPIRED` — nên sửa dòng 337 cho nhất quán; (3) Test 17 `refreshWithExpiredToken_returnsExpiredError` documented honestly là không test được EXPIRED path (integration test khó tạo expired token khi ttl config 7 ngày), fallback sang test INVALID signature — EXPIRED path chưa có integration coverage, chỉ có unit test ở JwtTokenProviderTest; (4) Rate limit pattern dùng userId từ token chưa validate Redis hash — attacker gửi nhiều token fake (cùng signature hợp lệ từ cùng user) vẫn consume counter của userId đó → potential DoS nhắm user cụ thể, nhưng cần lấy được raw token đã ký trước nên threat thấp; (5) Rate limit TTL=60s và limit=10 không khớp contract dòng 279 "30 requests/15 phút/IP" — implementation phòng brute force hiệu quả hơn nhưng LỆCH CONTRACT → cần hoặc update contract hoặc align impl. Recommend update contract (10/60s per-userId an toàn hơn 30/15min per-IP cho refresh flow); (6) X-Forwarded-For chưa sanitize (pre-existing). Constant-time compare đúng chuẩn, reuse detection đúng, rotation đúng thứ tự — 3 item BLOCKING-candidate đều PASS. Contract version: v0.2.1-auth → v0.3.0-auth (refresh endpoint implemented, 2 fixup items contract cần sync).

[2026-04-19 - W2D3 Phase C Review] Wire FE Login + Register with real API. Verdict: APPROVE WITH COMMENTS. W-FE-1 RESOLVED (regex `^[a-zA-Z_][a-zA-Z0-9_]{2,49}$` match exact BE, enforce 3-50 chars, first char not digit). Contract compliance PASS: registerApi payload strip confirmPassword explicit (RegisterPage.tsx:54-59); loginApi payload đúng `{username,password}`; UserDto types khớp (id as string UUID, avatarUrl nullable); setAuth nhận full AuthResponse và sync tokenStorage qua tokenStorage.setTokens TRƯỚC khi set Zustand. Error code handling: 6 BE codes đều được map (AUTH_INVALID_CREDENTIALS→field username, AUTH_ACCOUNT_LOCKED→toast, RATE_LIMITED→toast with retryAfter, AUTH_EMAIL_TAKEN/AUTH_USERNAME_TAKEN→field error, VALIDATION_FAILED→per-field qua details.fields). Security: password inputs giữ type="password"; show/hide toggle OK; không có console.log nào trong auth pages/utils; button disable khi loading. Non-blocking warnings: (1) handleAuthError case AUTH_ACCOUNT_DISABLED dead (BE không throw code này, chỉ AUTH_ACCOUNT_LOCKED); (2) registerSchema gộp length vào regex → error message không chính xác khi user gõ quá 50 ký tự (sẽ nói "bắt đầu bằng chữ cái" thay vì "quá dài"); (3) HomePage logout button là stub `onClick={() => {}}` — documented Ngày 5; (4) ProtectedRoute.tsx tồn tại nhưng chưa thấy wire vào App.tsx — có thể prep cho Ngày 4. Contract v0.2.1-auth không đổi.

[2026-04-19 - W2D1 Review] W-BE-3 + W-FE-2. Verdict: APPROVE WITH COMMENTS. W-BE-3: AuthMethod enum refactor clean, package đúng, getValue() đúng, getAuthMethodFromToken() có fallback an toàn, tests cover cả 2 enum values. W-FE-2: tokenStorage.ts pattern hoàn chỉnh, globalThis removed, circular dep phá sạch. Warning non-blocking: sau rehydrate accessToken=null → request đầu tiên không có Bearer header → server trả AUTH_REQUIRED → interceptor clear+redirect thay vì refresh. Cần authService.init() call /refresh ngay khi app load nếu có refreshToken. Bug pre-existing, không phải do diff này tạo ra.

[2026-04-19 - Ngày 4 Phase 3B Review] APPROVE WITH COMMENTS. Axios interceptor, Zustand store, registerSchema, useAuth hook đều solid — không có blocking issue. Hai warning đáng chú ý: (1) globalThis.__authStoreGetState acceptable cho V1 nhưng nên migrate sang lazy-import pattern ở tuần 2; (2) registerSchema thiếu validate username không bắt đầu bằng số theo contract. console.log trong onSubmit stub được chấp nhận vì có comment rõ ràng là placeholder. Contract v0.2.1-auth không đổi.

[2026-04-19 - W1 Fix Review] APPROVE. JwtTokenProvider.validateTokenDetailed() phân biệt VALID/EXPIRED/INVALID. Request attribute 'jwt_expired' set đúng. authenticationEntryPoint trả AUTH_TOKEN_EXPIRED vs AUTH_REQUIRED chính xác. Contract cập nhật với AUTH_TOKEN_EXPIRED code. Contract version: v0.2.1-auth.

## 2026-04-19 — Review Phase 3A: Spring Security 6 + JWT setup

### Verdict
APPROVE WITH COMMENTS

### Files reviewed
- `backend/src/main/java/com/chatapp/config/SecurityConfig.java`: Spring Security 6 lambda DSL, STATELESS, CORS, filter chain
- `backend/src/main/java/com/chatapp/security/JwtAuthFilter.java`: OncePerRequestFilter, no-throw design
- `backend/src/main/java/com/chatapp/security/JwtTokenProvider.java`: jjwt 0.12.x, secret from env, BCrypt 12
- `backend/src/main/java/com/chatapp/exception/GlobalExceptionHandler.java`: AppException, Validation, catch-all
- `backend/src/main/java/com/chatapp/exception/ErrorResponse.java`: record, @JsonInclude NON_NULL
- `backend/src/main/resources/application.yml`: JWT secret via env var, cors via env var
- `backend/src/test/java/com/chatapp/security/JwtTokenProviderTest.java`: 6 unit tests
- `backend/src/test/java/com/chatapp/security/SecurityConfigTest.java`: 4 integration tests

### Issues found
- [WARNING] `JwtTokenProvider.java`: access token hardcode `auth_method = "password"` — cần truyền dynamic khi OAuth. Code đã có comment "Tuần 2 sẽ truyền dynamic" nhưng nên đảm bảo signature API hỗ trợ param này sớm.
- [WARNING] `JwtAuthFilter.java`: DB query (`userRepository.findById`) cho mọi request authenticated. Khi load tăng cần cache User entity vào Redis (Phase sau).
- [WARNING] `application-test.yml`: JWT secret để rõ trong file test config — chấp nhận được cho dev/test nhưng đừng để value này leak ra production.
- [WARNING] `GlobalExceptionHandler.java`: AppException log ở level DEBUG — nếu client gửi AUTH_INVALID_CREDENTIALS liên tục sẽ không thấy trong log level INFO. Cân nhắc log WARN cho 4xx security errors.

### Contract impact
- Không thay đổi contract. ErrorResponse shape khớp API_CONTRACT.md. Error codes AUTH_REQUIRED, AUTH_FORBIDDEN, VALIDATION_FAILED, INTERNAL_ERROR đều đúng.

## 2026-04-19 — Contract update: Auth endpoints (tuần 1)

### Thêm vào API_CONTRACT.md
- `POST /api/auth/register` — 5 error codes
- `POST /api/auth/login` — 5 error codes
- `POST /api/auth/oauth` — 6 error codes
- `POST /api/auth/refresh` — 6 error codes
- `POST /api/auth/logout` — 4 error codes

### Thêm vào SOCKET_EVENTS.md
- Không có thay đổi (auth không dùng socket)

### Ghi chú
- Chốt refresh token rotation (mỗi lần refresh phát token mới, invalidate token cũ).
- OAuth auto-link theo email nếu email đã có trong DB (theo ARCHITECTURE.md mục edge case).
- Login rate limit chỉ tính lần thất bại, không tính login thành công.
- Logout yêu cầu gửi refreshToken trong body để server biết token nào cần xóa khỏi Redis.
- `isNewUser` field thêm vào OAuth response (ngoài token shape chuẩn) để FE hiện onboarding.

[2026-04-19] Viết API_CONTRACT.md v0.2-auth — 5 Auth endpoints (register, login, oauth, refresh, logout). Contract chốt, sẵn sàng cho BE/FE tuần 1.
[2026-04-19] Cập nhật API_CONTRACT.md: thêm Refresh Queue Pattern note vào /refresh, xác nhận isNewUser field trong /oauth response. Contract version: v0.2-auth (final cho tuần 1).
