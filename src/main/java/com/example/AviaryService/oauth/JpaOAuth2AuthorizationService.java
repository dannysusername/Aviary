package com.example.AviaryService.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.example.AviaryService.entity.OAuthAuthorizationEntity;
import com.example.AviaryService.repositories.OAuthAuthorizationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;

// Stores OAuth grants in the database (survives dyno restarts) using the
// Spring Authorization Server "core services with JPA" pattern.
//
// Token values are hashed before they are stored or looked up, so a database
// leak doesn't hand out working tokens. A loaded authorization therefore
// carries the hash, not the real token; Spring only uses the loaded token's
// dates and flags, and hash() leaves an already-hashed value alone so saving
// a loaded authorization doesn't hash it twice.
@Service
@Transactional
public class JpaOAuth2AuthorizationService implements OAuth2AuthorizationService {

    static final String HASH_PREFIX = "sha256:";

    private final OAuthAuthorizationRepository repository;
    private final RegisteredClientRepository registeredClientRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JpaOAuth2AuthorizationService(OAuthAuthorizationRepository repository,
                                         RegisteredClientRepository registeredClientRepository) {
        this.repository = repository;
        this.registeredClientRepository = registeredClientRepository;
        ClassLoader classLoader = JpaOAuth2AuthorizationService.class.getClassLoader();
        List<Module> securityModules = SecurityJackson2Modules.getModules(classLoader);
        this.objectMapper.registerModules(securityModules);
        this.objectMapper.registerModule(new OAuth2AuthorizationServerJackson2Module());
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        OAuthAuthorizationEntity existing = repository.findById(authorization.getId()).orElse(null);
        OAuthAuthorizationEntity entity = toEntity(authorization);
        if (existing != null) {
            entity.setLastUsedAt(existing.getLastUsedAt());
            if (entity.getClientName() == null) entity.setClientName(existing.getClientName());
        }
        repository.save(entity);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        repository.deleteById(authorization.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2Authorization findById(String id) {
        return repository.findById(id).map(this::toObject).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        if (token == null) return null;
        Optional<OAuthAuthorizationEntity> result;
        if (tokenType == null) {
            String hashed = hash(token);
            result = repository.findByState(token)
                .or(() -> repository.findByAuthorizationCodeValue(hashed))
                .or(() -> repository.findByAccessTokenValue(hashed))
                .or(() -> repository.findByRefreshTokenValue(hashed));
        } else if (OAuth2ParameterNames.STATE.equals(tokenType.getValue())) {
            result = repository.findByState(token);
        } else if (OAuth2ParameterNames.CODE.equals(tokenType.getValue())) {
            result = repository.findByAuthorizationCodeValue(hash(token));
        } else if (OAuth2TokenType.ACCESS_TOKEN.equals(tokenType)) {
            result = repository.findByAccessTokenValue(hash(token));
        } else if (OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {
            result = repository.findByRefreshTokenValue(hash(token));
        } else {
            result = Optional.empty();
        }
        return result.map(this::toObject).orElse(null);
    }

    // SHA-256 of a token, prefixed so an already-hashed value passes through.
    static String hash(String value) {
        if (value == null || value.startsWith(HASH_PREFIX)) return value;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HASH_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private OAuth2Authorization toObject(OAuthAuthorizationEntity entity) {
        RegisteredClient registeredClient = registeredClientRepository.findById(entity.getRegisteredClientId());
        if (registeredClient == null) {
            throw new DataRetrievalFailureException(
                "The client '" + entity.getRegisteredClientId() + "' could not be loaded.");
        }

        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(registeredClient)
            .id(entity.getId())
            .principalName(entity.getPrincipalName())
            .authorizationGrantType(new AuthorizationGrantType(entity.getAuthorizationGrantType()))
            .authorizedScopes(StringUtils.commaDelimitedListToSet(entity.getAuthorizedScopes()))
            .attributes(attributes -> attributes.putAll(parseMap(entity.getAttributes())));
        if (entity.getState() != null) {
            builder.attribute(OAuth2ParameterNames.STATE, entity.getState());
        }

        if (entity.getAuthorizationCodeValue() != null) {
            OAuth2AuthorizationCode code = new OAuth2AuthorizationCode(entity.getAuthorizationCodeValue(),
                entity.getAuthorizationCodeIssuedAt(), entity.getAuthorizationCodeExpiresAt());
            builder.token(code, metadata -> metadata.putAll(parseMap(entity.getAuthorizationCodeMetadata())));
        }
        if (entity.getAccessTokenValue() != null) {
            OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                entity.getAccessTokenValue(), entity.getAccessTokenIssuedAt(), entity.getAccessTokenExpiresAt(),
                StringUtils.commaDelimitedListToSet(entity.getAccessTokenScopes()));
            builder.token(accessToken, metadata -> metadata.putAll(parseMap(entity.getAccessTokenMetadata())));
        }
        if (entity.getRefreshTokenValue() != null) {
            OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(entity.getRefreshTokenValue(),
                entity.getRefreshTokenIssuedAt(), entity.getRefreshTokenExpiresAt());
            builder.token(refreshToken, metadata -> metadata.putAll(parseMap(entity.getRefreshTokenMetadata())));
        }
        return builder.build();
    }

    private OAuthAuthorizationEntity toEntity(OAuth2Authorization authorization) {
        OAuthAuthorizationEntity entity = new OAuthAuthorizationEntity();
        entity.setId(authorization.getId());
        entity.setRegisteredClientId(authorization.getRegisteredClientId());
        entity.setPrincipalName(authorization.getPrincipalName());
        entity.setAuthorizationGrantType(authorization.getAuthorizationGrantType().getValue());
        entity.setAuthorizedScopes(StringUtils.collectionToDelimitedString(authorization.getAuthorizedScopes(), ","));
        entity.setAttributes(writeMap(authorization.getAttributes()));
        entity.setState(authorization.getAttribute(OAuth2ParameterNames.STATE));

        RegisteredClient client = registeredClientRepository.findById(authorization.getRegisteredClientId());
        if (client != null) entity.setClientName(client.getClientName());

        setTokenValues(authorization.getToken(OAuth2AuthorizationCode.class),
            entity::setAuthorizationCodeValue, entity::setAuthorizationCodeIssuedAt,
            entity::setAuthorizationCodeExpiresAt, entity::setAuthorizationCodeMetadata);

        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getToken(OAuth2AccessToken.class);
        setTokenValues(accessToken, entity::setAccessTokenValue, entity::setAccessTokenIssuedAt,
            entity::setAccessTokenExpiresAt, entity::setAccessTokenMetadata);
        if (accessToken != null && accessToken.getToken().getScopes() != null) {
            entity.setAccessTokenScopes(StringUtils.collectionToDelimitedString(accessToken.getToken().getScopes(), ","));
        }

        setTokenValues(authorization.getToken(OAuth2RefreshToken.class),
            entity::setRefreshTokenValue, entity::setRefreshTokenIssuedAt,
            entity::setRefreshTokenExpiresAt, entity::setRefreshTokenMetadata);
        return entity;
    }

    private void setTokenValues(OAuth2Authorization.Token<?> token,
                                Consumer<String> valueConsumer,
                                Consumer<Instant> issuedAtConsumer,
                                Consumer<Instant> expiresAtConsumer,
                                Consumer<String> metadataConsumer) {
        if (token != null) {
            OAuth2Token oAuth2Token = token.getToken();
            valueConsumer.accept(hash(oAuth2Token.getTokenValue()));
            issuedAtConsumer.accept(oAuth2Token.getIssuedAt());
            expiresAtConsumer.accept(oAuth2Token.getExpiresAt());
            metadataConsumer.accept(writeMap(token.getMetadata()));
        }
    }

    private Map<String, Object> parseMap(String data) {
        if (data == null) return Map.of();
        try {
            return objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    private String writeMap(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }
}
