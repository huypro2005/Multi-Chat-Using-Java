package com.chatapp.message.repository;

import com.chatapp.message.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;


@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    /**
     * Lấy messages của conversation (chưa bị xóa), sắp xếp mới → cũ.
     * Dùng khi cursor == null (lấy trang đầu tiên).
     */
    List<Message> findByConversation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID conversationId,
            Pageable pageable);

    /**
     * Lấy messages trước cursor (cursor-based pagination).
     * Trả mới → cũ, caller reverse để trả FE cũ → mới.
     *
     * Dùng Spring Data method naming thay vì @Query để Hibernate tự handle
     * OffsetDateTime type binding (tránh H2 timezone comparison issue).
     */
    List<Message> findByConversation_IdAndCreatedAtBeforeAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID conversationId,
            OffsetDateTime before,
            Pageable pageable);

    /**
     * Đếm messages chưa xóa sau một thời điểm — dùng cho unread count (future use).
     */
    long countByConversation_IdAndCreatedAtAfterAndDeletedAtIsNull(
            UUID conversationId, OffsetDateTime after);

    /**
     * Kiểm tra message có tồn tại trong conversation không.
     * Dùng để validate reply_to_message_id thuộc đúng conversation.
     * NOTE: Không filter deleted_at — cho phép reply vào tin nhắn đã xóa.
     */
    boolean existsByIdAndConversation_Id(UUID messageId, UUID conversationId);

    /**
     * Forward pagination (catch-up after reconnect).
     * Lấy messages có createdAt > after, ORDER ASC (cũ → mới).
     *
     * IMPORTANT: KHÔNG filter deletedAt — FE cần biết placeholder state của messages đã xóa
     * khi catch-up sau reconnect. Content sẽ bị strip ở mapper tầng service.
     */
    List<Message> findByConversation_IdAndCreatedAtAfterOrderByCreatedAtAsc(
            UUID conversationId,
            OffsetDateTime after,
            Pageable pageable);

    /**
     * Count unread messages cho caller theo lastReadMessageId.
     *
     * Logic (contract v1.4.0-w7-read):
     * - KHÔNG count SYSTEM messages (lý do: system events fire nhiều trong group active —
     *   count vào unread làm badge phình vô nghĩa; user không cần đánh dấu đọc system notice).
     * - KHÔNG count soft-deleted messages (deletedAt IS NOT NULL).
     * - lastReadId IS NULL → count tất cả (user chưa từng đọc conv này).
     * - lastReadId NOT NULL → count messages có createdAt > created_at của lastRead message.
     *
     * Native query: JPQL không support subquery trong WHERE một cách portable với H2/Postgres.
     * CAST pattern: tránh H2 không nhận UUID type parameter trong native query.
     *
     * Cap (contract rule 5): gọi LEAST để tránh badge "9999+" gây shock UX.
     * Cap value 99: trả về min(actual_count, 99) — FE hiển thị "99+".
     *
     * @param convId     UUID string của conversation.
     * @param lastReadId UUID string của last read message; null nếu chưa đánh dấu.
     * @return count capped at 99 (per contract v1.4.0-w7-read rule 5).
     */
    @Query(value = """
            SELECT LEAST(COUNT(*), 99) FROM messages
            WHERE conversation_id = CAST(:convId AS UUID)
              AND deleted_at IS NULL
              AND type != 'SYSTEM'
              AND (
                :lastReadId IS NULL
                OR created_at > (
                    SELECT created_at FROM messages WHERE id = CAST(:lastReadId AS UUID)
                )
              )
            """, nativeQuery = true)
    long countUnread(
            @Param("convId") String convId,
            @Param("lastReadId") String lastReadId);
}
