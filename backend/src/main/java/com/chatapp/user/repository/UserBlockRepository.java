package com.chatapp.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.chatapp.user.entity.UserBlock;

import java.util.List;
import java.util.UUID;

public interface UserBlockRepository extends JpaRepository<UserBlock, UUID> {

    boolean existsByBlocker_IdAndBlocked_Id(UUID blockerId, UUID blockedId);

    List<UserBlock> findByBlocker_Id(UUID blockerId);
}
