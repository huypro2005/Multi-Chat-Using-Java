package com.chatapp.conversation.repository;

import com.chatapp.conversation.entity.ConversationMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ConversationMemberRepository extends JpaRepository<ConversationMember, UUID> {

    /**
     * Danh sách conversations của một user, sắp xếp theo joinedAt mới nhất.
     * Dùng cho "GET /api/conversations" — trả về conversations user đang tham gia.
     *
     * Lưu ý: Pageable ở đây dùng offset pagination vì đây là query phụ trợ
     * (lấy membership records). Cursor-based pagination sẽ được áp dụng
     * ở service layer khi query conversation list theo last_message_at.
     */
    Page<ConversationMember> findByUser_IdOrderByJoinedAtDesc(UUID userId, Pageable pageable);

    /**
     * Kiểm tra user có phải là thành viên của conversation không.
     * Dùng cho authorization check trước khi thực hiện thao tác.
     */
    boolean existsByConversation_IdAndUser_Id(UUID conversationId, UUID userId);
}
