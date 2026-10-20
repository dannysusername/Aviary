package com.example.AviaryService.mcp;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

// Runs MCP tool methods as if a bearer token for `username` with `scopes` had
// just been accepted on /mcp -- so tool tests don't need the JSON-RPC handshake.
public final class McpTestSupport {

    private McpTestSupport() {}

    public static <T> T as(String username, List<String> scopes, Supplier<T> call) {
        List<GrantedAuthority> authorities = scopes.stream()
            .map(s -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + s))
            .toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, null, authorities));
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    public static <T> T as(String username, Supplier<T> call) {
        return as(username, Arrays.asList("aviary.read", "aviary.timeline.import"), call);
    }
}
