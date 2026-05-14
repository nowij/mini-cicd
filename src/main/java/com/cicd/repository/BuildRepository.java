package com.cicd.repository;

import com.cicd.model.Build;
import com.cicd.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BuildRepository extends JpaRepository<Build, Long> {

    List<Build> findByProjectOrderByStartedAtDesc(Project project);

    Optional<Build> findTopByProjectOrderByStartedAtDesc(Project project);
}
