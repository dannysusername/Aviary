package com.example.AviaryService.repositories;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    // One flight-sync subscription per aircraft.
    Optional<Subscription> findByAircraft(Aircraft aircraft);
    List<Subscription> findByActiveTrue();
    void deleteByAircraft(Aircraft aircraft);
}
