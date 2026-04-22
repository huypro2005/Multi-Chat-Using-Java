package com.chatapp.file.constant;

import java.util.Set;
import java.util.UUID;

/**
 * Hằng số cho default/system files (ADR-021, W7-D4-fix).
 *
 * UUID fixed (seed trong Flyway V11) để:
 *  - User chưa upload avatar → trỏ về DEFAULT_USER_AVATAR.
 *  - Group chưa set avatar → trỏ về DEFAULT_GROUP_AVATAR.
 *  - Cleanup job SKIP 2 UUID này (double-safeguard với expires_at=9999-12-31).
 *
 * Physical files PHẢI được copy tay vào {@code ${STORAGE_PATH}/default/} sau deploy.
 * StorageService khi khởi động log WARN nếu thiếu (soft check — deploy pipeline có thể chưa sync).
 */
public final class FileConstants {

    private FileConstants() {}

    // =========================================================================
    // Default avatar UUIDs (fixed, seed V11)
    // =========================================================================

    public static final UUID DEFAULT_USER_AVATAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    public static final UUID DEFAULT_GROUP_AVATAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** Convenience set dùng cho cleanup job skip check. */
    public static final Set<UUID> DEFAULT_AVATAR_IDS = Set.of(
            DEFAULT_USER_AVATAR_ID,
            DEFAULT_GROUP_AVATAR_ID
    );

    // =========================================================================
    // Default avatar storage paths (relative — resolve với basePath)
    // =========================================================================

    public static final String DEFAULT_USER_AVATAR_PATH = "default/avatar_default.jpg";
    public static final String DEFAULT_GROUP_AVATAR_PATH = "default/group_default.jpg";

    // =========================================================================
    // Public URL helpers — ADR-021
    // =========================================================================

    /** Public endpoint URL cho default user avatar. Dùng trong UserDto khi user.avatarUrl null. */
    public static final String DEFAULT_USER_AVATAR_URL =
            "/api/files/" + DEFAULT_USER_AVATAR_ID + "/public";

    /** Public endpoint URL cho default group avatar. Dùng trong ConversationDto khi group.avatarFileId null. */
    public static final String DEFAULT_GROUP_AVATAR_URL =
            "/api/files/" + DEFAULT_GROUP_AVATAR_ID + "/public";

    /**
     * Build public URL cho bất kỳ fileId nào.
     * Format: /api/files/{id}/public
     */
    public static String publicUrl(UUID fileId) {
        return "/api/files/" + fileId + "/public";
    }

    /**
     * Build private URL cho fileId (yêu cầu JWT).
     * Format: /api/files/{id}
     */
    public static String privateUrl(UUID fileId) {
        return "/api/files/" + fileId;
    }
}
