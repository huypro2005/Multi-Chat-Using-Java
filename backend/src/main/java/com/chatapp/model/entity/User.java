package com.chatapp.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entity mapping bảng `users`.
 *
 * Lưu ý UUID: database generate bằng gen_random_uuid() (pgcrypto).
 * insertable=false, updatable=false để Hibernate không ghi đè giá trị DB.
 *
 * KHÔNG dùng @Data — gây vấn đề equals/hashCode với JPA lazy-loading relationships.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", insertable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    /** NULL nếu user chỉ dùng OAuth, không đặt password riêng. */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    /**
     * Trạng thái tài khoản: 'active' | 'suspended' | 'deleted'.
     * Dùng String thay vì enum để tránh migration khi thêm trạng thái mới.
     */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "active";

    /** Lưu full_name gốc khi user xóa tài khoản — để tham chiếu lịch sử tin nhắn. */
    @Column(name = "deleted_name", length = 100)
    private String deletedName;

    /** Timestamp lần đổi username gần nhất; NULL = chưa đổi bao giờ. */
    @Column(name = "username_changed_at")
    private OffsetDateTime usernameChangedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // --- Domain behavior ---

    public boolean isActive() {
        return "active".equals(status);
    }

    public boolean isDeleted() {
        return "deleted".equals(status);
    }

    public void markAsDeleted() {
        this.deletedName = this.fullName;
        this.fullName = "Deleted User";
        this.status = "deleted";
        this.avatarUrl = null;
    }
}
