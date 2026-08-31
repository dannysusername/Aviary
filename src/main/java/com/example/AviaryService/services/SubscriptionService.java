package com.example.AviaryService.services;

import org.springframework.stereotype.Service;

import com.example.AviaryService.entity.Subscription;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.SubscriptionRepository;

import jakarta.transaction.Transactional;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionService(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    @Transactional
    public boolean toggle(User user, String tailNumber) {
        Subscription sub = subscriptionRepository.findByUserAndTailNumber(user, tailNumber);
        //in the future only allow one tailNumber subscription per user

        if(sub == null) {
            sub = new Subscription(user, tailNumber);
        } else {
            sub.setActive(!sub.isActive());
        }

        subscriptionRepository.save(sub);
        return sub.isActive();

    }
    
}
