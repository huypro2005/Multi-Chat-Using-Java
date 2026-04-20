package com.chatapp.message.repository;

import com.chatapp.message.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
