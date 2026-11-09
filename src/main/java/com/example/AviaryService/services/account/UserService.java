package com.example.AviaryService.services.account;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.services.aircraft.AircraftValidationException;
import com.example.AviaryService.util.Validation;

// User accounts. Registers new users (checking the username and password
// rules) and saves the info card (on one aircraft) and the account-wide
// AeroAPI key.
@Service
public class UserService {
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AircraftRepository aircraftRepository;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AircraftRepository aircraftRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.aircraftRepository = aircraftRepository;
    }

    @Transactional
    public String registerUser(String username, String password, Model model) {
        // Same rules as the register.html inputs; checked here too because the
        // form can be bypassed. BCrypt ignores anything past 72 bytes.
        username = username == null ? "" : username.trim();
        if (!username.matches("[A-Za-z0-9._-]{3,50}")) {
            model.addAttribute("error", "Username must be 3-50 characters: letters, numbers, dot, underscore or dash.");
            return "register";
        }
        if (password == null || password.length() < 8 || password.length() > 72) {
            model.addAttribute("error", "Password must be 8-72 characters.");
            return "register";
        }
        if (userRepository.findByUsername(username) != null) { //If username exists
            model.addAttribute("error", "Username already exists");
            return "register";
        }
        User user = new User(username, passwordEncoder.encode(password));
        userRepository.save(user);
        // Every account starts with one empty aircraft, so the first-run
        // dashboard looks the same as before fleets existed.
        Aircraft first = aircraftRepository.save(new Aircraft(user));
        user.setLastViewedAircraft(first);
        userRepository.save(user);
        return "redirect:/login";
    }

    // Info card fields go on the aircraft the dashboard is showing; the
    // AeroAPI key is account-wide. Throws IllegalArgumentException (or its
    // AircraftValidationException subclass) for bad input.
    @Transactional
    public void updateUserInfo(Map<String, String> data, User user, Aircraft aircraft) {
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }

        Validation.maxLength("Make/model", data.get("makeModel"));
        Validation.maxLength("Owner name", data.get("ownerName"));
        Validation.maxLength("Serial number", data.get("makeModelSN"));
        // Stored encrypted (AeroApiKeyConverter), which roughly doubles the
        // length, so the plain key has a tighter limit than the column.
        Validation.maxLength("AeroAPI key", data.get("aeroApiKey"), 100);

        if (data.containsKey("tailNumber")) {
            String tailNumber = data.get("tailNumber");
            try {
                Validation.requireValidTailNumber(tailNumber, false);
            } catch (IllegalArgumentException e) {
                throw new AircraftValidationException("tailNumber", e.getMessage());
            }
            String key = Validation.tailNumberKey(tailNumber);
            if (key != null && aircraftRepository.existsByUserAndTailNumberKeyAndIdNot(user, key, aircraft.getId())) {
                throw new AircraftValidationException("tailNumber", "You already have an aircraft with this tail number.");
            }
            // Upper-cased (Aircraft.setTailNumber), matching how
            // SubscriptionService stores the subscribed registration, so the
            // dashboard-vs-subscription mismatch check compares like for like.
            aircraft.setTailNumber(tailNumber);
        }
        if (data.containsKey("makeModel")) aircraft.setMakeModel(data.get("makeModel"));
        if (data.containsKey("ownerName")) aircraft.setOwnerName(data.get("ownerName"));
        if (data.containsKey("makeModelSN")) aircraft.setMakeModelSN(data.get("makeModelSN"));
        if (data.containsKey("aeroApiKey")) user.setAeroApiKey(data.get("aeroApiKey"));

        aircraftRepository.save(aircraft);
        userRepository.save(user);
    }

}
