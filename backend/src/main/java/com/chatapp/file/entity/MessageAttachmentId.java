package com.chatapp.file.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite PK cho message_attachments (message_id + file_id).
 *
 * @Embeddable + @EqualsAndHashCode bắt buộc — JPA yêu cầu composite key có equals/hashCode ổn định.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageAttachmentId implements Serializable {

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "file_id", nullable = false)
    private UUID fileId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MessageAttachmentId that)) return false;
        return Objects.equals(messageId, that.messageId)
                && Objects.equals(fileId, that.fileId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, fileId);
    }
}
