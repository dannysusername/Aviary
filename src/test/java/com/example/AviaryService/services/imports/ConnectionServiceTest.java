package com.example.AviaryService.services.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.OAuthAuthorizationEntity;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.OAuthAuthorizationRepository;
import com.example.AviaryService.repositories.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ConnectionServiceTest {

    @Autowired ConnectionService connectionService;
    @Autowired OAuthAuthorizationRepository repository;
    @Autowired UserRepository userRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = saveUser("alice-");
        bob = saveUser("bob-");
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword("x");
        return userRepository.save(u);
    }

    private OAuthAuthorizationEntity grant(User owner, boolean finished) {
        OAuthAuthorizationEntity e = new OAuthAuthorizationEntity();
        e.setId(UUID.randomUUID().toString());
        e.setRegisteredClientId("https://assistant.example.com/oauth/client.json");
        e.setPrincipalName(owner.getUsername());
        e.setAuthorizationGrantType("authorization_code");
        e.setClientName("Test Assistant");
        if (finished) {
            e.setAccessTokenValue("sha256:" + UUID.randomUUID());
            e.setAccessTokenIssuedAt(Instant.now());
        }
        return repository.save(e);
    }

    @Test
    void listsOnlyOwnFinishedConnections() {
        grant(alice, true);
        grant(alice, false); // abandoned sign-in
        grant(bob, true);

        var list = connectionService.list(alice);
        assertEquals(1, list.size());
        assertEquals("Test Assistant", list.get(0).clientName());
        assertEquals("assistant.example.com", list.get(0).clientHost());
    }

    @Test
    void cannotDisconnectSomeoneElsesConnection() {
        OAuthAuthorizationEntity bobs = grant(bob, true);
        assertThrows(NoSuchElementException.class, () -> connectionService.disconnect(alice, bobs.getId()));
        assertTrue(repository.findById(bobs.getId()).isPresent());
    }

    @Test
    void disconnectRemovesTheGrant() {
        OAuthAuthorizationEntity mine = grant(alice, true);
        connectionService.disconnect(alice, mine.getId());
        assertTrue(repository.findById(mine.getId()).isEmpty());
    }
}
