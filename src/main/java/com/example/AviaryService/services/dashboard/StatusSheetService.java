package com.example.AviaryService.services.dashboard;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.entity.DTO.StatusSheetItemUpdateDTO;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.util.DueDates;
import com.example.AviaryService.util.Formatting;
import com.example.AviaryService.util.Parsing;
import com.example.AviaryService.util.Validation;


// The Status Sheet on the dashboard. Adds, edits, deletes, and
// reorders items, and marks maintenance done (which moves the due date and
// hours forward one cycle).
@Service
public class StatusSheetService {

    private final StatusSheetItemRepository statusSheetItemRepository;

    private final DescriptionOptionService descriptionOptionService;
    
    public StatusSheetService(UserRepository userRepository, StatusSheetItemRepository statusSheetItemRepository, DescriptionOptionService descriptionOptionService) {
        this.statusSheetItemRepository = statusSheetItemRepository;
        this.descriptionOptionService = descriptionOptionService;
    }

    @Transactional
    public StatusSheetItem addStatusSheet(Map<String, String> data, User user, Aircraft aircraft) {
        String item = data.get("item");
        String isTitle = data.getOrDefault("isTitle", "false");
        String description = data.get("description");
        String cycle = data.get("cycle");
        String timeLeft = data.get("timeLeft");
        String ajax = data.getOrDefault("ajax", "false");

        StatusSheetItem statusSheetItem = new StatusSheetItem();
        statusSheetItem.setItem(item);
        boolean isTitleRow = "true".equals(isTitle);
        statusSheetItem.setIsTitle(isTitleRow);

        if (!isTitleRow) {
            if (description != null) {
                statusSheetItem.setDescription(description);
                descriptionOptionService.saveCustomDescriptionOption(description, user);
            }
            statusSheetItem.setCycleCalendarValue(Parsing.parseIntOrNull(data.get("cycleCalendarValue")));
            statusSheetItem.setCycleCalendarUnit(Parsing.normalizeCalendarUnit(data.get("cycleCalendarUnit")));
            statusSheetItem.setCycleHours(Parsing.parseDoubleOrNull(data.get("cycleHours")));
            statusSheetItem.setLastDoneDate(blankToNull(data.get("lastDoneDate")));
            statusSheetItem.setLastDoneHours(blankToNull(data.get("lastDoneHours")));
            statusSheetItem.setDueDateDate(blankToNull(data.get("dueDateDate")));
            statusSheetItem.setDueDateHours(blankToNull(data.get("dueDateHours")));
            statusSheetItem.setTimeLeft(timeLeft);
        }
        statusSheetItem.setUser(user);
        statusSheetItem.setAircraft(aircraft);

        Integer maxOrder = statusSheetItemRepository.findMaxStatusSheetOrderByAircraft(aircraft);
        int newOrder = (maxOrder != null) ? maxOrder + 1 : 0;
        statusSheetItem.setStatusSheetOrder(newOrder);

        return statusSheetItemRepository.save(statusSheetItem);

    }

    // Saves an edited row. Throws NoSuchElementException if the row doesn't
    // exist, SecurityException if it isn't this user's, and
    // IllegalArgumentException for text that's too long.
    @Transactional
    public StatusSheetItem updateStatusSheet(User user, Long id, StatusSheetItemUpdateDTO updateDTO) {
        StatusSheetItem statusSheetItem = getOwned(user, id);

        Validation.maxLength("Item", updateDTO.getItem());
        Validation.maxLength("Description", updateDTO.getDescription());
        Validation.maxLength("Last done", updateDTO.getLastDoneHours());
        Validation.maxLength("Due date", updateDTO.getDueDateHours());

        if (updateDTO.getItem() != null) statusSheetItem.setItem(updateDTO.getItem());
        if (updateDTO.getDescription() != null) {
            String description = updateDTO.getDescription();
            String[] defaults = {"inspect", "test", "replace", "overhaul"};
            if (java.util.Arrays.asList(defaults).contains(description.toLowerCase())) {
                description = description.substring(0, 1).toUpperCase() + description.substring(1).toLowerCase();
            }
            statusSheetItem.setDescription(description);
            descriptionOptionService.saveCustomDescriptionOption(description, user);
        }
        // Structured cycle fields. The client sends them on every save so
        // null actually means "clear it" here — distinguish empty/null on
        // the client if you ever want partial updates.
        statusSheetItem.setCycleCalendarValue(updateDTO.getCycleCalendarValue());
        statusSheetItem.setCycleCalendarUnit(Parsing.normalizeCalendarUnit(updateDTO.getCycleCalendarUnit()));
        statusSheetItem.setCycleHours(updateDTO.getCycleHours());
        statusSheetItem.setLastDoneDate(updateDTO.getLastDoneDate());
        statusSheetItem.setLastDoneHours(updateDTO.getLastDoneHours());
        statusSheetItem.setDueDateDate(updateDTO.getDueDateDate());
        statusSheetItem.setDueDateHours(updateDTO.getDueDateHours());
        statusSheetItem.setTimeLeft(updateDTO.getTimeLeft());

        return statusSheetItemRepository.save(statusSheetItem);
    }

    // Throws NoSuchElementException / SecurityException like updateStatusSheet.
    @Transactional
    public void deleteStatusSheet(User user, Long id) {
        statusSheetItemRepository.delete(getOwned(user, id));
    }

    // Applies the drag-and-drop order: ids[i] gets position i, within one
    // aircraft. Rejects the whole list (nothing saved) if any id isn't a row
    // of that aircraft, so a drag can never reorder another plane's rows.
    @Transactional
    public void reorder(Aircraft aircraft, List<Long> ids) {
        List<StatusSheetItem> statusSheetItems = statusSheetItemRepository.findByAircraftOrderByIdAsc(aircraft);
        java.util.Set<Long> own = statusSheetItems.stream().map(StatusSheetItem::getId).collect(Collectors.toSet());
        for (Long id : ids) {
            if (!own.contains(id)) {
                throw new IllegalArgumentException("Those rows aren't all on this aircraft.");
            }
        }
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            StatusSheetItem statusSheetItem = statusSheetItems.stream()
                .filter(t -> t.getId().equals(id))
                .findFirst()
                .orElse(null);
            if (statusSheetItem != null) {
                statusSheetItem.setStatusSheetOrder(i);
            }
        }
        statusSheetItemRepository.saveAll(statusSheetItems);
    }

    // The row's new Last Done / Due Date / Time Left text after completing
    // maintenance, each "date hours" with blank parts left out.
    public record CompletedMaintenance(String lastDone, String dueDate, String timeLeft) {}

    // Marks maintenance done today at the row's aircraft's Time in Service and
    // rolls the due date/hours forward one cycle. The server picks "today"
    // and the hours so the result is the same from any tab. Throws
    // NoSuchElementException / SecurityException like updateStatusSheet, and
    // IllegalArgumentException when the row has no cycle.
    @Transactional
    public CompletedMaintenance completeMaintenance(User user, Long id) {
        StatusSheetItem statusSheetItem = getOwned(user, id);

        Integer calVal = statusSheetItem.getCycleCalendarValue();
        String  calUnit = Parsing.normalizeCalendarUnit(statusSheetItem.getCycleCalendarUnit());
        Double  hrsCycle = statusSheetItem.getCycleHours();
        boolean hasCalendar = (calVal != null && calVal > 0 && calUnit != null);
        boolean hasHours    = (hrsCycle != null && hrsCycle > 0);
        if (!hasCalendar && !hasHours) {
            throw new IllegalArgumentException(
                "Set a calendar cycle or an hours cycle before marking maintenance complete.");
        }

        LocalDate today = LocalDate.now();
        // The row's own aircraft, never another plane in the fleet.
        double currentTimeInService = statusSheetItem.getAircraft().getTimeInServiceHours();

        LocalDate dueDateLd = hasCalendar ? DueDates.nextDueDate(today, calVal, calUnit) : null;
        Double dueHours = hasHours ? DueDates.nextDueHours(currentTimeInService, hrsCycle) : null;

        // Only update fields that belong to the active cycle type — leave the other type's
        // fields untouched so they stay visible in the UI after repaint.
        if (hasCalendar) {
            statusSheetItem.setLastDoneDate(today.toString());
            statusSheetItem.setDueDateDate(dueDateLd != null ? dueDateLd.toString() : null);
        }
        if (hasHours) {
            statusSheetItem.setLastDoneHours(Formatting.formatHours(currentTimeInService));
            statusSheetItem.setDueDateHours(dueHours != null ? Formatting.formatHours(dueHours) : null);
        }
        // Same text the dashboard computes, from the row's saved due date/hours.
        String timeLeftStr = DueDates.formatTimeLeft(
            statusSheetItem.getDueDateDate(), statusSheetItem.getDueDateHours(), today, currentTimeInService);
        statusSheetItem.setTimeLeft(timeLeftStr);
        statusSheetItemRepository.save(statusSheetItem);

        // Build the strings from the actual saved state so the dashboard's
        // repaintDateHoursCell receives both the date and hours parts.
        return new CompletedMaintenance(
            joinParts(statusSheetItem.getLastDoneDate(), statusSheetItem.getLastDoneHours()),
            joinParts(statusSheetItem.getDueDateDate(), statusSheetItem.getDueDateHours()),
            timeLeftStr);
    }

    private StatusSheetItem getOwned(User user, Long id) {
        StatusSheetItem statusSheetItem = statusSheetItemRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Invalid Status Sheet item ID: " + id));
        if (user == null || statusSheetItem.getUser() == null || statusSheetItem.getUser().getId() != user.getId()) {
            throw new SecurityException("You do not own this Status Sheet item");
        }
        return statusSheetItem;
    }

    private static String joinParts(String date, String hours) {
        return Stream.of(date, hours)
            .filter(s -> s != null && !s.isEmpty()).collect(Collectors.joining(" "));
    }

    // Store "" as null so an empty input matches a never-set field.
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
