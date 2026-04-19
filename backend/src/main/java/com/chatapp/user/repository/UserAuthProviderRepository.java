package com.chatapp.user.repository;


import org.springframework.data.jpa.repository.JpaRepository;

import com.chatapp.user.entity.UserAuthProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAuthProviderRepository extends JpaRepository<UserAuthProvider, UUID> {

    Optional<UserAuthProvider> findByProviderAndProviderUid(String provider, String providerUid);

    List<UserAuthProvider> findByUser_Id(UUID userId);

    boolean existsByProviderAndProviderUidAndUser_IdNot(String provider, String providerUid, UUID userId);
}
