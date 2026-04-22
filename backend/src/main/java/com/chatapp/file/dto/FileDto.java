package com.chatapp.file.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response shape cho upload + hydration trong MessageDto.attachments.
 *
 * Khớp với API_CONTRACT.md "Files Management — FileDto shape v1.0.0-files-hybrid":
 * {
 *   "id": "uuid",
 *   "mime": "string",
 *   "name": "string (sanitized originalName)",
 *   "size": long,
 *   "url": "/api/files/{id}"              // private (is_public=false)
 *       | "/api/files/{id}/public",       // public  (is_public=true)
 *   "thumbUrl": "/api/files/{id}/thumb" | null,
 *   "iconType": "IMAGE|PDF|WORD|EXCEL|POWERPOINT|TEXT|ARCHIVE|GENERIC",
 *   "expiresAt": "ISO8601 UTC",
 *   "isPublic": boolean,                  // W7-D4-fix, ADR-021
 *   "publicUrl": "/api/files/{id}/public" | null   // W7-D4-fix, ADR-021
 * }
 *
 * Field notes (W7-D4-fix, ADR-021):
 *  - url: BE resolve theo is_public. Public → /public endpoint; private → gốc.
 *  - publicUrl: convenience — null nếu is_public=false, bằng url nếu is_public=true.
 *  - isPublic: duplicate của implicit state nhưng explicit để FE dễ filter/debug.
 *  - thumbUrl: CHỈ áp cho private images (public avatars không có thumbnail V1).
 *
 * JsonInclude.NON_ABSENT giữ null fields trong JSON response — FE cần kiểm tra null tường minh.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record FileDto(
        UUID id,
        String mime,
        String name,
        long size,
        String url,
        String thumbUrl,
        String iconType,
        OffsetDateTime expiresAt,
        boolean isPublic,
        String publicUrl
) {}
