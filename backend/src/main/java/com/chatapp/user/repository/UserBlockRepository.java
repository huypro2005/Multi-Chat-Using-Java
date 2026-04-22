package com.chatapp.user.repository;

import com.chatapp.user.entity.UserBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface UserBlockRepository extends JpaRepository<UserBlock, UUID> {

    /** Kiểm tra A block B (chỉ 1 chiều). */
    boolean existsByBlocker_IdAndBlocked_Id(UUID blockerId, UUID blockedId);

    /**
     * Bilateral check: A block B OR B block A.
     * Dùng để kiểm tra có thể gửi message trong DIRECT conv không.
     */
    @Query("SELECT COUNT(b) > 0 FROM UserBlock b WHERE " +
           "(b.blocker.id = :a AND b.blocked.id = :b) OR " +
           "(b.blocker.id = :b AND b.blocked.id = :a)")
    boolean existsBilateral(@Param("a") UUID a, @Param("b") UUID b);

    /** Danh sách users bị block bởi userId, sort mới nhất trước. */
    List<UserBlock> findByBlocker_IdOrderByCreatedAtDesc(UUID blockerId);

    /** Xóa block record (unblock). */
    @Modifying
    @Transactional
    @Query("DELETE FROM UserBlock b WHERE b.blocker.id = :blockerId AND b.blocked.id = :blockedId")
    void deleteByBlockerIdAndBlockedId(@Param("blockerId") UUID blockerId,
                                       @Param("blockedId") UUID blockedId);
}
