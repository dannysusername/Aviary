package com.example.AviaryService.support;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.UserRepository;

// Test helper: gives a saved user an aircraft (the per-plane info card and
// hours used to live on User itself).
public final class TestFleet {

    private TestFleet() {}

    public static Aircraft addAircraft(AircraftRepository aircraftRepository, UserRepository userRepository,
            User user, String tail, String ownerName, double blockTime, double timeInService) {
        Aircraft a = new Aircraft(user);
        a.setTailNumber(tail);
        a.setOwnerName(ownerName);
        a.setBlockTimeHours(blockTime);
        a.setTimeInServiceHours(timeInService);
        a = aircraftRepository.save(a);
        if (user.getLastViewedAircraft() == null) {
            user.setLastViewedAircraft(a);
            userRepository.save(user);
        }
        return a;
    }

    public static Aircraft addAircraft(AircraftRepository aircraftRepository, UserRepository userRepository, User user) {
        return addAircraft(aircraftRepository, userRepository, user, "", "", 0, 0);
    }
}
