package com.example.AviaryService.repositories;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.FlightSuggestion;
import com.example.AviaryService.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FlightSuggestionRepository extends JpaRepository<FlightSuggestion, Long> {
    // (user_id, fa_flight_id) is unique: a FlightAware flight belongs to one
    // tail number, so it can't appear on two of the same user's aircraft.
    Optional<FlightSuggestion> findByUserAndFaFlightId(User user, String faFlightId);
    List<FlightSuggestion> findByAircraftAndStatus(Aircraft aircraft, String status);
    List<FlightSuggestion> findByAircraftOrderByCreatedAtDesc(Aircraft aircraft);
    void deleteByAircraft(Aircraft aircraft);
}
