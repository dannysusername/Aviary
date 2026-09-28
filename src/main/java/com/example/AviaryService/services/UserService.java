package com.example.AviaryService.services;

import java.util.HashMap;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.util.Validation;

@Service
public class UserService {
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
        return "redirect:/login";
    }

    @Transactional
    public void updateUserInfo (Map<String, String> data,
        Authentication authentication) {
            User user = userRepository.findByUsername(authentication.getName());
            if (user == null) {
                throw new IllegalArgumentException("User not found");
            }

            Validation.maxLength("Make/model", data.get("makeModel"));
            Validation.maxLength("Tail number", data.get("tailNumber"));
            Validation.maxLength("Owner name", data.get("ownerName"));
            Validation.maxLength("Serial number", data.get("makeModelSN"));
            // Stored encrypted (AeroApiKeyConverter), which roughly doubles the
            // length, so the plain key has a tighter limit than the column.
            Validation.maxLength("AeroAPI key", data.get("aeroApiKey"), 100);

            // Update fields if provided in the request
            if (data.containsKey("makeModel")) user.setMakeModel(data.get("makeModel"));
            // Normalize to uppercase, matching how SubscriptionService stores the
            // subscribed registration, so the dashboard-vs-subscription mismatch
            // check compares like for like.
            if (data.containsKey("tailNumber")) {
                String tailNumber = data.get("tailNumber");
                user.setTailNumber(tailNumber == null ? null : tailNumber.trim().toUpperCase());
            }
            if (data.containsKey("ownerName")) user.setOwnerName(data.get("ownerName"));
            if (data.containsKey("makeModelSN")) user.setMakeModelSN(data.get("makeModelSN"));
            if (data.containsKey("aeroApiKey")) user.setAeroApiKey(data.get("aeroApiKey"));

            userRepository.save(user);

    }

}
