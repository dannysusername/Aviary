package com.example.AviaryService.services;

import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.AviaryService.entity.User;

// Reads a Garmin G1000 CSV file for the logbook's "Upload CSV" button and works
// out the block time and airborne time. Only reads; nothing is saved until the
// user submits the pre-filled flight log.
@Service
public class GarminCsvService {

    // ok=true: body holds the pre-filled values. ok=false: body holds an
    // "error" message (plus whatever times were detected, for debugging).
    public record CsvParseResult(boolean ok, Map<String, Object> body) {
        static CsvParseResult error(Map<String, Object> body) {
            return new CsvParseResult(false, body);
        }
    }

    // Reads a Garmin engine-data CSV and works out the flight's block time
    // (engine running) and airborne time, then pre-fills Out/In readings
    // from the user's current hours. Returns ok=false with an "error" entry
    // when the file isn't a Garmin CSV or no engine run is found.
    public CsvParseResult parse(InputStream in, User user) {

        // Block time: first → last row where oil pressure > 15 psi.
        // Mirrors the physical BlockTime meter on the Cirrus, which is oil-pressure activated.
        java.time.Instant blockStart = null;
        java.time.Instant blockEnd   = null;

        // Flight (airborne) time: GndSpd > 35 kt sustained for 3+ consecutive seconds.
        // Falls back to IAS when GndSpd is empty (no GPS fix yet).
        java.time.Instant airborneStart     = null;
        java.time.Instant airborneEnd       = null;
        int              airborneConsec    = 0;
        java.time.Instant airborneCandidate = null;

        try(Scanner scanner = new Scanner(in)) {
            int lineNumber = 0;
            HashMap<String, Integer> headerIndexMap = new HashMap<>();

            while(scanner.hasNextLine()) {
                String line = scanner.nextLine();
                lineNumber++;

                if(lineNumber == 3) {
                    String[] headers = line.split(",");
                    for(int i = 0; i < headers.length; i++) {
                        headerIndexMap.put(headers[i].trim(), i);
                    }

                    Set<String> expected = java.util.Set.of(
                        "Lcl Date", "Lcl Time", "UTCOfst", "AtvWpt", "Latitude", "Longitude",
                        "AltInd", "BaroA", "AltMSL", "OAT", "IAS", "GndSpd", "VSpd", "Pitch",
                        "Roll", "LatAc", "NormAc", "HDG", "TRK", "volt1", "volt2", "amp1",
                        "FQtyL", "FQtyR", "E1 FFlow", "E1 OilT", "E1 OilP", "E1 MAP", "E1 RPM",
                        "E1 %Pwr", "E1 CHT1", "E1 CHT2", "E1 CHT3", "E1 CHT4", "E1 CHT5", "E1 CHT6",
                        "E1 EGT1", "E1 EGT2", "E1 EGT3", "E1 EGT4", "E1 EGT5", "E1 EGT6",
                        "E1 TIT1", "E1 TIT2", "E1 Torq", "E1 NG", "E1 ITT", "E2 FFlow", "E2 MAP",
                        "E2 RPM", "E2 Torq", "E2 NG", "E2 ITT", "AltGPS", "TAS", "HSIS", "CRS",
                        "NAV1", "NAV2", "COM1", "COM2", "HCDI", "VCDI", "WndSpd", "WndDr",
                        "WptDst", "WptBrg", "MagVar", "AfcsOn", "RollM", "PitchM", "RollC",
                        "PichC", "VSpdG", "GPSfix", "HAL", "VAL", "HPLwas", "HPLfd", "VPLwas"
                    );
                    if(!headerIndexMap.keySet().containsAll(expected)) {
                        return CsvParseResult.error(Map.of("error", "Not a Garmin CSV file — headers do not match expected format"));
                    }

                    continue;
                }

                if(lineNumber <= 3) continue;

                String[] cols = line.split(",", -1);
                for(int i = 0; i < cols.length; i++) cols[i] = cols[i].trim();

                // Row timestamp = Lcl Date + Lcl Time + UTCOfst combined into
                // a real UTC instant (was just Lcl Time before -- the date
                // and offset were parsed as headers but never read, so the
                // actual date a flight happened was never saved anywhere).
                // Using the real clock instead of a bare time-of-day also
                // fixes a flight that crosses local midnight for free.
                Integer dateIdx = headerIndexMap.get("Lcl Date");
                Integer timeIdx = headerIndexMap.get("Lcl Time");
                Integer ofstIdx = headerIndexMap.get("UTCOfst");
                if (dateIdx == null || timeIdx == null || ofstIdx == null
                        || cols.length <= dateIdx || cols.length <= timeIdx || cols.length <= ofstIdx
                        || cols[dateIdx].isEmpty() || cols[timeIdx].isEmpty() || cols[ofstIdx].isEmpty()) {
                    continue;
                }

                java.time.Instant rowTime;
                try {
                    rowTime = java.time.OffsetDateTime.parse(cols[dateIdx] + "T" + cols[timeIdx] + cols[ofstIdx]).toInstant();
                } catch (Exception e) { continue; }

                // ── Block time via oil pressure ──────────────────────────────────
                Integer oilPIdx = headerIndexMap.get("E1 OilP");
                if(oilPIdx != null && cols.length > oilPIdx && !cols[oilPIdx].isEmpty()) {
                    try {
                        if(Double.parseDouble(cols[oilPIdx]) > 15.0) {
                            if(blockStart == null) blockStart = rowTime;
                            blockEnd = rowTime;
                        }
                    } catch(NumberFormatException ignored) {}
                }

                // ── Airborne time via groundspeed (IAS fallback) ─────────────────
                double speed = Double.NaN;
                Integer gndSpdIdx = headerIndexMap.get("GndSpd");
                if(gndSpdIdx != null && cols.length > gndSpdIdx && !cols[gndSpdIdx].isEmpty()) {
                    try { speed = Double.parseDouble(cols[gndSpdIdx]); } catch(NumberFormatException ignored) {}
                }
                if(Double.isNaN(speed)) {
                    Integer iasIdx = headerIndexMap.get("IAS");
                    if(iasIdx != null && cols.length > iasIdx && !cols[iasIdx].isEmpty()) {
                        try { speed = Double.parseDouble(cols[iasIdx]); } catch(NumberFormatException ignored) {}
                    }
                }

                if(!Double.isNaN(speed) && speed > 35.0) {
                    airborneConsec++;
                    if(airborneConsec == 1) airborneCandidate = rowTime;
                    if(airborneConsec >= 3 && airborneStart == null) airborneStart = airborneCandidate;
                    if(airborneStart != null) airborneEnd = rowTime;
                } else {
                    airborneConsec    = 0;
                    airborneCandidate = null;
                }
            }

            if(blockStart == null && airborneStart == null) {
                return CsvParseResult.error(
                    csvValues("Could not detect airtime or block time", blockStart, blockEnd, airborneStart, airborneEnd));
            }
            
            if(blockStart == null || blockEnd == null) {
                return CsvParseResult.error(
                    csvValues("Could not detect engine run — Oil pressure does not go up by 15psi", blockStart, blockEnd, airborneStart, airborneEnd));
            }
            

            /* 
            if(airborneStart == null || airborneEnd == null) {
                return CsvParseResult.error(
                    csvValues("Could not detect air time — Speed does not go above 35kts", blockStart, blockEnd, airborneStart, airborneEnd));
            }

            */

            //Calculate Block duration
            //Create Block String H: M: S: 
            Duration blockDuration = Duration.between(blockStart, blockEnd);
            String blockStr = "H:" + blockDuration.toHoursPart() + " M:" + blockDuration.toMinutesPart() + " S:" + blockDuration.toSecondsPart();


            String airStr = "N/A";
            Duration airDuration = null;
            if(airborneStart != null && airborneEnd != null) {
                airDuration = Duration.between(airborneStart, airborneEnd);
                airStr = "H:" + airDuration.toHoursPart() + " M:" + airDuration.toMinutesPart() + " S:" + airDuration.toSecondsPart();
            }

            double blockTimeOut = user.getBlockTimeHours();
            double blockTimeIn  = Math.round((blockTimeOut + blockDuration.toSeconds() / 3600.0) * 100.0) / 100.0;
            double timeInServiceOut  = user.getTimeInServiceHours();
            Double timeInServiceIn   = airDuration != null
                ? Math.round((timeInServiceOut + airDuration.toSeconds() / 3600.0) * 100.0) / 100.0
                : null;

            Map<String, Object> result = new HashMap<>();
            result.put("message",        "CSV parse completed");
            result.put("flightDuration", blockStr);
            result.put("airDuration",    airStr);
            result.put("blockTimeOut",       blockTimeOut);
            result.put("blockTimeIn",        blockTimeIn);
            result.put("blockTimeStart",     blockStart.toString());
            result.put("blockTimeEnd",       blockEnd.toString());
            result.put("timeInServiceOut",        timeInServiceIn != null ? timeInServiceOut : null);
            result.put("timeInServiceIn",         timeInServiceIn);
            result.put("timeInServiceStart",      airborneStart != null ? airborneStart.toString() : null);
            result.put("timeInServiceEnd",        airborneEnd != null ? airborneEnd.toString() : null);

            if(airDuration == null) {
                result.put("warning", "Air time not detected — Time in Service fields were not populated");
            }

            return new CsvParseResult(true, result);

        }

    }

    private static Map<String, Object> csvValues(String error, java.time.Instant blockStart, java.time.Instant blockEnd, java.time.Instant airborneStart, java.time.Instant airborneEnd) {
        Map<String, Object> map = new HashMap<>();
        map.put("error", error);
        map.put("blockStart", blockStart);
        map.put("blockEnd", blockEnd);
        map.put("airborneStart", airborneStart);
        map.put("airborneEnd", airborneEnd);

        return map;
    }
}
