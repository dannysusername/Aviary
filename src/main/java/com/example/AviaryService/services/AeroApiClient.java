package com.example.AviaryService.services;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;

// Gets flight data from FlightAware (AeroAPI). The rest of the app calls this
// and gets plain Java objects back, so only this file deals with AeroAPI's
// JSON and API key header. Endpoint details: docs/ADSB_DATA_SOURCE.md.
@Component
public class AeroApiClient {

    private static final String BASE_URL = "https://aeroapi.flightaware.com/aeroapi";

    // GET /flights/{ident} bounds, confirmed in aeroapi-openapi.yml (see
    // docs/ADSB_DATA_SOURCE.md "Reference: GET /flights/{ident}"). Public so
    // the controller can validate a user-supplied range with the same
    // numbers, and so the frontend can be told the truth instead of a
    // hardcoded copy of it.
    public static final int MAX_START_DAYS_BACK = 10;
    public static final int MAX_END_DAYS_AHEAD = 2;
    // What omitting start/end entirely actually returns -- one day further
    // back than the explicit max, per the same doc.
    public static final int DEFAULT_LOOKBACK_DAYS = 11;

    private final RestClient restClient = RestClient.create();

    // One AeroAPI flight, trimmed to what the poller needs.
    public record AeroApiFlight(
        String faFlightId,
        Instant actualOff,
        Instant actualOn,
        String origin,
        String destination,
        String status
    ) {}

    // Current-period usage for the key that made the call. See CHANGES.md
    // "Other AeroAPI endpoints worth considering later".
    public record AeroApiUsage(
        double totalCost,
        int totalCalls,
        int totalFailedCalls
    ) {}

    // GET /flights/{ident}, no start/end -- the default window (~11 days
    // back) is what the poller wants. Returns only completed ("Arrived")
    // flights with both actual_off and actual_on present; per
    // ADSB_SYNC_SPEC.md rule 4, in-progress/scheduled entries are skipped.
    public List<AeroApiFlight> getRecentFlights(String tailNumber, String apiKey) {
        return getRecentFlights(tailNumber, apiKey, null, null);
    }

    // Same call, with an explicit range -- what "Check flights now" sends
    // when the user picked custom dates instead of the default window.
    // start is inclusive, end is exclusive (AeroAPI's own semantics); bounds
    // are validated by the caller (FlightSyncService.syncNow), not here.
    public List<AeroApiFlight> getRecentFlights(String tailNumber, String apiKey, LocalDate start, LocalDate end) {
        StringBuilder path = new StringBuilder("/flights/" + tailNumber);
        if (start != null || end != null) {
            path.append("?");
            if (start != null) path.append("start=").append(start);
            if (start != null && end != null) path.append("&");
            if (end != null) path.append("end=").append(end);
        }
        JsonNode body = get(path.toString(), apiKey);

        List<AeroApiFlight> flights = new ArrayList<>();
        if (body == null || !body.has("flights")) {
            return flights;
        }

        for (JsonNode f : body.get("flights")) {
            if (!"Arrived".equals(text(f, "status"))) continue;

            Instant off = instant(f, "actual_off");
            Instant on = instant(f, "actual_on");
            if (off == null || on == null) continue;

            flights.add(new AeroApiFlight(
                text(f, "fa_flight_id"),
                off,
                on,
                text(f.path("origin"), "code_icao"),
                text(f.path("destination"), "code_icao"),
                text(f, "status")
            ));
        }
        return flights;
    }

    // GET /account/usage, scoped to the current calendar month (UTC).
    // Cheap, account-metadata call -- doubles as a key-validity check: an
    // invalid key throws the same informative error as getRecentFlights.
    public AeroApiUsage getUsage(String apiKey) {
        String startOfMonth = java.time.LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1).toString();
        JsonNode body = get("/account/usage?start=" + startOfMonth, apiKey);

        return new AeroApiUsage(
            body.path("total_cost").asDouble(0.0),
            body.path("total_calls").asInt(0),
            body.path("total_failed_calls").asInt(0)
        );
    }

    // Shared call + error translation for every AeroAPI GET. RestClient
    // throws automatically on any non-2xx (its default status handler) --
    // we don't have to check the status code ourselves. What we add here:
    // parsing AeroAPI's error body, which is a consistent
    // {title, reason, detail, status} shape across the whole API (confirmed
    // in aeroapi-openapi.yml), so a bad key or bad tail number shows up as
    // an actual message instead of a bare "401 Unauthorized".
    private JsonNode get(String path, String apiKey) {
        String url = BASE_URL + path;
        try {
            return restClient.get()
                .uri(url)
                .header("x-apikey", apiKey)
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            String detail = extractDetail(e.getResponseBodyAsString());
            throw new RuntimeException(
                "AeroAPI call to " + url + " failed (" + e.getStatusCode() + "): " + detail, e);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    // AeroAPI's error responses are { title, reason, detail, status } --
    // "detail" is the human-readable explanation (e.g. "invalid API key").
    private static String extractDetail(String responseBody) {
        try {
            
            JsonNode error = MAPPER.readTree(responseBody);
            String detail = text(error, "detail");
            return detail != null ? detail : responseBody;
        } catch (Exception parseFailure) {
            return responseBody; // not JSON, or not the expected shape -- show it raw
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : Instant.parse(value);
    }
}
