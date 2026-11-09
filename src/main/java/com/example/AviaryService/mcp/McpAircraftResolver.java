package com.example.AviaryService.mcp;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.util.Validation;

// Which aircraft an MCP tool call is about. Shared by every tool that takes
// an `aircraft` argument (specs/003-multi-aircraft-fleet/contracts/mcp-tools.md):
//   given   -> the owner's aircraft with that id, or that tail number
//              ignoring case and spaces; otherwise an error listing theirs
//   omitted -> the only aircraft, if there is exactly one; otherwise an error
// Only the token owner's aircraft are ever searched, so another account's id
// or tail behaves exactly like one that doesn't exist.
@Component
public class McpAircraftResolver {

    // The message goes back to the assistant as the tool's error text.
    public static class AircraftChoiceException extends IllegalArgumentException {
        public AircraftChoiceException(String message) {
            super(message);
        }
    }

    private final AircraftRepository aircraftRepository;

    public McpAircraftResolver(AircraftRepository aircraftRepository) {
        this.aircraftRepository = aircraftRepository;
    }

    public Aircraft resolve(User user, String aircraft) {
        List<Aircraft> fleet = aircraftRepository.findByUserOrderByIdAsc(user);
        if (fleet.isEmpty()) {
            // Every account should have one (registration, AircraftBackfill).
            // If one slipped through, give it an empty aircraft -- same as
            // the dashboard does -- rather than an unanswerable error.
            fleet = List.of(aircraftRepository.save(new Aircraft(user)));
        }
        if (aircraft == null || aircraft.isBlank()) {
            if (fleet.size() == 1) return fleet.get(0);
            throw new AircraftChoiceException("This account has " + fleet.size()
                + " aircraft. Say which one: " + describe(fleet) + ".");
        }
        String wanted = aircraft.trim();
        String key = Validation.tailNumberKey(wanted);
        for (Aircraft a : fleet) {
            if (wanted.equals(String.valueOf(a.getId()))) return a;
            if (key != null && key.equals(a.getTailNumberKey())) return a;
        }
        throw new AircraftChoiceException("Aircraft \"" + wanted + "\" not found. Your aircraft: "
            + describe(fleet) + ".");
    }

    // "N482DM (Cessna 172S), N731KR (Piper PA-28)"
    static String describe(List<Aircraft> fleet) {
        return fleet.stream().map(a -> {
            String mm = a.getMakeModel();
            String name = a.getTailNumber() == null || a.getTailNumber().isBlank()
                ? "Unnamed aircraft (id " + a.getId() + ")" : a.getTailNumber();
            return mm == null || mm.isBlank() ? name : name + " (" + mm + ")";
        }).collect(Collectors.joining(", "));
    }
}
