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
import com.example.AviaryService.util.DueDateAutoFill;
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
            statusSheetItem.setLastDoneHours(DueDateAutoFill.normalizeHours(data.get("lastDoneHours")));
            // New row: a Due Date the owner typed is theirs; a blank one fills
            // from Last Done + cycle (spec 007).
            DueDateAutoFill.Cycle cycleNow = cycleOf(statusSheetItem);
            String typedDueDate = blankToNull(data.get("dueDateDate"));
            String typedDueHours = DueDateAutoFill.normalizeHours(data.get("dueDateHours"));
            DueDateAutoFill.Resolved date = DueDateAutoFill.resolveDate(null, null, null, null,
                statusSheetItem.getLastDoneDate(), cycleNow, typedDueDate, typedDueDate != null);
            DueDateAutoFill.Resolved hours = DueDateAutoFill.resolveHours(null, null, null, null,
                statusSheetItem.getLastDoneHours(), cycleNow, typedDueHours, typedDueHours != null);
            statusSheetItem.setDueDateDate(date.value());
            statusSheetItem.setDueDateAuto(date.auto());
            statusSheetItem.setDueDateHours(hours.value());
            statusSheetItem.setDueHoursAuto(hours.auto());
            statusSheetItem.setTimeLeft(aircraft == null ? timeLeft : DueDates.formatTimeLeft(
                statusSheetItem.getDueDateDate(), statusSheetItem.getDueDateHours(), LocalDate.now(),
                aircraft.getTimeInServiceHours()));
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
        // Snapshot before applying the edit: auto-fill compares old and new.
        DueDateAutoFill.Cycle storedCycle = cycleOf(statusSheetItem);
        String storedLastDoneDate = statusSheetItem.getLastDoneDate();
        String storedLastDoneHours = statusSheetItem.getLastDoneHours();
        String storedDueDate = statusSheetItem.getDueDateDate();
        String storedDueHours = statusSheetItem.getDueDateHours();

        statusSheetItem.setCycleCalendarValue(updateDTO.getCycleCalendarValue());
        statusSheetItem.setCycleCalendarUnit(Parsing.normalizeCalendarUnit(updateDTO.getCycleCalendarUnit()));
        statusSheetItem.setCycleHours(updateDTO.getCycleHours());
        DueDateAutoFill.Cycle newCycle = cycleOf(statusSheetItem);

        String newLastDoneDate = blankToNull(updateDTO.getLastDoneDate());
        String newLastDoneHours = hoursIfChanged(storedLastDoneHours, updateDTO.getLastDoneHours());
        statusSheetItem.setLastDoneDate(newLastDoneDate);
        statusSheetItem.setLastDoneHours(newLastDoneHours);

        boolean legacyClient = updateDTO.getDueDateTouched() == null && updateDTO.getDueHoursTouched() == null;
        if (legacyClient) {
            // Older dashboard.js (no touched flags): save what it sent, as before.
            statusSheetItem.setDueDateDate(blankToNull(updateDTO.getDueDateDate()));
            statusSheetItem.setDueDateHours(hoursIfChanged(storedDueHours, updateDTO.getDueDateHours()));
        } else {
            boolean dateTouched = Boolean.TRUE.equals(updateDTO.getDueDateTouched());
            boolean hoursTouched = Boolean.TRUE.equals(updateDTO.getDueHoursTouched());
            DueDateAutoFill.Resolved date = DueDateAutoFill.resolveDate(
                storedLastDoneDate, storedCycle, storedDueDate, statusSheetItem.getDueDateAuto(),
                newLastDoneDate, newCycle, updateDTO.getDueDateDate(), dateTouched);
            DueDateAutoFill.Resolved hours = DueDateAutoFill.resolveHours(
                storedLastDoneHours, storedCycle, storedDueHours, statusSheetItem.getDueHoursAuto(),
                newLastDoneHours, newCycle,
                hoursTouched ? hoursIfChanged(storedDueHours, updateDTO.getDueDateHours()) : null, hoursTouched);
            statusSheetItem.setDueDateDate(date.value());
            statusSheetItem.setDueDateAuto(date.auto());
            statusSheetItem.setDueDateHours(hours.value());
            statusSheetItem.setDueHoursAuto(hours.auto());
        }
        statusSheetItem.setTimeLeft(DueDates.formatTimeLeft(statusSheetItem.getDueDateDate(),
            statusSheetItem.getDueDateHours(), LocalDate.now(), currentTimeInService(statusSheetItem)));

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
            statusSheetItem.setDueDateAuto(true);
        }
        if (hasHours) {
            statusSheetItem.setLastDoneHours(DueDateAutoFill.formatHours(currentTimeInService));
            statusSheetItem.setDueDateHours(DueDateAutoFill.formatHours(dueHours));
            statusSheetItem.setDueHoursAuto(true);
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

    // What "Reset to cycle" would set (spec 007): Due Date = stored Last Done +
    // cycle, for each part that can be computed. canReset is false when there
    // is nothing to count from or the Due Date already matches.
    public record CyclePreview(String dueDateDate, String dueDateHours, boolean canReset, String reason) {}

    public CyclePreview cyclePreview(User user, Long id) {
        return previewOf(getOwned(user, id));
    }

    private static CyclePreview previewOf(StatusSheetItem i) {
        DueDateAutoFill.Cycle cycle = cycleOf(i);
        String date = DueDateAutoFill.expectedDueDate(i.getLastDoneDate(), cycle);
        String hours = DueDateAutoFill.expectedDueHours(i.getLastDoneHours(), cycle);
        if (date == null && hours == null) {
            return new CyclePreview(null, null, false, "Enter Last Done first");
        }
        boolean dateDiffers = date != null && !DueDateAutoFill.sameDate(i.getDueDateDate(), date);
        boolean hoursDiffers = hours != null && !DueDateAutoFill.sameHours(i.getDueDateHours(), hours);
        if (!dateDiffers && !hoursDiffers) {
            return new CyclePreview(date, hours, false, "Due Date already matches the cycle");
        }
        return new CyclePreview(date, hours, true, null);
    }

    // "Reset to cycle": keeps Last Done, sets each computable Due Date part to
    // Last Done + cycle and hands it back to auto-fill.
    @Transactional
    public StatusSheetItem resetDueToCycle(User user, Long id) {
        StatusSheetItem i = getOwned(user, id);
        CyclePreview p = previewOf(i);
        if (!p.canReset()) {
            throw new IllegalArgumentException(p.reason());
        }
        if (p.dueDateDate() != null) {
            i.setDueDateDate(p.dueDateDate());
            i.setDueDateAuto(true);
        }
        if (p.dueDateHours() != null) {
            i.setDueDateHours(p.dueDateHours());
            i.setDueHoursAuto(true);
        }
        i.setTimeLeft(DueDates.formatTimeLeft(i.getDueDateDate(), i.getDueDateHours(), LocalDate.now(),
            currentTimeInService(i)));
        return statusSheetItemRepository.save(i);
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

    private static DueDateAutoFill.Cycle cycleOf(StatusSheetItem i) {
        return new DueDateAutoFill.Cycle(i.getCycleCalendarValue(), i.getCycleCalendarUnit(), i.getCycleHours());
    }

    // Hours text the owner did not change (including legacy text like
    // "1200 TT") is kept exactly as stored. A changed value must be a number
    // and is saved in the 25.0 format (spec 007).
    private static String hoursIfChanged(String stored, String submitted) {
        if (DueDateAutoFill.sameText(stored, submitted)) {
            return stored;
        }
        return DueDateAutoFill.normalizeHours(submitted);
    }

    private static Double currentTimeInService(StatusSheetItem i) {
        return i.getAircraft() != null ? i.getAircraft().getTimeInServiceHours() : null;
    }

    // Typo checks shown on the row; the Due Date still fills (spec 007).
    public List<String> warningsFor(StatusSheetItem i) {
        List<String> warnings = new java.util.ArrayList<>();
        LocalDate last = DueDateAutoFill.validDate(i.getLastDoneDate());
        if (last != null && last.isAfter(LocalDate.now())) {
            warnings.add("Last Done is in the future. Check the date.");
        }
        java.math.BigDecimal lastHours = DueDateAutoFill.parseHours(i.getLastDoneHours());
        Double tis = currentTimeInService(i);
        if (lastHours != null && tis != null && lastHours.doubleValue() > tis) {
            warnings.add("Last Done hours are above current Time in Service. Check the hours.");
        }
        return warnings;
    }

    // Store "" as null so an empty input matches a never-set field.
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
