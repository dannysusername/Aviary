package com.example.AviaryService.services.imports;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.OAuthAuthorizationEntity;
import com.example.AviaryService.entity.OAuthConsentEntity;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.OAuthAuthorizationRepository;
import com.example.AviaryService.repositories.OAuthConsentRepository;

// The owner's connected AI assistants, for the Connected assistants page.
// Only finished sign-ins count -- a grant with no access token is a sign-in
// that was started and abandoned.
@Service
public class ConnectionService {

    public record Connection(String id, String clientId, String clientName, String clientHost, Instant connectedAt, Instant lastUsedAt) {}

    private final OAuthAuthorizationRepository authorizationRepository;
    private final OAuthConsentRepository consentRepository;

    public ConnectionService(OAuthAuthorizationRepository authorizationRepository,
                             OAuthConsentRepository consentRepository) {
        this.authorizationRepository = authorizationRepository;
        this.consentRepository = consentRepository;
    }

    @Transactional(readOnly = true)
    public List<Connection> list(User user) {
        return authorizationRepository.findByPrincipalName(user.getUsername()).stream()
            .filter(a -> a.getAccessTokenValue() != null)
            .map(a -> new Connection(a.getId(), a.getRegisteredClientId(),
                a.getClientName() != null ? a.getClientName() : hostOf(a.getRegisteredClientId()),
                hostOf(a.getRegisteredClientId()),
                a.getAccessTokenIssuedAt(),
                a.getLastUsedAt()))
            .sorted(Comparator.comparing(Connection::connectedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    // Deletes the grant (the assistant's token stops working on its next
    // request) and the remembered consent (it has to ask again). Throws
    // NoSuchElementException if the id isn't one of this owner's grants.
    @Transactional
    public void disconnect(User user, String id) {
        OAuthAuthorizationEntity grant = authorizationRepository.findByIdAndPrincipalName(id, user.getUsername())
            .orElseThrow(() -> new NoSuchElementException("No such connection"));
        authorizationRepository.delete(grant);
        consentRepository.deleteById(new OAuthConsentEntity.Key(grant.getRegisteredClientId(), user.getUsername()));
    }

    static String hostOf(String clientId) {
        try {
            String host = URI.create(clientId).getHost();
            return host != null ? host : clientId;
        } catch (Exception e) {
            return clientId;
        }
    }
}
