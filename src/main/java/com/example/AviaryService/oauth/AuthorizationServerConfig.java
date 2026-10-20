package com.example.AviaryService.oauth;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeRequestAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

// Aviary is its own OAuth authorization server so an AI assistant can sign in
// as an owner (MCP auth spec). Owners sign in on the normal /login page and
// approve the assistant on /oauth2/consent. Assistants are public clients
// identified by a Client ID Metadata Document, must use PKCE (S256), and get
// opaque access tokens that /mcp checks against the database.
@Configuration
public class AuthorizationServerConfig {

    private static final String ALL_SCOPES =
        CimdRegisteredClientRepository.SCOPE_READ + " " + CimdRegisteredClientRepository.SCOPE_IMPORT;

    @Bean
    @Order(0)
    public SecurityFilterChain authorizationServerFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = OAuth2AuthorizationServerConfigurer.authorizationServer();
        OAuth2AuthorizationCodeRequestAuthenticationConverter codeRequestConverter =
            new OAuth2AuthorizationCodeRequestAuthenticationConverter();

        http
            .securityMatcher(authorizationServer.getEndpointsMatcher())
            .with(authorizationServer, as -> as
                .authorizationEndpoint(endpoint -> endpoint
                    .consentPage("/oauth2/consent")
                    // An assistant that asks for no scope gets both; an empty
                    // grant would just fail on every /mcp call.
                    .authorizationRequestConverters(converters -> converters.add(0, request ->
                        isCodeRequestWithoutScope(request)
                            ? codeRequestConverter.convert(new DefaultScopeRequest(request))
                            : null))
                    .authenticationProviders(configureRedirectUriValidator()))
                .authorizationServerMetadataEndpoint(metadata -> metadata
                    .authorizationServerMetadataCustomizer(builder -> builder
                        .tokenEndpointAuthenticationMethods(methods -> {
                            methods.clear();
                            methods.add("none");
                        })
                        .codeChallengeMethods(methods -> {
                            methods.clear();
                            methods.add("S256");
                        })
                        .scopes(scopes -> {
                            scopes.add(CimdRegisteredClientRepository.SCOPE_READ);
                            scopes.add(CimdRegisteredClientRepository.SCOPE_IMPORT);
                        })
                        .claim("client_id_metadata_document_supported", true))))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            // Not signed in yet -> Aviary's normal login page, then back here.
            .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(@Value("${aviary.oauth.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    private static boolean isCodeRequestWithoutScope(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
            && "code".equals(request.getParameter("response_type"))
            && (request.getParameter("scope") == null || request.getParameter("scope").isBlank());
    }

    private static class DefaultScopeRequest extends HttpServletRequestWrapper {
        DefaultScopeRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getParameter(String name) {
            return "scope".equals(name) ? ALL_SCOPES : super.getParameter(name);
        }

        @Override
        public String[] getParameterValues(String name) {
            return "scope".equals(name) ? new String[] { ALL_SCOPES } : super.getParameterValues(name);
        }

        // Spring Authorization Server reads GET parameters from the raw query
        // string, so the default scope has to be added there too.
        @Override
        public String getQueryString() {
            String original = super.getQueryString() == null ? "" : super.getQueryString();
            StringBuilder qs = new StringBuilder();
            for (String part : original.split("&")) {
                if (part.isEmpty() || part.equals("scope") || part.startsWith("scope=")) continue;
                if (qs.length() > 0) qs.append('&');
                qs.append(part);
            }
            if (qs.length() > 0) qs.append('&');
            qs.append("scope=").append(java.net.URLEncoder.encode(ALL_SCOPES, java.nio.charset.StandardCharsets.UTF_8));
            return qs.toString();
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> map = new HashMap<>(super.getParameterMap());
            map.put("scope", new String[] { ALL_SCOPES });
            return Collections.unmodifiableMap(map);
        }
    }

    private static Consumer<List<org.springframework.security.authentication.AuthenticationProvider>> configureRedirectUriValidator() {
        return providers -> providers.forEach(provider -> {
            if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codeProvider) {
                Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> redirectCheck =
                    AuthorizationServerConfig::validateRedirectUri;
                codeProvider.setAuthenticationValidator(
                    redirectCheck.andThen(OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR));
            }
        });
    }

    // Exact match against the client's metadata document, except that a
    // loopback redirect (localhost / 127.0.0.1 / [::1]) may use any port --
    // desktop assistants listen on a random local port
    // (RFC 8252 section 7.3). Spring's default validator rejects "localhost".
    static void validateRedirectUri(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationCodeRequestAuthenticationToken auth = context.getAuthentication();
        RegisteredClient client = context.getRegisteredClient();
        String requested = auth.getRedirectUri();
        Set<String> registered = client.getRedirectUris();

        if (requested == null || requested.isBlank()) {
            if (registered.size() == 1) return;
            throw invalidRedirect(auth);
        }
        if (registered.contains(requested)) return;

        URI req = parse(requested);
        if (req != null && isLoopback(req) && req.getFragment() == null) {
            for (String r : registered) {
                URI reg = parse(r);
                if (reg != null && isLoopback(reg)
                    && req.getScheme().equalsIgnoreCase(reg.getScheme())
                    && samePath(req, reg)
                    && java.util.Objects.equals(req.getRawQuery(), reg.getRawQuery())) {
                    return;
                }
            }
        }
        throw invalidRedirect(auth);
    }

    private static URI parse(String uri) {
        try {
            URI u = URI.create(uri);
            return u.getScheme() == null || u.getHost() == null ? null : u;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        return "http".equalsIgnoreCase(uri.getScheme())
            && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host) || "::1".equals(host));
    }

    private static boolean samePath(URI a, URI b) {
        String pa = a.getPath() == null || a.getPath().isEmpty() ? "/" : a.getPath();
        String pb = b.getPath() == null || b.getPath().isEmpty() ? "/" : b.getPath();
        return pa.equals(pb);
    }

    // Built without the redirect URI so Spring shows an error instead of
    // redirecting to the address that just failed the check.
    private static OAuth2AuthorizationCodeRequestAuthenticationException invalidRedirect(
            OAuth2AuthorizationCodeRequestAuthenticationToken auth) {
        OAuth2AuthorizationCodeRequestAuthenticationToken withoutRedirect =
            new OAuth2AuthorizationCodeRequestAuthenticationToken(auth.getAuthorizationUri(), auth.getClientId(),
                (org.springframework.security.core.Authentication) auth.getPrincipal(), null, auth.getState(),
                auth.getScopes(), auth.getAdditionalParameters());
        withoutRedirect.setAuthenticated(true);
        return new OAuth2AuthorizationCodeRequestAuthenticationException(
            new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST, "redirect_uri is not registered for this client", null),
            withoutRedirect);
    }
}
