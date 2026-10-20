package com.example.AviaryService.mcp;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.services.flights.FlightLogService;


//MCP tools for flight logs. Thin door in front of FlightLogService.
@Component
public class FlightTools {

    //Only what the AI needs, never include the user (it holds the password hash)
    public record FlightSummary(Long id, String from, String to, Double blockTimeOut, Double blockTimeIn) {}

    private final FlightLogService flightLogService;
    private final McpCurrentUser currentUser;

    public FlightTools(FlightLogService flightLogService, McpCurrentUser currentUser) {
        this.flightLogService = flightLogService;
        this.currentUser = currentUser;
    }

    @Tool(name = "list_flights", description = "List the owner's logged flights, newest first.")
    public List<FlightSummary> listFlights() {
        currentUser.requireScope("aviary.read");
        User user = currentUser.require();
        return flightLogService.listFor(user).stream()
            .map(f -> new FlightSummary(f.getId(), f.getFromAirport(), f.getToAirport(), f.getBlockTimeOut(), f.getBlockTimeIn()))
        .toList();
    }

}
