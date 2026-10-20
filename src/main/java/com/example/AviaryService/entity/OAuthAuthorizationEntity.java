package com.example.AviaryService.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

// One connected AI assistant: the OAuth grant Aviary issued to it for one
// owner. Deleting the row disconnects the assistant -- its access token stops
// working on the next request. Token values are stored as SHA-256 hashes
// ("sha256:<hex>"), never the token itself. See oauth/JpaOAuth2AuthorizationService.
@Entity
@Table(name = "oauth_authorizations", indexes = {
    @Index(name = "idx_oauth_auth_principal", columnList = "principalName"),
    @Index(name = "idx_oauth_auth_access", columnList = "accessTokenValue"),
    @Index(name = "idx_oauth_auth_refresh", columnList = "refreshTokenValue"),
    @Index(name = "idx_oauth_auth_code", columnList = "authorizationCodeValue"),
    @Index(name = "idx_oauth_auth_state", columnList = "state")
})
public class OAuthAuthorizationEntity {

    @Id
    private String id;

    // The assistant's client_id: the https URL of its metadata document.
    @Column(length = 1000)
    private String registeredClientId;

    // Aviary username the assistant acts for.
    private String principalName;

    private String authorizationGrantType;

    @Column(length = 1000)
    private String authorizedScopes;

    @Column(columnDefinition = "TEXT")
    private String attributes;

    @Column(length = 500)
    private String state;

    private String authorizationCodeValue;
    private Instant authorizationCodeIssuedAt;
    private Instant authorizationCodeExpiresAt;
    @Column(columnDefinition = "TEXT")
    private String authorizationCodeMetadata;

    private String accessTokenValue;
    private Instant accessTokenIssuedAt;
    private Instant accessTokenExpiresAt;
    @Column(columnDefinition = "TEXT")
    private String accessTokenMetadata;
    @Column(length = 1000)
    private String accessTokenScopes;

    private String refreshTokenValue;
    private Instant refreshTokenIssuedAt;
    private Instant refreshTokenExpiresAt;
    @Column(columnDefinition = "TEXT")
    private String refreshTokenMetadata;

    // Shown on the Connected assistants page.
    private String clientName;
    private Instant lastUsedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRegisteredClientId() { return registeredClientId; }
    public void setRegisteredClientId(String registeredClientId) { this.registeredClientId = registeredClientId; }
    public String getPrincipalName() { return principalName; }
    public void setPrincipalName(String principalName) { this.principalName = principalName; }
    public String getAuthorizationGrantType() { return authorizationGrantType; }
    public void setAuthorizationGrantType(String authorizationGrantType) { this.authorizationGrantType = authorizationGrantType; }
    public String getAuthorizedScopes() { return authorizedScopes; }
    public void setAuthorizedScopes(String authorizedScopes) { this.authorizedScopes = authorizedScopes; }
    public String getAttributes() { return attributes; }
    public void setAttributes(String attributes) { this.attributes = attributes; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getAuthorizationCodeValue() { return authorizationCodeValue; }
    public void setAuthorizationCodeValue(String authorizationCodeValue) { this.authorizationCodeValue = authorizationCodeValue; }
    public Instant getAuthorizationCodeIssuedAt() { return authorizationCodeIssuedAt; }
    public void setAuthorizationCodeIssuedAt(Instant authorizationCodeIssuedAt) { this.authorizationCodeIssuedAt = authorizationCodeIssuedAt; }
    public Instant getAuthorizationCodeExpiresAt() { return authorizationCodeExpiresAt; }
    public void setAuthorizationCodeExpiresAt(Instant authorizationCodeExpiresAt) { this.authorizationCodeExpiresAt = authorizationCodeExpiresAt; }
    public String getAuthorizationCodeMetadata() { return authorizationCodeMetadata; }
    public void setAuthorizationCodeMetadata(String authorizationCodeMetadata) { this.authorizationCodeMetadata = authorizationCodeMetadata; }
    public String getAccessTokenValue() { return accessTokenValue; }
    public void setAccessTokenValue(String accessTokenValue) { this.accessTokenValue = accessTokenValue; }
    public Instant getAccessTokenIssuedAt() { return accessTokenIssuedAt; }
    public void setAccessTokenIssuedAt(Instant accessTokenIssuedAt) { this.accessTokenIssuedAt = accessTokenIssuedAt; }
    public Instant getAccessTokenExpiresAt() { return accessTokenExpiresAt; }
    public void setAccessTokenExpiresAt(Instant accessTokenExpiresAt) { this.accessTokenExpiresAt = accessTokenExpiresAt; }
    public String getAccessTokenMetadata() { return accessTokenMetadata; }
    public void setAccessTokenMetadata(String accessTokenMetadata) { this.accessTokenMetadata = accessTokenMetadata; }
    public String getAccessTokenScopes() { return accessTokenScopes; }
    public void setAccessTokenScopes(String accessTokenScopes) { this.accessTokenScopes = accessTokenScopes; }
    public String getRefreshTokenValue() { return refreshTokenValue; }
    public void setRefreshTokenValue(String refreshTokenValue) { this.refreshTokenValue = refreshTokenValue; }
    public Instant getRefreshTokenIssuedAt() { return refreshTokenIssuedAt; }
    public void setRefreshTokenIssuedAt(Instant refreshTokenIssuedAt) { this.refreshTokenIssuedAt = refreshTokenIssuedAt; }
    public Instant getRefreshTokenExpiresAt() { return refreshTokenExpiresAt; }
    public void setRefreshTokenExpiresAt(Instant refreshTokenExpiresAt) { this.refreshTokenExpiresAt = refreshTokenExpiresAt; }
    public String getRefreshTokenMetadata() { return refreshTokenMetadata; }
    public void setRefreshTokenMetadata(String refreshTokenMetadata) { this.refreshTokenMetadata = refreshTokenMetadata; }
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
