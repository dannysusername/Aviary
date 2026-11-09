package com.example.AviaryService.mcp;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.oauth.CimdRegisteredClientRepository;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;

// MCP tool listing the owner's aircraft, so an assistant can ask which one an
// import is for and pass it as `aircraft` to the other tools.
@Component
public class AircraftTools {

    public record AircraftSummary(Long aircraftId, String tailNumber, String makeModel,
                                  Double timeInServiceHours, long timelineItems) {}

    private final AircraftRepository aircraftRepository;
    private final ServiceTimelineRepository timelineRepository;
    private final McpCurrentUser currentUser;

    public AircraftTools(AircraftRepository aircraftRepository, ServiceTimelineRepository timelineRepository,
                         McpCurrentUser currentUser) {
        this.aircraftRepository = aircraftRepository;
        this.timelineRepository = timelineRepository;
        this.currentUser = currentUser;
    }

    @Tool(name = "list_aircraft", description = """
        The owner's aircraft. When there is more than one, ask the owner which aircraft their records are for \
        and pass its tail number (or aircraftId) as `aircraft` to list_service_timeline, list_flights and \
        propose_timeline_import.""")
    public List<AircraftSummary> listAircraft() {
        currentUser.requireScope(CimdRegisteredClientRepository.SCOPE_READ);
        User user = currentUser.require();
        return aircraftRepository.findByUserOrderByIdAsc(user).stream()
            .map(a -> new AircraftSummary(a.getId(), a.getTailNumber(), a.getMakeModel(),
                a.getTimeInServiceHours(), timelineRepository.countByAircraft(a)))
            .toList();
    }
}
