package com.example.AviaryService.mcp;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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
    private final McpAircraftResolver aircraftResolver;

    public FlightTools(FlightLogService flightLogService, McpCurrentUser currentUser,
                       McpAircraftResolver aircraftResolver) {
        this.flightLogService = flightLogService;
        this.currentUser = currentUser;
        this.aircraftResolver = aircraftResolver;
    }

    @Tool(name = "list_flights", description = "List one aircraft's logged flights, in date order.")
    public List<FlightSummary> listFlights(
            @ToolParam(description = "Tail number or aircraftId from list_aircraft. Required when the owner has more than one aircraft.", required = false)
            String aircraft) {
        currentUser.requireScope("aviary.read");
        User user = currentUser.require();
        return flightLogService.listFor(aircraftResolver.resolve(user, aircraft)).stream()
            .map(f -> new FlightSummary(f.getId(), f.getFromAirport(), f.getToAirport(), f.getBlockTimeOut(), f.getBlockTimeIn()))
        .toList();
    }

}
