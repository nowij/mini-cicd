package com.cicd.service;

import com.cicd.model.CicdSettings;
import com.cicd.repository.CicdSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private final CicdSettingsRepository repository;

    public CicdSettings getSettings() {
        return repository.findById(1L).orElseGet(() -> {
            CicdSettings defaults = new CicdSettings();
            return repository.save(defaults);
        });
    }

    @Transactional
    public CicdSettings save(int logRetentionDays, int cleanupHour, int cleanupMinute) {
        CicdSettings settings = getSettings();
        settings.setLogRetentionDays(logRetentionDays);
        settings.setCleanupHour(cleanupHour);
        settings.setCleanupMinute(cleanupMinute);
        return repository.save(settings);
    }
}
