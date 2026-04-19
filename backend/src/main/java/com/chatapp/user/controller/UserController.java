package com.chatapp.user.controller;

import com.chatapp.conversation.dto.UserSearchDto;
import com.chatapp.conversation.service.ConversationService;
import com.chatapp.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller cho user endpoints.
 *
 * GET /api/users/search?q=&limit= → tìm user theo username/fullName
 *
 * Auth required: tất cả.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final ConversationService conversationService;

    /**
     * GET /api/users/search?q={query}&limit={limit}
     * Tìm user theo username hoặc fullName.
     * Contract: q >= 2 chars after trim, limit 1-20 (default 10).
     */
    @GetMapping("/search")
    public List<UserSearchDto> searchUsers(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit,
            @AuthenticationPrincipal User currentUser
    ) {
        return conversationService.searchUsers(currentUser.getId(), q, limit);
    }
}
