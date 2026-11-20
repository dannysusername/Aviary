package com.example.AviaryService.repositories;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.StatusSheetItem;
import com.example.AviaryService.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface StatusSheetItemRepository extends JpaRepository<StatusSheetItem, Long> {
    // One aircraft's Status Sheet. statusSheetOrder is ordered within an aircraft.
    List<StatusSheetItem> findByAircraftOrderByStatusSheetOrderAsc(Aircraft aircraft);
    List<StatusSheetItem> findByAircraftOrderByIdAsc(Aircraft aircraft);
    long countByAircraft(Aircraft aircraft);

    @Query("SELECT MAX(st.statusSheetOrder) FROM StatusSheetItem st WHERE st.aircraft = :aircraft")
    Integer findMaxStatusSheetOrderByAircraft(@Param("aircraft") Aircraft aircraft);

    // The whole fleet, grouped by aircraft (alert digest, picker counts).
    List<StatusSheetItem> findByUserOrderByAircraftIdAscStatusSheetOrderAsc(User user);

    // Every row the account owns, across aircraft (tests, counts).
    List<StatusSheetItem> findByUserOrderByStatusSheetOrderAsc(User user);

    void deleteByAircraft(Aircraft aircraft);
}
