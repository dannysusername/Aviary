package com.example.AviaryService.services.flights;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.Subscription;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.SubscriptionRepository;

// Manages each aircraft's flight-tracking subscription (one per aircraft).
// Always looks it up by aircraft, not by tail number. Looking it up by tail
// number used to miss the row when the tail number changed, so the app kept
// calling AeroAPI for a plane the user couldn't unsubscribe from. The AeroAPI
// key stays account-wide, on User.
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final AircraftRepository aircraftRepository;

    public SubscriptionService(SubscriptionRepository subscriptionRepository, AircraftRepository aircraftRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.aircraftRepository = aircraftRepository;
    }

    @Transactional
    public Subscription subscribe(User user, Aircraft aircraft, String registration) {
        if (user.getAeroApiKey() == null || user.getAeroApiKey().isEmpty()) {
            throw new IllegalArgumentException("Connect an AeroAPI key in Settings before turning on flight sync.");
        }
        String reg = registration == null ? "" : registration.trim().toUpperCase();
        if (reg.isEmpty()) {
            throw new IllegalArgumentException("Enter a registration number to subscribe.");
        }

        Subscription sub = subscriptionRepository.findByAircraft(aircraft).orElse(null);
        if (sub != null && sub.isActive()) {
            throw new IllegalArgumentException("You're already subscribed. Unsubscribe first to change the registration.");
        }

        if (sub == null) {
            sub = new Subscription(user, reg);
            sub.setAircraft(aircraft);
        } else {
            // Reactivating a kept row -- may be a different registration than
            // last time, and the schedule/lastChecked are deliberately kept.
            sub.setTailNumber(reg);
            sub.setActive(true);
            sub.setStatus("OK");
        }
        subscriptionRepository.save(sub);

        // Seed the aircraft's tail number only when it's empty; if it
        // already has a different one typed in, the client offers to line
        // them up rather than overwriting silently. Skipped if another of
        // this user's aircraft already uses that tail.
        if ((aircraft.getTailNumber() == null || aircraft.getTailNumber().isBlank())
                && !aircraftRepository.existsByUserAndTailNumberKeyAndIdNot(
                    user, com.example.AviaryService.util.Validation.tailNumberKey(reg), aircraft.getId())) {
            aircraft.setTailNumber(reg);
            aircraftRepository.save(aircraft);
        }
        return sub;
    }

    @Transactional
    public void unsubscribe(Aircraft aircraft) {
        Subscription sub = subscriptionRepository.findByAircraft(aircraft)
            .orElseThrow(() -> new IllegalArgumentException("You don't have a subscription."));
        sub.setActive(false);
        subscriptionRepository.save(sub);
    }

    // Full removal, including the saved schedule. Must unsubscribe first, same
    // rule as editing the registration.
    @Transactional
    public void delete(Aircraft aircraft) {
        Subscription sub = subscriptionRepository.findByAircraft(aircraft).orElse(null);
        if (sub == null) {
            return;
        }
        if (sub.isActive()) {
            throw new IllegalArgumentException("Unsubscribe before deleting the subscription.");
        }
        subscriptionRepository.delete(sub);
    }

    // See docs/specs/ADSB_SYNC_SPEC.md "Poll interval" for the 1-9 day / 0-23 hour bounds.
    @Transactional
    public void updateSettings(Aircraft aircraft, int pollIntervalDays, int preferredCheckHour) {
        if (pollIntervalDays < 1 || pollIntervalDays > 9) {
            throw new IllegalArgumentException("Check interval must be between 1 and 9 days.");
        }
        if (preferredCheckHour < 0 || preferredCheckHour > 23) {
            throw new IllegalArgumentException("Preferred hour must be between 0 and 23.");
        }
        Subscription sub = subscriptionRepository.findByAircraft(aircraft)
            .orElseThrow(() -> new IllegalArgumentException("Turn on flight sync first."));
        sub.setPollIntervalDays(pollIntervalDays);
        sub.setPreferredCheckHour(preferredCheckHour);
        subscriptionRepository.save(sub);
    }

}
