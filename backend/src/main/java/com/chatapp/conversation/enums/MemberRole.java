package com.chatapp.conversation.enums;

/**
 * Vai trò của thành viên trong một conversation.
 * Lưu dưới dạng string trong DB (EnumType.STRING).
 *
 * OWNER  — người tạo nhóm; có toàn quyền, kể cả xóa nhóm và chuyển quyền.
 * ADMIN  — quản trị viên; có thể thêm/xóa member nhưng không xóa nhóm.
 * MEMBER — thành viên thường.
 *
 * Áp dụng cho GROUP conversation. ONE_ON_ONE luôn có 2 OWNER.
 */
public enum MemberRole {
    OWNER,
    ADMIN,
    MEMBER
}
