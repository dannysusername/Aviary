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
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.AeroApiClient;
import com.example.AviaryService.services.DescriptionOptionService;
import com.example.AviaryService.services.FlightLogService;
import com.example.AviaryService.services.FlightSuggestionService;
import com.example.AviaryService.services.FlightSyncService;
import com.example.AviaryService.services.GarminCsvService;
import com.example.AviaryService.services.HoursService;
import com.example.AviaryService.services.OutboundEmailLimiter;
import com.example.AviaryService.services.PdfExportService;
import com.example.AviaryService.services.SendGridEmailService;
import com.example.AviaryService.services.SubscriptionService;
import com.example.AviaryService.services.TimelineService;
import com.example.AviaryService.services.UserService;
import com.example.AviaryService.util.Validation;

import org.apache.catalina.connector.Response;
//import org.checkerframework.checker.units.qual.Speed;
import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

//import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The main app. Handles almost everything a logged-in owner does:
// register and login pages, the dashboard, aircraft hours, the maintenance
// timeline, flight logs (including Garmin CSV upload), suggested flights,
// flight-tracking subscriptions, and the PDF report.
//
// Uses @Controller (not @RestController) so it can return HTML pages like
// /login and /dashboard. Methods marked @ResponseBody send back JSON instead.
@Controller
public class UserController {
    private final UserRepository userRepository;
    private final ServiceTimelineRepository serviceTimelineRepository;
    private final PasswordEncoder passwordEncoder;
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
    private final FlightLogService flightLogService;
    private final GarminCsvService garminCsvService;

    public UserController(UserRepository userRepository, ServiceTimelineRepository serviceTimelineRepository,
            PasswordEncoder passwordEncoder, DescriptionOptionRepository descriptionOptionRepository,
            SubscriptionService subscriptionService, UserService userService,
            TimelineService timelineService, DescriptionOptionService descriptionOptionService, HoursService hoursService,
            FlightSuggestionService flightSuggestionService,
            com.example.AviaryService.repositories.FlightSuggestionRepository flightSuggestionRepository,
            com.example.AviaryService.repositories.SubscriptionRepository subscriptionRepository,
            AeroApiClient aeroApiClient, FlightSyncService flightSyncService, PdfExportService pdfExportService,
            SendGridEmailService sendGridEmailService, OutboundEmailLimiter emailLimiter,
            FlightLogService flightLogService, GarminCsvService garminCsvService) {

        this.userRepository = userRepository;
        this.serviceTimelineRepository = serviceTimelineRepository;
        this.passwordEncoder = passwordEncoder;

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
        this.flightLogService = flightLogService;
        this.garminCsvService = garminCsvService;
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
        model.addAttribute("flightlogs", flightLogService.listFor(user));
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
    // `today` is the browser's local date, so Time Left in the PDF matches the
    // dashboard even when the server's UTC date differs. Only trusted within a
    // day of the server's date; anything else falls back to the server's.
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> downloadPdf(@RequestParam(required = false) String today,
            Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        java.time.LocalDate serverToday = java.time.LocalDate.now();
        java.time.LocalDate asOf = serverToday;
        try {
            java.time.LocalDate clientToday = today == null ? null : java.time.LocalDate.parse(today);
            if (clientToday != null && Math.abs(java.time.temporal.ChronoUnit.DAYS.between(serverToday, clientToday)) <= 1) {
                asOf = clientToday;
            }
        } catch (java.time.format.DateTimeParseException ignored) {
            // bad value -> server date
        }
        byte[] pdf = pdfExportService.generateDashboardPdf(user, asOf);

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
        headers.setContentDisposition(org.springframework.http.ContentDisposition.attachment()
            .filename("Aviary_Dashboard_" + asOf + ".pdf")
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
    public ResponseEntity<Map<String, String>> updateTimeline(
            @PathVariable Long id,
            @RequestBody TimelineUpdateDTO updateDTO,
            Authentication authentication) {
        try {
            User user = userRepository.findByUsername(authentication.getName());
            timelineService.updateTimeline(user, id, updateDTO);
            return ResponseEntity.ok(Map.of("status", "success"));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("status", "error", "message", e.getMessage()));
        } catch (IllegalArgumentException | java.util.NoSuchElementException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to update timeline {}", id, e);
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Could not save that change."));
        }
    }


    @DeleteMapping("/delete/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteTimeline(@PathVariable Long id, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        try {
            timelineService.deleteTimeline(user, id);
            return ResponseEntity.ok().build();
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
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
        timelineService.reorder(user, ids);
    }

    // NEW: GET flight logs (for AJAX if needed)
    @GetMapping("/flightlogs")
    @ResponseBody
    public List<FlightLog> getFlightLogs(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        return flightLogService.listFor(user);
    }

    // POST to add flight log
    @PostMapping(value = "/addflightlog", consumes = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addFlightLog(
            @RequestBody FlightLog newLog,
            @RequestParam(required = false, defaultValue = "false") boolean force,
            Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("User not authenticated"));
        }

        FlightLogService.AddedFlightLog added;
        try {
            added = flightLogService.add(user, newLog, force);
        } catch (FlightLogService.InaccurateEntryException e) {
            // Not a hard error: the frontend shows this as a confirm ("Add it
            // anyway?") and resubmits with force=true if the user says yes.
            Map<String, Object> body = new HashMap<>();
            body.put("inaccurate", true);
            body.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        }

        FlightLog savedLog = added.log();
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
        response.put("newBlockTime", added.totals().blockTime());
        response.put("newTimeInService", added.totals().timeInService());
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

        try (InputStream in = file.getInputStream()) {
            GarminCsvService.CsvParseResult result = garminCsvService.parse(in, user);
            return result.ok()
                ? ResponseEntity.ok(result.body())
                : ResponseEntity.badRequest().body(result.body());
        } catch (IOException e) {
            log.error("Failed to read CSV file: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read the uploaded file"));
        }
    }

    private static Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "error");
        body.put("message", message);
        return body;
    }

    // POST: complete maintenance on a row. See TimelineService.completeMaintenance.
    @PostMapping("/completeMaintenance/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> completeMaintenance(
            @PathVariable Long id, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody("User not authenticated"));
        }
        TimelineService.CompletedMaintenance done;
        try {
            done = timelineService.completeMaintenance(user, id);
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("Row not found"));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorBody("You do not own this row"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "ok");
        resp.put("lastDone", done.lastDone());
        resp.put("dueDate", done.dueDate());
        resp.put("timeLeft", done.timeLeft());
        return ResponseEntity.ok(resp);
    }

    // POST to add a custom description option directly (before any row uses it)
    @PostMapping(value = "/addDescriptionOption", consumes = "application/json")
    @ResponseBody
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
    public ResponseEntity<Map<String, Object>> deleteFlightLog(@PathVariable Long id, Authentication authentication) {
        try {
            User user = userRepository.findByUsername(authentication.getName());
            FlightLogService.HourTotals totals = flightLogService.delete(user, id);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("newBlockTime", totals.blockTime());
            response.put("newTimeInService", totals.timeInService());
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
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