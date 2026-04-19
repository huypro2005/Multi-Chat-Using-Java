package com.chatapp.conversation.entity;

import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.user.entity.User;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entity mapping bảng `conversations`.
 *
 * - UUID được database generate bằng gen_random_uuid() (pgcrypto).
 *   insertable=false, updatable=false để Hibernate không ghi đè.
 * - KHÔNG dùng @Data — gây vấn đề equals/hashCode với JPA lazy-loading.
 * - Tất cả relationships là LAZY.
 * - @PrePersist / @PreUpdate tự set timestamps.
 */
@Entity
@Table(name = "conversations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Conversation {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Loại conversation: ONE_ON_ONE hoặc GROUP.
     * Lưu dưới dạng string trong DB.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ConversationType type;

    /**
     * Tên nhóm — NULL cho 1-1 chat.
     */
    @Column(name = "name", length = 100)
    private String name;

    /**
     * Avatar nhóm — NULL cho 1-1 chat.
     */
    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    /**
     * User tạo conversation. ON DELETE SET NULL nên có thể null nếu user bị xóa.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Timestamp của tin nhắn gần nhất trong conversation.
     * Dùng để sort danh sách conversation theo thứ tự hoạt động.
     * NULL = conversation mới, chưa có tin nhắn.
     */
    @Column(name = "last_message_at")
    private OffsetDateTime lastMessageAt;

    /**
     * Danh sách thành viên — LAZY để tránh N+1.
     * Dùng JOIN FETCH trong query khi thực sự cần.
     */
    @OneToMany(mappedBy = "conversation", fetch = FetchType.LAZY)
    @Builder.Default
    private List<ConversationMember> members = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // --- Domain behavior ---

    public boolean isGroup() {
        return ConversationType.GROUP == this.type;
    }

    public boolean isOneOnOne() {
        return ConversationType.ONE_ON_ONE == this.type;
    }

    /**
     * Cập nhật lastMessageAt khi có tin nhắn mới.
     * Chỉ cập nhật nếu timestamp mới sau timestamp hiện tại.
     */
    public void touchLastMessage(OffsetDateTime messageTime) {
        if (this.lastMessageAt == null || messageTime.isAfter(this.lastMessageAt)) {
            this.lastMessageAt = messageTime;
        }
    }
}
