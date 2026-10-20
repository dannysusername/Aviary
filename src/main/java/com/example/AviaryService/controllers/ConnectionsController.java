package com.example.AviaryService.controllers;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import com.example.AviaryService.entity.TimelineImportRecord;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.TimelineImportRecordRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.imports.ConnectionService;

// Account -> Connected assistants: the AI assistants signed in to this
// owner's account, with a Disconnect button for each.
@Controller
public class ConnectionsController {

    private final ConnectionService connectionService;
    private final UserRepository userRepository;
    private final TimelineImportRecordRepository importRecordRepository;

    public ConnectionsController(ConnectionService connectionService, UserRepository userRepository,
                                 TimelineImportRecordRepository importRecordRepository) {
        this.connectionService = connectionService;
        this.userRepository = userRepository;
        this.importRecordRepository = importRecordRepository;
    }

    @GetMapping("/account/connections")
    public String connections(Principal principal, Model model) {
        User user = userRepository.findByUsername(principal.getName());
        model.addAttribute("connections", connectionService.list(user));
        // Recent imports, grouped by the assistant that made them.
        Map<String, List<TimelineImportRecord>> imports = new LinkedHashMap<>();
        for (TimelineImportRecord r : importRecordRepository.findTop20ByUserOrderByImportedAtDesc(user)) {
            if (r.getClientId() != null) {
                imports.computeIfAbsent(r.getClientId(), k -> new ArrayList<>()).add(r);
            }
        }
        model.addAttribute("imports", imports);
        return "connections";
    }

    @PostMapping("/account/connections/{id}/disconnect")
    public String disconnect(Principal principal, @PathVariable String id) {
        User user = userRepository.findByUsername(principal.getName());
        try {
            connectionService.disconnect(user, id);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "redirect:/account/connections?disconnected";
    }
}
