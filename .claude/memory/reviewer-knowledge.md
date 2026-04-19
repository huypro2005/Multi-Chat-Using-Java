# Reviewer Knowledge — Tri thức chắt lọc cho code-reviewer

> File này là **bộ nhớ bền vững** của code-reviewer.
> Khác với BE và FE, reviewer có vai trò cross-cutting → knowledge tập trung vào:
> (1) Contract đã chốt, (2) Review standard đã áp dụng, (3) Architectural decision records.
> Giới hạn: ~400 dòng (được phép dài hơn BE/FE vì bao quát toàn cục).

---

## Architectural Decision Records (ADR)

*(Mỗi quyết định kiến trúc lớn ghi 1 record. Format ngắn gọn.)*

### ADR-001: (chưa có)
- **Quyết định**: 
- **Bối cảnh**: 
- **Lý do**: 
- **Trade-off**: 
- **Ngày**: 

---

## Contract version hiện tại

- **API_CONTRACT.md**: v0.2-auth (5 Auth endpoints: register, login, oauth, refresh, logout — chốt 2026-04-19)
- **SOCKET_EVENTS.md**: v0.1 (skeleton, chưa có event)

*(Tăng minor version khi thêm endpoint/event, major khi breaking change.)*

## Auth contract — quyết định thiết kế đã chốt

- **Refresh token rotation**: mỗi lần `/refresh` phát token mới, invalidate token cũ trong Redis. BE phải implement atomic check-and-rotate.
- **Rate limit login**: chỉ tính lần thất bại (sai credentials), không tính thành công.
- **User enumeration protection**: `/login` trả `AUTH_INVALID_CREDENTIALS` cho cả sai username lẫn sai password, cùng 1 message.
- **OAuth auto-link**: nếu email từ Firebase đã có trong `users` table → link provider tự động, không tạo user mới. Thứ tự kiểm tra: `user_auth_providers` (by googleUid) → `users` (by email) → tạo mới.
- **Logout yêu cầu refreshToken trong body**: để server xóa đúng token khỏi Redis (single-device logout). Logout all devices là endpoint riêng, ngoài scope tuần 1.
- **isNewUser field**: `/oauth` response thêm field `isNewUser: boolean` ngoài token shape chuẩn — đây là exception có documented intent, không phải contract drift.

---

## Review standard đã áp dụng (bài học rút ra)

*(Ghi khi phát hiện vấn đề nào đó XUẤT HIỆN NHIỀU LẦN trong review — cần nâng thành quy tắc.)*

### Vấn đề thường gặp ở BE
- (chưa có)

### Vấn đề thường gặp ở FE
- (chưa có)

### Contract lệch thường gặp
- (chưa có)

---

## Approved patterns (pattern đã review và OK, khuyên dùng)

### BE patterns
- (chưa có)

### FE patterns
- (chưa có)

---

## Rejected patterns (đã review và từ chối, không dùng)

*(Format: pattern là gì → tại sao từ chối → dùng gì thay thế)*

- (chưa có)

---

## Changelog contract

*(Log mỗi lần thay đổi contract, ngắn gọn. Chi tiết đầy đủ ở cuối API_CONTRACT.md và SOCKET_EVENTS.md.)*

- (chưa có entry)
