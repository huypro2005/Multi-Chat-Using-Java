package com.chatapp.config;

import java.security.Principal;

/**
 * Principal implementation cho STOMP session.
 * <p>
 * Lưu userId (UUID stringify) sau khi {@link AuthChannelInterceptor} verify JWT ở CONNECT frame.
 * Spring resolve principal vào {@code accessor.getUser()} cho các frame sau của cùng session,
 * và cũng dùng để route {@code /user/queue/*} destinations.
 * <p>
 * Dùng record để immutable + gọn. {@link Principal#getName()} trả {@code name} theo convention record.
 */
public record StompPrincipal(String name) implements Principal {

    @Override
    public String getName() {
        return name;
    }
}
