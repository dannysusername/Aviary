package com.example.AviaryService.services.aircraft;

// A request that acts on "the current aircraft" didn't say which one, and the
// account has more than one. Never guess: guessing could write to the wrong plane.
public class AircraftRequiredException extends RuntimeException {
    public AircraftRequiredException() {
        super("Choose an aircraft.");
    }
}
