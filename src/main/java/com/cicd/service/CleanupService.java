package com.cicd.service;

import com.cicd.model.Build;
import com.cicd.repository.BuildRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CleanupService implements SchedulingConfigurer {

    private final BuildRepository buildRepository;
    private final SettingsService settingsService;

    @Value("${cicd.log-dir:./workspace/logs}")
    private String logDir;

    // 설정이 바뀌면 다음 실행 시 새 cron이 반영됨
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
            this::cleanOldBuilds,
            context -> new CronTrigger(settingsService.getSettings().toCronExpression())
                    .nextExecution(context)
        );
    }

    @Transactional
    public void cleanOldBuilds() {
        var settings = settingsService.getSettings();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(settings.getLogRetentionDays());
        List<Build> oldBuilds = buildRepository.findByStartedAtBefore(cutoff);

        if (oldBuilds.isEmpty()) return;

        int deletedFiles = 0;
        for (Build build : oldBuilds) {
            var logPath = Paths.get(logDir, "build-" + build.getId() + ".log");
            try {
                if (Files.deleteIfExists(logPath)) deletedFiles++;
            } catch (IOException e) {
                log.warn("로그 파일 삭제 실패: {}", logPath, e);
            }
        }

        buildRepository.deleteAll(oldBuilds);
        log.info("오래된 빌드 정리 완료 - DB: {}건, 로그 파일: {}개 삭제 (기준: {}일 이전)",
                oldBuilds.size(), deletedFiles, settings.getLogRetentionDays());
    }
}
