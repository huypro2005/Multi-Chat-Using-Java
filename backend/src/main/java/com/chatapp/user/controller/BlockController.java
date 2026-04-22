package com.chatapp.user.controller;

import com.chatapp.conversation.dto.UserSearchDto;
import com.chatapp.user.entity.User;
import com.chatapp.user.service.BlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller cho block/unblock endpoints (W8-D2, ADR-024).
 *
 * POST   /api/users/{id}/block   → block user (201 Created hoặc 200 idempotent)
 * DELETE /api/users/{id}/block   → unblock user (204 No Content)
 * GET    /api/users/blocked      → list blocked users ({items: UserSearchDto[]})
 *
 * Auth: tất cả endpoints yêu cầu JWT.
 * Privacy: không expose "isBlockedByMe" trong getUser — chỉ trả trong listBlocked.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class BlockController {

    private final BlockService blockService;

    /**
     * Block user. Idempotent — đã block thì no-op, trả 201.
     */
    @PostMapping("/{id}/block")
    @ResponseStatus(HttpStatus.CREATED)
    public void block(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser
    ) {
        blockService.block(currentUser.getId(), id);
    }

    /**
     * Unblock user. Trả 404 nếu chưa block.
     */
    @DeleteMapping("/{id}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser
    ) {
        blockService.unblock(currentUser.getId(), id);
    }

    /**
     * Danh sách users đã bị block bởi caller.
     */
    @GetMapping("/blocked")
    public Map<String, List<UserSearchDto>> listBlocked(
            @AuthenticationPrincipal User currentUser
    ) {
        return Map.of("items", blockService.listBlocked(currentUser.getId()));
    }
}
