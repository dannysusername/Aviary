package com.example.AviaryService.services.aircraft;

// The account already has Aircraft.MAX_PER_USER aircraft. Still shown under
// the tail number field on the dashboard; the MCP add_aircraft tool reports
// it as LIMIT_REACHED.
public class AircraftLimitException extends AircraftValidationException {
    public AircraftLimitException(String message) {
        super("tailNumber", message);
    }
}
