package com.chatapp.user.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entity mapping bảng `user_auth_providers`.
 * Mỗi record là 1 OAuth provider được link vào 1 user.
 * 1 user có thể có nhiều providers (Google + Facebook, ...).
 * UNIQUE constraint: (provider, provider_uid) — đảm bảo 1 OAuth account
 * không thể link vào 2 user khác nhau.
 */
@Entity
@Table(name = "user_auth_providers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAuthProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Tên provider: 'google', 'facebook', ... */
    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    /** UID phía provider (Firebase UID, Facebook ID, ...) */
    @Column(name = "provider_uid", nullable = false, length = 255)
    private String providerUid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
