package com.example.AviaryService.services.aircraft;

// An aircraft id that doesn't exist or belongs to another account. Both look
// the same to the caller (404), so ids can't be probed across accounts.
public class AircraftNotFoundException extends RuntimeException {
    public AircraftNotFoundException() {
        super("Aircraft not found.");
    }
}
