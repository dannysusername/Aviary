package com.example.AviaryService.repositories;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ServiceTimelineRepository extends JpaRepository<ServiceTimeline, Long> {
    // One aircraft's timeline. timelineOrder is ordered within an aircraft.
    List<ServiceTimeline> findByAircraftOrderByTimelineOrderAsc(Aircraft aircraft);
    List<ServiceTimeline> findByAircraftOrderByIdAsc(Aircraft aircraft);
    long countByAircraft(Aircraft aircraft);

    @Query("SELECT MAX(st.timelineOrder) FROM ServiceTimeline st WHERE st.aircraft = :aircraft")
    Integer findMaxTimelineOrderByAircraft(@Param("aircraft") Aircraft aircraft);

    // The whole fleet, grouped by aircraft (alert digest, picker counts).
    List<ServiceTimeline> findByUserOrderByAircraftIdAscTimelineOrderAsc(User user);

    // Every row the account owns, across aircraft (tests, counts).
    List<ServiceTimeline> findByUserOrderByTimelineOrderAsc(User user);

    void deleteByAircraft(Aircraft aircraft);
}
