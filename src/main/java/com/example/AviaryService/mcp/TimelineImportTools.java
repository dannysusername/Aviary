package com.example.AviaryService.mcp;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.oauth.CimdRegisteredClientRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.services.imports.TimelineImportService;
import com.example.AviaryService.services.imports.TimelineImportService.ImportResult;
import com.example.AviaryService.util.DueDates;
import com.example.AviaryService.util.Formatting;
import com.example.AviaryService.util.TimelineImportRules;
import com.example.AviaryService.util.TimelineImportRules.InputItem;
import com.example.AviaryService.util.TimelineImportRules.RejectedRow;

// MCP tools for importing a service timeline. Thin door in front of
// TimelineImportService: check scope, resolve the token's owner, delegate.
// Outputs are records that never include the User (it holds the password hash).
@Component
public class TimelineImportTools {

    public record ImportFormat(Map<String, String> itemFields, List<String> rules, int maxItems, String example) {}

    public record TimelineRow(Long id, String item, String description, boolean isTitle,
                              String lastDone, String due, String cycle, String timeLeft) {}

    private final TimelineImportService importService;
    private final ServiceTimelineRepository timelineRepository;
    private final McpCurrentUser currentUser;

    public TimelineImportTools(TimelineImportService importService, ServiceTimelineRepository timelineRepository,
                               McpCurrentUser currentUser) {
        this.importService = importService;
        this.timelineRepository = timelineRepository;
        this.currentUser = currentUser;
    }

    @Tool(name = "get_import_format", description = """
        How Aviary service timeline items must look, with the rules and an example. Call this first, then \
        convert the owner's maintenance records (any format) into these items before proposing.""")
    public ImportFormat getImportFormat() {
        currentUser.requireScope(CimdRegisteredClientRepository.SCOPE_READ);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("item", "required: the maintenance item's name, up to 255 characters");
        fields.put("description", "optional, up to 255 characters");
        fields.put("isTitle", "true for a heading row that groups the items under it");
        fields.put("lastDoneDate", "YYYY-MM-DD");
        fields.put("lastDoneHours", "time in service hours when last done, 0 or more");
        fields.put("cycleCalendarValue", "whole number 1-1200");
        fields.put("cycleCalendarUnit", "DAYS, MONTHS, or YEARS");
        fields.put("cycleHours", "hours interval, above 0");
        fields.put("dueDateDate", "YYYY-MM-DD, only if no interval is known");
        fields.put("dueDateHours", "hours, only if no interval is known");
        fields.put("dateAmbiguous", "true if you weren't sure which date was meant");
        fields.put("onCondition", "true for an item with no fixed interval (inspected/replaced on condition); it needs no dates");
        fields.put("sourceRow", "the original line from the owner's records");
        return new ImportFormat(fields, TimelineImportRules.RULES, TimelineImportRules.MAX_ITEMS,
            TimelineImportRules.FORMAT_EXAMPLE);
    }

    @Tool(name = "list_service_timeline", description = "The owner's current service timeline items, in order.")
    public List<TimelineRow> listServiceTimeline() {
        currentUser.requireScope(CimdRegisteredClientRepository.SCOPE_READ);
        User user = currentUser.require();
        LocalDate today = LocalDate.now();
        return timelineRepository.findByUserOrderByTimelineOrderAsc(user).stream()
            .map(t -> new TimelineRow(t.getId(), t.getItem(), t.getDescription(), t.getIsTitle(),
                join(t.getLastDoneDate(), t.getLastDoneHours()),
                join(t.getDueDateDate(), t.getDueDateHours()),
                Formatting.formatCycle(t.getCycleCalendarValue(), t.getCycleCalendarUnit(), t.getCycleHours()),
                t.getIsTitle() ? null
                    : DueDates.formatTimeLeft(t.getDueDateDate(), t.getDueDateHours(), today, user.getTimeInServiceHours())))
            .toList();
    }

    @Tool(name = "propose_timeline_import", description = """
        Preview importing service timeline items. Send every maintenance item in the owner's records -- an item \
        only needs a name. Changes nothing: returns a proposal with the items as Aviary would store them, flags \
        and notes for the owner to check, and rows that can't be imported with reasons. Show the owner the \
        proposal before confirming. Calling this again replaces the previous proposal (use it to revise).""")
    public ImportResult proposeTimelineImport(
            @ToolParam(description = "Items converted from the owner's records; see get_import_format") List<InputItem> items,
            @ToolParam(description = "Rows that are not maintenance items at all, each with a reason. Never put a real item here just because it has no dates.", required = false)
            List<RejectedRow> rejectedBySource) {
        currentUser.requireScope(CimdRegisteredClientRepository.SCOPE_IMPORT);
        User user = currentUser.require();
        return importService.propose(user, items, rejectedBySource, currentUser.clientId());
    }

    @Tool(name = "continue_poor_fit", description = """
        For a POOR_FIT proposal: the owner agreed to continue with only the rows that fit. Returns the proposal, \
        which can then be confirmed.""")
    public ImportResult continuePoorFit(@ToolParam(description = "proposalId from propose_timeline_import") Long proposalId) {
        currentUser.requireScope(CimdRegisteredClientRepository.SCOPE_IMPORT);
        return importService.continuePoorFit(currentUser.require(), proposalId);
    }

    @Tool(name = "confirm_timeline_import", description = """
        Add a proposal's items to the owner's service timeline. Only call this after the owner has seen the \
        proposal and said to go ahead. Items matching ones already on the timeline are skipped unless listed in \
        addDuplicatesAnyway. Existing items are never changed.""")
    public ImportResult confirmTimelineImport(
            @ToolParam(description = "proposalId from propose_timeline_import") Long proposalId,
            @ToolParam(description = "Indexes of DUPLICATE_EXISTING items the owner wants added anyway", required = false)
            List<Integer> addDuplicatesAnyway) {
        currentUser.requireScope(CimdRegisteredClientRepository.SCOPE_IMPORT);
        return importService.confirm(currentUser.require(), proposalId,
            addDuplicatesAnyway == null ? null : new HashSet<>(addDuplicatesAnyway));
    }

    private static String join(String date, String hours) {
        String d = date == null ? "" : date;
        String h = hours == null ? "" : hours;
        String s = (d + " " + h).trim();
        return s.isEmpty() ? null : s;
    }
}
