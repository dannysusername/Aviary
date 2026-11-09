package com.example.AviaryService.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.User;

public interface AircraftRepository extends JpaRepository<Aircraft, Long> {
    // The only way to load an aircraft from a client-supplied id: scoped to
    // its owner, so another account's id behaves exactly like a missing one.
    Optional<Aircraft> findByIdAndUser(Long id, User user);
    List<Aircraft> findByUserOrderByIdAsc(User user);
    Optional<Aircraft> findFirstByUserOrderByIdAsc(User user);
    long countByUser(User user);
    boolean existsByUserAndTailNumberKey(User user, String tailNumberKey);
    boolean existsByUserAndTailNumberKeyAndIdNot(User user, String tailNumberKey, Long id);
}
