package com.example.AviaryService.services;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.entity.DTO.TimelineUpdateDTO;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.util.DueDates;
import com.example.AviaryService.util.Formatting;
import com.example.AviaryService.util.Parsing;
import com.example.AviaryService.util.Validation;


// The maintenance timeline on the dashboard. Adds, edits, deletes, and
// reorders items, and marks maintenance done (which moves the due date and
// hours forward one cycle).
@Service
public class TimelineService {

    private final ServiceTimelineRepository serviceTimelineRepository;

    private final DescriptionOptionService descriptionOptionService;
    
    public TimelineService(UserRepository userRepository, ServiceTimelineRepository serviceTimelineRepository, DescriptionOptionService descriptionOptionService) {
        this.serviceTimelineRepository = serviceTimelineRepository;
        this.descriptionOptionService = descriptionOptionService;
    }

    @Transactional
    public ServiceTimeline addTimeline(Map<String, String> data, User user) {
        String item = data.get("item");
        String isTitle = data.getOrDefault("isTitle", "false");
        String description = data.get("description");
        String cycle = data.get("cycle");
        String timeLeft = data.get("timeLeft");
        String ajax = data.getOrDefault("ajax", "false");

        ServiceTimeline timeline = new ServiceTimeline();
        timeline.setItem(item);
        boolean isTitleRow = "true".equals(isTitle);
        timeline.setIsTitle(isTitleRow);

        if (!isTitleRow) {
            if (description != null) {
                timeline.setDescription(description);
                descriptionOptionService.saveCustomDescriptionOption(description, user);
            }
            timeline.setCycleCalendarValue(Parsing.parseIntOrNull(data.get("cycleCalendarValue")));
            timeline.setCycleCalendarUnit(Parsing.normalizeCalendarUnit(data.get("cycleCalendarUnit")));
            timeline.setCycleHours(Parsing.parseDoubleOrNull(data.get("cycleHours")));
            timeline.setLastDoneDate(blankToNull(data.get("lastDoneDate")));
            timeline.setLastDoneHours(blankToNull(data.get("lastDoneHours")));
            timeline.setDueDateDate(blankToNull(data.get("dueDateDate")));
            timeline.setDueDateHours(blankToNull(data.get("dueDateHours")));
            timeline.setTimeLeft(timeLeft);
        }
        timeline.setUser(user);

        Integer maxOrder = serviceTimelineRepository.findMaxTimelineOrderByUser(user);
        int newOrder = (maxOrder != null) ? maxOrder + 1 : 0;
        timeline.setTimelineOrder(newOrder);

        return serviceTimelineRepository.save(timeline);

    }

    // Saves an edited row. Throws NoSuchElementException if the row doesn't
    // exist, SecurityException if it isn't this user's, and
    // IllegalArgumentException for text that's too long.
    @Transactional
    public ServiceTimeline updateTimeline(User user, Long id, TimelineUpdateDTO updateDTO) {
        ServiceTimeline timeline = getOwned(user, id);

        Validation.maxLength("Item", updateDTO.getItem());
        Validation.maxLength("Description", updateDTO.getDescription());
        Validation.maxLength("Last done", updateDTO.getLastDoneHours());
        Validation.maxLength("Due date", updateDTO.getDueDateHours());

        if (updateDTO.getItem() != null) timeline.setItem(updateDTO.getItem());
        if (updateDTO.getDescription() != null) {
            String description = updateDTO.getDescription();
            String[] defaults = {"inspect", "test", "replace", "overhaul"};
            if (java.util.Arrays.asList(defaults).contains(description.toLowerCase())) {
                description = description.substring(0, 1).toUpperCase() + description.substring(1).toLowerCase();
            }
            timeline.setDescription(description);
            descriptionOptionService.saveCustomDescriptionOption(description, user);
        }
        // Structured cycle fields. The client sends them on every save so
        // null actually means "clear it" here — distinguish empty/null on
        // the client if you ever want partial updates.
        timeline.setCycleCalendarValue(updateDTO.getCycleCalendarValue());
        timeline.setCycleCalendarUnit(Parsing.normalizeCalendarUnit(updateDTO.getCycleCalendarUnit()));
        timeline.setCycleHours(updateDTO.getCycleHours());
        timeline.setLastDoneDate(updateDTO.getLastDoneDate());
        timeline.setLastDoneHours(updateDTO.getLastDoneHours());
        timeline.setDueDateDate(updateDTO.getDueDateDate());
        timeline.setDueDateHours(updateDTO.getDueDateHours());
        timeline.setTimeLeft(updateDTO.getTimeLeft());

        return serviceTimelineRepository.save(timeline);
    }

    // Throws NoSuchElementException / SecurityException like updateTimeline.
    @Transactional
    public void deleteTimeline(User user, Long id) {
        serviceTimelineRepository.delete(getOwned(user, id));
    }

    // Applies the drag-and-drop order: ids[i] gets position i. Ids that
    // aren't this user's rows are ignored.
    @Transactional
    public void reorder(User user, List<Long> ids) {
        List<ServiceTimeline> timelines = serviceTimelineRepository.findByUserOrderByIdAsc(user);
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            ServiceTimeline timeline = timelines.stream()
                .filter(t -> t.getId().equals(id))
                .findFirst()
                .orElse(null);
            if (timeline != null) {
                timeline.setTimelineOrder(i);
            }
        }
        serviceTimelineRepository.saveAll(timelines);
    }

    // The row's new Last Done / Due Date / Time Left text after completing
    // maintenance, each "date hours" with blank parts left out.
    public record CompletedMaintenance(String lastDone, String dueDate, String timeLeft) {}

    // Marks maintenance done today at the user's current Time in Service and
    // rolls the due date/hours forward one cycle. The server picks "today"
    // and the hours so the result is the same from any tab. Throws
    // NoSuchElementException / SecurityException like updateTimeline, and
    // IllegalArgumentException when the row has no cycle.
    @Transactional
    public CompletedMaintenance completeMaintenance(User user, Long id) {
        ServiceTimeline timeline = getOwned(user, id);

        Integer calVal = timeline.getCycleCalendarValue();
        String  calUnit = Parsing.normalizeCalendarUnit(timeline.getCycleCalendarUnit());
        Double  hrsCycle = timeline.getCycleHours();
        boolean hasCalendar = (calVal != null && calVal > 0 && calUnit != null);
        boolean hasHours    = (hrsCycle != null && hrsCycle > 0);
        if (!hasCalendar && !hasHours) {
            throw new IllegalArgumentException(
                "Set a calendar cycle or an hours cycle before marking maintenance complete.");
        }

        LocalDate today = LocalDate.now();
        double currentTimeInService = user.getTimeInServiceHours();

        LocalDate dueDateLd = null;
        if (hasCalendar) {
            switch (calUnit) {
                case "DAYS":   dueDateLd = today.plusDays(calVal); break;
                case "MONTHS": dueDateLd = today.plusMonths(calVal); break;
                case "YEARS":  dueDateLd = today.plusYears(calVal); break;
                default:
                    throw new IllegalArgumentException("Invalid calendar unit: " + calUnit);
            }
        }
        Double dueHours = hasHours ? (currentTimeInService + hrsCycle) : null;

        // Only update fields that belong to the active cycle type — leave the other type's
        // fields untouched so they stay visible in the UI after repaint.
        if (hasCalendar) {
            timeline.setLastDoneDate(today.toString());
            timeline.setDueDateDate(dueDateLd != null ? dueDateLd.toString() : null);
        }
        if (hasHours) {
            timeline.setLastDoneHours(Formatting.formatHours(currentTimeInService));
            timeline.setDueDateHours(dueHours != null ? Formatting.formatHours(dueHours) : null);
        }
        // Same text the dashboard computes, from the row's saved due date/hours.
        String timeLeftStr = DueDates.formatTimeLeft(
            timeline.getDueDateDate(), timeline.getDueDateHours(), today, currentTimeInService);
        timeline.setTimeLeft(timeLeftStr);
        serviceTimelineRepository.save(timeline);

        // Build the strings from the actual saved state so the dashboard's
        // repaintDateHoursCell receives both the date and hours parts.
        return new CompletedMaintenance(
            joinParts(timeline.getLastDoneDate(), timeline.getLastDoneHours()),
            joinParts(timeline.getDueDateDate(), timeline.getDueDateHours()),
            timeLeftStr);
    }

    private ServiceTimeline getOwned(User user, Long id) {
        ServiceTimeline timeline = serviceTimelineRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Invalid timeline ID: " + id));
        if (user == null || timeline.getUser() == null || timeline.getUser().getId() != user.getId()) {
            throw new SecurityException("You do not own this timeline");
        }
        return timeline;
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
