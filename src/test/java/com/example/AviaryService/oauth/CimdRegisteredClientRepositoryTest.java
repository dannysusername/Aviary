package com.example.AviaryService.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;

class CimdRegisteredClientRepositoryTest {

    private static final String URL = "https://assistant.example.com/client.json";

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final CimdFetcher fetcher = mock(CimdFetcher.class);
    private final MutableClock clock = new MutableClock();
    private final CimdRegisteredClientRepository repo = new CimdRegisteredClientRepository(fetcher, clock);

    private void documentIsGood() {
        when(fetcher.fetch(URL)).thenReturn(
            new CimdFetcher.ClientMetadata(URL, "Assistant", List.of("http://localhost/callback")));
    }

    @Test
    void buildsPublicPkceClientWithOpaqueTokens() {
        documentIsGood();
        RegisteredClient c = repo.findByClientId(URL);
        assertEquals(URL, c.getClientId());
        assertTrue(c.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE));
        assertTrue(c.getClientSettings().isRequireProofKey());
        assertTrue(c.getClientSettings().isRequireAuthorizationConsent());
        assertEquals(OAuth2TokenFormat.REFERENCE, c.getTokenSettings().getAccessTokenFormat());
    }

    @Test
    void nonCimdClientIdIsUnknownWithoutFetching() {
        assertNull(repo.findByClientId("my-client"));
        assertNull(repo.findByClientId("http://assistant.example.com/client.json"));
    }

    @Test
    void cachesForAnHour() {
        documentIsGood();
        repo.findByClientId(URL);
        repo.findByClientId(URL);
        verify(fetcher, times(1)).fetch(URL);
        clock.now = clock.now.plus(Duration.ofMinutes(61));
        repo.findByClientId(URL);
        verify(fetcher, times(2)).fetch(URL);
    }

    @Test
    void keepsLastGoodCopyWhenRefetchFails() {
        documentIsGood();
        repo.findByClientId(URL);
        when(fetcher.fetch(URL)).thenThrow(new IllegalArgumentException("site down"));
        clock.now = clock.now.plus(Duration.ofHours(2));
        assertNotNull(repo.findByClientId(URL));
    }

    @Test
    void unknownWhenFirstFetchFails() {
        when(fetcher.fetch(URL)).thenThrow(new IllegalArgumentException("bad"));
        assertNull(repo.findByClientId(URL));
    }

    @Test
    void registrationIsRefused() {
        documentIsGood();
        RegisteredClient c = repo.findByClientId(URL);
        assertThrows(UnsupportedOperationException.class, () -> repo.save(c));
    }
}
