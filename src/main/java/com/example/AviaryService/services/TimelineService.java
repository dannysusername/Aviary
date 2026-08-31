package com.example.AviaryService.services;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.ServiceTimelineRepository;
import com.example.AviaryService.repositories.UserRepository;
import com.example.AviaryService.util.Parsing;

import jakarta.transaction.Transactional;

@Service
public class TimelineService {

    private final ServiceTimelineRepository serviceTimelineRepository;

    private final DescriptionOptionService descriptionOptionService;
    
    public TimelineService(UserRepository userRepository, ServiceTimelineRepository serviceTimelineRepository, DescriptionOptionService descriptionOptionService) {
        this.serviceTimelineRepository = serviceTimelineRepository;
        this.descriptionOptionService = descriptionOptionService;
    }

    @Transactional
    public ServiceTimeline addTimeline(Map<String, String> data, User user) {
        String item = data.get("item");
        String isTitle = data.getOrDefault("isTitle", "false");
        String description = data.get("description");
        String cycle = data.get("cycle");
        String lastDone = data.get("lastDone");
        String dueDate = data.get("dueDate");
        String timeLeft = data.get("timeLeft");
        String ajax = data.getOrDefault("ajax", "false");

        ServiceTimeline timeline = new ServiceTimeline();
        timeline.setItem(item);
        boolean isTitleRow = "true".equals(isTitle);
        timeline.setIsTitle(isTitleRow);

        if (!isTitleRow) {
            if (description != null) {
                timeline.setDescription(description);
                descriptionOptionService.saveCustomDescriptionOption(description, user);
            }
            timeline.setCycleCalendarValue(Parsing.parseIntOrNull(data.get("cycleCalendarValue")));
            timeline.setCycleCalendarUnit(Parsing.normalizeCalendarUnit(data.get("cycleCalendarUnit")));
            timeline.setCycleHours(Parsing.parseDoubleOrNull(data.get("cycleHours")));
            timeline.setTimeLeft(timeLeft);
        }
        timeline.setUser(user);

        Integer maxOrder = serviceTimelineRepository.findMaxTimelineOrderByUser(user);
        int newOrder = (maxOrder != null) ? maxOrder + 1 : 0;
        timeline.setTimelineOrder(newOrder);

        return serviceTimelineRepository.save(timeline);

    }
}
