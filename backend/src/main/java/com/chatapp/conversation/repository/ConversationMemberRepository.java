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
}
