package com.example.AviaryService.services;

import org.springframework.stereotype.Service;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.UserRepository;

import jakarta.transaction.Transactional;

@Service
public class HoursService {
    
    private final UserRepository userRepository;

    public HoursService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public void updateHours(Double hobbsTimeToAdd,
        Double tachTimeToAdd, Double newHobbsTime,
        Double newTachTime, User user) {
            
        boolean updated = false;

        if (newHobbsTime != null) { //If newHobbsTime has a value
            user.setHobbsHours(newHobbsTime);
            // Manual edit also sets the floor — logs can raise this, never lower it.
            user.setHobbsManualBaseline(newHobbsTime);
            System.out.println("Setting Hobbs time to: " + newHobbsTime);
            updated = true;
        } else if (hobbsTimeToAdd != null) { //else if hobbsTimeToAdd has a value
            double currentHobbs = user.getHobbsHours();
            double newHobbs = currentHobbs + hobbsTimeToAdd;
            user.setHobbsHours(newHobbs);
            user.setHobbsManualBaseline(newHobbs);
            System.out.println("Adding " + hobbsTimeToAdd + " to current Hobbs: " + currentHobbs);
            updated = true;
        }

        double finalHobbs = user.getHobbsHours();
        System.out.println("Current hobbs: " + finalHobbs);

        if (newTachTime != null) { //If newTachTime has a value
            user.setTachHours(newTachTime);
            user.setTachManualBaseline(newTachTime);
            System.out.println("Setting Tach time to: " + newTachTime);
            updated = true;
        } else if (tachTimeToAdd != null) { //else if tachTimeToAdd has a value
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

        }

}
