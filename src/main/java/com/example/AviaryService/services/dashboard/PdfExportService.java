package com.example.AviaryService.services.dashboard;

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
import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.repositories.FlightLogRepository;
import com.example.AviaryService.repositories.StatusSheetItemRepository;
import com.example.AviaryService.util.DueDates;
import com.example.AviaryService.util.Formatting;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

// Makes the maintenance report PDF from templates/pdf-export.html, using the
// openhtmltopdf library. See docs/specs/SHARE_EXPORT_SPEC.md.
//
// It replaced an older way that took a screenshot of the dashboard. Those PDFs
// were huge and scrolled badly. This one has real text and tables.
@Service
public class PdfExportService {

    private final ITemplateEngine templateEngine;
    private final StatusSheetItemRepository statusSheetItemRepository;
    private final FlightLogRepository flightLogRepository;

    public PdfExportService(ITemplateEngine templateEngine,
            StatusSheetItemRepository statusSheetItemRepository,
            FlightLogRepository flightLogRepository) {
        this.templateEngine = templateEngine;
        this.statusSheetItemRepository = statusSheetItemRepository;
        this.flightLogRepository = flightLogRepository;
    }

    public byte[] generateDashboardPdf(Aircraft aircraft) {
        return generateDashboardPdf(aircraft, LocalDate.now());
    }

    // `today` drives Time Left, so the PDF matches what the user's own browser
    // shows (the server may be a day ahead/behind in UTC).
    public byte[] generateDashboardPdf(Aircraft aircraft, LocalDate today) {
        Context context = new Context();
        context.setVariable("makeModel", aircraft.getMakeModel());
        context.setVariable("tailNumber", aircraft.getTailNumber());
        context.setVariable("ownerName", aircraft.getOwnerName());
        context.setVariable("makeModelSN", aircraft.getMakeModelSN());
        context.setVariable("hobbsTimeHours", formatHoursOrBlank(aircraft.getHobbsTimeHours()));
        context.setVariable("timeInServiceHours", formatHoursOrBlank(aircraft.getTimeInServiceHours()));
        context.setVariable("generatedAt", DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
            .withZone(ZoneOffset.UTC).format(Instant.now()) + " UTC");
        context.setVariable("statusSheetRows", buildStatusSheetRows(aircraft, today));
        context.setVariable("logRows", buildLogRows(aircraft));

        String html = templateEngine.process("pdf-export", context);
        return renderPdf(html);
    }

    // Alert emails cover the whole fleet but carry one attachment: each
    // aircraft's report, one after another, merged into a single PDF.
    public byte[] generateFleetPdf(List<Aircraft> fleet, LocalDate today) {
        if (fleet.size() == 1) {
            return generateDashboardPdf(fleet.get(0), today);
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            org.apache.pdfbox.multipdf.PDFMergerUtility merger = new org.apache.pdfbox.multipdf.PDFMergerUtility();
            for (Aircraft aircraft : fleet) {
                merger.addSource(new org.apache.pdfbox.io.RandomAccessReadBuffer(generateDashboardPdf(aircraft, today)));
            }
            merger.setDestinationStream(out);
            merger.mergeDocuments(org.apache.pdfbox.io.IOUtils.createMemoryOnlyStreamCache());
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to generate fleet PDF: " + e.getMessage(), e);
        }
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

    private List<Map<String, Object>> buildStatusSheetRows(Aircraft aircraft, LocalDate today) {
        return statusSheetItemRepository.findByAircraftOrderByStatusSheetOrderAsc(aircraft).stream()
            .map(t -> statusSheetRowData(t, today, aircraft.getTimeInServiceHours()))
            .collect(Collectors.toList());
    }

    private Map<String, Object> statusSheetRowData(StatusSheetItem t, LocalDate today, Double currentTimeInService) {
        Map<String, Object> row = new HashMap<>();
        row.put("isTitle", t.getIsTitle());
        row.put("item", t.getItem());
        row.put("description", t.getDescription());
        row.put("cycle", Formatting.formatCycle(t.getCycleCalendarValue(), t.getCycleCalendarUnit(), t.getCycleHours()));
        row.put("lastDone", joinNonBlank(t.getLastDoneDate(), withHrs(t.getLastDoneHoursDisplay())));
        row.put("dueDate", joinNonBlank(t.getDueDateDate(), withHrs(t.getDueDateHoursDisplay())));
        // Computed now, not the stored timeLeft column -- that's only as fresh
        // as the last edit to the row.
        String timeLeft = DueDates.formatTimeLeft(t.getDueDateDate(), t.getDueDateHours(), today, currentTimeInService);
        row.put("timeLeft", timeLeft);
        row.put("overdue", timeLeft.contains("overdue"));
        return row;
    }

    private List<Map<String, Object>> buildLogRows(Aircraft aircraft) {
        List<FlightLog> logs = flightLogRepository.findByAircraft(aircraft).stream()
            .sorted(Comparator
                .<FlightLog, Instant>comparing(log -> {
                    Instant start = log.getHobbsTimeStart() != null ? log.getHobbsTimeStart() : log.getTimeInServiceStart();
                    return start == null ? Instant.MAX : start;
                })
                .thenComparing(FlightLog::getId))
            .toList();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (FlightLog log : logs) {
            Map<String, Object> row = new HashMap<>();
            Instant start = log.getHobbsTimeStart() != null ? log.getHobbsTimeStart() : log.getTimeInServiceStart();
            row.put("date", start == null ? "" : FLIGHT_DATE.format(start));
            row.put("fromAirport", log.getFromAirport());
            row.put("toAirport", log.getToAirport());
            row.put("hobbsTimeOut", formatHoursOrBlank(log.getHobbsTimeOut()));
            row.put("hobbsTimeIn", formatHoursOrBlank(log.getHobbsTimeIn()));
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
