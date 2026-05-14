package com.chatapp.reaction.repository;

import com.chatapp.reaction.entity.MessageReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for MessageReaction (V13 — W8-D1).
 *
 * Key queries:
 * - findByMessageIdAndUserId: toggle check (existing reaction for user on message).
 * - findAllByMessageIdIn: batch load for N+1 mitigation during list messages.
 * - deleteByMessageIdAndUserId: REMOVED toggle path.
 */
@Repository
public interface MessageReactionRepository extends JpaRepository<MessageReaction, UUID> {

    /**
     * Tìm reaction hiện tại của user trên message — dùng cho toggle logic.
     */
    Optional<MessageReaction> findByMessageIdAndUserId(UUID messageId, UUID userId);

    /**
     * Batch load reactions cho nhiều messages — N+1 mitigation (BLOCKING pattern).
     * Gọi 1 query duy nhất với IN clause thay vì N queries trong loop.
     *
     * Order by createdAt ASC — consistent order cho aggregate (userIds list).
     */
    @Query("SELECT r FROM MessageReaction r WHERE r.messageId IN :messageIds ORDER BY r.createdAt ASC")
    List<MessageReaction> findAllByMessageIdIn(@Param("messageIds") Collection<UUID> messageIds);

    /**
     * REMOVED toggle: xóa reaction của user trên message.
     * Dùng thay cho delete(entity) để tránh load entity trước khi xóa.
     */
    void deleteByMessageIdAndUserId(UUID messageId, UUID userId);
}
