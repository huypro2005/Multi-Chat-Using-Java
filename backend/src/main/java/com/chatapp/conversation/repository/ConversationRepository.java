package com.chatapp.conversation.repository;

import com.chatapp.conversation.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /**
     * Load conversation kèm danh sách members trong một query (JOIN FETCH).
     * Tránh N+1 khi cần truy cập members ngay sau khi load conversation.
     *
     * Lưu ý: kết quả là Optional vì conversation có thể không tồn tại.
     * Tuần 4 sẽ bổ sung query phức tạp hơn (filter by user, cursor-based, ...).
     */
    @Query("SELECT c FROM Conversation c LEFT JOIN FETCH c.members WHERE c.id = :id")
    Optional<Conversation> findByIdWithMembers(@Param("id") UUID id);
}
