package com.example.AviaryService.services;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.util.DueDates;
import com.example.AviaryService.util.Formatting;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

// Renders templates/pdf-export.html to real PDF bytes via openhtmltopdf --
// see docs/SHARE_EXPORT_SPEC.md. Replaced an earlier client-side
// html2canvas+jsPDF approach that screenshotted the dashboard: that produced
// huge rasterized pages that scrolled badly in PDF viewers. This produces
// real vector text/tables instead.
@Service
public class PdfExportService {

    private final ITemplateEngine templateEngine;
    private final ServiceTimelineRepository serviceTimelineRepository;
    private final FlightLogRepository flightLogRepository;

    public PdfExportService(ITemplateEngine templateEngine,
            ServiceTimelineRepository serviceTimelineRepository,
            FlightLogRepository flightLogRepository) {
        this.templateEngine = templateEngine;
        this.serviceTimelineRepository = serviceTimelineRepository;
        this.flightLogRepository = flightLogRepository;
    }

    public byte[] generateDashboardPdf(User user) {
        return generateDashboardPdf(user, LocalDate.now());
    }

    // `today` drives Time Left, so the PDF matches what the user's own browser
    // shows (the server may be a day ahead/behind in UTC).
    public byte[] generateDashboardPdf(User user, LocalDate today) {
        Context context = new Context();
        context.setVariable("makeModel", user.getMakeModel());
        context.setVariable("tailNumber", user.getTailNumber());
        context.setVariable("ownerName", user.getOwnerName());
        context.setVariable("makeModelSN", user.getMakeModelSN());
        context.setVariable("blockTimeHours", formatHoursOrBlank(user.getBlockTimeHours()));
        context.setVariable("timeInServiceHours", formatHoursOrBlank(user.getTimeInServiceHours()));
        context.setVariable("generatedAt", DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
            .withZone(ZoneOffset.UTC).format(Instant.now()) + " UTC");
        context.setVariable("timelineRows", buildTimelineRows(user, today));
        context.setVariable("logRows", buildLogRows(user));

        String html = templateEngine.process("pdf-export", context);
        return renderPdf(html);
    }

    // openhtmltopdf-pdfbox parses its input as XML, not HTML5 -- feed it the
    // Thymeleaf output directly since pdf-export.html is hand-written as
    // well-formed XHTML for exactly this reason.
    private byte[] renderPdf(String html) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(outputStream);
            builder.run();
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> buildTimelineRows(User user, LocalDate today) {
        return serviceTimelineRepository.findByUserOrderByTimelineOrderAsc(user).stream()
            .map(t -> timelineRowData(t, today, user.getTimeInServiceHours()))
            .collect(Collectors.toList());
    }

    private Map<String, Object> timelineRowData(ServiceTimeline t, LocalDate today, Double currentTimeInService) {
        Map<String, Object> row = new HashMap<>();
        row.put("isTitle", t.getIsTitle());
        row.put("item", t.getItem());
        row.put("description", t.getDescription());
        row.put("cycle", Formatting.formatCycle(t.getCycleCalendarValue(), t.getCycleCalendarUnit(), t.getCycleHours()));
        row.put("lastDone", joinNonBlank(t.getLastDoneDate(), withHrs(t.getLastDoneHours())));
        row.put("dueDate", joinNonBlank(t.getDueDateDate(), withHrs(t.getDueDateHours())));
        // Computed now, not the stored timeLeft column -- that's only as fresh
        // as the last edit to the row.
        String timeLeft = DueDates.formatTimeLeft(t.getDueDateDate(), t.getDueDateHours(), today, currentTimeInService);
        row.put("timeLeft", timeLeft);
        row.put("overdue", timeLeft.contains("overdue"));
        return row;
    }

    private List<Map<String, Object>> buildLogRows(User user) {
        List<FlightLog> logs = flightLogRepository.findByUser(user).stream()
            .sorted(Comparator
                .<FlightLog, Instant>comparing(log -> {
                    Instant start = log.getBlockTimeStart() != null ? log.getBlockTimeStart() : log.getTimeInServiceStart();
                    return start == null ? Instant.MAX : start;
                })
                .thenComparing(FlightLog::getId))
            .toList();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (FlightLog log : logs) {
            Map<String, Object> row = new HashMap<>();
            Instant start = log.getBlockTimeStart() != null ? log.getBlockTimeStart() : log.getTimeInServiceStart();
            row.put("date", start == null ? "" : FLIGHT_DATE.format(start));
            row.put("fromAirport", log.getFromAirport());
            row.put("toAirport", log.getToAirport());
            row.put("blockTimeOut", formatHoursOrBlank(log.getBlockTimeOut()));
            row.put("blockTimeIn", formatHoursOrBlank(log.getBlockTimeIn()));
            row.put("timeInServiceOut", formatHoursOrBlank(log.getTimeInServiceOut()));
            row.put("timeInServiceIn", formatHoursOrBlank(log.getTimeInServiceIn()));
            rows.add(row);
        }
        return rows;
    }

    private static final DateTimeFormatter FLIGHT_DATE =
        DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneOffset.UTC);

    // Meter readings always show at least tenths, like the dashboard:
    // 1415.0 stays "1415.0" (not "1415"), 1100.25 stays "1100.25".
    private static String formatHoursOrBlank(Double hours) {
        if (hours == null) return "";
        String s = Formatting.formatHours(hours);
        return s.contains(".") ? s : s + ".0";
    }

    private static String withHrs(String hours) {
        return hours == null || hours.isBlank() ? hours : hours.trim() + " hrs";
    }

    private static String joinNonBlank(String a, String b) {
        return Stream.of(a, b).filter(s -> s != null && !s.isEmpty()).collect(Collectors.joining(" "));
    }
}
