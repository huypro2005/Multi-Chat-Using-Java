package com.chatapp.file.service;

import com.chatapp.file.entity.FileRecord;
import com.chatapp.file.repository.FileRecordRepository;
import com.chatapp.file.repository.MessageAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Authorization rules cho file download + thumbnail (W6-D2).
 *
 * <p>Rule (theo API_CONTRACT.md §Files Management):
 * <ol>
 *   <li>Caller là {@code uploader_id} của file → cho phép.</li>
 *   <li>Caller là member của conversation chứa message có attachment trỏ tới file này → cho phép.</li>
 *   <li>Ngược lại: NOT_FOUND (anti-enumeration — merge với not-exist / expired / non-accessible
 *       để không leak file existence).</li>
 * </ol>
 *
 * <p>Thêm anti-enumeration invariants:
 * <ul>
 *   <li>File đã expire ({@code expires_at < now()}) → NOT_FOUND (không trả 410 Gone).</li>
 *   <li>File {@code expired=true} (cleanup job đã xoá disk) → NOT_FOUND (xử lý gián tiếp qua
 *       {@link FileRecordRepository#findByIdAndExpiredFalse}).</li>
 * </ul>
 *
 * <p>KHÔNG dùng {@code AppException} — trả {@code Optional.empty()} để caller quyết định
 * error code (NOT_FOUND cho download, hoặc ghost fail-open cho internal workflow).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileAuthService {

    private final FileRecordRepository fileRecordRepository;
    private final MessageAttachmentRepository messageAttachmentRepository;

    /**
     * Tìm file mà caller có quyền access. Trả empty khi:
     *  - File không tồn tại.
     *  - File đã bị cleanup-job xoá (expired=true trên DB).
     *  - File đã qua {@code expires_at} (30 ngày sau upload).
     *  - Caller không phải uploader VÀ không phải member của conv chứa attachment file này.
     */
    @Transactional(readOnly = true)
    public Optional<FileRecord> findAccessibleById(UUID fileId, UUID userId) {
        if (fileId == null || userId == null) {
            return Optional.empty();
        }

        // findByIdAndExpiredFalse cũng loại bỏ record đã bị cleanup job mark expired=true.
        FileRecord record = fileRecordRepository.findByIdAndExpiredFalse(fileId).orElse(null);
        if (record == null) {
            return Optional.empty();
        }

        // Expiry check (defense-in-depth: cleanup job có thể chậm).
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (record.getExpiresAt() != null && record.getExpiresAt().isBefore(now)) {
            return Optional.empty();
        }

        // Rule 1: uploader luôn được truy cập.
        if (record.getUploaderId() != null && record.getUploaderId().equals(userId)) {
            return Optional.of(record);
        }

        // Rule 2: conv-member check qua JOIN message_attachments → messages → conversation_members.
        boolean isMemberAttached = messageAttachmentRepository
                .existsByFileIdAndConvMemberUserId(fileId, userId);
        if (isMemberAttached) {
            return Optional.of(record);
        }

        return Optional.empty();
    }
}
