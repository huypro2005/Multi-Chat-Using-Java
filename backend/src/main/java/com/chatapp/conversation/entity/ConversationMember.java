package com.chatapp.conversation.entity;

import com.chatapp.conversation.enums.MemberRole;
import com.chatapp.user.entity.User;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entity mapping bảng `conversation_members`.
 *
 * - UUID được database generate bằng gen_random_uuid() (pgcrypto).
 * - KHÔNG dùng @Data — tránh vấn đề equals/hashCode với JPA lazy-loading.
 * - Tất cả @ManyToOne là LAZY.
 * - UNIQUE constraint (conversation_id, user_id) được enforce ở DB level.
 */
@Entity
@Table(
    name = "conversation_members",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_members_conv_user",
        columnNames = {"conversation_id", "user_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationMember {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Conversation mà member này thuộc về.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    /**
     * User là thành viên.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Vai trò trong conversation: OWNER | ADMIN | MEMBER.
     * Lưu dưới dạng string trong DB.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Builder.Default
    private MemberRole role = MemberRole.MEMBER;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private OffsetDateTime joinedAt;

    /**
     * ID của tin nhắn cuối cùng user đã đọc trong conversation này.
     * NULL = chưa đọc tin nào, hoặc chưa track.
     */
    @Column(name = "last_read_message_id")
    private UUID lastReadMessageId;

    /**
     * Tắt thông báo đến thời điểm này.
     * NULL = không mute.
     */
    @Column(name = "muted_until")
    private OffsetDateTime mutedUntil;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (joinedAt == null) joinedAt = OffsetDateTime.now();
    }

    // --- Domain behavior ---

    public boolean isOwner() {
        return MemberRole.OWNER == this.role;
    }

    public boolean isAdmin() {
        return MemberRole.ADMIN == this.role;
    }

    public boolean canManageMembers() {
        return this.role == MemberRole.OWNER || this.role == MemberRole.ADMIN;
    }

    /**
     * Kiểm tra user hiện tại có đang bị mute không.
     */
    public boolean isMuted() {
        return this.mutedUntil != null && OffsetDateTime.now().isBefore(this.mutedUntil);
    }
}
