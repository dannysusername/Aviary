package com.example.AviaryService.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.AviaryService.entity.OAuthAuthorizationEntity;

// Token values passed in here are already hashed -- see JpaOAuth2AuthorizationService.
public interface OAuthAuthorizationRepository extends JpaRepository<OAuthAuthorizationEntity, String> {
    Optional<OAuthAuthorizationEntity> findByState(String state);
    Optional<OAuthAuthorizationEntity> findByAuthorizationCodeValue(String authorizationCode);
    Optional<OAuthAuthorizationEntity> findByAccessTokenValue(String accessToken);
    Optional<OAuthAuthorizationEntity> findByRefreshTokenValue(String refreshToken);
    List<OAuthAuthorizationEntity> findByPrincipalName(String principalName);
    Optional<OAuthAuthorizationEntity> findByIdAndPrincipalName(String id, String principalName);
}
