package com.example.AviaryService.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.AviaryService.entity.Subscription;
import com.example.AviaryService.entity.User;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Subscription findByUserAndTailNumber(User user, String tailNumber);

    List<Subscription> findByUser(User user);

    List<Subscription> findByActiveTrue();
}