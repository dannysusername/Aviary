package com.example.AviaryService.services.aircraft;

// A rejected aircraft field, with the form field it belongs to so the page
// can show the message next to it.
public class AircraftValidationException extends IllegalArgumentException {
    private final String field;

    public AircraftValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
