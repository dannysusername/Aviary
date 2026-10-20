package com.example.AviaryService.oauth;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// OAuth Protected Resource Metadata (RFC 9728) for /mcp. An assistant that
// gets a 401 from /mcp reads this to find out where to sign in.
@RestController
public class ProtectedResourceMetadataController {

    private final String issuer;

    public ProtectedResourceMetadataController(@Value("${aviary.oauth.issuer}") String issuer) {
        this.issuer = issuer;
    }

    @GetMapping("/.well-known/oauth-protected-resource")
    public Map<String, Object> metadata() {
        return Map.of(
            "resource", issuer + "/mcp",
            "authorization_servers", List.of(issuer),
            "scopes_supported", List.of(CimdRegisteredClientRepository.SCOPE_READ,
                CimdRegisteredClientRepository.SCOPE_IMPORT),
            "bearer_methods_supported", List.of("header"),
            "resource_name", "Aviary");
    }
}
