package com.chatapp.user.controller;

import com.chatapp.conversation.dto.UserSearchDto;
import com.chatapp.conversation.service.ConversationService;
import com.chatapp.auth.dto.response.UserDto;
import com.chatapp.exception.AppException;
import com.chatapp.file.constant.FileConstants;
import com.chatapp.file.entity.FileRecord;
import com.chatapp.file.repository.FileRecordRepository;
import com.chatapp.user.dto.request.UpdateProfileRequest;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

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
    private final UserRepository userRepository;
    private final FileRecordRepository fileRecordRepository;

    /**
     * GET /api/users/{id}
     * Lấy thông tin public của user theo ID.
     * Trả 404 nếu user không tồn tại hoặc không active.
     */
    @GetMapping("/{id}")
    public UserSearchDto getUserById(
            @PathVariable UUID id,
            @AuthenticationPrincipal User currentUser
    ) {
        return conversationService.getUserById(id);
    }

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

    @GetMapping("/me")
    public UserDto getCurrentUser(@AuthenticationPrincipal User currentUser) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Không tìm thấy người dùng"));
        return UserDto.from(user);
    }

    @PatchMapping("/me")
    public UserDto updateProfile(
            @RequestBody UpdateProfileRequest req,
            @AuthenticationPrincipal User currentUser
    ) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Không tìm thấy người dùng"));

        if (req.getFullName() != null) {
            String trimmed = req.getFullName().trim();
            if (trimmed.length() < 2 || trimmed.length() > 100) {
                throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Họ tên phải từ 2 đến 100 ký tự");
            }
            user.setFullName(trimmed);
        }

        if (req.isAvatarUrlProvided()) {
            if (req.getAvatarUrl() == null) {
                user.setAvatarUrl(FileConstants.DEFAULT_USER_AVATAR_URL);
            } else {
                UUID avatarFileId = extractPublicAvatarFileId(req.getAvatarUrl());
                FileRecord file = fileRecordRepository.findByIdAndExpiredFalse(avatarFileId)
                        .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "Tệp không tồn tại"));
                if (file.getUploaderId() == null || !file.getUploaderId().equals(currentUser.getId())) {
                    throw new AppException(HttpStatus.FORBIDDEN, "AVATAR_NOT_OWNED", "Không phải avatar của bạn");
                }
                if (!file.isPublic()) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "AVATAR_NOT_PUBLIC", "Avatar phải là ảnh công khai");
                }
                user.setAvatarUrl(FileConstants.publicUrl(file.getId()));
            }
        }

        return UserDto.from(userRepository.save(user));
    }

    private UUID extractPublicAvatarFileId(String avatarUrl) {
        final String prefix = "/api/files/";
        final String suffix = "/public";
        if (avatarUrl == null || !avatarUrl.startsWith(prefix) || !avatarUrl.endsWith(suffix)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "avatarUrl không hợp lệ");
        }
        String rawId = avatarUrl.substring(prefix.length(), avatarUrl.length() - suffix.length());
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException ex) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "avatarUrl không hợp lệ");
        }
    }
}
