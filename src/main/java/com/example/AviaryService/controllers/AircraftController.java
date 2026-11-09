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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.aircraft.AircraftService;
import com.example.AviaryService.services.aircraft.AircraftValidationException;
import com.example.AviaryService.services.aircraft.FleetSummaryService;

// The owner's fleet: list it (for the picker), add an aircraft, remove one.
// See specs/003-multi-aircraft-fleet/contracts/http-endpoints.md.
@RestController
@RequestMapping("/aircraft")
public class AircraftController {

    private final AircraftService aircraftService;
    private final FleetSummaryService fleetSummaryService;
    private final UserRepository userRepository;

    public AircraftController(AircraftService aircraftService, FleetSummaryService fleetSummaryService,
            UserRepository userRepository) {
        this.aircraftService = aircraftService;
        this.fleetSummaryService = fleetSummaryService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String today, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        Aircraft current = user.getLastViewedAircraft();
        return fleetView(aircraftService.listFor(user), fleetSummaryService.summarize(user, DashboardDates.asOf(today)),
            current == null ? null : current.getId());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        try {
            Aircraft created = aircraftService.create(user,
                body.get("tailNumber"), body.get("makeModel"), body.get("ownerName"), body.get("makeModelSN"));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", created.getId());
            resp.put("redirect", "/dashboard?aircraft=" + created.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);
        } catch (AircraftValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "field", e.getField(), "message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        try {
            Aircraft next = aircraftService.remove(user, id, body == null ? null : body.get("confirm"));
            return ResponseEntity.ok(Map.of("redirect", "/dashboard?aircraft=" + next.getId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // The picker's rows. Shared with the dashboard, which renders the same
    // list into the page on load.
    static List<Map<String, Object>> fleetView(List<Aircraft> fleet, Map<Long, FleetSummaryService.Counts> counts,
            Long selectedId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Aircraft a : fleet) {
            FleetSummaryService.Counts c = counts.getOrDefault(a.getId(), FleetSummaryService.Counts.NONE);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("displayName", a.getDisplayName());
            m.put("tailNumber", a.getTailNumber());
            m.put("makeModel", a.getMakeModel());
            m.put("overdue", c.overdue());
            m.put("dueSoon", c.dueSoon());
            m.put("selected", a.getId().equals(selectedId));
            out.add(m);
        }
        return out;
    }
}
