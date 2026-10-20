package com.example.AviaryService.oauth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

// Aviary has no client sign-up. An AI assistant identifies itself with an
// https client_id URL (Client ID Metadata Document, the MCP spec's preferred
// method), and this builds the client from that document on demand. Dynamic
// Client Registration is deliberately unsupported -- save() refuses.
@Component
public class CimdRegisteredClientRepository implements RegisteredClientRepository {

    public static final String SCOPE_READ = "aviary.read";
    public static final String SCOPE_IMPORT = "aviary.timeline.import";

    // Spring Authorization Server 1.4 never gives public clients a refresh
    // token, so the access token is the whole session. Kept long enough that
    // the owner isn't sent back to sign in every hour; safe because tokens are
    // opaque, stored hashed, and Disconnect kills one on its next request.
    static final Duration ACCESS_TOKEN_TTL = Duration.ofDays(7);
    static final Duration CACHE_TTL = Duration.ofHours(1);

    private record Cached(RegisteredClient client, Instant fetchedAt) {}

    private final CimdFetcher fetcher;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public CimdRegisteredClientRepository(CimdFetcher fetcher) {
        this(fetcher, Clock.systemUTC());
    }

    CimdRegisteredClientRepository(CimdFetcher fetcher, Clock clock) {
        this.fetcher = fetcher;
        this.clock = clock;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException("Client registration is not supported; use a Client ID Metadata Document");
    }

    // The client's id is its URL, so both lookups are the same.
    @Override
    public RegisteredClient findById(String id) {
        return findByClientId(id);
    }

    // null when the URL isn't a usable CIMD document -- Spring then rejects
    // the sign-in with invalid_client.
    @Override
    public RegisteredClient findByClientId(String clientId) {
        if (clientId == null || !CimdFetcher.looksLikeCimdUrl(clientId)) {
            return null;
        }
        Cached hit = cache.get(clientId);
        if (hit != null && hit.fetchedAt().plus(CACHE_TTL).isAfter(clock.instant())) {
            return hit.client();
        }
        CimdFetcher.ClientMetadata metadata;
        try {
            metadata = fetcher.fetch(clientId);
        } catch (IllegalArgumentException e) {
            // The assistant's site is down or its document broke: keep using
            // the last good copy rather than cutting off a connected assistant.
            return hit != null ? hit.client() : null;
        }
        RegisteredClient client = toClient(metadata);
        cache.put(clientId, new Cached(client, clock.instant()));
        return client;
    }

    private static RegisteredClient toClient(CimdFetcher.ClientMetadata metadata) {
        return RegisteredClient.withId(metadata.clientId())
            .clientId(metadata.clientId())
            .clientName(metadata.clientName())
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUris(uris -> uris.addAll(metadata.redirectUris()))
            .scope(SCOPE_READ)
            .scope(SCOPE_IMPORT)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(true)
                .build())
            .tokenSettings(TokenSettings.builder()
                .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                .accessTokenTimeToLive(ACCESS_TOKEN_TTL)
                .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                .refreshTokenTimeToLive(Duration.ofDays(30))
                .reuseRefreshTokens(false)
                .build())
            .build();
    }
}
