package com.chatapp.file.repository;

import com.chatapp.file.entity.MessageAttachment;
import com.chatapp.file.entity.MessageAttachmentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository cho M2M message_attachments.
 *
 * W6-D1 stub: chỉ expose lookup cơ bản. W6-D2 sẽ thêm method query attachments
 * theo messageId (JOIN files) để hydrate MessageDto.
 */
@Repository
public interface MessageAttachmentRepository
        extends JpaRepository<MessageAttachment, MessageAttachmentId> {

    /** Các attachment gắn vào 1 message (sắp xếp theo display_order ASC). */
    List<MessageAttachment> findByIdMessageIdOrderByDisplayOrderAsc(UUID messageId);

    /** Các attachment dùng file này — reverse lookup cho authorization check W6-D2. */
    List<MessageAttachment> findByIdFileId(UUID fileId);
}
