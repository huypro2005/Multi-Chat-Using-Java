package com.chatapp.user.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * PATCH /api/users/me request.
 *
 * Tristate avatarUrl:
 * - not provided => no change
 * - provided null => reset to default avatar
 * - provided string => set to provided public avatar URL (validated in controller)
 */
public class UpdateProfileRequest {

    private String fullName;
    private String avatarUrl;
    private boolean avatarUrlProvided;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public boolean isAvatarUrlProvided() {
        return avatarUrlProvided;
    }

    @JsonAnySetter
    public void setField(String name, Object value) {
        if ("avatarUrl".equals(name)) {
            this.avatarUrlProvided = true;
            this.avatarUrl = value == null ? null : value.toString();
        }
    }
}
