package com.example.AviaryService.entity;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

// The scopes an owner approved for one assistant, so the consent page is only
// shown once. Deleted together with the authorization on disconnect.
@Entity
@Table(name = "oauth_consents")
@IdClass(OAuthConsentEntity.Key.class)
public class OAuthConsentEntity {

    @Id
    @Column(length = 1000)
    private String registeredClientId;

    @Id
    private String principalName;

    @Column(length = 1000)
    private String authorities;

    public String getRegisteredClientId() { return registeredClientId; }
    public void setRegisteredClientId(String registeredClientId) { this.registeredClientId = registeredClientId; }
    public String getPrincipalName() { return principalName; }
    public void setPrincipalName(String principalName) { this.principalName = principalName; }
    public String getAuthorities() { return authorities; }
    public void setAuthorities(String authorities) { this.authorities = authorities; }

    public static class Key implements Serializable {
        private String registeredClientId;
        private String principalName;

        public Key() {}

        public Key(String registeredClientId, String principalName) {
            this.registeredClientId = registeredClientId;
            this.principalName = principalName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(registeredClientId, k.registeredClientId)
                && Objects.equals(principalName, k.principalName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(registeredClientId, principalName);
        }
    }
}
