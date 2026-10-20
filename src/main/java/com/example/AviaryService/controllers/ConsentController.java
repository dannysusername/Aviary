package com.example.AviaryService.controllers;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.AviaryService.oauth.CimdRegisteredClientRepository;

// The "Allow <assistant> to access your Aviary account?" page shown during an
// AI assistant's sign-in. Approve/Deny post back to /oauth2/authorize, which
// Spring Authorization Server handles.
@Controller
public class ConsentController {

    public record ScopeLine(String scope, String description) {}

    private final RegisteredClientRepository registeredClientRepository;

    public ConsentController(RegisteredClientRepository registeredClientRepository) {
        this.registeredClientRepository = registeredClientRepository;
    }

    @GetMapping("/oauth2/consent")
    public String consent(Principal principal, Model model,
                          @RequestParam("client_id") String clientId,
                          @RequestParam(value = "scope", required = false) String scope,
                          @RequestParam("state") String state) {
        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            model.addAttribute("error", "This assistant could not be verified.");
            return "consent";
        }

        Set<String> requested = new LinkedHashSet<>();
        if (scope != null) {
            for (String s : scope.split(" ")) {
                if (client.getScopes().contains(s)) requested.add(s);
            }
        }
        List<ScopeLine> lines = new ArrayList<>();
        for (String s : requested) {
            lines.add(new ScopeLine(s, describe(s)));
        }

        model.addAttribute("clientId", clientId);
        model.addAttribute("clientName", client.getClientName());
        model.addAttribute("clientHost", URI.create(clientId).getHost());
        model.addAttribute("state", state);
        model.addAttribute("scopes", lines);
        model.addAttribute("username", principal.getName());
        return "consent";
    }

    private static String describe(String scope) {
        return switch (scope) {
            case CimdRegisteredClientRepository.SCOPE_READ -> "Read your service timeline and flight log";
            case CimdRegisteredClientRepository.SCOPE_IMPORT ->
                "Propose service timeline items for you to review, and add them once you confirm";
            default -> scope;
        };
    }
}
