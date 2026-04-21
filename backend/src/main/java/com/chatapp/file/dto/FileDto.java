package com.chatapp.file.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response shape cho upload + hydration trong MessageDto.attachments.
 *
 * Khớp với API_CONTRACT.md "Files Management — FileDto shape":
 * {
 *   "id": "uuid",
 *   "mime": "string",
 *   "name": "string (sanitized originalName)",
 *   "size": long,
 *   "url": "/api/files/{id}",
 *   "thumbUrl": "/api/files/{id}/thumb" | null,
 *   "expiresAt": "ISO8601 UTC"
 * }
 *
 * thumbUrl null khi không phải image (PDF, v.v.) — FE fallback hiển thị icon generic.
 * JsonInclude.NON_NULL để không serialize null thumbUrl (shape gọn hơn cho PDF).
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record FileDto(
        UUID id,
        String mime,
        String name,
        long size,
        String url,
        String thumbUrl,
        OffsetDateTime expiresAt
) {}
