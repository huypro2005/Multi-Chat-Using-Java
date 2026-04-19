package com.chatapp.conversation.repository;

import com.chatapp.conversation.entity.ConversationMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
