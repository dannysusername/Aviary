package com.example.AviaryService.services.flights;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.FlightSuggestion;
import com.example.AviaryService.entity.Subscription;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.FlightSuggestionRepository;
import com.example.AviaryService.repositories.SubscriptionRepository;
import com.example.AviaryService.services.flights.AeroApiClient.AeroApiFlight;

// Checks FlightAware for new flights of each subscribed plane. For each active
// subscription (whose user has an AeroAPI key) that's due for a check, it asks
// AeroAPI and turns new finished flights into suggestions the user can accept
// or dismiss. It never writes to the logbook directly.
// See docs/specs/ADSB_SYNC_SPEC.md ("The poller", rule 1).
@Service
public class FlightSyncService {

    private static final Logger log = LoggerFactory.getLogger(FlightSyncService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final FlightSuggestionRepository flightSuggestionRepository;
    private final AeroApiClient aeroApiClient;

    public FlightSyncService(SubscriptionRepository subscriptionRepository,
            FlightSuggestionRepository flightSuggestionRepository,
            AeroApiClient aeroApiClient) {
        this.subscriptionRepository = subscriptionRepository;
        this.flightSuggestionRepository = flightSuggestionRepository;
        this.aeroApiClient = aeroApiClient;
    }

    // Ticks hourly; isDue() below decides whether any given subscription
    // actually gets an AeroAPI call this tick.
    @Scheduled(cron = "0 0 * * * *")
    public void syncDueSubscriptions() {
        for (Subscription subscription : subscriptionRepository.findByActiveTrue()) {
            try {
                syncOne(subscription);
            } catch (Exception e) {
                // One bad key or tail must never stall the whole run.
                log.warn("AeroAPI sync failed for subscription {}: {}", subscription.getId(), e.getMessage());
            }
        }
    }

    private void syncOne(Subscription subscription) {
        if (!isDue(subscription)) {
            return;
        }
        syncNow(subscription);
    }

    // Calls AeroAPI right now, skipping the interval/hour gate -- the
    // "Check now" button in Settings uses this directly. Returns how many
    // new pending suggestions were created. Uses AeroAPI's default window
    // (~11 days back, see AeroApiClient.DEFAULT_LOOKBACK_DAYS).
    public int syncNow(Subscription subscription) {
        return syncNow(subscription, null, null);
    }

    // Same, with a user-chosen range -- what "Check flights now" sends when
    // custom start/end dates were entered instead of leaving them blank.
    // Bounds match GET /flights/{ident} exactly (docs/specs/ADSB_DATA_SOURCE.md):
    // start up to MAX_START_DAYS_BACK in the past, end up to
    // MAX_END_DAYS_AHEAD in the future, start before end.
    public int syncNow(Subscription subscription, LocalDate start, LocalDate end) {
        User user = subscription.getUser();
        String apiKey = user.getAeroApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            return 0; // not AeroAPI-backed, nothing for this poller to do
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (start != null && start.isBefore(today.minusDays(AeroApiClient.MAX_START_DAYS_BACK))) {
            throw new IllegalArgumentException(
                "Start date can't be more than " + AeroApiClient.MAX_START_DAYS_BACK + " days in the past.");
        }
        if (end != null && end.isAfter(today.plusDays(AeroApiClient.MAX_END_DAYS_AHEAD))) {
            throw new IllegalArgumentException(
                "End date can't be more than " + AeroApiClient.MAX_END_DAYS_AHEAD + " days in the future.");
        }
        if (start != null && end != null && !start.isBefore(end)) {
            throw new IllegalArgumentException("Start date must be before end date.");
        }

        List<AeroApiFlight> flights = aeroApiClient.getRecentFlights(subscription.getTailNumber(), apiKey, start, end);
        int newCount = 0;
        for (AeroApiFlight flight : flights) {
            if (saveIfNew(user, subscription.getTailNumber(), flight)) {
                newCount++;
            }
        }

        subscription.setLastCheckedAt(Instant.now());
        subscriptionRepository.save(subscription);
        return newCount;
    }

    private boolean isDue(Subscription subscription) {
        int currentHour = ZonedDateTime.now(ZoneId.systemDefault()).getHour();
        if (currentHour != subscription.getPreferredCheckHour()) {
            return false;
        }
        Instant lastChecked = subscription.getLastCheckedAt();
        if (lastChecked == null) {
            return true;
        }
        long daysSince = ChronoUnit.DAYS.between(lastChecked, Instant.now());
        return daysSince >= subscription.getPollIntervalDays();
    }

    @Transactional
    boolean saveIfNew(User user, String tailNumber, AeroApiFlight flight) {
        // Unique constraint on (user, fa_flight_id) is the real guarantee;
        // this check just avoids a doomed insert attempt in the common case.
        if (flightSuggestionRepository.findByUserAndFaFlightId(user, flight.faFlightId()).isPresent()) {
            return false;
        }
        FlightSuggestion suggestion = new FlightSuggestion(
            user, tailNumber, flight.faFlightId(),
            flight.actualOff(), flight.actualOn(),
            flight.origin(), flight.destination()
        );
        flightSuggestionRepository.save(suggestion);
        return true;
    }
}
