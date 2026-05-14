package com.chatapp.reaction.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity mapping bảng message_reactions (V13 — W8-D1).
 *
 * 1 row per (user, message) — UNIQUE(message_id, user_id) enforced at DB level.
 * Toggle semantics handled in ReactionService:
 *   - INSERT → ADDED
 *   - DELETE → REMOVED
 *   - UPDATE emoji → CHANGED
 *
 * KHÔNG dùng @Data (equals/hashCode lazy-loading issue).
 * UUID PK: @PrePersist generate nếu null (pattern W3-BE-1).
 */
@Entity
@Table(name = "message_reactions")
@Getter
@Setter
@NoArgsConstructor
public class MessageReaction {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Emoji Unicode character(s). Max 20 bytes UTF-8 (DB column limit).
     * EmojiValidator enforces byte-length check before regex.
     */
    @Column(nullable = false, length = 20)
    private String emoji;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
