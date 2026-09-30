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
import org.springframework.core.annotation.Order;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.LoginAttemptService;

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

    @Bean
    @Order(1)
    public SecurityFilterChain staticResourcesFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/css/**", "/js/**", "/images/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .headers(headers -> headers.cacheControl(cache -> cache.disable()))
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        System.out.println("SecurityConfig loaded");
        http
            .authorizeHttpRequests(auth -> auth
            .requestMatchers("/register", "/login", "/css/**", "/js/**", "/images/**").permitAll()
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
                .defaultSuccessUrl("/dashboard", true) // After login, go here
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

}