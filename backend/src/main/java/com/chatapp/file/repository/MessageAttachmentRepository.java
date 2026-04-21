package com.chatapp.file.repository;

import com.chatapp.file.entity.MessageAttachment;
import com.chatapp.file.entity.MessageAttachmentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository cho M2M message_attachments.
 *
 * W6-D1: lookup cơ bản.
 * W6-D2: thêm query cho authorization check (conv member) và validation (unique).
 */
@Repository
public interface MessageAttachmentRepository
        extends JpaRepository<MessageAttachment, MessageAttachmentId> {

    /** Các attachment gắn vào 1 message (sắp xếp theo display_order ASC). */
    List<MessageAttachment> findByIdMessageIdOrderByDisplayOrderAsc(UUID messageId);

    /** Các attachment dùng file này — reverse lookup cho authorization check. */
    List<MessageAttachment> findByIdFileId(UUID fileId);

    /**
     * Check xem fileId đã được attach vào message nào chưa (W6-D2 validation —
     * UNIQUE attachment per message: 1 file không được re-use nhiều message).
     */
    boolean existsByIdFileId(UUID fileId);

    /**
     * W6-D2 authorization check: user có phải member của conversation chứa
     * message nào có attachment trỏ tới fileId này không?
     *
     * <p>JOIN path: {@code message_attachments} (file_id) → {@code messages} (message_id)
     * → {@code conversation_members} (conversation_id, user_id).
     *
     * <p>Chỉ COUNT > 0 để tránh load entity thừa. Trả boolean.
     */
    @Query("SELECT COUNT(ma) > 0 FROM MessageAttachment ma " +
            "JOIN Message m ON ma.id.messageId = m.id " +
            "JOIN ConversationMember cm ON cm.conversation.id = m.conversation.id " +
            "WHERE ma.id.fileId = :fileId AND cm.user.id = :userId")
    boolean existsByFileIdAndConvMemberUserId(@Param("fileId") UUID fileId,
                                              @Param("userId") UUID userId);
}
