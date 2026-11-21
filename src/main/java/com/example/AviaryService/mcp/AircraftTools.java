package com.example.AviaryService.mcp;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.oauth.CimdRegisteredClientRepository;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.example.AviaryService.services.aircraft.AircraftLimitException;
import com.example.AviaryService.services.aircraft.AircraftService;
import com.example.AviaryService.services.aircraft.AircraftValidationException;
import com.example.AviaryService.util.Validation;

// MCP tools for the owner's fleet: list_aircraft, so an assistant can ask
// which one an import is for and pass it as `aircraft` to the other tools,
// and add_aircraft (spec 012), so records for a plane that isn't on the
// account yet get their own aircraft instead of landing on another one.
@Component
public class AircraftTools {

    public record AircraftSummary(Long aircraftId, String tailNumber, String makeModel,
                                  Double timeInServiceHours, long statusSheetItems) {}

    // status: ADDED, ALREADY_EXISTS, INVALID or LIMIT_REACHED. aircraft is
    // the new one (ADDED), the existing one (ALREADY_EXISTS), otherwise null.
    public record AddAircraftResult(String status, String message, AircraftSummary aircraft) {}

    private final AircraftRepository aircraftRepository;
    private final StatusSheetItemRepository statusSheetItemRepository;
    private final AircraftService aircraftService;
    private final McpCurrentUser currentUser;

    public AircraftTools(AircraftRepository aircraftRepository, StatusSheetItemRepository statusSheetItemRepository,
                         AircraftService aircraftService, McpCurrentUser currentUser) {
        this.aircraftRepository = aircraftRepository;
        this.statusSheetItemRepository = statusSheetItemRepository;
        this.aircraftService = aircraftService;
        this.currentUser = currentUser;
    }

    @Tool(name = "list_aircraft", description = """
        The owner's aircraft. When there is more than one, ask the owner which aircraft their records are for \
        and pass its tail number (or aircraftId) as `aircraft` to list_status_sheet, list_flights and \
        propose_status_sheet_import. If the owner's records are for a plane that isn't listed, add it with \
        add_aircraft first.""")
    public List<AircraftSummary> listAircraft() {
        currentUser.requireScope(CimdRegisteredClientRepository.SCOPE_READ);
        User user = currentUser.require();
        return aircraftRepository.findByUserOrderByIdAsc(user).stream().map(this::summary).toList();
    }

    @Tool(name = "add_aircraft", description = """
        Add an aircraft to the owner's account. Use it when the owner's records are for a plane that isn't in \
        list_aircraft yet, then pass the returned tail number as `aircraft` (and `recordsTailNumber`) to \
        propose_status_sheet_import. The new aircraft starts empty. Doesn't change which aircraft the owner's \
        dashboard opens on. If the tail number is already on the account, returns ALREADY_EXISTS with that \
        aircraft: import into it instead.""")
    public AddAircraftResult addAircraft(
            @ToolParam(description = "The aircraft's tail number, e.g. N731KR") String tailNumber,
            @ToolParam(description = "Make and model, e.g. Piper PA-28", required = false) String makeModel,
            @ToolParam(description = "Operator name", required = false) String operatorName,
            @ToolParam(description = "Serial number", required = false) String serialNumber) {
        currentUser.requireScope(CimdRegisteredClientRepository.SCOPE_IMPORT);
        User user = currentUser.require();
        String key = Validation.tailNumberKey(tailNumber);
        if (key != null) {
            var existing = aircraftRepository.findByUserAndTailNumberKey(user, key);
            if (existing.isPresent()) {
                Aircraft a = existing.get();
                return new AddAircraftResult("ALREADY_EXISTS", a.getDisplayName() + " is already on this account (aircraftId "
                    + a.getId() + "). Import into it instead.", summary(a));
            }
        }
        try {
            Aircraft created = aircraftService.create(user, tailNumber, makeModel, operatorName, serialNumber, false);
            return new AddAircraftResult("ADDED", "Added " + created.getDisplayName() + ".", summary(created));
        } catch (AircraftLimitException e) {
            return new AddAircraftResult("LIMIT_REACHED", e.getMessage(), null);
        } catch (AircraftValidationException e) {
            return new AddAircraftResult("INVALID", e.getMessage(), null);
        }
    }

    private AircraftSummary summary(Aircraft a) {
        return new AircraftSummary(a.getId(), a.getTailNumber(), a.getMakeModel(),
            a.getTimeInServiceHours(), statusSheetItemRepository.countByAircraft(a));
    }
}
