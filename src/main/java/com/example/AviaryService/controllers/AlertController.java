package com.example.AviaryService.controllers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.AviaryService.entity.AlertPreference;
import com.example.AviaryService.entity.AlertRecipient;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.AlertRecipientService;
import com.example.AviaryService.services.AlertService;

// Owner-facing maintenance-alerts API. All routes require an authenticated user
// (SecurityConfig: anyRequest().authenticated()). The token-guarded
// confirm/decline/unsubscribe links live in AlertPublicController. See
// docs/ALERTS_SPEC.md ("Endpoints").
@RestController
@RequestMapping("/alerts")
public class AlertController {

    private final AlertService alertService;
    private final AlertRecipientService recipientService;
    private final UserRepository userRepository;
    private final ServiceTimelineRepository serviceTimelineRepository;

    public AlertController(AlertService alertService, AlertRecipientService recipientService,
            UserRepository userRepository, ServiceTimelineRepository serviceTimelineRepository) {
        this.alertService = alertService;
        this.recipientService = recipientService;
        this.userRepository = userRepository;
        this.serviceTimelineRepository = serviceTimelineRepository;
    }

    @GetMapping("/preferences")
    public ResponseEntity<Map<String, Object>> getPreferences(Authentication auth) {
        User user = currentUser(auth);
        if (user == null) return unauthorized();
        AlertPreference prefs = alertService.getOrCreatePrefs(user);
        return ResponseEntity.ok(prefsBody(user, prefs));
    }

    @PutMapping("/preferences")
    public ResponseEntity<Map<String, Object>> updatePreferences(
            @RequestBody Map<String, Object> body, Authentication auth) {
        User user = currentUser(auth);
        if (user == null) return unauthorized();
        AlertPreference current = alertService.getOrCreatePrefs(user);
        try {
            AlertPreference prefs = alertService.updatePrefs(user,
                asBool(body.get("enabled"), current.isAlertsEnabled()),
                asInt(body.get("checkHour"), current.getCheckHour()),
                asInt(body.get("leadTimeDays"), current.getLeadTimeDays()),
                asInt(body.get("leadTimeHours"), current.getLeadTimeHours()),
                asInt(body.get("overdueRenudgeDays"), current.getOverdueRenudgeDays()));
            return ResponseEntity.ok(prefsBody(user, prefs));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/recipients")
    public ResponseEntity<Map<String, Object>> listRecipients(Authentication auth) {
        User user = currentUser(auth);
        if (user == null) return unauthorized();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AlertRecipient r : recipientService.list(user)) {
            rows.add(recipientBody(r));
        }
        return ResponseEntity.ok(Map.of("recipients", rows));
    }

    @PostMapping("/recipients")
    public ResponseEntity<Map<String, Object>> addRecipient(
            @RequestParam String channel,
            @RequestParam String destination,
            @RequestParam(required = false) String label,
            Authentication auth) {
        User user = currentUser(auth);
        if (user == null) return unauthorized();
        AlertRecipient.Channel ch;
        try {
            ch = AlertRecipient.Channel.valueOf(channel.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Pick Email or Text."));
        }
        try {
            AlertRecipient r = recipientService.add(user, ch, destination, label);
            return ResponseEntity.ok(recipientBody(r));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/recipients/{id}")
    public ResponseEntity<Map<String, Object>> removeRecipient(@PathVariable long id, Authentication auth) {
        User user = currentUser(auth);
        if (user == null) return unauthorized();
        try {
            recipientService.remove(user, id);
            return ResponseEntity.ok(Map.of("status", "removed"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/recipients/{id}/resend")
    public ResponseEntity<Map<String, Object>> resendConfirmation(@PathVariable long id, Authentication auth) {
        User user = currentUser(auth);
        if (user == null) return unauthorized();
        try {
            AlertRecipient r = recipientService.resend(user, id);
            return ResponseEntity.ok(recipientBody(r));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/send-now")
    public ResponseEntity<Map<String, Object>> sendNow(Authentication auth) {
        User user = currentUser(auth);
        if (user == null) return unauthorized();
        int sent = alertService.sendNow(user);
        return ResponseEntity.ok(Map.of("status", "ok", "sent", sent));
    }

    // -- helpers --

    private User currentUser(Authentication auth) {
        return auth == null ? null : userRepository.findByUsername(auth.getName());
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated."));
    }

    private Map<String, Object> prefsBody(User user, AlertPreference prefs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", prefs.isAlertsEnabled());
        m.put("checkHour", prefs.getCheckHour());
        m.put("leadTimeDays", prefs.getLeadTimeDays());
        m.put("leadTimeHours", prefs.getLeadTimeHours());
        m.put("overdueRenudgeDays", prefs.getOverdueRenudgeDays());

        // Readiness hints for the "turn on" dialog (advisory only).
        List<String> missing = new ArrayList<>();
        long itemCount = serviceTimelineRepository.findByUserOrderByTimelineOrderAsc(user).stream()
            .filter(t -> !t.getIsTitle()).count();
        if (itemCount == 0) missing.add("Add at least one maintenance item");
        if (isBlank(user.getTailNumber())) missing.add("Set your tail number");
        if (user.getTimeInServiceHours() == null) missing.add("Enter aircraft hours");
        m.put("readiness", Map.of("missing", missing, "ready", missing.isEmpty()));
        return m;
    }

    private Map<String, Object> recipientBody(AlertRecipient r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("channel", r.getChannel());
        m.put("destination", r.getDestination());
        m.put("label", r.getLabel());
        m.put("status", r.getStatus());
        m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        return m;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean asBool(Object v, boolean dflt) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return dflt;
    }

    private static int asInt(Object v, int dflt) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return dflt; }
        }
        return dflt;
    }
}
