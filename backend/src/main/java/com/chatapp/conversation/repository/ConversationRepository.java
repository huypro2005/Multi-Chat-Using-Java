package com.chatapp.conversation.repository;

import com.chatapp.conversation.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /**
     * Load conversation kèm danh sách members (JOIN FETCH).
     * Tránh N+1 khi cần truy cập members ngay sau khi load conversation.
     */
    @Query("SELECT c FROM Conversation c LEFT JOIN FETCH c.members m LEFT JOIN FETCH m.user WHERE c.id = :id")
    Optional<Conversation> findByIdWithMembers(@Param("id") UUID id);

    /**
     * Tìm ONE_ON_ONE conversation đã tồn tại giữa 2 users.
     * Trả về String để tránh JDBC driver mapping issues (H2 trả byte[], PG trả UUID string).
     * Service layer sẽ convert sang UUID.
     */
    @Query(value = """
            SELECT CAST(c.id AS VARCHAR) FROM conversations c
            JOIN conversation_members m1 ON m1.conversation_id = c.id AND m1.user_id = CAST(:userId1 AS UUID)
            JOIN conversation_members m2 ON m2.conversation_id = c.id AND m2.user_id = CAST(:userId2 AS UUID)
            WHERE c.type = 'ONE_ON_ONE'
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findExistingOneOnOne(@Param("userId1") String userId1, @Param("userId2") String userId2);

    /**
     * Lấy danh sách conversations của user (paginated), sắp xếp theo last_message_at DESC NULLS LAST, created_at DESC.
     * Native query để sort đúng theo contract.
     * Dùng CAST(id AS VARCHAR) để tránh H2 trả byte[] thay vì UUID string.
     */
    @Query(value = """
            SELECT CAST(c.id AS VARCHAR), c.type, c.name, c.avatar_url, c.last_message_at, c.created_at,
                   (SELECT COUNT(*) FROM conversation_members WHERE conversation_id = c.id) AS member_count
            FROM conversations c
            JOIN conversation_members m ON m.conversation_id = c.id AND m.user_id = CAST(:userId AS UUID)
            ORDER BY c.last_message_at DESC NULLS LAST, c.created_at DESC
            LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<Object[]> findConversationsByUserPaginated(
            @Param("userId") String userId,
            @Param("size") int size,
            @Param("offset") int offset);

    /**
     * Đếm tổng số conversations của user — dùng cho pagination metadata.
     */
    @Query(value = """
            SELECT COUNT(*) FROM conversations c
            JOIN conversation_members m ON m.conversation_id = c.id AND m.user_id = CAST(:userId AS UUID)
            """, nativeQuery = true)
    long countConversationsByUser(@Param("userId") String userId);
}
