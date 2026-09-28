package com.example.AviaryService.controllers;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.AviaryService.entity.DescriptionOption;
import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.entity.DTO.TimelineUpdateDTO;
import com.example.AviaryService.repositories.DescriptionOptionRepository;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.AeroApiClient;
import com.example.AviaryService.services.DescriptionOptionService;
import com.example.AviaryService.services.FlightSuggestionService;
import com.example.AviaryService.services.FlightSyncService;
import com.example.AviaryService.services.HoursService;
import com.example.AviaryService.services.OutboundEmailLimiter;
import com.example.AviaryService.services.PdfExportService;
import com.example.AviaryService.services.SendGridEmailService;
import com.example.AviaryService.services.SubscriptionService;
import com.example.AviaryService.services.TimelineService;
import com.example.AviaryService.services.UserService;
import com.example.AviaryService.util.Formatting;
import com.example.AviaryService.util.Parsing;
import com.example.AviaryService.util.Validation;

import org.apache.catalina.connector.Response;
//import org.checkerframework.checker.units.qual.Speed;
import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
import jakarta.transaction.Transactional;

//import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

@Controller
public class UserController {
    private final UserRepository userRepository;
    private final ServiceTimelineRepository serviceTimelineRepository;
    private final PasswordEncoder passwordEncoder;
    private final FlightLogRepository flightLogRepository;
    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final SubscriptionService subscriptionService;
    private final UserService userService;
    private final TimelineService timelineService;
    private final DescriptionOptionService descriptionOptionService;
    private final HoursService hoursService;
    private final FlightSuggestionService flightSuggestionService;
    private final com.example.AviaryService.repositories.FlightSuggestionRepository flightSuggestionRepository;
    private final com.example.AviaryService.repositories.SubscriptionRepository subscriptionRepository;
    private final AeroApiClient aeroApiClient;
    private final FlightSyncService flightSyncService;
    private final PdfExportService pdfExportService;
    private final SendGridEmailService sendGridEmailService;
    private final OutboundEmailLimiter emailLimiter;

    public UserController(UserRepository userRepository, ServiceTimelineRepository serviceTimelineRepository,
            PasswordEncoder passwordEncoder, DescriptionOptionRepository descriptionOptionRepository,
            FlightLogRepository flightLogRepository, SubscriptionService subscriptionService, UserService userService,
            TimelineService timelineService, DescriptionOptionService descriptionOptionService, HoursService hoursService,
            FlightSuggestionService flightSuggestionService,
            com.example.AviaryService.repositories.FlightSuggestionRepository flightSuggestionRepository,
            com.example.AviaryService.repositories.SubscriptionRepository subscriptionRepository,
            AeroApiClient aeroApiClient, FlightSyncService flightSyncService, PdfExportService pdfExportService,
            SendGridEmailService sendGridEmailService, OutboundEmailLimiter emailLimiter) {

        this.userRepository = userRepository;
        this.serviceTimelineRepository = serviceTimelineRepository;
        this.passwordEncoder = passwordEncoder;
        this.flightLogRepository = flightLogRepository;

        this.subscriptionService = subscriptionService;
        this.userService = userService;
        this.timelineService = timelineService;
        this.descriptionOptionService = descriptionOptionService;
        this.hoursService = hoursService;
        this.flightSuggestionService = flightSuggestionService;
        this.flightSuggestionRepository = flightSuggestionRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.aeroApiClient = aeroApiClient;
        this.flightSyncService = flightSyncService;
        this.pdfExportService = pdfExportService;
        this.sendGridEmailService = sendGridEmailService;
        this.emailLimiter = emailLimiter;
    }

    @GetMapping("/register")
    public String showRegisterForm() {
        return "register";
    }

    @PostMapping("/register")
    public String registerUser(@RequestParam String username, @RequestParam String password, Model model) {
       return userService.registerUser(username, password, model);
    }

    @GetMapping("/login")
    public String showLoginForm() {
        return "login";
    }

    @PostMapping("/updateUserInfo")
    @ResponseBody
    public ResponseEntity<Map<String, String>> updateUserInfo(
        @RequestBody Map<String, String> data,
        Authentication authentication) {
            try {
                userService.updateUserInfo(data, authentication);
                return ResponseEntity.ok(Map.of("status", "success"));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
            }
    }

    @GetMapping("/dashboard")
    public String showDashboard(Model model, Authentication authentication) {
        String username = authentication.getName();
        User user = userRepository.findByUsername(username);
        model.addAttribute("username", username);
        model.addAttribute("timelines", serviceTimelineRepository.findByUserOrderByTimelineOrderAsc(user));
        model.addAttribute("descriptionOptions", descriptionOptionService.cleanupAndLoadDescriptionOptions(user));
        model.addAttribute("blockTimeHours", user.getBlockTimeHours());
        model.addAttribute("timeInServiceHours", user.getTimeInServiceHours());

        model.addAttribute("makeModel", user.getMakeModel());
        model.addAttribute("tailNumber", user.getTailNumber());
        model.addAttribute("ownerName", user.getOwnerName());
        model.addAttribute("makeModelSN", user.getMakeModelSN());
        model.addAttribute("flightlogs", sortedByFlightTime(flightLogRepository.findByUser(user)));
        model.addAttribute("blockTimeUpdatedAt", user.getBlockTimeUpdatedAt() != null ? user.getBlockTimeUpdatedAt().toString() : null);
        model.addAttribute("timeInServiceUpdatedAt", user.getTimeInServiceUpdatedAt() != null ? user.getTimeInServiceUpdatedAt().toString() : null);
        model.addAttribute("blockTimeUpdatedSource", user.getBlockTimeUpdatedSource());
        model.addAttribute("timeInServiceUpdatedSource", user.getTimeInServiceUpdatedSource());
        String aeroApiKey = user.getAeroApiKey();
        boolean hasAeroApiKey = aeroApiKey != null && !aeroApiKey.isEmpty();
        model.addAttribute("aeroApiKey", hasAeroApiKey
            ? "••••" + aeroApiKey.substring(Math.max(0, aeroApiKey.length() - 4))
            : "");
        model.addAttribute("hasAeroApiKey", hasAeroApiKey);
        model.addAttribute("flightSuggestions", flightSuggestionRepository.findByUserAndStatus(user, "pending"));

        com.example.AviaryService.entity.Subscription subscription =
            subscriptionRepository.findByUser(user).orElse(null);
        model.addAttribute("subscriptionActive", subscription != null && subscription.isActive());
        model.addAttribute("subscriptionExists", subscription != null);
        model.addAttribute("subscribedRegistration", subscription != null ? subscription.getTailNumber() : "");
        model.addAttribute("pollIntervalDays", subscription != null ? subscription.getPollIntervalDays() : 1);
        model.addAttribute("preferredCheckHour", subscription != null ? subscription.getPreferredCheckHour() : 3);
        model.addAttribute("aeroDefaultLookbackDays", AeroApiClient.DEFAULT_LOOKBACK_DAYS);
        model.addAttribute("aeroMaxStartDaysBack", AeroApiClient.MAX_START_DAYS_BACK);
        model.addAttribute("aeroMaxEndDaysAhead", AeroApiClient.MAX_END_DAYS_AHEAD);

        return "dashboard";
    }

    // Share menu's Download PDF. Server-side rendering (openhtmltopdf) --
    // see docs/SHARE_EXPORT_SPEC.md and PdfExportService. Real vector
    // text/tables, not the earlier client-side screenshot approach.
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> downloadPdf(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        byte[] pdf = pdfExportService.generateDashboardPdf(user);

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
        headers.setContentDisposition(org.springframework.http.ContentDisposition.attachment()
            .filename("Aviary_Dashboard_" + java.time.LocalDate.now() + ".pdf")
            .build());
        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }

    // Share menu's "Email PDF" -- generates the same dashboard PDF as GET /pdf
    // and sends it to an arbitrary recipient via SendGrid. See
    // docs/SHARE_EXPORT_SPEC.md ("Channels -> Email"). Note the spec's
    // cross-cutting concerns: recipient validation plus the shared per-user
    // email cap (OutboundEmailLimiter) are in place.
    @PostMapping("/pdf/email")
    @ResponseBody
    public ResponseEntity<Map<String, String>> emailPdf(@RequestParam String recipient,
            Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!sendGridEmailService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Email sending is not configured on this server."));
        }
        if (recipient == null || !recipient.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Enter a valid email address."));
        }
        emailLimiter.acquire(user);

        byte[] pdf = pdfExportService.generateDashboardPdf(user);
        String filename = "Aviary_Dashboard_" + java.time.LocalDate.now() + ".pdf";
        try {
            sendGridEmailService.sendPdf(
                recipient.trim(),
                "Your Aviary maintenance record",
                "Attached is the maintenance record for " + user.getTailNumber() + ".",
                pdf,
                filename);
        } catch (RuntimeException e) {
            log.error("Failed to email dashboard PDF to {}", recipient, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Could not send the email. Please try again later."));
        }
        return ResponseEntity.ok(Map.of("status", "sent", "recipient", recipient.trim()));
    }

    // Current-period AeroAPI spend for the logged-in user's own key. Doubles
    // as a validity check -- an invalid key surfaces as a clear 400 here
    // instead of the user waiting for the next scheduled sync to fail.
    @GetMapping("/aeroapi/usage")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getAeroApiUsage(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        String apiKey = user.getAeroApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No AeroAPI key connected"));
        }
        try {
            AeroApiClient.AeroApiUsage usage = aeroApiClient.getUsage(apiKey);
            return ResponseEntity.ok(Map.of(
                "totalCost", usage.totalCost(),
                "totalCalls", usage.totalCalls(),
                "totalFailedCalls", usage.totalFailedCalls()
            ));
        } catch (Exception e) {
            log.warn("AeroAPI usage check failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Could not verify that key with AeroAPI."));
        }
    }

    // Manual, on-demand poll -- bypasses the interval/hour gate entirely.
    // start/end are optional ISO dates (yyyy-MM-dd) from the "Custom check
    // range" fields; omitted means AeroAPI's own default window. See
    // docs/ADSB_SYNC_SPEC.md and docs/ADSB_DATA_SOURCE.md.
    @PostMapping("/subscription/check-now")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> checkNow(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        com.example.AviaryService.entity.Subscription subscription =
            subscriptionRepository.findByUser(user).orElse(null);
        if (subscription == null || !subscription.isActive()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Flight sync is not turned on."));
        }
        try {
            java.time.LocalDate startDate = (start == null || start.isBlank()) ? null : java.time.LocalDate.parse(start);
            java.time.LocalDate endDate = (end == null || end.isBlank()) ? null : java.time.LocalDate.parse(end);
            int newCount = flightSyncService.syncNow(subscription, startDate, endDate);
            return ResponseEntity.ok(Map.of("status", "success", "newFlights", newCount));
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid date format."));
        } catch (Exception e) {
            log.warn("Manual AeroAPI check failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // See docs/ADSB_SYNC_SPEC.md "Poll interval" -- how often and at what
    // local hour the background poller checks this subscription.
    @PostMapping("/subscription/settings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateSubscriptionSettings(
            @RequestBody Map<String, Object> data, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        try {
            if (!(data.get("pollIntervalDays") instanceof Number days)
                    || !(data.get("preferredCheckHour") instanceof Number hour)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Pick a check interval and hour."));
            }
            subscriptionService.updateSettings(user, days.intValue(), hour.intValue());
            return ResponseEntity.ok(Map.of("status", "success"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Every AeroAPI suggestion ever seen, any status -- an audit trail
    // independent of FlightLog's lifecycle. See docs/CHANGES.md.
    @GetMapping("/flightsuggestions/all")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getAllFlightSuggestions(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        List<Map<String, Object>> result = flightSuggestionRepository.findByUserOrderByCreatedAtDesc(user)
            .stream()
            .map(s -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", s.getId());
                m.put("origin", s.getOrigin());
                m.put("destination", s.getDestination());
                m.put("departureTime", s.getDepartureTime().toString());
                m.put("arrivalTime", s.getArrivalTime().toString());
                m.put("minutesAirborne", s.getMinutesAirborne());
                m.put("status", s.getStatus());
                return m;
            })
            .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // AeroAPI-detected flight: turn a suggestion into a real FlightLog row.
    // See docs/ADSB_SYNC_SPEC.md rule 1 -- this is the only thing that ever
    // writes a suggestion to the log book; the poller never does.
    @PostMapping("/flightsuggestions/{id}/accept")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> acceptFlightSuggestion(
            @PathVariable long id, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        try {
            FlightLog flightLog = flightSuggestionService.accept(id, user);
            Map<String, Object> response = new HashMap<>();
            response.put("id", flightLog.getId());
            response.put("fromAirport", flightLog.getFromAirport());
            response.put("toAirport", flightLog.getToAirport());
            response.put("blockTimeIn", flightLog.getBlockTimeIn());
            response.put("blockTimeOut", flightLog.getBlockTimeOut());
            response.put("timeInServiceIn", flightLog.getTimeInServiceIn());
            response.put("timeInServiceOut", flightLog.getTimeInServiceOut());
            response.put("timeInServiceStart", flightLog.getTimeInServiceStart() != null ? flightLog.getTimeInServiceStart().toString() : null);
            response.put("timeInServiceEnd", flightLog.getTimeInServiceEnd() != null ? flightLog.getTimeInServiceEnd().toString() : null);
            response.put("newTimeInService", user.getTimeInServiceHours());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // Permanent for that user -- see docs/ADSB_SYNC_SPEC.md "UX flow".
    @PostMapping("/flightsuggestions/{id}/dismiss")
    @ResponseBody
    public ResponseEntity<Map<String, String>> dismissFlightSuggestion(
            @PathVariable long id, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        try {
            flightSuggestionService.dismiss(id, user);
            return ResponseEntity.ok(Map.of("status", "success"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // Permanent removal, not the same as dismiss -- see FlightSuggestionService.delete.
    @DeleteMapping("/flightsuggestions/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, String>> deleteFlightSuggestion(
            @PathVariable long id, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        try {
            flightSuggestionService.delete(id, user);
            return ResponseEntity.ok(Map.of("status", "success"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/dashboard")
    public ResponseEntity<?> addTimeline(
            @RequestBody Map<String, String> data,
            Authentication authentication) {
                
        String item = data.get("item");
        if (item == null || item.isEmpty()) {
            return ResponseEntity.badRequest().body("Item is required");
        }
        for (String value : data.values()) {
            if (value != null && value.length() > Validation.MAX_TEXT) {
                return ResponseEntity.badRequest().body(errorBody(
                    "Entries must be " + Validation.MAX_TEXT + " characters or fewer."));
            }
        }

        String ajax = data.getOrDefault("ajax", "false");
        User user = userRepository.findByUsername(authentication.getName());
        ServiceTimeline timeline = timelineService.addTimeline(data, user);

        if ("true".equals(ajax)) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", timeline.getId());
            response.put("item", timeline.getItem());
            response.put("description", timeline.getDescription());
            response.put("cycleCalendarValue", timeline.getCycleCalendarValue());
            response.put("cycleCalendarUnit", timeline.getCycleCalendarUnit());
            response.put("cycleHours", timeline.getCycleHours());
            response.put("lastDoneDate", timeline.getLastDoneDate());
            response.put("lastDoneHours", timeline.getLastDoneHours());
            response.put("dueDateDate", timeline.getDueDateDate());
            response.put("dueDateHours", timeline.getDueDateHours());
            response.put("timeLeft", timeline.getTimeLeft());
            response.put("isTitle", timeline.getIsTitle());
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/dashboard")).build();
        }
    }

    // One subscription per user. Subscribe needs a registration; unsubscribe
    // and delete need only the authenticated user, so clearing the dashboard
    // tail number can never strand an active subscription. See
    // docs/ADSB_SYNC_SPEC.md.
    @PostMapping("/subscription/subscribe")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> subscribe(@RequestBody Map<String, String> data, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        try {
            com.example.AviaryService.entity.Subscription sub =
                subscriptionService.subscribe(user, data.get("registration"));
            return ResponseEntity.ok(Map.of("active", true, "registration", sub.getTailNumber()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/subscription/unsubscribe")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> unsubscribe(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        try {
            subscriptionService.unsubscribe(user);
            return ResponseEntity.ok(Map.of("active", false));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/subscription")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteSubscription(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        try {
            subscriptionService.delete(user);
            return ResponseEntity.ok(Map.of("status", "success"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/updateHours")
    @ResponseBody
    public ResponseEntity<Map<String, String>> updateHours(
            @RequestParam(required = false) Double blockTimeToAdd,
            @RequestParam(required = false) Double timeInServiceToAdd,
            @RequestParam(required = false) Double newBlockTime,
            @RequestParam(required = false) Double newTimeInService,
            Authentication authentication) {
        try {
            User user = userRepository.findByUsername(authentication.getName());
            if (user == null) {
                throw new IllegalArgumentException("User not found");
            }
            hoursService.updateHours(blockTimeToAdd, timeInServiceToAdd, newBlockTime, newTimeInService, user);
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("newBlockTime", String.valueOf(user.getBlockTimeHours()));
            response.put("newTimeInService", String.valueOf(user.getTimeInServiceHours()));
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update hours", e);
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Could not update hours."));
        }
    }


    @PostMapping("/update/{id}")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, String>> updateTimeline(
            @PathVariable Long id,
            @RequestBody TimelineUpdateDTO updateDTO,
            Authentication authentication) {
        try {
            ServiceTimeline timeline = serviceTimelineRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid timeline ID: " + id));

            User user = userRepository.findByUsername(authentication.getName());
            if (user == null || timeline.getUser() == null || timeline.getUser().getId() != user.getId()) {
                Map<String, String> forbidden = new HashMap<>();
                forbidden.put("status", "error");
                forbidden.put("message", "You do not own this timeline");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbidden);
            }

            Validation.maxLength("Item", updateDTO.getItem());
            Validation.maxLength("Description", updateDTO.getDescription());
            Validation.maxLength("Last done", updateDTO.getLastDoneHours());
            Validation.maxLength("Due date", updateDTO.getDueDateHours());

            System.out.println("Received updateDTO: item=" + updateDTO.getItem() + ", cycle=" + updateDTO.getCycle() +
                    ", description=" + updateDTO.getDescription() + ", lastDone=" + updateDTO.getLastDone() +
                    ", dueDate=" + updateDTO.getDueDate() + ", timeLeft=" + updateDTO.getTimeLeft());

            if (updateDTO.getItem() != null) timeline.setItem(updateDTO.getItem());
            if (updateDTO.getDescription() != null) {
                String description = updateDTO.getDescription();
                String[] defaults = {"inspect", "test", "replace", "overhaul"};
                if (java.util.Arrays.asList(defaults).contains(description.toLowerCase())) {
                    description = description.substring(0, 1).toUpperCase() + description.substring(1).toLowerCase();
                }
                timeline.setDescription(description);
                descriptionOptionService.saveCustomDescriptionOption(description, userRepository.findByUsername(authentication.getName()));
            }
            // Structured cycle fields. The client sends them on every save so
            // null actually means "clear it" here — distinguish empty/null on
            // the client if you ever want partial updates.
            timeline.setCycleCalendarValue(updateDTO.getCycleCalendarValue());
            timeline.setCycleCalendarUnit(Parsing.normalizeCalendarUnit(updateDTO.getCycleCalendarUnit()));
            timeline.setCycleHours(updateDTO.getCycleHours());
            timeline.setLastDoneDate(updateDTO.getLastDoneDate());
            timeline.setLastDoneHours(updateDTO.getLastDoneHours());
            timeline.setDueDateDate(updateDTO.getDueDateDate());
            timeline.setDueDateHours(updateDTO.getDueDateHours());
            timeline.setTimeLeft(updateDTO.getTimeLeft());
            
            ServiceTimeline savedTimeline = serviceTimelineRepository.save(timeline);
            System.out.println("Saved timeline with item: " + savedTimeline.getItem() + ", " + savedTimeline.getDescription() + ", " + savedTimeline.getLastDoneHours() + ", " + savedTimeline.getLastDoneDate() + ", "+ savedTimeline.getDueDateHours() + ", "+ savedTimeline.getDueDateDate() + ", " + savedTimeline.getTimeLeft());

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update timeline {}", id, e);
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Could not save that change."));
        }
    }


    @DeleteMapping("/delete/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteTimeline(@PathVariable Long id, Authentication authentication) {
        ServiceTimeline timeline = serviceTimelineRepository.findById(id).orElse(null);
        if (timeline == null) {
            return ResponseEntity.notFound().build();
        }
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null || timeline.getUser() == null || timeline.getUser().getId() != user.getId()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        serviceTimelineRepository.delete(timeline);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/deleteOption/{id}")
    @ResponseBody
    public ResponseEntity<String> deleteOption(@PathVariable Long id, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
        }
        try {
            descriptionOptionService.deleteOption(user, id);
            return ResponseEntity.ok("Option deleted");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }

    @PostMapping("/updateOrder")
    @ResponseBody
    public void updateOrder(@RequestBody List<Long> ids, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        List<ServiceTimeline> timelines = serviceTimelineRepository.findByUserOrderByIdAsc(user);
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            ServiceTimeline timeline = timelines.stream()
                .filter(t -> t.getId().equals(id))
                .findFirst()
                .orElse(null);
            if (timeline != null) {
                timeline.setTimelineOrder(i);
            }
        }
        serviceTimelineRepository.saveAll(timelines);
    }

    // NEW: GET flight logs (for AJAX if needed)
    @GetMapping("/flightlogs")
    @ResponseBody
    public List<FlightLog> getFlightLogs(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        return sortedByFlightTime(flightLogRepository.findByUser(user));
    }

    // Chronological order for display: prefer blockTimeStart (engine start),
    // fall back to timeInServiceStart (wheels-off) when only that's known.
    // Rows with neither (legacy entries, or a manual entry saved without
    // dates) sort last, in insertion order among themselves.
    private static List<FlightLog> sortedByFlightTime(List<FlightLog> logs) {
        return logs.stream()
            .sorted(java.util.Comparator
                .<FlightLog, java.time.Instant>comparing(log -> {
                    java.time.Instant t = log.getBlockTimeStart() != null ? log.getBlockTimeStart() : log.getTimeInServiceStart();
                    return t == null ? java.time.Instant.MAX : t;
                })
                .thenComparing(FlightLog::getId))
            .collect(java.util.stream.Collectors.toList());
    }

    // POST to add flight log
    @PostMapping(value = "/addflightlog", consumes = "application/json")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> addFlightLog(
            @RequestBody FlightLog newLog,
            @RequestParam(required = false, defaultValue = "false") boolean force,
            Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("User not authenticated"));
        }

        // Clamp readings to hundredths. Real meters never exceed 2dp (Hobbs
        // 0.1, Tach 0.01), and this keeps CSV-prefilled or client-computed
        // values from carrying float artifacts into storage.
        newLog.setBlockTimeOut(Formatting.roundHoursOrNull(newLog.getBlockTimeOut()));
        newLog.setBlockTimeIn(Formatting.roundHoursOrNull(newLog.getBlockTimeIn()));
        newLog.setTimeInServiceOut(Formatting.roundHoursOrNull(newLog.getTimeInServiceOut()));
        newLog.setTimeInServiceIn(Formatting.roundHoursOrNull(newLog.getTimeInServiceIn()));

        if (tooLong(newLog.getFromAirport()) || tooLong(newLog.getToAirport())) {
            return ResponseEntity.badRequest().body(errorBody(
                "Airport must be " + Validation.MAX_TEXT + " characters or fewer."));
        }

        // ── Validation ─────────────────────────────────────────────────────────
        // Reject incomplete entries before they can corrupt displayed hours.
        // Rule: must provide at least one COMPLETE pair (blockTimeOut+blockTimeIn or
        // timeInServiceOut+timeInServiceIn). Partial pairs (e.g. only timeInServiceOut) are the exact case
        // that used to silently wipe the user's manual hours to zero.
        Double ho = newLog.getBlockTimeOut(), hi = newLog.getBlockTimeIn();
        Double to = newLog.getTimeInServiceOut(), ti = newLog.getTimeInServiceIn();
        boolean blockTimePair = (ho != null && hi != null);
        boolean timeInServicePair  = (to != null && ti != null);
        boolean blockTimePartial = (ho == null) != (hi == null);  // exactly one set
        boolean timeInServicePartial  = (to == null) != (ti == null);

        if (!blockTimePair && !timeInServicePair) {
            return ResponseEntity.badRequest().body(errorBody(
                "Enter both Block Time Out and Block Time In, or both Time in Service Out and Time in Service In."));
        }
        if (blockTimePartial) {
            return ResponseEntity.badRequest().body(errorBody(
                "Block time entry is incomplete — enter both Block Time Out and Block Time In."));
        }
        if (timeInServicePartial) {
            return ResponseEntity.badRequest().body(errorBody(
                "Time in Service entry is incomplete — enter both Time in Service Out and Time in Service In."));
        }
        if (blockTimePair && (ho < 0 || hi < 0 || hi < ho)) {
            return ResponseEntity.badRequest().body(errorBody(
                "Block Time In must be ≥ Block Time Out, and values cannot be negative."));
        }
        if (timeInServicePair && (to < 0 || ti < 0 || ti < to)) {
            return ResponseEntity.badRequest().body(errorBody(
                "Time in Service In must be ≥ Time in Service Out, and values cannot be negative."));
        }

        // A CSV-prefilled or manually-typed entry -- decides whether
        // HoursService.recomputeChain treats this row's Out/In as a fixed
        // anchor ("manual") or something it can rewrite to fit
        // chronologically ("csv"). AeroAPI rows are never created here --
        // see FlightSuggestionService.accept().
        String source = newLog.getSource();
        if (source == null || source.isBlank()) source = "manual";
        newLog.setSource(source);

        // Only a manually-typed reading gets sanity-checked against nearby
        // flights -- see HoursService.checkAccuracy. A CSV/AeroAPI reading
        // has no physical meter behind it, so there's nothing to be
        // "inaccurate": it just gets slotted into the chain below.
        if ("manual".equals(source) && !force) {
            List<FlightLog> existingLogs = flightLogRepository.findByUser(user);
            HoursService.AccuracyIssue blockIssue = blockTimePair
                ? hoursService.checkAccuracy(existingLogs, newLog.getBlockTimeStart(), newLog.getBlockTimeEnd(), ho, hi, true)
                : null;
            HoursService.AccuracyIssue serviceIssue = timeInServicePair
                ? hoursService.checkAccuracy(existingLogs, newLog.getTimeInServiceStart(), newLog.getTimeInServiceEnd(), to, ti, false)
                : null;
            if (blockIssue != null || serviceIssue != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(inaccuracyBody(blockIssue, serviceIssue));
            }
        }

        // Force user ownership server-side regardless of what the client sent.
        newLog.setUser(user);
        FlightLog savedLog = flightLogRepository.save(newLog);

        double newBlockTime = hoursService.recomputeChain(user, /*useBlockTime=*/true);
        double newTimeInService  = hoursService.recomputeChain(user, /*useBlockTime=*/false);
        user.setBlockTimeHours(newBlockTime);
        user.setTimeInServiceHours(newTimeInService);
        java.time.Instant flightNow = java.time.Instant.now();
        user.setBlockTimeUpdatedAt(flightNow);
        user.setBlockTimeUpdatedSource("flightlog");
        user.setTimeInServiceUpdatedAt(flightNow);
        user.setTimeInServiceUpdatedSource("flightlog");
        userRepository.save(user);

        Map<String, Object> response = new HashMap<>();
        response.put("id", savedLog.getId());
        response.put("fromAirport", savedLog.getFromAirport());
        response.put("toAirport", savedLog.getToAirport());
        response.put("blockTimeIn", savedLog.getBlockTimeIn());
        response.put("blockTimeOut", savedLog.getBlockTimeOut());
        response.put("blockTimeStart", savedLog.getBlockTimeStart() != null ? savedLog.getBlockTimeStart().toString() : null);
        response.put("blockTimeEnd", savedLog.getBlockTimeEnd() != null ? savedLog.getBlockTimeEnd().toString() : null);
        response.put("timeInServiceIn", savedLog.getTimeInServiceIn());
        response.put("timeInServiceOut", savedLog.getTimeInServiceOut());
        response.put("timeInServiceStart", savedLog.getTimeInServiceStart() != null ? savedLog.getTimeInServiceStart().toString() : null);
        response.put("timeInServiceEnd", savedLog.getTimeInServiceEnd() != null ? savedLog.getTimeInServiceEnd().toString() : null);
        response.put("newBlockTime", newBlockTime);
        response.put("newTimeInService", newTimeInService);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value="/logbook/upload-csv", consumes="multipart/form-data")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> uploadCsv(@RequestParam("csvfile") MultipartFile file, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }

        log.info("Received file: name={}, size={} bytes", file.getOriginalFilename(), file.getSize());

        return parseGarminCsv(file, user);
    }

    public static ResponseEntity<Map<String,Object>> parseGarminCsv(MultipartFile file, User user) {

        // Block time: first → last row where oil pressure > 15 psi.
        // Mirrors the physical BlockTime meter on the Cirrus, which is oil-pressure activated.
        java.time.Instant blockStart = null;
        java.time.Instant blockEnd   = null;

        // Flight (airborne) time: GndSpd > 35 kt sustained for 3+ consecutive seconds.
        // Falls back to IAS when GndSpd is empty (no GPS fix yet).
        java.time.Instant airborneStart     = null;
        java.time.Instant airborneEnd       = null;
        int              airborneConsec    = 0;
        java.time.Instant airborneCandidate = null;

        try(Scanner scanner = new Scanner(file.getInputStream())) {
            int lineNumber = 0;
            HashMap<String, Integer> headerIndexMap = new HashMap<>();

            while(scanner.hasNextLine()) {
                String line = scanner.nextLine();
                lineNumber++;

                if(lineNumber == 3) {
                    String[] headers = line.split(",");
                    for(int i = 0; i < headers.length; i++) {
                        headerIndexMap.put(headers[i].trim(), i);
                    }

                    Set<String> expected = java.util.Set.of(
                        "Lcl Date", "Lcl Time", "UTCOfst", "AtvWpt", "Latitude", "Longitude",
                        "AltInd", "BaroA", "AltMSL", "OAT", "IAS", "GndSpd", "VSpd", "Pitch",
                        "Roll", "LatAc", "NormAc", "HDG", "TRK", "volt1", "volt2", "amp1",
                        "FQtyL", "FQtyR", "E1 FFlow", "E1 OilT", "E1 OilP", "E1 MAP", "E1 RPM",
                        "E1 %Pwr", "E1 CHT1", "E1 CHT2", "E1 CHT3", "E1 CHT4", "E1 CHT5", "E1 CHT6",
                        "E1 EGT1", "E1 EGT2", "E1 EGT3", "E1 EGT4", "E1 EGT5", "E1 EGT6",
                        "E1 TIT1", "E1 TIT2", "E1 Torq", "E1 NG", "E1 ITT", "E2 FFlow", "E2 MAP",
                        "E2 RPM", "E2 Torq", "E2 NG", "E2 ITT", "AltGPS", "TAS", "HSIS", "CRS",
                        "NAV1", "NAV2", "COM1", "COM2", "HCDI", "VCDI", "WndSpd", "WndDr",
                        "WptDst", "WptBrg", "MagVar", "AfcsOn", "RollM", "PitchM", "RollC",
                        "PichC", "VSpdG", "GPSfix", "HAL", "VAL", "HPLwas", "HPLfd", "VPLwas"
                    );
                    if(!headerIndexMap.keySet().containsAll(expected)) {
                        return ResponseEntity.badRequest().body(Map.of("error", "Not a Garmin CSV file — headers do not match expected format"));
                    }

                    continue;
                }

                if(lineNumber <= 3) continue;

                String[] cols = line.split(",", -1);
                for(int i = 0; i < cols.length; i++) cols[i] = cols[i].trim();

                // Row timestamp = Lcl Date + Lcl Time + UTCOfst combined into
                // a real UTC instant (was just Lcl Time before -- the date
                // and offset were parsed as headers but never read, so the
                // actual date a flight happened was never saved anywhere).
                // Using the real clock instead of a bare time-of-day also
                // fixes a flight that crosses local midnight for free.
                Integer dateIdx = headerIndexMap.get("Lcl Date");
                Integer timeIdx = headerIndexMap.get("Lcl Time");
                Integer ofstIdx = headerIndexMap.get("UTCOfst");
                if (dateIdx == null || timeIdx == null || ofstIdx == null
                        || cols.length <= dateIdx || cols.length <= timeIdx || cols.length <= ofstIdx
                        || cols[dateIdx].isEmpty() || cols[timeIdx].isEmpty() || cols[ofstIdx].isEmpty()) {
                    continue;
                }

                java.time.Instant rowTime;
                try {
                    rowTime = java.time.OffsetDateTime.parse(cols[dateIdx] + "T" + cols[timeIdx] + cols[ofstIdx]).toInstant();
                } catch (Exception e) { continue; }

                // ── Block time via oil pressure ──────────────────────────────────
                Integer oilPIdx = headerIndexMap.get("E1 OilP");
                if(oilPIdx != null && cols.length > oilPIdx && !cols[oilPIdx].isEmpty()) {
                    try {
                        if(Double.parseDouble(cols[oilPIdx]) > 15.0) {
                            if(blockStart == null) blockStart = rowTime;
                            blockEnd = rowTime;
                        }
                    } catch(NumberFormatException ignored) {}
                }

                // ── Airborne time via groundspeed (IAS fallback) ─────────────────
                double speed = Double.NaN;
                Integer gndSpdIdx = headerIndexMap.get("GndSpd");
                if(gndSpdIdx != null && cols.length > gndSpdIdx && !cols[gndSpdIdx].isEmpty()) {
                    try { speed = Double.parseDouble(cols[gndSpdIdx]); } catch(NumberFormatException ignored) {}
                }
                if(Double.isNaN(speed)) {
                    Integer iasIdx = headerIndexMap.get("IAS");
                    if(iasIdx != null && cols.length > iasIdx && !cols[iasIdx].isEmpty()) {
                        try { speed = Double.parseDouble(cols[iasIdx]); } catch(NumberFormatException ignored) {}
                    }
                }

                if(!Double.isNaN(speed) && speed > 35.0) {
                    airborneConsec++;
                    if(airborneConsec == 1) airborneCandidate = rowTime;
                    if(airborneConsec >= 3 && airborneStart == null) airborneStart = airborneCandidate;
                    if(airborneStart != null) airborneEnd = rowTime;
                } else {
                    airborneConsec    = 0;
                    airborneCandidate = null;
                }
            }

            if(blockStart == null && airborneStart == null) {
                return ResponseEntity.badRequest().body(
                    csvValues("Could not detect airtime or block time", blockStart, blockEnd, airborneStart, airborneEnd));
            }
            
            if(blockStart == null || blockEnd == null) {
                return ResponseEntity.badRequest().body(
                    csvValues("Could not detect engine run — Oil pressure does not go up by 15psi", blockStart, blockEnd, airborneStart, airborneEnd));
            }
            

            /* 
            if(airborneStart == null || airborneEnd == null) {
                return ResponseEntity.badRequest().body(
                    csvValues("Could not detect air time — Speed does not go above 35kts", blockStart, blockEnd, airborneStart, airborneEnd));
            }

            */

            //Calculate Block duration
            //Create Block String H: M: S: 
            Duration blockDuration = Duration.between(blockStart, blockEnd);
            String blockStr = "H:" + blockDuration.toHoursPart() + " M:" + blockDuration.toMinutesPart() + " S:" + blockDuration.toSecondsPart();


            String airStr = "N/A";
            Duration airDuration = null;
            if(airborneStart != null && airborneEnd != null) {
                airDuration = Duration.between(airborneStart, airborneEnd);
                airStr = "H:" + airDuration.toHoursPart() + " M:" + airDuration.toMinutesPart() + " S:" + airDuration.toSecondsPart();
            }

            double blockTimeOut = user.getBlockTimeHours();
            double blockTimeIn  = Math.round((blockTimeOut + blockDuration.toSeconds() / 3600.0) * 100.0) / 100.0;
            double timeInServiceOut  = user.getTimeInServiceHours();
            Double timeInServiceIn   = airDuration != null
                ? Math.round((timeInServiceOut + airDuration.toSeconds() / 3600.0) * 100.0) / 100.0
                : null;

            Map<String, Object> result = new HashMap<>();
            result.put("message",        "CSV parse completed");
            result.put("flightDuration", blockStr);
            result.put("airDuration",    airStr);
            result.put("blockTimeOut",       blockTimeOut);
            result.put("blockTimeIn",        blockTimeIn);
            result.put("blockTimeStart",     blockStart.toString());
            result.put("blockTimeEnd",       blockEnd.toString());
            result.put("timeInServiceOut",        timeInServiceIn != null ? timeInServiceOut : null);
            result.put("timeInServiceIn",         timeInServiceIn);
            result.put("timeInServiceStart",      airborneStart != null ? airborneStart.toString() : null);
            result.put("timeInServiceEnd",        airborneEnd != null ? airborneEnd.toString() : null);

            if(airDuration == null) {
                result.put("warning", "Air time not detected — Time in Service fields were not populated");
            }

            return ResponseEntity.ok(result);

        } catch (IOException e) {
            log.error("Failed to read CSV file: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read the uploaded file"));
        }

    }

    private static Map<String, Object> csvValues(String error, java.time.Instant blockStart, java.time.Instant blockEnd, java.time.Instant airborneStart, java.time.Instant airborneEnd) {
        Map<String, Object> map = new HashMap<>();
        map.put("error", error);
        map.put("blockStart", blockStart);
        map.put("blockEnd", blockEnd);
        map.put("airborneStart", airborneStart);
        map.put("airborneEnd", airborneEnd);

        return map;
    }

    private static boolean tooLong(String value) {
        return value != null && value.length() > Validation.MAX_TEXT;
    }

    private static Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "error");
        body.put("message", message);
        return body;
    }

    // A manually-typed reading that doesn't line up with the user's other
    // flight logs -- see HoursService.checkAccuracy. Not a hard error: the
    // frontend shows this as a confirm ("Add it anyway?") and resubmits
    // with force=true if the user says yes.
    private static Map<String, Object> inaccuracyBody(HoursService.AccuracyIssue blockIssue, HoursService.AccuracyIssue serviceIssue) {
        List<String> sentences = new ArrayList<>();
        if (blockIssue != null) sentences.add(describeAccuracyIssue("Block Time", blockIssue));
        if (serviceIssue != null) sentences.add(describeAccuracyIssue("Time in Service", serviceIssue));

        Map<String, Object> body = new HashMap<>();
        body.put("inaccurate", true);
        body.put("message", "This entry doesn't match your other flight logs. "
            + String.join(" ", sentences) + " Add it anyway?");
        return body;
    }

    private static String describeAccuracyIssue(String label, HoursService.AccuracyIssue issue) {
        Double floor = issue.expectedFloor();
        Double ceiling = issue.expectedCeiling();
        if (floor != null && ceiling != null) {
            return label + ": based on nearby flights, Out should be at least " + round2(floor)
                + " and In should be no more than " + round2(ceiling)
                + " (you entered Out: " + round2(issue.enteredOut()) + ", In: " + round2(issue.enteredIn()) + ").";
        } else if (floor != null) {
            return label + ": based on nearby flights, Out should be at least " + round2(floor)
                + " (you entered " + round2(issue.enteredOut()) + ").";
        } else {
            return label + ": based on nearby flights, In should be no more than " + round2(ceiling)
                + " (you entered " + round2(issue.enteredIn()) + ").";
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // POST: complete maintenance on a row.
    // Server authoritatively picks "today" and "current time-in-service hours" so the
    // result is the same regardless of which tab the user clicks from.
    @PostMapping("/completeMaintenance/{id}")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> completeMaintenance(
            @PathVariable Long id, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody("User not authenticated"));
        }
        ServiceTimeline timeline = serviceTimelineRepository.findById(id).orElse(null);
        if (timeline == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("Row not found"));
        }
        if (timeline.getUser() == null || timeline.getUser().getId() != user.getId()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("You do not own this row"));
        }

        Integer calVal = timeline.getCycleCalendarValue();
        String  calUnit = Parsing.normalizeCalendarUnit(timeline.getCycleCalendarUnit());
        Double  hrsCycle = timeline.getCycleHours();
        boolean hasCalendar = (calVal != null && calVal > 0 && calUnit != null);
        boolean hasHours    = (hrsCycle != null && hrsCycle > 0);
        if (!hasCalendar && !hasHours) {
            return ResponseEntity.badRequest().body(errorBody(
                "Set a calendar cycle or an hours cycle before marking maintenance complete."));
        }

        java.time.LocalDate today = java.time.LocalDate.now();
        double currentTimeInService = user.getTimeInServiceHours();

        java.time.LocalDate dueDateLd = null;
        if (hasCalendar) {
            switch (calUnit) {
                case "DAYS":   dueDateLd = today.plusDays(calVal); break;
                case "MONTHS": dueDateLd = today.plusMonths(calVal); break;
                case "YEARS":  dueDateLd = today.plusYears(calVal); break;
                default:
                    return ResponseEntity.badRequest().body(errorBody("Invalid calendar unit: " + calUnit));
            }
        }
        Double dueHours = hasHours ? (currentTimeInService + hrsCycle) : null;

        String timeLeftStr = computeTimeLeftString(dueDateLd, dueHours, today, currentTimeInService);
        timeline.setTimeLeft(timeLeftStr);

        // Only update fields that belong to the active cycle type — leave the other type's
        // fields untouched so they stay visible in the UI after repaint.
        if (hasCalendar) {
            timeline.setLastDoneDate(today.toString());
            timeline.setDueDateDate(dueDateLd != null ? dueDateLd.toString() : null);
        }
        if (hasHours) {
            timeline.setLastDoneHours(Formatting.formatHours(currentTimeInService));
            timeline.setDueDateHours(dueHours != null ? Formatting.formatHours(dueHours) : null);
        }
        serviceTimelineRepository.save(timeline);

        // Build response strings from the actual saved state so repaintDateHoursCell
        // receives both the date and hours parts.
        String lastDoneStr = java.util.stream.Stream.of(timeline.getLastDoneDate(), timeline.getLastDoneHours())
            .filter(s -> s != null && !s.isEmpty()).collect(java.util.stream.Collectors.joining(" "));
        String dueDateStr = java.util.stream.Stream.of(timeline.getDueDateDate(), timeline.getDueDateHours())
            .filter(s -> s != null && !s.isEmpty()).collect(java.util.stream.Collectors.joining(" "));

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "ok");
        resp.put("lastDone", lastDoneStr);
        resp.put("dueDate", dueDateStr);
        resp.put("timeLeft", timeLeftStr);
        return ResponseEntity.ok(resp);
    }

    // Stored format matches the existing "YYYY-MM-DD <hours>" convention that
    // the rest of the app already parses (see calculateTimeLeft in dashboard.js).

    private static String computeTimeLeftString(java.time.LocalDate dueDate, Double dueHours,
                                                java.time.LocalDate today, double currentTimeInService) {
        StringBuilder sb = new StringBuilder();
        if (dueDate != null) {
            long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, dueDate);
            sb.append(daysLeft < 0
                ? Math.abs(daysLeft) + " days overdue"
                : daysLeft + " days left");
        }
        if (dueHours != null) {
            double hoursLeft = Math.round((dueHours - currentTimeInService) * 10.0) / 10.0;
            String h = hoursLeft < 0
                ? Math.abs(hoursLeft) + " hours overdue"
                : hoursLeft + " hours left";
            if (sb.length() > 0) sb.append('\n');
            sb.append(h);
        }
        return sb.length() == 0 ? "N/A" : sb.toString();
    }

    // POST to add a custom description option directly (before any row uses it)
    @PostMapping(value = "/addDescriptionOption", consumes = "application/json")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> addDescriptionOption(
            @RequestBody Map<String, String> body, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody("User not authenticated"));
        }

        try {
            DescriptionOption saved = descriptionOptionService.addOption(user, body == null ? null : body.get("option"));
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "ok");
            resp.put("id", saved.getId());
            resp.put("option", saved.getOption());
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        }
    
    }

    // DELETE flight log
    @DeleteMapping("/deleteflightlog/{id}")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteFlightLog(@PathVariable Long id, Authentication authentication) {
        try {
            User user = userRepository.findByUsername(authentication.getName());
            FlightLog log = flightLogRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Flight log not found"));
            if (!log.getUser().equals(user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            // This log came from an accepted AeroAPI suggestion -- deleting it
            // should give the flight back, not lose it forever. Otherwise the
            // suggestion stays "accepted" with nothing pointing at it, and
            // its fa_flight_id permanently blocks re-suggesting that flight.
            if (log.getFaFlightId() != null) {
                flightSuggestionRepository.findByUserAndFaFlightId(user, log.getFaFlightId())
                    .ifPresent(suggestion -> {
                        suggestion.setStatus("pending");
                        flightSuggestionRepository.save(suggestion);
                    });
            }

            flightLogRepository.delete(log);

            double newBlockTime = hoursService.recomputeChain(user, /*useBlockTime=*/true);
            double newTimeInService  = hoursService.recomputeChain(user, /*useBlockTime=*/false);
            user.setBlockTimeHours(newBlockTime);
            user.setTimeInServiceHours(newTimeInService);
            java.time.Instant flightNow = java.time.Instant.now();
            user.setBlockTimeUpdatedAt(flightNow);
            user.setBlockTimeUpdatedSource("flightlog");
            user.setTimeInServiceUpdatedAt(flightNow);
            user.setTimeInServiceUpdatedSource("flightlog");
            userRepository.save(user);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("newBlockTime", newBlockTime);
            response.put("newTimeInService", newTimeInService);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    // Superseded by HoursService.recomputeChain, which does the same
    // baseline-floor job but also keeps every individual log's Out/In
    // accurate by real flight time instead of just tracking the max.

    // Drops blank entries and any custom option that duplicates a built-in
    // (case-insensitive). Self-heals legacy bad rows on first dashboard load
    // after this fix ships.

    
}