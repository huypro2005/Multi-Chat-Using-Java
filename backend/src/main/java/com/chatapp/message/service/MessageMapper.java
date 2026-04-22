package com.chatapp.message.service;

import com.chatapp.file.dto.FileDto;
import com.chatapp.file.entity.FileRecord;
import com.chatapp.file.entity.MessageAttachment;
import com.chatapp.file.repository.FileRecordRepository;
import com.chatapp.file.repository.MessageAttachmentRepository;
import com.chatapp.message.dto.MessageDto;
import com.chatapp.message.dto.ReplyPreviewDto;
import com.chatapp.message.dto.SenderDto;
import com.chatapp.message.entity.Message;
import com.chatapp.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Tách DTO mapping khỏi MessageService để cả broadcaster và service có thể reuse.
 *
 * Design: @Component singleton, stateless → thread-safe.
 *
 * Content strip (W5-D3): nếu message đã bị soft delete (deletedAt != null),
 * content sẽ bị set = null trong DTO để tránh leak nội dung đã xóa.
 * Rule này áp dụng TẤT CẢ path: REST list, REST create response, WS broadcast, WS ACK.
 *
 * Attachments (W6-D1): load từ JOIN message_attachments ORDER BY display_order.
 * - Mỗi attachment map sang FileDto (url/thumbUrl server-computed).
 * - Khi message deleted → attachments = [] (strip để không leak file URL sau delete).
 * - Luôn non-null (dùng Collections.emptyList() thay vì null) — FE không phải check null.
 *
 * ReplyPreviewDto (W5-D4): thêm field deletedAt — null nếu source chưa bị xóa,
 * ISO8601 string nếu source đã soft-delete. contentPreview = null khi source deleted.
 *
 * <p><b>N+1 warning (W6-D2)</b>: hiện tại mỗi {@link #toDto(Message)} gọi 1 query
 * {@code findByIdMessageIdOrderByDisplayOrderAsc} + N query {@code findById} cho mỗi
 * file. Cho page 50 messages với attachments → worst-case ~51 + 50 × N_attach queries.
 * Acceptable V1 (Hibernate 2nd-level cache + pageSize ≤ 50). V2 cần optimize bằng
 * {@code @EntityGraph} hoặc một JOIN query trả Message + attachments + files batch.
 */
@Component
@RequiredArgsConstructor
public class MessageMapper {

    private static final int CONTENT_PREVIEW_MAX_LENGTH = 100;

    private final MessageAttachmentRepository messageAttachmentRepository;
    private final FileRecordRepository fileRecordRepository;

    public MessageDto toDto(Message message) {
        SenderDto senderDto = null;
        if (message.getSender() != null) {
            User sender = message.getSender();
            senderDto = new SenderDto(
                    sender.getId(),
                    sender.getUsername(),
                    sender.getFullName(),
                    sender.getAvatarUrl()
            );
        }

        ReplyPreviewDto replyPreview = null;
        if (message.getReplyToMessage() != null) {
            replyPreview = toReplyPreview(message.getReplyToMessage());
        }

        // Strip content when soft-deleted — tránh leak nội dung đã bị xóa
        boolean isDeleted = message.getDeletedAt() != null;
        String content = isDeleted ? null : message.getContent();
        String deletedBy = message.getDeletedBy() != null ? message.getDeletedBy().toString() : null;

        // Attachments (W6-D1): strip khi soft-deleted, else load qua JOIN.
        List<FileDto> attachmentDtos = isDeleted
                ? Collections.emptyList()
                : loadAttachmentDtos(message);

        return new MessageDto(
                message.getId(),
                message.getConversation().getId(),
                senderDto,
                message.getType(),
                content,
                attachmentDtos,
                replyPreview,
                message.getEditedAt(),
                message.getCreatedAt(),
                message.getDeletedAt(),
                deletedBy,
                message.getSystemEventType(),
                message.getSystemMetadata()
        );
    }

    /**
     * Map a source Message into a ReplyPreviewDto (1-level shallow).
     *
     * - If source is soft-deleted: contentPreview = null, deletedAt = ISO8601 string.
     * - If source is not deleted: contentPreview = trimmed to 100 chars, deletedAt = null.
     * - Quoting a deleted source is allowed (per W5-D4 spec).
     */
    public ReplyPreviewDto toReplyPreview(Message source) {
        String senderName = source.getSender() != null
                ? source.getSender().getFullName()
                : "Deleted User";

        if (source.getDeletedAt() != null) {
            return new ReplyPreviewDto(
                    source.getId(),
                    senderName,
                    null,
                    source.getDeletedAt().toString()
            );
        }

        String preview = source.getContent();
        if (preview != null && preview.length() > CONTENT_PREVIEW_MAX_LENGTH) {
            preview = preview.substring(0, CONTENT_PREVIEW_MAX_LENGTH) + "...";
        }
        return new ReplyPreviewDto(source.getId(), senderName, preview, null);
    }

    // =========================================================================
    // Private helpers — attachment loading (W6-D1)
    // =========================================================================

    /**
     * Load attachments của message + map sang FileDto. Trả empty list nếu không có.
     *
     * <p>Implementation notes:
     * <ul>
     *   <li>Query attachments theo display_order ASC (giữ thứ tự hiển thị trong bubble).</li>
     *   <li>Mỗi attachment load FileRecord riêng (N+1 — documented warning trên class).</li>
     *   <li>Nếu FileRecord không tìm thấy (đã bị xoá cứng — hiếm), skip row đó thay vì crash.</li>
     *   <li>thumbUrl tự compute theo thumbnailInternalPath (W6-D2): null cho PDF/non-image.</li>
     * </ul>
     */
    private List<FileDto> loadAttachmentDtos(Message message) {
        List<MessageAttachment> attachments = messageAttachmentRepository
                .findByIdMessageIdOrderByDisplayOrderAsc(message.getId());
        if (attachments.isEmpty()) {
            return Collections.emptyList();
        }
        return attachments.stream()
                .map(ma -> fileRecordRepository.findById(ma.getId().getFileId())
                        .map(this::toFileDto).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    private FileDto toFileDto(FileRecord record) {
        String baseUrl = "/api/files/" + record.getId();
        String thumbUrl = record.getThumbnailInternalPath() != null ? baseUrl + "/thumb" : null;
        return new FileDto(
                record.getId(),
                record.getMime(),
                record.getOriginalName(),
                record.getSizeBytes(),
                baseUrl,
                thumbUrl,
                resolveIconType(record.getMime()),
                record.getExpiresAt()
        );
    }

    /**
     * Keep iconType mapping consistent with FileService.toDto().
     */
    private static String resolveIconType(String mime) {
        if (mime == null) return "GENERIC";
        if (mime.startsWith("image/")) return "IMAGE";
        if ("application/pdf".equals(mime)) return "PDF";
        if (mime.contains("wordprocessingml") || "application/msword".equals(mime)) return "WORD";
        if (mime.contains("spreadsheetml") || "application/vnd.ms-excel".equals(mime)) return "EXCEL";
        if (mime.contains("presentationml") || "application/vnd.ms-powerpoint".equals(mime)) return "POWERPOINT";
        if ("text/plain".equals(mime)) return "TEXT";
        if (mime.contains("zip") || mime.contains("7z")) return "ARCHIVE";
        return "GENERIC";
    }
}
