package com.example.AviaryService.mcp;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Component;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.UserRepository;

// Who an MCP tool call is acting for: the owner the access token was issued
// to. Every tool goes through here, so no tool can pick a user on its own.
@Component
public class McpCurrentUser {

    private final UserRepository userRepository;

    public McpCurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // The token's owner. Throws instead of returning null so a tool can never
    // fall through to "no user" and touch someone else's rows.
    public User require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new AccessDeniedException("Not signed in");
        }
        User user = userRepository.findByUsername(auth.getName());
        if (user == null) {
            throw new AccessDeniedException("Account not found");
        }
        return user;
    }

    // The assistant's client_id (its metadata URL), or null outside a real
    // token -- recorded on proposals so the owner can see which assistant
    // imported what.
    public String clientId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal) {
            Object id = principal.getAttribute("client_id");
            return id == null ? null : id.toString();
        }
        return null;
    }

    // The token was granted this scope, e.g. "aviary.timeline.import".
    public void requireScope(String scope) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean granted = auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("SCOPE_" + scope));
        if (!granted) {
            throw new AccessDeniedException("This assistant was not granted " + scope);
        }
    }
}
