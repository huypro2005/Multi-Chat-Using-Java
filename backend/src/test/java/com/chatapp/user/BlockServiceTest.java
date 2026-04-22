package com.chatapp.user;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.message.service.MessageMapper;
import com.chatapp.message.service.PinService;
import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserBlock;
import com.chatapp.user.repository.UserBlockRepository;
import com.chatapp.user.repository.UserRepository;
import com.chatapp.user.service.BlockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests cho BlockService (W8-D2).
 *
 *  B01: Block self → CANNOT_BLOCK_SELF
 *  B02: Block non-existent user → USER_NOT_FOUND
 *  B03: Block happy path → row created
 *  B04: Block idempotent (already blocked) → no-op, no save
 *  B05: Unblock happy path → row deleted
 *  B06: Unblock không tồn tại → BLOCK_NOT_FOUND
 *  B07: isBilaterallyBlocked — A blocks B → true
 *  B08: isBilaterallyBlocked — B blocks A (reverse) → true (bilateral)
 *  B09: isBilaterallyBlocked — no block → false
 *  B10: isBlockedBy — returns correct direction check
 *  B11: listBlocked — returns user list
 *  B12: listBlocked — empty list when no blocks
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlockServiceTest {

    @Mock private UserBlockRepository blockRepo;
    @Mock private UserRepository userRepo;

    private BlockService blockService;

    private UUID userAId;
    private UUID userBId;
    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        blockService = new BlockService(blockRepo, userRepo);

        userAId = UUID.randomUUID();
        userBId = UUID.randomUUID();

        userA = new User();
        userA.setId(userAId);
        userA.setUsername("alice");
        userA.setFullName("Alice Nguyen");
        userA.setAvatarUrl(null);

        userB = new User();
        userB.setId(userBId);
        userB.setUsername("bob");
        userB.setFullName("Bob Tran");
        userB.setAvatarUrl(null);

        when(userRepo.findById(userBId)).thenReturn(Optional.of(userB));
        when(userRepo.existsById(userBId)).thenReturn(true);
        when(userRepo.getReferenceById(userAId)).thenReturn(userA);
    }

    // =========================================================================
    // B01: Block self → CANNOT_BLOCK_SELF
    // =========================================================================

    @Test
    void B01_block_self_fails() {
        assertThatThrownBy(() -> blockService.block(userAId, userAId))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo("CANNOT_BLOCK_SELF"));

        verify(blockRepo, never()).save(any());
    }

    // =========================================================================
    // B02: Block non-existent user → USER_NOT_FOUND
    // =========================================================================

    @Test
    void B02_block_nonexistent_user_fails() {
        UUID unknownId = UUID.randomUUID();
        when(userRepo.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> blockService.block(userAId, unknownId))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo("USER_NOT_FOUND"));

        verify(blockRepo, never()).save(any());
    }

    // =========================================================================
    // B03: Block happy path
    // =========================================================================

    @Test
    void B03_block_happy() {
        when(blockRepo.existsByBlocker_IdAndBlocked_Id(userAId, userBId)).thenReturn(false);

        blockService.block(userAId, userBId);

        verify(blockRepo).save(any(UserBlock.class));
    }

    // =========================================================================
    // B04: Block idempotent (already blocked) → no-op
    // =========================================================================

    @Test
    void B04_block_idempotent() {
        when(blockRepo.existsByBlocker_IdAndBlocked_Id(userAId, userBId)).thenReturn(true);

        blockService.block(userAId, userBId); // no exception

        verify(blockRepo, never()).save(any());
    }

    // =========================================================================
    // B05: Unblock happy path
    // =========================================================================

    @Test
    void B05_unblock_happy() {
        when(blockRepo.existsByBlocker_IdAndBlocked_Id(userAId, userBId)).thenReturn(true);

        blockService.unblock(userAId, userBId);

        verify(blockRepo).deleteByBlockerIdAndBlockedId(userAId, userBId);
    }

    // =========================================================================
    // B06: Unblock not found → BLOCK_NOT_FOUND
    // =========================================================================

    @Test
    void B06_unblock_not_blocked_fails() {
        when(blockRepo.existsByBlocker_IdAndBlocked_Id(userAId, userBId)).thenReturn(false);

        assertThatThrownBy(() -> blockService.unblock(userAId, userBId))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo("BLOCK_NOT_FOUND"));

        verify(blockRepo, never()).deleteByBlockerIdAndBlockedId(any(), any());
    }

    // =========================================================================
    // B07: isBilaterallyBlocked — A blocks B → true
    // =========================================================================

    @Test
    void B07_bilateral_A_blocks_B_returns_true() {
        when(blockRepo.existsBilateral(userAId, userBId)).thenReturn(true);

        assertThat(blockService.isBilaterallyBlocked(userAId, userBId)).isTrue();
    }

    // =========================================================================
    // B08: isBilaterallyBlocked — B blocks A → true (bilateral)
    // =========================================================================

    @Test
    void B08_bilateral_B_blocks_A_returns_true() {
        when(blockRepo.existsBilateral(userBId, userAId)).thenReturn(true);

        assertThat(blockService.isBilaterallyBlocked(userBId, userAId)).isTrue();
    }

    // =========================================================================
    // B09: isBilaterallyBlocked — no block → false
    // =========================================================================

    @Test
    void B09_bilateral_no_block_returns_false() {
        when(blockRepo.existsBilateral(userAId, userBId)).thenReturn(false);

        assertThat(blockService.isBilaterallyBlocked(userAId, userBId)).isFalse();
    }

    // =========================================================================
    // B10: isBlockedBy — directional check
    // =========================================================================

    @Test
    void B10_isBlockedBy_correct_direction() {
        when(blockRepo.existsByBlocker_IdAndBlocked_Id(userAId, userBId)).thenReturn(true);
        when(blockRepo.existsByBlocker_IdAndBlocked_Id(userBId, userAId)).thenReturn(false);

        assertThat(blockService.isBlockedBy(userAId, userBId)).isTrue();
        assertThat(blockService.isBlockedBy(userBId, userAId)).isFalse();
    }

    // =========================================================================
    // B11: listBlocked — returns correct user list
    // =========================================================================

    @Test
    void B11_list_blocked_users_correct() {
        UserBlock block = mock(UserBlock.class);
        when(block.getBlocked()).thenReturn(userB);
        when(blockRepo.findByBlocker_IdOrderByCreatedAtDesc(userAId)).thenReturn(List.of(block));

        var result = blockService.listBlocked(userAId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(userBId);
        assertThat(result.get(0).username()).isEqualTo("bob");
    }

    // =========================================================================
    // B12: listBlocked — empty list when no blocks
    // =========================================================================

    @Test
    void B12_list_blocked_empty() {
        when(blockRepo.findByBlocker_IdOrderByCreatedAtDesc(userAId)).thenReturn(List.of());

        var result = blockService.listBlocked(userAId);

        assertThat(result).isEmpty();
    }
}
