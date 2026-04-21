package com.chatapp.conversation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Request body cho PATCH /api/conversations/{id} (W7-D1).
 *
 * Tristate semantics cho `avatarFileId`:
 *  - field absent → không đổi avatar.
 *  - field = null → remove avatar (set conversations.avatar_file_id = NULL).
 *  - field = uuid → set avatar mới (validate exists + uploader + MIME image).
 *
 * Lý do dùng Map + @JsonAnySetter thay vì record: Jackson deserialize record không phân biệt
 * được "field absent" và "field = null" — cả hai đều set field = null. Map giữ lại key-presence.
 * Service đọc qua {@link #hasName()} / {@link #hasAvatarFileId()} / {@link #isRemoveAvatar()}.
 */
public class UpdateGroupRequest {
    private final Map<String, Object> rawFields = new HashMap<>();

    @JsonAnySetter
    public void set(String key, Object value) {
        rawFields.put(key, value);
    }

    public boolean hasName() {
        return rawFields.containsKey("name");
    }

    public String getName() {
        Object v = rawFields.get("name");
        return v != null ? v.toString() : null;
    }

    public boolean hasAvatarFileId() {
        return rawFields.containsKey("avatarFileId");
    }

    /**
     * TRUE khi client gửi explicitly "avatarFileId": null → yêu cầu remove.
     */
    public boolean isRemoveAvatar() {
        return rawFields.containsKey("avatarFileId") && rawFields.get("avatarFileId") == null;
    }

    /**
     * UUID mới nếu có. Null khi absent hoặc khi client gửi null (= remove intent).
     */
    public UUID getAvatarFileId() {
        Object v = rawFields.get("avatarFileId");
        if (v == null) return null;
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isEmpty() {
        return !hasName() && !hasAvatarFileId();
    }
}
