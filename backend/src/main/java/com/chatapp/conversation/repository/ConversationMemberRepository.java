package com.chatapp.conversation.repository;

import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.MemberRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface ConversationMemberRepository extends JpaRepository<ConversationMember, UUID> {

    /**
     * Danh sách conversations của một user, sắp xếp theo joinedAt mới nhất.
     */
    Page<ConversationMember> findByUser_IdOrderByJoinedAtDesc(UUID userId, Pageable pageable);

    /**
     * Kiểm tra user có phải là thành viên của conversation không.
     */
    boolean existsByConversation_IdAndUser_Id(UUID conversationId, UUID userId);

    /**
     * Tìm membership record của user trong conversation — dùng cho authorization check
     * và load muted_until cho response.
     */
    Optional<ConversationMember> findByConversation_IdAndUser_Id(UUID conversationId, UUID userId);

    /**
     * W7-D1: List all members của một conversation sort theo joinedAt ASC.
     * Dùng cho GET /{id} và broadcast helpers cần enumerate members.
     */
    List<ConversationMember> findByConversation_IdOrderByJoinedAtAsc(UUID conversationId);

    /**
     * W7-D1: List members theo role, sort joinedAt ASC. Dùng cho auto-transfer
     * (OWNER leave → promote oldest ADMIN → oldest MEMBER).
     */
    List<ConversationMember> findByConversation_IdAndRoleOrderByJoinedAtAsc(UUID conversationId, MemberRole role);

    /**
     * W7-D1: Đếm members của một conversation. Dùng để check GROUP_FULL trước INSERT batch
     * và memberCount trong summary. Native COUNT an toàn hơn List.size() khi list lớn.
     */
    long countByConversation_Id(UUID conversationId);

    /**
     * W7-D1 DELETE group: hard-delete toàn bộ rows members của 1 conversation.
     * Flyway ON DELETE CASCADE từ conversation_members.conversation_id sẽ handle khi
     * hard-delete conversation, nhưng W7 dùng soft-delete → cần explicit.
     */
    @Modifying
    @Query("DELETE FROM ConversationMember m WHERE m.conversation.id = :conversationId")
    int deleteByConversation_Id(@Param("conversationId") UUID conversationId);

    // ==========================================================================
    // W7-D2: Member Management (race-safe locks)
    // ==========================================================================

    /**
     * W7-D2: SELECT rows với FOR UPDATE lock — race-safe check trước INSERT batch
     * để tránh 2 request cùng add quá 50 members. Caller count kết quả ở Java.
     *
     * Lý do SELECT-then-count: H2 90145 `FOR UPDATE is not allowed in DISTINCT or grouped select`
     * (COUNT là aggregate). Postgres allow COUNT FOR UPDATE nhưng để portable, dùng SELECT rows.
     *
     * Alternative (V2 Postgres-only optimization): dùng `SELECT COUNT(*) ... FOR UPDATE` để
     * giảm bandwidth. V1 nhóm tối đa 50 rows → SELECT tất cả không tốn kém.
     */
    @Query(value = """
            SELECT CAST(cm.id AS VARCHAR) FROM conversation_members cm
            WHERE cm.conversation_id = CAST(:convId AS UUID)
            FOR UPDATE
            """, nativeQuery = true)
    List<String> lockMembersForUpdate(@Param("convId") String convId);

    /**
     * W7-D2: Load membership với FOR UPDATE lock — race-safe cho /leave và /transfer-owner.
     *
     * Native SQL (xem ghi chú countByConversationIdForUpdate). Sau khi lock, Hibernate
     * merge row vào persistence context; không cần refresh entity.
     */
    @Query(value = """
            SELECT * FROM conversation_members cm
            WHERE cm.conversation_id = CAST(:convId AS UUID)
              AND cm.user_id = CAST(:userId AS UUID)
            FOR UPDATE
            """, nativeQuery = true)
    Optional<ConversationMember> findByConversationIdAndUserIdForUpdate(
            @Param("convId") String convId, @Param("userId") String userId);

    /**
     * W7-D2: Candidates cho auto-transfer khi OWNER leave.
     * ORDER BY role priority ADMIN (0) < MEMBER (1), sau đó joinedAt ASC (oldest first).
     * Trả List để có thể fallback MEMBER khi không có ADMIN.
     *
     * LƯU Ý: chỉ SELECT từ members của conv, EXCLUDE caller.
     *
     * Dùng native SQL vì JPQL CASE với enum literal không portable H2/Postgres.
     * role column là VARCHAR (EnumType.STRING) nên so string trực tiếp.
     */
    @Query(value = """
            SELECT * FROM conversation_members cm
            WHERE cm.conversation_id = CAST(:convId AS UUID)
              AND cm.user_id != CAST(:excludeId AS UUID)
            ORDER BY CASE cm.role WHEN 'ADMIN' THEN 0
                                  WHEN 'MEMBER' THEN 1
                                  ELSE 2 END ASC,
                     cm.joined_at ASC
            """, nativeQuery = true)
    List<ConversationMember> findCandidatesForOwnerTransfer(
            @Param("convId") String convId, @Param("excludeId") String excludeId);

    /**
     * W7-D2: Lấy Set userIds của conv — dùng để classify add batch (already-member check)
     * chỉ trong 1 query thay vì N query findById.
     */
    @Query("SELECT m.user.id FROM ConversationMember m WHERE m.conversation.id = :convId")
    Set<UUID> findUserIdsByConversationId(@Param("convId") UUID convId);
}
