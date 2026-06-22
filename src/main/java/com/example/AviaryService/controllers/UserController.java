package com.example.AviaryService.controllers;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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

//import org.checkerframework.checker.units.qual.Speed;
import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
import jakarta.transaction.Transactional;

//import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

@Controller
public class UserController {
    @Autowired private UserRepository userRepository;
    @Autowired private ServiceTimelineRepository serviceTimelineRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private DescriptionOptionRepository descriptionOptionRepository;
    @Autowired private FlightLogRepository flightLogRepository;

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    @GetMapping("/register")
    public String showRegisterForm() {
        return "register";
    }

    @PostMapping("/register")
    public String registerUser(@RequestParam String username, @RequestParam String password, Model model) {
        if (userRepository.findByUsername(username) != null) { //If username exists
            model.addAttribute("error", "Username already exists");
            return "register";
        }
        User user = new User(username, passwordEncoder.encode(password));
        userRepository.save(user);
        return "redirect:/login";
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
            User user = userRepository.findByUsername(authentication.getName());
            if (user == null) {
                throw new IllegalArgumentException("User not found");
            }

            // Update fields if provided in the request
            if (data.containsKey("makeModel")) user.setMakeModel(data.get("makeModel"));
            if (data.containsKey("tailNumber")) user.setTailNumber(data.get("tailNumber"));
            if (data.containsKey("ownerName")) user.setOwnerName(data.get("ownerName"));
            if (data.containsKey("makeModelSN")) user.setMakeModelSN(data.get("makeModelSN"));

            userRepository.save(user);

            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @GetMapping("/dashboard")
    @Transactional
    public String showDashboard(Model model, Authentication authentication) {
        String username = authentication.getName();
        User user = userRepository.findByUsername(username);
        model.addAttribute("username", username);
        model.addAttribute("timelines", serviceTimelineRepository.findByUserOrderByTimelineOrderAsc(user));
        model.addAttribute("descriptionOptions", cleanupAndLoadDescriptionOptions(user));
        model.addAttribute("hobbsHours", user.getHobbsHours());
        model.addAttribute("tachHours", user.getTachHours());

        model.addAttribute("makeModel", user.getMakeModel());
        model.addAttribute("tailNumber", user.getTailNumber());
        model.addAttribute("ownerName", user.getOwnerName());
        model.addAttribute("makeModelSN", user.getMakeModelSN());
        model.addAttribute("flightlogs", flightLogRepository.findByUser(user));
        model.addAttribute("hobbsUpdatedAt", user.getHobbsUpdatedAt() != null ? user.getHobbsUpdatedAt().toString() : null);
        model.addAttribute("tachUpdatedAt", user.getTachUpdatedAt() != null ? user.getTachUpdatedAt().toString() : null);
        model.addAttribute("hobbsUpdatedSource", user.getHobbsUpdatedSource());
        model.addAttribute("tachUpdatedSource", user.getTachUpdatedSource());

        return "dashboard";
    }

    @PostMapping("/dashboard")
    public ResponseEntity<?> addTimeline(
            @RequestBody Map<String, String> data,
            Authentication authentication) {
        String item = data.get("item");
        if (item == null || item.isEmpty()) {
            return ResponseEntity.badRequest().body("Item is required");
        }

        String isTitle = data.getOrDefault("isTitle", "false");
        String description = data.get("description");
        String cycle = data.get("cycle");
        String lastDone = data.get("lastDone");
        String dueDate = data.get("dueDate");
        String timeLeft = data.get("timeLeft");
        String ajax = data.getOrDefault("ajax", "false");

        User user = userRepository.findByUsername(authentication.getName());
        ServiceTimeline timeline = new ServiceTimeline();
        timeline.setItem(item);
        boolean isTitleRow = "true".equals(isTitle);
        timeline.setIsTitle(isTitleRow);
        if (!isTitleRow) {
            if (description != null) {
                timeline.setDescription(description);
                saveCustomDescriptionOption(description, user);
            }
            timeline.setCycleCalendarValue(parseIntOrNull(data.get("cycleCalendarValue")));
            timeline.setCycleCalendarUnit(normalizeCalendarUnit(data.get("cycleCalendarUnit")));
            timeline.setCycleHours(parseDoubleOrNull(data.get("cycleHours")));
            timeline.setTimeLeft(timeLeft);
        }
        timeline.setUser(user);

        Integer maxOrder = serviceTimelineRepository.findMaxTimelineOrderByUser(user);
        int newOrder = (maxOrder != null) ? maxOrder + 1 : 0;
        timeline.setTimelineOrder(newOrder);

        serviceTimelineRepository.save(timeline);

        if ("true".equals(ajax)) {
            Map<String, Object> response = new HashMap<>();
            response.put("id", timeline.getId());
            response.put("item", timeline.getItem());
            response.put("description", timeline.getDescription());
            response.put("cycleCalendarValue", timeline.getCycleCalendarValue());
            response.put("cycleCalendarUnit", timeline.getCycleCalendarUnit());
            response.put("cycleHours", timeline.getCycleHours());
            response.put("timeLeft", timeline.getTimeLeft());
            response.put("isTitle", timeline.getIsTitle());
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/dashboard")).build();
        }
    }
    

    @PostMapping("/updateHours")
    @ResponseBody
    public ResponseEntity<Map<String, String>> updateHours(
            @RequestParam(required = false) Double hobbsTimeToAdd,
            @RequestParam(required = false) Double tachTimeToAdd,
            @RequestParam(required = false) Double newHobbsTime,
            @RequestParam(required = false) Double newTachTime,
            Authentication authentication) {
        try {
            User user = userRepository.findByUsername(authentication.getName());
            if (user == null) {
                throw new IllegalArgumentException("User not found");
            }

            boolean updated = false;

            if (newHobbsTime != null) {
                user.setHobbsHours(newHobbsTime);
                // Manual edit also sets the floor — logs can raise this, never lower it.
                user.setHobbsManualBaseline(newHobbsTime);
                System.out.println("Setting Hobbs time to: " + newHobbsTime);
                updated = true;
            } else if (hobbsTimeToAdd != null) {
                double currentHobbs = user.getHobbsHours();
                double newHobbs = currentHobbs + hobbsTimeToAdd;
                user.setHobbsHours(newHobbs);
                user.setHobbsManualBaseline(newHobbs);
                System.out.println("Adding " + hobbsTimeToAdd + " to current Hobbs: " + currentHobbs);
                updated = true;
            }

            double finalHobbs = user.getHobbsHours();
            System.out.println("Current hobbs: " + finalHobbs);

            if (newTachTime != null) {
                user.setTachHours(newTachTime);
                user.setTachManualBaseline(newTachTime);
                System.out.println("Setting Tach time to: " + newTachTime);
                updated = true;
            } else if (tachTimeToAdd != null) {
                double currentTach = user.getTachHours();
                double newTach = currentTach + tachTimeToAdd;
                user.setTachHours(newTach);
                user.setTachManualBaseline(newTach);
                System.out.println("Adding " + tachTimeToAdd + " to current Tach: " + currentTach);
                updated = true;
            }

            double finalTach = user.getTachHours();
            System.out.println("Current Tach: " + finalTach);

            if (!updated) {
                throw new IllegalArgumentException("At least one update parameter must be provided");
            }

            java.time.Instant now = java.time.Instant.now();
            if (newHobbsTime != null || hobbsTimeToAdd != null) {
                user.setHobbsUpdatedAt(now);
                user.setHobbsUpdatedSource("manual");
            }
            if (newTachTime != null || tachTimeToAdd != null) {
                user.setTachUpdatedAt(now);
                user.setTachUpdatedSource("manual");
            }

            userRepository.save(user);
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("newHobbs", String.valueOf(user.getHobbsHours()));
            response.put("newTach", String.valueOf(user.getTachHours()));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", e.getMessage());
            System.out.println("Error updating hours: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
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
                saveCustomDescriptionOption(description, userRepository.findByUsername(authentication.getName()));
            }
            // Structured cycle fields. The client sends them on every save so
            // null actually means "clear it" here — distinguish empty/null on
            // the client if you ever want partial updates.
            timeline.setCycleCalendarValue(updateDTO.getCycleCalendarValue());
            timeline.setCycleCalendarUnit(normalizeCalendarUnit(updateDTO.getCycleCalendarUnit()));
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
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }


    @DeleteMapping("/delete/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteTimeline(@PathVariable Long id, Authentication authentication) {
        ServiceTimeline timeline = serviceTimelineRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid timeline ID: " + id));
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null || timeline.getUser() == null || timeline.getUser().getId() != user.getId()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        serviceTimelineRepository.delete(timeline);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/deleteOption/{id}")
    @ResponseBody
    @Transactional
    public ResponseEntity<String> deleteOption(@PathVariable Long id, Authentication authentication) {
        try {
            log.info("Attempting to delete option with ID: {}", id);
            User user = userRepository.findByUsername(authentication.getName());
            if (user == null) {
                log.error("User not found for username: {}", authentication.getName());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
            }
            log.info("Authenticated user: {}", user.getUsername());
            DescriptionOption option = descriptionOptionRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Option not found"));
            log.info("Found option: {} for user: {}", option.getOption(), option.getUser().getUsername());
            if (!option.getUser().equals(user)) {
                log.warn("User {} does not own option {}", user.getUsername(), option.getOption());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You do not own this option");
            }
            descriptionOptionRepository.delete(option);
            log.info("Option {} deleted successfully", option.getOption());
            return ResponseEntity.ok("Option deleted");
        } catch (Exception e) {
            log.error("Error deleting option with ID: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error deleting option: " + e.getMessage());
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
        return flightLogRepository.findByUser(user);
    }

    // POST to add flight log
    @PostMapping(value = "/addflightlog", consumes = "application/json")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> addFlightLog(@RequestBody FlightLog newLog, Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("User not authenticated"));
        }

        // ── Validation ─────────────────────────────────────────────────────────
        // Reject incomplete entries before they can corrupt displayed hours.
        // Rule: must provide at least one COMPLETE pair (hobbsOut+hobbsIn or
        // tachOut+tachIn). Partial pairs (e.g. only tachOut) are the exact case
        // that used to silently wipe the user's manual hours to zero.
        Double ho = newLog.getHobbsOut(), hi = newLog.getHobbsIn();
        Double to = newLog.getTachOut(), ti = newLog.getTachIn();
        boolean hobbsPair = (ho != null && hi != null);
        boolean tachPair  = (to != null && ti != null);
        boolean hobbsPartial = (ho == null) != (hi == null);  // exactly one set
        boolean tachPartial  = (to == null) != (ti == null);

        if (!hobbsPair && !tachPair) {
            return ResponseEntity.badRequest().body(errorBody(
                "Enter both Hobbs Out and Hobbs In, or both Tach Out and Tach In."));
        }
        if (hobbsPartial) {
            return ResponseEntity.badRequest().body(errorBody(
                "Hobbs entry is incomplete — enter both Hobbs Out and Hobbs In."));
        }
        if (tachPartial) {
            return ResponseEntity.badRequest().body(errorBody(
                "Tach entry is incomplete — enter both Tach Out and Tach In."));
        }
        if (hobbsPair && (ho < 0 || hi < 0 || hi < ho)) {
            return ResponseEntity.badRequest().body(errorBody(
                "Hobbs In must be ≥ Hobbs Out, and values cannot be negative."));
        }
        if (tachPair && (to < 0 || ti < 0 || ti < to)) {
            return ResponseEntity.badRequest().body(errorBody(
                "Tach In must be ≥ Tach Out, and values cannot be negative."));
        }

        // Force user ownership server-side regardless of what the client sent.
        newLog.setUser(user);
        FlightLog savedLog = flightLogRepository.save(newLog);

        List<FlightLog> allLogs = flightLogRepository.findByUser(user);
        double newHobbs = computeDisplayedHours(user, allLogs, /*useHobbs=*/true);
        double newTach  = computeDisplayedHours(user, allLogs, /*useHobbs=*/false);
        user.setHobbsHours(newHobbs);
        user.setTachHours(newTach);
        java.time.Instant flightNow = java.time.Instant.now();
        user.setHobbsUpdatedAt(flightNow);
        user.setHobbsUpdatedSource("flightlog");
        user.setTachUpdatedAt(flightNow);
        user.setTachUpdatedSource("flightlog");
        userRepository.save(user);

        Map<String, Object> response = new HashMap<>();
        response.put("id", savedLog.getId());
        response.put("fromAirport", savedLog.getFromAirport());
        response.put("toAirport", savedLog.getToAirport());
        response.put("hobbsIn", savedLog.getHobbsIn());
        response.put("hobbsOut", savedLog.getHobbsOut());
        response.put("tachIn", savedLog.getTachIn());
        response.put("tachOut", savedLog.getTachOut());
        response.put("newHobbs", newHobbs);
        response.put("newTach", newTach);
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
        // Mirrors the physical Hobbs meter on the Cirrus, which is oil-pressure activated.
        LocalTime blockStart = null;
        LocalTime blockEnd   = null;

        // Flight (airborne) time: GndSpd > 35 kt sustained for 3+ consecutive seconds.
        // Falls back to IAS when GndSpd is empty (no GPS fix yet).
        LocalTime airborneStart     = null;
        LocalTime airborneEnd       = null;
        int       airborneConsec    = 0;
        LocalTime airborneCandidate = null;

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

                //If Lcl Time does not exist in this row
                Integer timeIdx = headerIndexMap.get("Lcl Time");
                if(timeIdx == null || cols.length <= timeIdx || cols[timeIdx].isEmpty()) continue;

                //Puts the time into a Local time object
                LocalTime rowTime;
                try { rowTime = LocalTime.parse(cols[timeIdx]); }
                catch(Exception e) { continue; }

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

            double hobbsOut = user.getHobbsHours();
            double hobbsIn  = Math.round((hobbsOut + blockDuration.toSeconds() / 3600.0) * 100.0) / 100.0;
            double tachOut  = user.getTachHours();
            Double tachIn   = airDuration != null
                ? Math.round((tachOut + airDuration.toSeconds() / 3600.0) * 100.0) / 100.0
                : null;

            Map<String, Object> result = new HashMap<>();
            result.put("message",        "CSV parse completed");
            result.put("flightDuration", blockStr);
            result.put("airDuration",    airStr);
            result.put("hobbsOut",       hobbsOut);
            result.put("hobbsIn",        hobbsIn);
            result.put("tachOut",        tachIn != null ? tachOut : null);
            result.put("tachIn",         tachIn);

            if(airDuration == null) {
                result.put("warning", "Air time not detected — Tach fields were not populated");
            }

            return ResponseEntity.ok(result);

        } catch (IOException e) {
            log.error("Failed to read CSV file: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read the uploaded file"));
        }

    }

    private static Map<String, Object> csvValues(String error, LocalTime blockStart, LocalTime blockEnd, LocalTime airborneStart, LocalTime airborneEnd) {
        Map<String, Object> map = new HashMap<>();
        map.put("error", error);
        map.put("blockStart", blockStart);
        map.put("blockEnd", blockEnd);
        map.put("airborneStart", airborneStart);
        map.put("airborneEnd", airborneEnd);

        return map;
    }

    private static Map<String, Object> errorBody(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "error");
        body.put("message", message);
        return body;
    }

    // POST: complete maintenance on a row.
    // Server authoritatively picks "today" and "current tach hours" so the
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
        String  calUnit = normalizeCalendarUnit(timeline.getCycleCalendarUnit());
        Double  hrsCycle = timeline.getCycleHours();
        boolean hasCalendar = (calVal != null && calVal > 0 && calUnit != null);
        boolean hasHours    = (hrsCycle != null && hrsCycle > 0);
        if (!hasCalendar && !hasHours) {
            return ResponseEntity.badRequest().body(errorBody(
                "Set a calendar cycle or an hours cycle before marking maintenance complete."));
        }

        java.time.LocalDate today = java.time.LocalDate.now();
        double currentTach = user.getTachHours();

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
        Double dueHours = hasHours ? (currentTach + hrsCycle) : null;

        String timeLeftStr = computeTimeLeftString(dueDateLd, dueHours, today, currentTach);
        timeline.setTimeLeft(timeLeftStr);

        // Only update fields that belong to the active cycle type — leave the other type's
        // fields untouched so they stay visible in the UI after repaint.
        if (hasCalendar) {
            timeline.setLastDoneDate(today.toString());
            timeline.setDueDateDate(dueDateLd != null ? dueDateLd.toString() : null);
        }
        if (hasHours) {
            timeline.setLastDoneHours(formatHours(currentTach));
            timeline.setDueDateHours(dueHours != null ? formatHours(dueHours) : null);
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

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Double.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String normalizeCalendarUnit(String raw) {
        if (raw == null) return null;
        String u = raw.trim().toUpperCase();
        if (u.isEmpty()) return null;
        if (u.equals("DAYS") || u.equals("MONTHS") || u.equals("YEARS")) return u;
        return null;
    }

    // Stored format matches the existing "YYYY-MM-DD <hours>" convention that
    // the rest of the app already parses (see calculateTimeLeft in dashboard.js).
    private static String formatHours(double hours) {
        return hours == Math.floor(hours)
            ? Long.toString((long) hours)
            : Double.toString(hours);
    }

    private static String buildDateHoursString(java.time.LocalDate date, Double hours) {
        StringBuilder sb = new StringBuilder();
        if (date != null) sb.append(date.toString());
        if (hours != null) {
            if (sb.length() > 0) sb.append(' ');
            // Trim trailing zeros: 100.0 -> "100", 100.5 -> "100.5"
            sb.append(hours == Math.floor(hours)
                ? Long.toString((long) (double) hours)
                : Double.toString(hours));
        }
        return sb.toString();
    }

    private static String computeTimeLeftString(java.time.LocalDate dueDate, Double dueHours,
                                                java.time.LocalDate today, double currentTach) {
        StringBuilder sb = new StringBuilder();
        if (dueDate != null) {
            long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, dueDate);
            sb.append(daysLeft < 0
                ? Math.abs(daysLeft) + " days overdue"
                : daysLeft + " days left");
        }
        if (dueHours != null) {
            double hoursLeft = Math.round((dueHours - currentTach) * 10.0) / 10.0;
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
        String raw = body == null ? null : body.get("option");
        if (raw == null) return ResponseEntity.badRequest().body(errorBody("Missing option"));
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return ResponseEntity.badRequest().body(errorBody("Option cannot be blank"));
        if (DEFAULT_DESCRIPTION_OPTIONS.contains(trimmed.toLowerCase())) {
            return ResponseEntity.badRequest().body(errorBody("That option already exists as a default"));
        }
        DescriptionOption existing = descriptionOptionRepository.findByUser(user).stream()
            .filter(opt -> opt.getOption() != null && opt.getOption().equalsIgnoreCase(trimmed))
            .findFirst().orElse(null);
        DescriptionOption saved = (existing != null)
            ? existing
            : descriptionOptionRepository.save(new DescriptionOption(trimmed, user));
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "ok");
        resp.put("id", saved.getId());
        resp.put("option", saved.getOption());
        return ResponseEntity.ok(resp);
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
            flightLogRepository.delete(log);

            List<FlightLog> remainingLogs = flightLogRepository.findByUser(user);
            double newHobbs = computeDisplayedHours(user, remainingLogs, /*useHobbs=*/true);
            double newTach  = computeDisplayedHours(user, remainingLogs, /*useHobbs=*/false);
            user.setHobbsHours(newHobbs);
            user.setTachHours(newTach);
            java.time.Instant flightNow = java.time.Instant.now();
            user.setHobbsUpdatedAt(flightNow);
            user.setHobbsUpdatedSource("flightlog");
            user.setTachUpdatedAt(flightNow);
            user.setTachUpdatedSource("flightlog");
            userRepository.save(user);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("newHobbs", newHobbs);
            response.put("newTach", newTach);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    /**
     * Meter-snapshot recompute: the airframe meter only goes up.
     *
     * Displayed hours = max(manualBaseline, highest hobbsIn/tachIn across user's logs).
     *
     * If the baseline has never been written (legacy users), seed it from the
     * user's current hobbsHours/tachHours so log activity can never silently
     * lower the displayed value. After this call, the baseline is locked in
     * and subsequent operations behave predictably.
     */
    private double computeDisplayedHours(User user, List<FlightLog> logs, boolean useHobbs) {
        Double baselineBoxed = useHobbs ? user.getHobbsManualBaseline() : user.getTachManualBaseline();
        if (baselineBoxed == null) {
            // Seed once from the existing displayed value. Mutates the user;
            // caller is expected to persist it.
            double seed = useHobbs ? user.getHobbsHours() : user.getTachHours();
            if (useHobbs) user.setHobbsManualBaseline(seed);
            else          user.setTachManualBaseline(seed);
            baselineBoxed = seed;
        }
        double baseline = baselineBoxed;

        double maxFromLogs = 0.0;
        boolean anyReading = false;
        for (FlightLog log : logs) {
            Double reading = useHobbs ? log.getHobbsIn() : log.getTachIn();
            if (reading != null) {
                if (!anyReading || reading > maxFromLogs) maxFromLogs = reading;
                anyReading = true;
            }
        }
        return anyReading ? Math.max(baseline, maxFromLogs) : baseline;
    }

    private static final java.util.Set<String> DEFAULT_DESCRIPTION_OPTIONS =
        java.util.Set.of("inspect", "test", "replace", "overhaul");

    // Drops blank entries and any custom option that duplicates a built-in
    // (case-insensitive). Self-heals legacy bad rows on first dashboard load
    // after this fix ships.
    private List<DescriptionOption> cleanupAndLoadDescriptionOptions(User user) {
        List<DescriptionOption> all = descriptionOptionRepository.findByUser(user);
        java.util.Set<String> keptLower = new java.util.HashSet<>();
        List<DescriptionOption> kept = new java.util.ArrayList<>();
        List<DescriptionOption> toDelete = new java.util.ArrayList<>();
        for (DescriptionOption opt : all) {
            String value = opt.getOption();
            String trimmed = value == null ? "" : value.trim();
            String lower = trimmed.toLowerCase();
            boolean isBlank = trimmed.isEmpty();
            boolean isDefault = DEFAULT_DESCRIPTION_OPTIONS.contains(lower);
            boolean isDup = !keptLower.add(lower);
            if (isBlank || isDefault || isDup) {
                toDelete.add(opt);
            } else {
                kept.add(opt);
            }
        }
        if (!toDelete.isEmpty()) descriptionOptionRepository.deleteAll(toDelete);
        return kept;
    }

    private void saveCustomDescriptionOption(String description, User user) {
        if (description == null) return;
        String trimmed = description.trim();
        if (trimmed.isEmpty()) return;
        if (DEFAULT_DESCRIPTION_OPTIONS.contains(trimmed.toLowerCase())) return;
        boolean alreadyExists = descriptionOptionRepository.findByUser(user).stream()
            .anyMatch(opt -> opt.getOption() != null && opt.getOption().equalsIgnoreCase(trimmed));
        if (!alreadyExists) {
            descriptionOptionRepository.save(new DescriptionOption(trimmed, user));
        }
    }
}