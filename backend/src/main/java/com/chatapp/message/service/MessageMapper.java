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
import com.chatapp.message.enums.MessageType;
import com.chatapp.reaction.dto.ReactionAggregateDto;
import com.chatapp.reaction.entity.MessageReaction;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final UserRepository userRepository;

    /**
     * Map Message → MessageDto (no reactions — dùng cho single-message contexts như ACK/edit).
     * Reactions = empty list (caller đảm bảo reactions đã được load riêng nếu cần).
     */
    public MessageDto toDto(Message message) {
        return toDto(message, null, Collections.emptyList());
    }

    /**
     * Map Message → MessageDto với reactions batch-loaded (N+1 mitigation — W8-D1).
     *
     * @param message        message entity
     * @param currentUserId  caller UUID để compute currentUserReacted (null → all false)
     * @param reactions      reactions cho message này (pre-loaded batch bởi caller)
     */
    public MessageDto toDto(Message message, UUID currentUserId, List<MessageReaction> reactions) {
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

        // Reactions (W8-D1):
        // - Strip khi soft-deleted hoặc SYSTEM message (UX "hồn ma" + SYSTEM không cho react V1).
        // - Luôn non-null (empty list nếu không có reactions).
        boolean isSystem = MessageType.SYSTEM == message.getType();
        List<ReactionAggregateDto> reactionAggregates = (isDeleted || isSystem)
                ? Collections.emptyList()
                : aggregateReactions(reactions, currentUserId);

        // Pin info (W8-D2): null khi deleted (ghim không hiển thị trên message đã xóa)
        java.time.Instant pinnedAt = null;
        Map<String, Object> pinnedBy = null;
        if (!isDeleted && message.getPinnedAt() != null) {
            pinnedAt = message.getPinnedAt();
            if (message.getPinnedByUserId() != null) {
                User pinner = userRepository.findById(message.getPinnedByUserId()).orElse(null);
                String pinnerName = pinner != null ? pinner.getFullName() : "Unknown";
                Map<String, Object> pinnedByMap = new HashMap<>();
                pinnedByMap.put("userId", message.getPinnedByUserId().toString());
                pinnedByMap.put("userName", pinnerName);
                pinnedBy = pinnedByMap;
            }
        }

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
                message.getSystemMetadata(),
                reactionAggregates,
                pinnedAt,
                pinnedBy
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
    // Private helpers — reaction aggregation (W8-D1)
    // =========================================================================

    /**
     * Aggregate reactions theo emoji — group by emoji, compute count/userIds/currentUserReacted.
     *
     * Sort: count DESC, emoji ASC (codepoint compare — deterministic stable sort per contract).
     * FE renders ReactionBar theo order này — sort đảm bảo UI không flicker.
     *
     * currentUserReacted: in-memory check userIds.contains(currentUserId) — KHÔNG query riêng.
     * Emoji với count 0 KHÔNG xuất hiện (filtered khi không có reactions).
     *
     * @param reactions   list reactions đã batch-load cho message này
     * @param currentUserId caller UUID (null → currentUserReacted luôn false)
     * @return sorted aggregate list (count DESC, emoji ASC), empty list nếu reactions rỗng
     */
    public List<ReactionAggregateDto> aggregateReactions(List<MessageReaction> reactions, UUID currentUserId) {
        if (reactions == null || reactions.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<MessageReaction>> grouped = reactions.stream()
                .collect(Collectors.groupingBy(MessageReaction::getEmoji));

        List<ReactionAggregateDto> aggregates = grouped.entrySet().stream()
                .map(entry -> {
                    String emoji = entry.getKey();
                    List<MessageReaction> reactionList = entry.getValue();
                    List<UUID> userIds = reactionList.stream()
                            .map(MessageReaction::getUserId)
                            .toList();
                    boolean currentUserReacted = currentUserId != null
                            && userIds.contains(currentUserId);
                    return new ReactionAggregateDto(emoji, userIds.size(), userIds, currentUserReacted);
                })
                .sorted(Comparator.comparingInt(ReactionAggregateDto::count).reversed()
                        .thenComparing(ReactionAggregateDto::emoji))
                .toList();

        return aggregates;
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

    /**
     * Map FileRecord → FileDto cho attachment hydration trong MessageDto.
     *
     * W7-D4-fix (ADR-021): Message attachments gần như luôn private. Nhưng vẫn
     * respect record.isPublic() — nếu file upload với ?public=true và sau đó
     * attach vào message (unusual nhưng có thể), serve qua public URL.
     */
    private FileDto toFileDto(FileRecord record) {
        boolean pub = record.isPublic();
        String url = pub
                ? com.chatapp.file.constant.FileConstants.publicUrl(record.getId())
                : com.chatapp.file.constant.FileConstants.privateUrl(record.getId());
        String thumbUrl = (!pub && record.getThumbnailInternalPath() != null)
                ? com.chatapp.file.constant.FileConstants.privateUrl(record.getId()) + "/thumb"
                : null;
        String publicUrl = pub ? url : null;
        return new FileDto(
                record.getId(),
                record.getMime(),
                record.getOriginalName(),
                record.getSizeBytes(),
                url,
                thumbUrl,
                resolveIconType(record.getMime()),
                record.getExpiresAt(),
                pub,
                publicUrl
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
