package com.example.AviaryService.oauth;

import java.util.HashSet;
import java.util.Set;

import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.example.AviaryService.entity.OAuthConsentEntity;
import com.example.AviaryService.repositories.OAuthConsentRepository;

// Remembers which scopes an owner approved for an assistant.
@Service
@Transactional
public class JpaOAuth2AuthorizationConsentService implements OAuth2AuthorizationConsentService {

    private final OAuthConsentRepository repository;
    private final RegisteredClientRepository registeredClientRepository;

    public JpaOAuth2AuthorizationConsentService(OAuthConsentRepository repository,
                                                RegisteredClientRepository registeredClientRepository) {
        this.repository = repository;
        this.registeredClientRepository = registeredClientRepository;
    }

    @Override
    public void save(OAuth2AuthorizationConsent consent) {
        repository.save(toEntity(consent));
    }

    @Override
    public void remove(OAuth2AuthorizationConsent consent) {
        repository.deleteById(new OAuthConsentEntity.Key(consent.getRegisteredClientId(), consent.getPrincipalName()));
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
        return repository.findById(new OAuthConsentEntity.Key(registeredClientId, principalName))
            .map(this::toObject).orElse(null);
    }

    private OAuth2AuthorizationConsent toObject(OAuthConsentEntity entity) {
        RegisteredClient client = registeredClientRepository.findById(entity.getRegisteredClientId());
        if (client == null) {
            throw new DataRetrievalFailureException(
                "The client '" + entity.getRegisteredClientId() + "' could not be loaded.");
        }
        OAuth2AuthorizationConsent.Builder builder =
            OAuth2AuthorizationConsent.withId(entity.getRegisteredClientId(), entity.getPrincipalName());
        for (String authority : StringUtils.commaDelimitedListToSet(entity.getAuthorities())) {
            builder.authority(new SimpleGrantedAuthority(authority));
        }
        return builder.build();
    }

    private OAuthConsentEntity toEntity(OAuth2AuthorizationConsent consent) {
        OAuthConsentEntity entity = new OAuthConsentEntity();
        entity.setRegisteredClientId(consent.getRegisteredClientId());
        entity.setPrincipalName(consent.getPrincipalName());
        Set<String> authorities = new HashSet<>();
        for (GrantedAuthority authority : consent.getAuthorities()) {
            authorities.add(authority.getAuthority());
        }
        entity.setAuthorities(StringUtils.collectionToCommaDelimitedString(authorities));
        return entity;
    }
}
