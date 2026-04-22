package com.chatapp.user.service;

import com.chatapp.conversation.dto.UserSearchDto;
import com.chatapp.exception.AppException;
import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserBlock;
import com.chatapp.user.repository.UserBlockRepository;
import com.chatapp.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service xử lý block/unblock user (W8-D2, ADR-024).
 *
 * Bilateral block:
 *   A block B → cả A và B đều không gửi được direct message cho nhau.
 *
 * Idempotent:
 *   Block đã tồn tại → no-op (không throw).
 *
 * Privacy:
 *   Không expose "hasBlockedMe" — B không biết A đã block B.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlockService {

    private final UserBlockRepository blockRepository;
    private final UserRepository userRepository;

    /**
     * Block target user. Idempotent — đã block → no-op.
     *
     * @throws AppException CANNOT_BLOCK_SELF nếu blockerId == blockedId
     * @throws AppException USER_NOT_FOUND nếu target không tồn tại
     */
    @Transactional
    public void block(UUID blockerId, UUID blockedId) {
        if (blockerId.equals(blockedId)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "CANNOT_BLOCK_SELF",
                    "Không thể tự chặn mình");
        }

        User blocked = userRepository.findById(blockedId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                        "Không tìm thấy người dùng"));

        // Idempotent: đã block → no-op
        if (blockRepository.existsByBlocker_IdAndBlocked_Id(blockerId, blockedId)) {
            return;
        }

        User blocker = userRepository.getReferenceById(blockerId);

        UserBlock block = new UserBlock();
        block.setBlocker(blocker);
        block.setBlocked(blocked);
        blockRepository.save(block);

        log.info("[Block] User {} blocked user {}", blockerId, blockedId);
    }

    /**
     * Unblock target user.
     *
     * @throws AppException BLOCK_NOT_FOUND nếu chưa block target
     */
    @Transactional
    public void unblock(UUID blockerId, UUID blockedId) {
        if (!blockRepository.existsByBlocker_IdAndBlocked_Id(blockerId, blockedId)) {
            throw new AppException(HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND",
                    "Chưa chặn người dùng này");
        }

        blockRepository.deleteByBlockerIdAndBlockedId(blockerId, blockedId);

        log.info("[Block] User {} unblocked user {}", blockerId, blockedId);
    }

    /**
     * Kiểm tra bilateral block — A block B hoặc B block A.
     * Dùng để ngăn gửi direct message khi bất kỳ chiều nào có block.
     */
    @Transactional(readOnly = true)
    public boolean isBilaterallyBlocked(UUID userA, UUID userB) {
        return blockRepository.existsBilateral(userA, userB);
    }

    /**
     * Kiểm tra A đã block B không (chỉ 1 chiều, dùng cho isBlockedByMe).
     */
    @Transactional(readOnly = true)
    public boolean isBlockedBy(UUID blockerId, UUID blockedId) {
        return blockRepository.existsByBlocker_IdAndBlocked_Id(blockerId, blockedId);
    }

    /**
     * Danh sách users đã bị block bởi userId, sort mới nhất trước.
     */
    @Transactional(readOnly = true)
    public List<UserSearchDto> listBlocked(UUID userId) {
        return blockRepository.findByBlocker_IdOrderByCreatedAtDesc(userId).stream()
                .map(b -> b.getBlocked())
                .map(UserSearchDto::from)
                .toList();
    }
}
