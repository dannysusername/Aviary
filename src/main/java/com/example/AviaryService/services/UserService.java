package com.example.AviaryService.services;

import java.util.HashMap;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.UserRepository;

@Service
public class UserService {
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public String registerUser(String username, String password, Model model) {
        if (userRepository.findByUsername(username) != null) { //If username exists
            model.addAttribute("error", "Username already exists");
            return "register";
        }
        User user = new User(username, passwordEncoder.encode(password));
        userRepository.save(user);
        return "redirect:/login";
    }

    public void updateUserInfo (Map<String, String> data,
        Authentication authentication) {
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

    }

}
