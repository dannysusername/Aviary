package com.example.AviaryService.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.AviaryService.entity.OAuthAuthorizationEntity;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.OAuthAuthorizationRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.imports.ConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;

// The whole sign-in an AI assistant does: authorize -> consent -> token with
// PKCE -> call /mcp -> owner disconnects -> token stops working. The CIMD
// download is stubbed; everything else is the real filter chains and the
// real (hashed) database storage.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OAuthFlowIntegrationTest {

    private static final String CLIENT_ID = "https://assistant.example.com/oauth/client.json";
    private static final String REDIRECT = "http://127.0.0.1:54321/callback";

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired OAuthAuthorizationRepository authorizationRepository;
    @Autowired ConnectionService connectionService;
    @Autowired com.example.AviaryService.repositories.FlightLogRepository flightLogRepository;
    @MockitoBean CimdFetcher cimdFetcher;

    private final ObjectMapper json = new ObjectMapper();
    private User owner;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setUsername("oauth-" + System.nanoTime());
        owner.setPassword("x");
        userRepository.save(owner);
        when(cimdFetcher.fetch(anyString())).thenReturn(new CimdFetcher.ClientMetadata(
            CLIENT_ID, "Test Assistant", List.of("http://localhost/callback")));
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static Map<String, List<String>> query(String url) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams();
    }

    // Spring reads GET /oauth2/authorize parameters from the raw query string,
    // so they go in the URL rather than MockMvc's .param().
    private static java.net.URI authorizeUrl(String... keyValues) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/oauth2/authorize");
        for (int i = 0; i < keyValues.length; i += 2) {
            b.queryParam(keyValues[i], keyValues[i + 1]);
        }
        return b.encode().build().toUri();
    }

    // Runs authorize + consent and returns the authorization code.
    private String authorize(String verifier) throws Exception {
        MvcResult toConsent = mvc.perform(get(authorizeUrl(
                    "response_type", "code",
                    "client_id", CLIENT_ID,
                    "redirect_uri", REDIRECT,
                    "scope", "aviary.read aviary.timeline.import",
                    "state", "client-state",
                    "code_challenge", challenge(verifier),
                    "code_challenge_method", "S256",
                    "resource", "http://localhost:8080/mcp"))
                .with(user(owner.getUsername())))
            .andReturn();
        assertEquals(302, toConsent.getResponse().getStatus(), toConsent.getResponse().getErrorMessage());
        String consentUrl = toConsent.getResponse().getRedirectedUrl();
        assertNotNull(consentUrl);
        assertTrue(consentUrl.contains("/oauth2/consent"), consentUrl);
        String serverState = query(consentUrl).get("state").get(0);

        MvcResult toClient = mvc.perform(post("/oauth2/authorize")
                .param("client_id", CLIENT_ID)
                .param("state", java.net.URLDecoder.decode(serverState, StandardCharsets.UTF_8))
                .param("scope", "aviary.read")
                .param("scope", "aviary.timeline.import")
                .with(user(owner.getUsername())))
            .andExpect(status().is3xxRedirection())
            .andReturn();
        String callback = toClient.getResponse().getRedirectedUrl();
        assertTrue(callback.startsWith(REDIRECT), callback);
        assertEquals("client-state", query(callback).get("state").get(0));
        return query(callback).get("code").get(0);
    }

    private Map<?, ?> exchange(String code, String verifier) throws Exception {
        MvcResult tokens = mvc.perform(post("/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", java.net.URLDecoder.decode(code, StandardCharsets.UTF_8))
                .param("redirect_uri", REDIRECT)
                .param("client_id", CLIENT_ID)
                .param("code_verifier", verifier))
            .andExpect(status().isOk())
            .andReturn();
        return json.readValue(tokens.getResponse().getContentAsString(), Map.class);
    }

    private int callMcp(String accessToken) throws Exception {
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
        return mvc.perform(post("/mcp")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(initialize))
            .andReturn().getResponse().getStatus();
    }

    // A real JSON-RPC tool call over /mcp: proves the token's owner reaches
    // the tool (not just that the filter let the request through).
    private String callTool(String accessToken, String tool) throws Exception {
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
        MvcResult init = mvc.perform(post("/mcp")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(initialize))
            .andReturn();
        String session = init.getResponse().getHeader("Mcp-Session-Id");
        assertNotNull(session, "status " + init.getResponse().getStatus() + " " + init.getResponse().getContentAsString());

        mvc.perform(post("/mcp")
            .header("Authorization", "Bearer " + accessToken)
            .header("Mcp-Session-Id", session)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));

        MvcResult call = mvc.perform(post("/mcp")
                .header("Authorization", "Bearer " + accessToken)
                .header("Mcp-Session-Id", session)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":{}}}"))
            .andReturn();
        if (call.getRequest().isAsyncStarted()) {
            call = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(call)).andReturn();
        }
        return call.getResponse().getContentAsString();
    }

    @Test
    void toolCallRunsAsTheTokenOwner() throws Exception {
        com.example.AviaryService.entity.FlightLog log = new com.example.AviaryService.entity.FlightLog();
        log.setUser(owner);
        log.setFromAirport("KPAO");
        log.setToAirport("KMRY");
        flightLogRepository.save(log);

        String verifier = "verifier-" + "t".repeat(50);
        String accessToken = (String) exchange(authorize(verifier), verifier).get("access_token");
        String body = callTool(accessToken, "list_flights");
        assertTrue(body.contains("KPAO"), body);
        assertFalse(body.contains("\"isError\":true"), body);
    }

    @Test
    void fullSignInThenDisconnect() throws Exception {
        String verifier = "verifier-" + "x".repeat(50);
        Map<?, ?> tokens = exchange(authorize(verifier), verifier);

        String accessToken = (String) tokens.get("access_token");
        assertNotNull(accessToken);
        assertEquals("Bearer", tokens.get("token_type"));
        // Spring Authorization Server 1.4 gives public clients no refresh token;
        // the 7-day access token is the session (see CimdRegisteredClientRepository).
        assertFalse(tokens.containsKey("refresh_token"));

        // Stored hashed, never as the token itself.
        List<OAuthAuthorizationEntity> rows = authorizationRepository.findByPrincipalName(owner.getUsername());
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).getAccessTokenValue().startsWith("sha256:"));
        assertNotEquals(accessToken, rows.get(0).getAccessTokenValue());
        assertEquals("Test Assistant", rows.get(0).getClientName());

        assertEquals(200, callMcp(accessToken));

        // Shows on the Connected assistants page, then the owner disconnects it.
        List<ConnectionService.Connection> connections = connectionService.list(owner);
        assertEquals(1, connections.size());
        connectionService.disconnect(owner, connections.get(0).id());

        assertEquals(401, callMcp(accessToken));
    }

    @Test
    void wrongVerifierIsRejected() throws Exception {
        String verifier = "verifier-" + "y".repeat(50);
        String code = authorize(verifier);
        mvc.perform(post("/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", java.net.URLDecoder.decode(code, StandardCharsets.UTF_8))
                .param("redirect_uri", REDIRECT)
                .param("client_id", CLIENT_ID)
                .param("code_verifier", "some-other-verifier-" + "z".repeat(40)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void codeCannotBeUsedTwice() throws Exception {
        String verifier = "verifier-" + "w".repeat(50);
        String code = authorize(verifier);
        exchange(code, verifier);
        mvc.perform(post("/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("code", java.net.URLDecoder.decode(code, StandardCharsets.UTF_8))
                .param("redirect_uri", REDIRECT)
                .param("client_id", CLIENT_ID)
                .param("code_verifier", verifier))
            .andExpect(status().isBadRequest());
    }

    @Test
    void authorizeWithoutPkceIsRejected() throws Exception {
        MvcResult result = mvc.perform(get(authorizeUrl(
                    "response_type", "code",
                    "client_id", CLIENT_ID,
                    "redirect_uri", REDIRECT,
                    "scope", "aviary.read",
                    "state", "s"))
                .with(user(owner.getUsername())))
            .andReturn();
        String redirect = result.getResponse().getRedirectedUrl();
        assertTrue(redirect == null || redirect.contains("error="), String.valueOf(redirect));
        assertTrue(redirect == null || !redirect.contains("code="), redirect);
    }

    @Test
    void unregisteredRedirectUriIsRejectedWithoutRedirecting() throws Exception {
        MvcResult result = mvc.perform(get(authorizeUrl(
                    "response_type", "code",
                    "client_id", CLIENT_ID,
                    "redirect_uri", "https://evil.example.net/callback",
                    "scope", "aviary.read",
                    "state", "s",
                    "code_challenge", challenge("v".repeat(50)),
                    "code_challenge_method", "S256"))
                .with(user(owner.getUsername())))
            .andReturn();
        assertEquals(400, result.getResponse().getStatus());
    }

    @Test
    void notSignedInGoesToLogin() throws Exception {
        MvcResult result = mvc.perform(get(authorizeUrl(
                    "response_type", "code",
                    "client_id", CLIENT_ID,
                    "redirect_uri", REDIRECT,
                    "scope", "aviary.read",
                    "state", "s",
                    "code_challenge", challenge("v".repeat(50)),
                    "code_challenge_method", "S256"))
                .accept(MediaType.TEXT_HTML))
            .andReturn();
        assertEquals(302, result.getResponse().getStatus(), result.getResponse().getErrorMessage());
        assertTrue(result.getResponse().getRedirectedUrl().endsWith("/login"));
    }

    @Test
    void noScopeRequestedGetsBothScopes() throws Exception {
        MvcResult result = mvc.perform(get(authorizeUrl(
                    "response_type", "code",
                    "client_id", CLIENT_ID,
                    "redirect_uri", REDIRECT,
                    "state", "s",
                    "code_challenge", challenge("v".repeat(50)),
                    "code_challenge_method", "S256"))
                .with(user(owner.getUsername())))
            .andReturn();
        assertEquals(302, result.getResponse().getStatus(), result.getResponse().getErrorMessage());
        String scope = java.net.URLDecoder.decode(
            query(result.getResponse().getRedirectedUrl()).get("scope").get(0), StandardCharsets.UTF_8);
        assertTrue(scope.contains("aviary.read") && scope.contains("aviary.timeline.import"), scope);
    }

    @Test
    void metadataAdvertisesCimdAndPkce() throws Exception {
        MvcResult result = mvc.perform(get("/.well-known/oauth-authorization-server"))
            .andExpect(status().isOk()).andReturn();
        Map<?, ?> metadata = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertEquals(Boolean.TRUE, metadata.get("client_id_metadata_document_supported"));
        assertEquals(List.of("none"), metadata.get("token_endpoint_auth_methods_supported"));
        assertEquals(List.of("S256"), metadata.get("code_challenge_methods_supported"));
        assertFalse(metadata.containsKey("registration_endpoint"));

        MvcResult prm = mvc.perform(get("/.well-known/oauth-protected-resource"))
            .andExpect(status().isOk()).andReturn();
        Map<?, ?> resource = json.readValue(prm.getResponse().getContentAsString(), Map.class);
        assertEquals("http://localhost:8080/mcp", resource.get("resource"));
    }
}
