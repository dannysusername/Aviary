package com.example.AviaryService.repositories;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FlightLogRepository extends JpaRepository<FlightLog, Long> {
    List<FlightLog> findByAircraft(Aircraft aircraft);
    Optional<FlightLog> findByAircraftAndFaFlightId(Aircraft aircraft, String faFlightId);
    long countByAircraft(Aircraft aircraft);
    void deleteByAircraft(Aircraft aircraft);

    // Every flight the account owns, across aircraft.
    List<FlightLog> findByUser(User user);
}
