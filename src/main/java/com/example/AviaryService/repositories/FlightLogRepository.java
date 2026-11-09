package com.example.AviaryService.repositories;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.FlightLog;
import com.example.AviaryService.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface FlightLogRepository extends JpaRepository<FlightLog, Long> {
    List<FlightLog> findByAircraft(Aircraft aircraft);
    Optional<FlightLog> findByAircraftAndFaFlightId(Aircraft aircraft, String faFlightId);
    long countByAircraft(Aircraft aircraft);
    void deleteByAircraft(Aircraft aircraft);

    // Every flight the account owns, across aircraft.
    List<FlightLog> findByUser(User user);

    // Public stats: total time-in-service hours across all flights. Rows with a
    // missing reading, or in <= out, are skipped so one bad row can't lower the
    // total. Null when no row qualifies.
    @Query("select sum(f.timeInServiceIn - f.timeInServiceOut) from FlightLog f "
         + "where f.timeInServiceIn is not null and f.timeInServiceOut is not null "
         + "and f.timeInServiceIn > f.timeInServiceOut")
    Double sumHoursFlown();
}
