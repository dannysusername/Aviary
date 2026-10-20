package com.example.AviaryService.oauth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.repositories.OAuthAuthorizationRepository;

// Checks a /mcp bearer token against the stored grants -- in process, no HTTP
// call. A token is good only while its grant row exists, so Disconnect (which
// deletes the row) cuts the assistant off on its very next request.
@Component
public class LocalOpaqueTokenIntrospector implements OpaqueTokenIntrospector {

    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

    private final OAuth2AuthorizationService authorizationService;
    private final OAuthAuthorizationRepository repository;
    private final Clock clock;

    @Autowired
    public LocalOpaqueTokenIntrospector(OAuth2AuthorizationService authorizationService,
                                        OAuthAuthorizationRepository repository) {
        this(authorizationService, repository, Clock.systemUTC());
    }

    LocalOpaqueTokenIntrospector(OAuth2AuthorizationService authorizationService,
                                 OAuthAuthorizationRepository repository, Clock clock) {
        this.authorizationService = authorizationService;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        OAuth2Authorization authorization = authorizationService.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
        if (authorization == null) {
            throw new BadOpaqueTokenException("Unknown or revoked token");
        }
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        Instant now = clock.instant();
        if (accessToken == null || accessToken.isInvalidated()
            || (accessToken.getToken().getExpiresAt() != null && !accessToken.getToken().getExpiresAt().isAfter(now))) {
            throw new BadOpaqueTokenException("Token is no longer active");
        }

        touchLastUsed(authorization.getId(), now);

        List<GrantedAuthority> authorities = accessToken.getToken().getScopes().stream()
            .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + scope))
            .toList();
        return new DefaultOAuth2AuthenticatedPrincipal(authorization.getPrincipalName(),
            Map.of("sub", authorization.getPrincipalName(), "client_id", authorization.getRegisteredClientId()),
            authorities);
    }

    // "Last used" on the Connected assistants page. Written at most once a
    // minute so a busy assistant doesn't turn every call into a DB write.
    private void touchLastUsed(String authorizationId, Instant now) {
        repository.findById(authorizationId).ifPresent(entity -> {
            Instant last = entity.getLastUsedAt();
            if (last == null || last.plus(LAST_USED_RESOLUTION).isBefore(now)) {
                entity.setLastUsedAt(now);
                repository.save(entity);
            }
        });
    }
}
