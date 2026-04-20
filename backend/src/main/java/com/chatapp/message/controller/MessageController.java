package com.chatapp.message.controller;

import com.chatapp.exception.AppException;
import com.chatapp.message.dto.MessageDto;
import com.chatapp.message.dto.MessageListResponse;
import com.chatapp.message.dto.SendMessageRequest;
import com.chatapp.message.service.MessageService;
import com.chatapp.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller cho Message endpoints.
 *
 * POST /api/conversations/{convId}/messages     → gửi tin nhắn (201)
 * GET  /api/conversations/{convId}/messages     → lấy lịch sử (200, cursor-based pagination)
 *
 * Auth required: tất cả. JWT được validate bởi JwtAuthFilter.
 * @AuthenticationPrincipal User: Spring Security inject User entity trực tiếp (xem JwtAuthFilter).
 */
@RestController
@RequestMapping("/api/conversations/{convId}/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * POST /api/conversations/{convId}/messages
     * Gửi tin nhắn vào conversation.
     *
     * @param convId  UUID của conversation
     * @param req     body: { content, type?, replyToMessageId? }
     * @param user    authenticated user (từ JWT)
     * @return 201 Created + MessageDto
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageDto sendMessage(
            @PathVariable UUID convId,
            @Valid @RequestBody SendMessageRequest req,
            @AuthenticationPrincipal User user) {
        return messageService.sendMessage(user.getId(), convId, req);
    }

    /**
     * GET /api/conversations/{convId}/messages?cursor=&after=&limit=
     * Lấy lịch sử tin nhắn với cursor-based pagination.
     *
     * Pagination modes:
     * - cursor only: backward pagination (load older messages). createdAt < cursor, DESC → reversed ASC.
     * - after only: forward pagination (catch-up after reconnect). createdAt > after, ASC.
     *   Include cả deleted messages (FE cần placeholder state).
     * - both null: first page (newest messages), DESC → reversed ASC.
     * - both non-null: 400 VALIDATION_FAILED — mutually exclusive.
     *
     * @param convId  UUID của conversation
     * @param cursor  ISO8601 backward cursor (optional — null = trang đầu tiên)
     * @param after   ISO8601 forward cursor for catch-up (optional, mutex with cursor)
     * @param limit   số messages per page (1-100, default 50)
     * @param user    authenticated user
     * @return 200 OK + MessageListResponse { items, hasMore, nextCursor }
     */
    @GetMapping
    public ResponseEntity<MessageListResponse> getMessages(
            @PathVariable UUID convId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal User user) {

        // cursor và after are mutually exclusive
        if (cursor != null && after != null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "cursor và after không thể dùng cùng nhau",
                    Map.of("error", "cursor and after are mutually exclusive params"));
        }

        OffsetDateTime cursorTime = parseCursor(cursor);
        OffsetDateTime afterTime = parseCursor(after);   // same ISO8601 parse logic
        return ResponseEntity.ok(messageService.getMessages(user.getId(), convId, cursorTime, afterTime, limit));
    }

    // -------------------------------------------------------------------------

    private OffsetDateTime parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            // Normalize to UTC to ensure consistent DB comparison
            return OffsetDateTime.parse(cursor)
                    .atZoneSameInstant(ZoneOffset.UTC)
                    .toOffsetDateTime();
        } catch (DateTimeParseException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "cursor không hợp lệ, phải là ISO8601 OffsetDateTime",
                    Map.of("field", "cursor", "error", "Invalid ISO8601 datetime format"));
        }
    }
}
