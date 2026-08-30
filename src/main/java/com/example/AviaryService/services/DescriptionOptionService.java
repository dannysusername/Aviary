package com.example.AviaryService.services;

import org.springframework.stereotype.Service;

import com.example.AviaryService.entity.DescriptionOption;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.DescriptionOptionRepository;

@Service
public class DescriptionOptionService {

    private final DescriptionOptionRepository descriptionOptionRepository;

    public DescriptionOptionService(DescriptionOptionRepository descriptionOptionRepository) {
        this.descriptionOptionRepository = descriptionOptionRepository;
    }

    private static final java.util.Set<String> DEFAULT_DESCRIPTION_OPTIONS =
        java.util.Set.of("inspect", "test", "replace", "overhaul");
        
    public void saveCustomDescriptionOption(String description, User user) {
        if (description == null) return;
        String trimmed = description.trim();
        if (trimmed.isEmpty()) return;
        if (DEFAULT_DESCRIPTION_OPTIONS.contains(trimmed.toLowerCase())) return;
        boolean alreadyExists = descriptionOptionRepository.findByUser(user).stream()
            .anyMatch(opt -> opt.getOption() != null && opt.getOption().equalsIgnoreCase(trimmed));
        if (!alreadyExists) {
            descriptionOptionRepository.save(new DescriptionOption(trimmed, user));
        }
    }
}
