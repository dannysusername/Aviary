package com.example.AviaryService.services;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.AviaryService.entity.DescriptionOption;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.DescriptionOptionRepository;

import jakarta.transaction.Transactional;

import java.util.List;

import org.slf4j.Logger;

@Service
public class DescriptionOptionService {

    private final DescriptionOptionRepository descriptionOptionRepository;
    private static final Logger log = LoggerFactory.getLogger(DescriptionOptionService.class);

    public DescriptionOptionService(DescriptionOptionRepository descriptionOptionRepository) {
        this.descriptionOptionRepository = descriptionOptionRepository;
    }

    private static final java.util.Set<String> DEFAULT_DESCRIPTION_OPTIONS =
        java.util.Set.of("inspect", "test", "replace", "overhaul");
        
    @Transactional
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
    
    @Transactional
    public DescriptionOption addOption(User user, String raw) {
        if (raw == null) throw new IllegalArgumentException("Missing Option");
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Option cannot be blank");
        if (DEFAULT_DESCRIPTION_OPTIONS.contains(trimmed.toLowerCase())) {
            throw new IllegalArgumentException("That option already exists as a default");
        }
        DescriptionOption existing = descriptionOptionRepository.findByUser(user).stream()
            .filter(opt -> opt.getOption() != null && opt.getOption().equalsIgnoreCase(trimmed))
            .findFirst().orElse(null);
        return (existing != null)
            ? existing
            : descriptionOptionRepository.save(new DescriptionOption(trimmed, user));

    }

    @Transactional
    public void deleteOption(User user, Long id) {
        DescriptionOption option = descriptionOptionRepository.findById(id).orElse(null);
        if (option == null) {
            log.warn("Delete option {} failed for user {}: not found", id, user.getUsername());
            throw new IllegalArgumentException("Option not found");
        }
        if (!option.getUser().equals(user)) {
            log.warn("Delete option {} refused: user {} does not own it", id, user.getUsername());
            throw new SecurityException("You do not own this option");
        }
        descriptionOptionRepository.delete(option);
        log.info("Deleted option {} ({}) for user {}", id, option.getOption(), user.getUsername());
    }

    @Transactional
    public List<DescriptionOption> cleanupAndLoadDescriptionOptions(User user) {
        List<DescriptionOption> all = descriptionOptionRepository.findByUser(user);
        java.util.Set<String> keptLower = new java.util.HashSet<>();
        List<DescriptionOption> kept = new java.util.ArrayList<>();
        List<DescriptionOption> toDelete = new java.util.ArrayList<>();
        for (DescriptionOption opt : all) {
            String value = opt.getOption();
            String trimmed = value == null ? "" : value.trim();
            String lower = trimmed.toLowerCase();
            boolean isBlank = trimmed.isEmpty();
            boolean isDefault = DEFAULT_DESCRIPTION_OPTIONS.contains(lower);
            boolean isDup = !keptLower.add(lower);
            if (isBlank || isDefault || isDup) {
                toDelete.add(opt);
            } else {
                kept.add(opt);
            }
        }
        if (!toDelete.isEmpty()) descriptionOptionRepository.deleteAll(toDelete);
        return kept;
    }


}
