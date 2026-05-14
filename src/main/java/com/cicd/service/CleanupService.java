package com.cicd.service;

import com.cicd.model.Build;
import com.cicd.repository.BuildRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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
public class CleanupService {

    private final BuildRepository buildRepository;

    @Value("${cicd.log-dir:./workspace/logs}")
    private String logDir;

    // 매일 새벽 2시에 실행
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanOldBuilds() {
        LocalDateTime cutoff = LocalDateTime.now().minusWeeks(1);
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
        log.info("오래된 빌드 정리 완료 - DB: {}건, 로그 파일: {}개 삭제 (기준: {})",
                oldBuilds.size(), deletedFiles, cutoff);
    }
}
