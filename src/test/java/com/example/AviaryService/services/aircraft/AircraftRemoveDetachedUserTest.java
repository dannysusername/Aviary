package com.example.AviaryService.services.aircraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.support.TestFleet;

// Spec 012 R1: production (heroku profile) runs with open-in-view=false, so
// the User the controller passes in is detached. remove() used to touch the
// user's lazy aircraft list and fail with a 500 there, while every test
// (open-in-view on, or @Transactional) passed. Not @Transactional on purpose:
// the user below is detached, exactly as on Heroku.
@SpringBootTest
@ActiveProfiles("test")
class AircraftRemoveDetachedUserTest {

    @Autowired AircraftService aircraftService;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired UserRepository userRepository;

    @Test
    void removeWorksWithADetachedUser() {
        User u = new User();
        u.setUsername("detached-" + System.nanoTime());
        u.setPassword("x");
        u = userRepository.save(u);
        Aircraft first = TestFleet.addAircraft(aircraftRepository, userRepository, u, "N53037", "", 0, 0);
        Aircraft k = TestFleet.addAircraft(aircraftRepository, userRepository, u, "K", "", 0, 0);
        User viewing = userRepository.findById(u.getId()).orElseThrow();
        viewing.setLastViewedAircraft(k);
        userRepository.save(viewing);

        User detached = userRepository.findByUsername(u.getUsername()); // as AircraftController does
        Aircraft next = aircraftService.remove(detached, k.getId(), "k");

        assertEquals(first.getId(), next.getId());
        assertTrue(aircraftRepository.findById(k.getId()).isEmpty());
        assertEquals(first.getId(), userRepository.findById(u.getId()).orElseThrow().getLastViewedAircraft().getId());
    }
}
