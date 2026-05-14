package com.cicd.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 빌드 로그를 파일로 저장하고 SSE로 실시간 스트리밍합니다.
 * - 빌드 진행 중: 파일 기록 + SSE 전송
 * - 빌드 완료 후: 파일에서 읽어서 반환
 */
@Slf4j
@Service
public class LogService {

    @Value("${cicd.log-dir:./workspace/logs}")
    private String logDir;

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> completedMap = new ConcurrentHashMap<>();

    public void init(Long buildId) {
        emitters.put(buildId, new CopyOnWriteArrayList<>());
        completedMap.put(buildId, false);
        // 로그 파일 초기화
        try {
            Files.createDirectories(Paths.get(logDir));
            Files.deleteIfExists(getLogPath(buildId));
        } catch (IOException e) {
            log.warn("Failed to init log file for build {}", buildId, e);
        }
    }

    public void append(Long buildId, String line) {
        // 파일에 기록
        try (var writer = new FileWriter(getLogPath(buildId).toFile(), true);
             var bw = new BufferedWriter(writer)) {
            bw.write(line);
            bw.newLine();
        } catch (IOException e) {
            log.warn("Failed to write log for build {}", buildId, e);
        }

        // 연결된 SSE 클라이언트에 전송 (없으면 skip)
        List<SseEmitter> list = emitters.get(buildId);
        if (list == null || list.isEmpty()) return;

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("log").data(line));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) list.removeAll(dead);
    }

    public void complete(Long buildId) {
        completedMap.put(buildId, true);
        List<SseEmitter> list = emitters.getOrDefault(buildId, List.of());
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("done").data("BUILD_COMPLETE"));
                emitter.complete();
            } catch (Exception ignored) {}
        }
        emitters.remove(buildId);
    }

    public SseEmitter subscribe(Long buildId) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10분 타임아웃

        // 이미 쌓인 로그 먼저 전송
        try {
            Path logPath = getLogPath(buildId);
            if (Files.exists(logPath)) {
                for (String line : Files.readAllLines(logPath)) {
                    emitter.send(SseEmitter.event().name("log").data(line));
                }
            }
            if (completedMap.getOrDefault(buildId, false)) {
                emitter.send(SseEmitter.event().name("done").data("BUILD_COMPLETE"));
                emitter.complete();
                return emitter;
            }
        } catch (Exception e) {
            log.warn("Error replaying logs for build {}", buildId, e);
            return emitter;
        }

        emitters.computeIfAbsent(buildId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(buildId, emitter));
        emitter.onTimeout(() -> removeEmitter(buildId, emitter));
        return emitter;
    }

    public List<String> readLogLines(Long buildId) {
        try {
            Path logPath = getLogPath(buildId);
            if (Files.exists(logPath)) {
                return Files.readAllLines(logPath);
            }
        } catch (IOException e) {
            log.warn("Failed to read log file for build {}", buildId, e);
        }
        return List.of();
    }

    private void removeEmitter(Long buildId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(buildId);
        if (list != null) list.remove(emitter);
    }

    private Path getLogPath(Long buildId) {
        return Paths.get(logDir, "build-" + buildId + ".log");
    }
}
