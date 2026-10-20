package com.example.AviaryService.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.account.LoginAttemptService;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final UserRepository userRepository;
    @Value("${aviary.remember-me.key}")
    private String rememberMeKey;

    public SecurityConfig(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(LoginAttemptService loginAttemptService) {

        return username -> {
            long start = System.currentTimeMillis();
            User user = userRepository.findByUsername(username);
            long end = System.currentTimeMillis();
            System.out.println("User lookup time: " + (end - start) + "ms");
            if (user == null) {
                throw new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found: " + username);
            }
            return org.springframework.security.core.userdetails.User
                    .withUsername(user.getUsername())
                    .password(user.getPassword())
                    .roles("USER") // Simple role for now
                    // Too many recent wrong passwords -- see LoginAttemptService.
                    .accountLocked(loginAttemptService.isLocked(username))
                    .build();
        };
    }

    // /mcp is called by AI assistants, not browsers: no session, no CSRF, and
    // every request carries an OAuth access token Aviary issued (see
    // oauth/AuthorizationServerConfig). A missing or dead token gets a 401 that
    // points the assistant at the sign-in metadata, per the MCP auth spec.
    @Bean
    @Order(1)
    public SecurityFilterChain mcpFilterChain(HttpSecurity http, OpaqueTokenIntrospector introspector,
                                              @Value("${aviary.oauth.issuer}") String issuer) throws Exception {
        String challenge = "Bearer resource_metadata=\"" + issuer + "/.well-known/oauth-protected-resource\"";
        http
            .securityMatcher("/mcp", "/mcp/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("SCOPE_aviary.read"))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .oauth2ResourceServer(oauth -> oauth
                .opaqueToken(opaque -> opaque.introspector(introspector))
                // setStatus, not sendError: sendError forwards to /error, which
                // the main chain answers with a redirect to /login instead of 401.
                .authenticationEntryPoint((request, response, ex) -> {
                    response.setHeader("WWW-Authenticate", challenge);
                    response.setStatus(401);
                })
                .accessDeniedHandler((request, response, ex) -> response.setStatus(403)));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain staticResourcesFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/css/**", "/js/**", "/images/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .headers(headers -> headers.cacheControl(cache -> cache.disable()))
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        System.out.println("SecurityConfig loaded");
        http
            .authorizeHttpRequests(auth -> auth
            .requestMatchers("/register", "/login", "/css/**", "/js/**", "/images/**").permitAll()
            // OAuth discovery for MCP clients -- public by spec (RFC 9728).
            .requestMatchers("/.well-known/oauth-protected-resource").permitAll()
            // Token-guarded alert links clicked from an email inbox -- the opaque
            // token is the auth, no login. See AlertPublicController.
            .requestMatchers("/alerts/confirm", "/alerts/decline", "/alerts/unsubscribe").permitAll()
            .anyRequest().authenticated()

            )
            // The emailed-link POSTs carry their own unguessable token and come
            // from a page with no session, so a CSRF token adds nothing there.
            .csrf(csrf -> csrf.ignoringRequestMatchers("/alerts/confirm", "/alerts/decline", "/alerts/unsubscribe"))
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(loginSuccessHandler())
                // Locked out by LoginAttemptService -> say so instead of "wrong password".
                .failureHandler((request, response, exception) -> response.sendRedirect(
                    request.getContextPath() + (exception instanceof LockedException ? "/login?locked" : "/login?error")))
                .permitAll()
            )
            .rememberMe(remember -> remember
                .key(rememberMeKey)
                .rememberMeParameter("remember")
                .tokenValiditySeconds(14 * 24 * 60 * 60)
            )  
            .logout(logout -> logout
                .logoutUrl("/logout") // POST endpoint
                .logoutSuccessUrl("/login?logout") // Redirect after logout
                .permitAll()
            );
        return http.build();
    }

    // After login, go to the dashboard -- unless the login was the middle of an
    // AI assistant's sign-in (/oauth2/authorize), which has to resume so the
    // owner reaches the consent page.
    private static AuthenticationSuccessHandler loginSuccessHandler() {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        SavedRequestAwareAuthenticationSuccessHandler resume = new SavedRequestAwareAuthenticationSuccessHandler();
        resume.setRequestCache(requestCache);
        return (request, response, authentication) -> {
            SavedRequest saved = requestCache.getRequest(request, response);
            if (saved != null && saved.getRedirectUrl().contains("/oauth2/authorize")) {
                resume.onAuthenticationSuccess(request, response, authentication);
            } else {
                requestCache.removeRequest(request, response);
                response.sendRedirect(request.getContextPath() + "/dashboard");
            }
        };
    }

}