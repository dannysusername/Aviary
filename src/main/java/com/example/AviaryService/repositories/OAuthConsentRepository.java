package com.example.AviaryService.repositories;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.AviaryService.entity.OAuthConsentEntity;

public interface OAuthConsentRepository extends JpaRepository<OAuthConsentEntity, OAuthConsentEntity.Key> {
}
