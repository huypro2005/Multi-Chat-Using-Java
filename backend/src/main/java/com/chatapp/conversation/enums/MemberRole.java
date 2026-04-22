package com.chatapp.conversation.enums;

/**
 * Vai trò của thành viên trong một conversation.
 * Lưu dưới dạng string trong DB (EnumType.STRING).
 *
 * OWNER  — người tạo nhóm; có toàn quyền, kể cả xoá nhóm và chuyển quyền.
 * ADMIN  — quản trị viên; có thể thêm/xoá member thường nhưng không xoá nhóm.
 * MEMBER — thành viên thường.
 *
 * Áp dụng cho GROUP conversation. ONE_ON_ONE luôn có 2 role=MEMBER (hoặc 1 OWNER + 1 MEMBER
 * tuỳ legacy). Role không có ý nghĩa với ONE_ON_ONE — được giữ vì NOT NULL constraint.
 *
 * Permission methods embed tại đây để TRÁNH scatter if-else khắp service layer.
 * Xem API_CONTRACT.md §Group Chat Authorization Matrix (W7).
 */
public enum MemberRole {
    OWNER,
    ADMIN,
    MEMBER;

    /**
     * OWNER hoặc ADMIN mới được rename group / đổi avatar.
     */
    public boolean canRename() {
        return this != MEMBER;
    }

    /**
     * OWNER hoặc ADMIN mới được add members.
     */
    public boolean canAddMembers() {
        return this != MEMBER;
    }

    /**
     * Check kick permission dựa trên role của target.
     * OWNER: kick bất kỳ ai ngoại trừ OWNER (không tự kick — dùng /leave).
     * ADMIN: chỉ kick MEMBER.
     * MEMBER: không kick được ai.
     */
    public boolean canRemoveMember(MemberRole targetRole) {
        if (this == OWNER) return targetRole != OWNER;
        if (this == ADMIN) return targetRole == MEMBER;
        return false;
    }

    /**
     * Chỉ OWNER mới được đổi role ADMIN ↔ MEMBER.
     */
    public boolean canChangeRole() {
        return this == OWNER;
    }

    /**
     * Chỉ OWNER mới được xoá (soft-delete) group.
     */
    public boolean canDeleteGroup() {
        return this == OWNER;
    }

    /**
     * Chỉ OWNER mới được transfer ownership.
     */
    public boolean canTransferOwnership() {
        return this == OWNER;
    }

    /**
     * OWNER hoặc ADMIN — dùng cho pin authorization trong GROUP conv.
     */
    public boolean isAdminOrHigher() {
        return this == OWNER || this == ADMIN;
    }
}
