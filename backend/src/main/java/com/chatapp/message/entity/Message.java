package com.chatapp.message.entity;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.message.converter.JsonMapConverter;
import com.chatapp.message.enums.MessageType;
import com.chatapp.user.entity.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Entity mapping bảng `messages`.
 *
 * UUID: @PrePersist generate nếu null (pattern W3-BE-1).
 * Database cũng có DEFAULT gen_random_uuid() cho direct SQL insert.
 *
 * KHÔNG dùng @Data — gây vấn đề equals/hashCode với JPA lazy-loading.
 * Tất cả @ManyToOne là LAZY.
 * Soft delete qua deleted_at — không xóa cứng.
 */
@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Conversation chứa tin nhắn này.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    /**
     * User gửi tin nhắn. ON DELETE SET NULL — nếu user bị xóa, sender_id = null.
     * Nullable vì ON DELETE SET NULL.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id")
    private User sender;

    /**
     * Loại tin nhắn: TEXT | IMAGE | FILE | SYSTEM.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    @Builder.Default
    private MessageType type = MessageType.TEXT;

    /**
     * Nội dung tin nhắn. Max 5000 chars được enforce ở service/validation layer.
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * Tin nhắn gốc nếu đây là reply. Self-reference, nullable, 1 level only.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reply_to_message_id")
    private Message replyToMessage;

    /**
     * Timestamp lần cuối tin nhắn bị sửa. NULL = chưa bao giờ sửa.
     */
    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    /**
     * Soft delete timestamp. NULL = chưa xóa. Non-null = đã bị xóa.
     */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /**
     * User who performed the soft delete. NULL = not deleted.
     * Maps to deleted_by UUID column. ON DELETE SET NULL handled at DB level.
     */
    @Column(name = "deleted_by")
    private UUID deletedBy;

    /**
     * SYSTEM message event type (e.g. GROUP_CREATED, MEMBER_ADDED).
     * Null for all non-SYSTEM messages. Non-null required for SYSTEM messages.
     * See SystemEventType constants.
     */
    @Column(name = "system_event_type", length = 50)
    private String systemEventType;

    /**
     * SYSTEM message metadata — structured JSONB containing actorId, actorName,
     * and optional fields like targetId, targetName, oldValue, newValue, autoTransferred.
     * Null for non-SYSTEM messages. Non-null for SYSTEM messages.
     *
     * Uses JsonMapConverter (portable H2/PostgreSQL): H2 stores as VARCHAR, Postgres as JSONB.
     * @JdbcTypeCode(SqlTypes.JSON) was replaced because H2 returns JSON as a raw String
     * rather than parsed JSON, causing MismatchedInputException in Hibernate 6 (W7-D4 pitfall).
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "system_metadata")
    private Map<String, Object> systemMetadata;

    /**
     * Timestamp khi tin nhắn được ghim. NULL = chưa ghim.
     */
    @Column(name = "pinned_at")
    private Instant pinnedAt;

    /**
     * UUID của user đã ghim tin nhắn. NULL khi chưa ghim hoặc user bị xóa.
     * ON DELETE SET NULL handled at DB level (V14 migration).
     */
    @Column(name = "pinned_by_user_id")
    private UUID pinnedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        // Normalize to UTC so H2 stores consistent timestamps (avoids +07:00 offset stripping)
        if (createdAt == null) createdAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        if (type == null) type = MessageType.TEXT;
    }

    // --- Domain behavior ---

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    public void markAsDeleted() {
        this.deletedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }

    public void markAsDeletedBy(UUID byUserId) {
        this.deletedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        this.deletedBy = byUserId;
    }
}
