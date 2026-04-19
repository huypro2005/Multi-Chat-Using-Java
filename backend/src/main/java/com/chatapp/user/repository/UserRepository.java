package com.chatapp.user.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.chatapp.user.entity.User;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    /**
     * Tìm user theo username (prefix-match) hoặc fullName (substring-match).
     * Luôn exclude caller và user không active.
     * Sắp xếp theo username ASC để ổn định.
     */
    @Query("""
            SELECT u FROM User u WHERE
            (LOWER(u.username) LIKE LOWER(CONCAT(:q, '%')) OR
             LOWER(u.fullName) LIKE LOWER(CONCAT('%', :q, '%')))
            AND u.id != :currentUserId
            AND u.status = 'active'
            ORDER BY u.username ASC
            """)
    List<User> searchUsers(@Param("q") String q,
                           @Param("currentUserId") UUID currentUserId,
                           Pageable pageable);
}
