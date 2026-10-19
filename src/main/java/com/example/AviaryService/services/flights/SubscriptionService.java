package com.example.AviaryService.services.flights;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.AviaryService.entity.Subscription;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.SubscriptionRepository;
import com.example.AviaryService.repositories.UserRepository;

// Manages each user's flight-tracking subscription (one per user).
// Always looks it up by user, not by tail number. Looking it up by tail number
// used to miss the row when the tail number changed, so the app kept calling
// AeroAPI for a plane the user couldn't unsubscribe from.
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    public SubscriptionService(SubscriptionRepository subscriptionRepository, UserRepository userRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Subscription subscribe(User user, String registration) {
        if (user.getAeroApiKey() == null || user.getAeroApiKey().isEmpty()) {
            throw new IllegalArgumentException("Connect an AeroAPI key in Settings before turning on flight sync.");
        }
        String reg = registration == null ? "" : registration.trim().toUpperCase();
        if (reg.isEmpty()) {
            throw new IllegalArgumentException("Enter a registration number to subscribe.");
        }

        Subscription sub = subscriptionRepository.findByUser(user).orElse(null);
        if (sub != null && sub.isActive()) {
            throw new IllegalArgumentException("You're already subscribed. Unsubscribe first to change the registration.");
        }

        if (sub == null) {
            sub = new Subscription(user, reg);
        } else {
            // Reactivating a kept row -- may be a different registration than
            // last time, and the schedule/lastChecked are deliberately kept.
            sub.setTailNumber(reg);
            sub.setActive(true);
            sub.setStatus("OK");
        }
        subscriptionRepository.save(sub);

        // Seed the dashboard tail number only when it's empty; if the user
        // already has a different one typed in, the client offers to line
        // them up rather than overwriting silently.
        if (user.getTailNumber() == null || user.getTailNumber().isBlank()) {
            user.setTailNumber(reg);
            userRepository.save(user);
        }
        return sub;
    }

    @Transactional
    public void unsubscribe(User user) {
        Subscription sub = subscriptionRepository.findByUser(user)
            .orElseThrow(() -> new IllegalArgumentException("You don't have a subscription."));
        sub.setActive(false);
        subscriptionRepository.save(sub);
    }

    // Full removal, including the saved schedule. Must unsubscribe first, same
    // rule as editing the registration.
    @Transactional
    public void delete(User user) {
        Subscription sub = subscriptionRepository.findByUser(user).orElse(null);
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
    public void updateSettings(User user, int pollIntervalDays, int preferredCheckHour) {
        if (pollIntervalDays < 1 || pollIntervalDays > 9) {
            throw new IllegalArgumentException("Check interval must be between 1 and 9 days.");
        }
        if (preferredCheckHour < 0 || preferredCheckHour > 23) {
            throw new IllegalArgumentException("Preferred hour must be between 0 and 23.");
        }
        Subscription sub = subscriptionRepository.findByUser(user)
            .orElseThrow(() -> new IllegalArgumentException("Turn on flight sync first."));
        sub.setPollIntervalDays(pollIntervalDays);
        sub.setPreferredCheckHour(preferredCheckHour);
        subscriptionRepository.save(sub);
    }

}
