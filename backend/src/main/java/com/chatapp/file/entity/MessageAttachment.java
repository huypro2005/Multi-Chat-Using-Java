package com.chatapp.file.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Entity map bảng `message_attachments` — M2M messages ↔ files.
 *
 * Composite key (message_id, file_id). Không FK navigation property ở đây
 * để tránh cycle với Message entity; lookup qua repository method riêng.
 *
 * display_order: thứ tự hiển thị trong bubble (0 = đầu tiên).
 * W6-D1 scope: chưa link vào MessageService flow (làm ở W6-D2). Entity + repo
 * đã setup sẵn để W6-D2 gắn attachments vào message.
 */
@Entity
@Table(name = "message_attachments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageAttachment {

    @EmbeddedId
    private MessageAttachmentId id;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private short displayOrder = 0;
}
