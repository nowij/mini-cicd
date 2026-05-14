package com.cicd.repository;

import com.cicd.model.CicdSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CicdSettingsRepository extends JpaRepository<CicdSettings, Long> {
}
